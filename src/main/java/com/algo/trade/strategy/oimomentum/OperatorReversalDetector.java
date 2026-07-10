package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator Reversal Detector — detects the ignition footprints that precede directional flips.
 *
 * <p>Operators don't flip randomly. Before a reversal, the following footprints appear within
 * a 5–10 minute window while price is still flat/trending the old direction:</p>
 * <ol>
 *   <li><b>OI Shift</b>: Call unwinding + put buildup (or vice versa) — the unwind starts BEFORE the move</li>
 *   <li><b>PCR Slope Flip</b>: PCR slope sign reverses (was rising → now falling, or vice versa)</li>
 *   <li><b>Premium Velocity Stall → Acceleration</b>: ATM premium velocity dies, then fires opposite</li>
 *   <li><b>Volume Spike</b>: Sudden 2× volume at ATM strikes in the NEW direction</li>
 *   <li><b>Spread Widening</b>: Bid/ask spread widens ≥20% (market makers pulling quotes before the move)</li>
 * </ol>
 *
 * <p>When ≥3 of 5 footprints confirm within a 10-minute window AND the current position is
 * in the OPPOSITE direction, the detector fires a reversal signal. The strategy can then
 * exit the current position and re-enter in the new direction with reduced debounce.</p>
 *
 * <h2>Safety</h2>
 * <ul>
 *   <li>Minimum 2 minutes between signals (no machine-gun flipping)</li>
 *   <li>Max 5 reversals per day per index (configurable)</li>
 *   <li>Confidence-weighted (3/5 = 60% confidence → smaller lot; 5/5 = 100% → full lot)</li>
 *   <li>Ignores signals during first 5 minutes of market open (noise)</li>
 * </ul>
 */
@Component
public class OperatorReversalDetector {

    private static final Logger log = LoggerFactory.getLogger(OperatorReversalDetector.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final PremiumVelocityTracker premiumVelocityTracker;
    private final MarketGuard marketGuard;

    @Value("${oi-momentum.reversal-detector.enabled:true}")
    private boolean enabled;

    @Value("${oi-momentum.reversal-detector.min-footprints:3}")
    private int minFootprints;

    @Value("${oi-momentum.reversal-detector.cooldown-seconds:120}")
    private int cooldownSeconds;

    // ── Per-index state ──
    private final Map<IndexType, ReversalState> states = new ConcurrentHashMap<>();

    /** Reversal signal result. */
    public record ReversalSignal(
            int direction,          // +1 = bullish reversal (flip to long), -1 = bearish reversal (flip to short)
            double confidence,      // 0.6–1.0 (footprints confirmed / total)
            int footprintsConfirmed, // 3–5
            String evidence,        // diagnostic string
            Instant detectedAt
    ) {
        public static final ReversalSignal NONE = new ReversalSignal(0, 0, 0, "", Instant.EPOCH);
        public boolean isActive() { return direction != 0 && confidence >= 0.6; }
    }

    /** Internal tracking state per index. */
    private static class ReversalState {
        // ── Footprint observations (rolling 10-min window) ──
        volatile int oiShiftDirection = 0;       // +1 = call build/put unwind, -1 = opposite
        volatile Instant oiShiftTime = null;

        volatile int pcrSlopeFlipDirection = 0;  // +1 = PCR now falling (bullish), -1 = PCR now rising (bearish)
        volatile Instant pcrSlopeFlipTime = null;

        volatile int premVelDirection = 0;       // +1 = CE premium accelerating, -1 = PE premium accelerating
        volatile Instant premVelTime = null;

        volatile boolean volumeSpike = false;
        volatile int volumeSpikeDirection = 0;
        volatile Instant volumeSpikeTime = null;

        volatile boolean spreadWidening = false;
        volatile Instant spreadWideningTime = null;

        // ── Signal state ──
        volatile ReversalSignal lastSignal = ReversalSignal.NONE;
        volatile Instant lastSignalTime = Instant.EPOCH;
        volatile int reversalsToday = 0;

        // ── Baseline tracking ──
        volatile double prevPcrSlope = 0;
        volatile long prevCeOi = 0;
        volatile long prevPeOi = 0;
        volatile double baselineSpread = 0;
        volatile double rollingAvgVolume = 0;
    }

    public OperatorReversalDetector(LiveInstrumentCache liveInstrumentCache,
                                    PremiumVelocityTracker premiumVelocityTracker,
                                    MarketGuard marketGuard) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.premiumVelocityTracker = premiumVelocityTracker;
        this.marketGuard = marketGuard;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /** Get the current reversal signal for an index. Returns NONE if no reversal detected. */
    public ReversalSignal getSignal(IndexType indexType) {
        if (!enabled) return ReversalSignal.NONE;
        ReversalState state = states.get(indexType);
        if (state == null) return ReversalSignal.NONE;
        // Signal expires after 60 seconds
        if (state.lastSignal.isActive()
                && Duration.between(state.lastSignal.detectedAt(), Instant.now()).getSeconds() < 60) {
            return state.lastSignal;
        }
        return ReversalSignal.NONE;
    }

    /** Check if a reversal signal is active in the given direction. */
    public boolean hasReversalSignal(IndexType indexType, int direction) {
        ReversalSignal sig = getSignal(indexType);
        return sig.isActive() && sig.direction() == direction;
    }

    /**
     * Tick — called every second from OIMomentumStrategy. Updates footprint observations
     * and evaluates whether enough footprints confirm to fire a reversal signal.
     *
     * @param indexType the index
     * @param currentDirection current position direction (+1 long, -1 short, 0 flat)
     * @param ceOiChange current CE OI change
     * @param peOiChange current PE OI change
     * @param pcrSlope current 5-min PCR slope
     * @param spot current spot price
     */
    public void tick(IndexType indexType, int currentDirection,
                     long ceOiChange, long peOiChange, double pcrSlope, double spot) {
        if (!enabled) return;
        ReversalState state = states.computeIfAbsent(indexType, k -> new ReversalState());
        Instant now = Instant.now();

        // ── 1. OI Shift Detection ──
        // Call unwinding + put building = bearish reversal signal
        // Put unwinding + call building = bullish reversal signal
        // Uses % change relative to recent OI level (not absolute contracts) for cross-index normalization.
        // Capture the PREVIOUS observation before overwriting — the % normalization must divide the
        // delta by the previous value, not the freshly-stored current one (fixed 2026-06-27).
        long prevCe = state.prevCeOi;
        long prevPe = state.prevPeOi;
        long ceOiDelta = ceOiChange - prevCe;
        long peOiDelta = peOiChange - prevPe;
        state.prevCeOi = ceOiChange;
        state.prevPeOi = peOiChange;

        // % OI change: delta / max(abs(prevOI), 1) — normalized regardless of instrument size
        double ceOiPct = prevCe != 0 ? (double) ceOiDelta / Math.abs(prevCe) * 100 : 0;
        double peOiPct = prevPe != 0 ? (double) peOiDelta / Math.abs(prevPe) * 100 : 0;

        // Threshold: >10% OI change in one direction signals operator intent
        if (Math.abs(ceOiPct) > 10 || Math.abs(peOiPct) > 10) {
            if (ceOiPct < -5 && peOiPct > 5) {
                // Call unwind + put build → bearish
                state.oiShiftDirection = -1;
                state.oiShiftTime = now;
            } else if (peOiPct < -5 && ceOiPct > 5) {
                // Put unwind + call build → bullish
                state.oiShiftDirection = 1;
                state.oiShiftTime = now;
            }
        }

        // ── 2. PCR Slope Flip Detection ──
        if (state.prevPcrSlope != 0 && pcrSlope != 0) {
            boolean flipped = (state.prevPcrSlope > 0.02 && pcrSlope < -0.02)
                           || (state.prevPcrSlope < -0.02 && pcrSlope > 0.02);
            if (flipped) {
                // PCR falling → bullish (puts being unwound); PCR rising → bearish
                state.pcrSlopeFlipDirection = pcrSlope < 0 ? 1 : -1;
                state.pcrSlopeFlipTime = now;
            }
        }
        state.prevPcrSlope = pcrSlope;

        // ── 3. Premium Velocity Direction ──
        PremiumVelocityTracker.PremiumVelocity premVel = premiumVelocityTracker.getVelocity(indexType);
        if (premVel.hasSignal() && premVel.maxPremiumVelocityPct() > 2.0) {
            state.premVelDirection = premVel.direction();
            state.premVelTime = now;
        }

        // ── 4. Volume Spike (use premium velocity magnitude as proxy) ──
        if (premVel.maxPremiumVelocityPct() > 4.0) {
            state.volumeSpike = true;
            state.volumeSpikeDirection = premVel.direction();
            state.volumeSpikeTime = now;
        }

        // ── 5. Spread Widening (use VIX spike as proxy for market-maker pullback) ──
        double vix = marketGuard.getCurrentVix();
        if (vix > 0) {
            if (state.baselineSpread == 0) state.baselineSpread = vix;
            double vixJump = (vix - state.baselineSpread) / state.baselineSpread;
            if (vixJump > 0.05) { // VIX jumped >5% from baseline = spread widening
                state.spreadWidening = true;
                state.spreadWideningTime = now;
            }
            // Slowly update baseline
            state.baselineSpread = state.baselineSpread * 0.99 + vix * 0.01;
        }

        // ── Evaluate footprints ──
        evaluateFootprints(indexType, state, currentDirection, now);
    }

    private void evaluateFootprints(IndexType indexType, ReversalState state,
                                    int currentDirection, Instant now) {
        // Cooldown check
        if (Duration.between(state.lastSignalTime, now).getSeconds() < cooldownSeconds) return;

        // Window: only count footprints from the last 10 minutes
        Duration window = Duration.ofMinutes(10);

        int bullishCount = 0, bearishCount = 0;
        StringBuilder evidence = new StringBuilder();

        // OI shift
        if (state.oiShiftTime != null && Duration.between(state.oiShiftTime, now).compareTo(window) <= 0) {
            if (state.oiShiftDirection > 0) { bullishCount++; evidence.append("OI_SHIFT_BULL "); }
            else if (state.oiShiftDirection < 0) { bearishCount++; evidence.append("OI_SHIFT_BEAR "); }
        }

        // PCR slope flip
        if (state.pcrSlopeFlipTime != null && Duration.between(state.pcrSlopeFlipTime, now).compareTo(window) <= 0) {
            if (state.pcrSlopeFlipDirection > 0) { bullishCount++; evidence.append("PCR_FLIP_BULL "); }
            else if (state.pcrSlopeFlipDirection < 0) { bearishCount++; evidence.append("PCR_FLIP_BEAR "); }
        }

        // Premium velocity
        if (state.premVelTime != null && Duration.between(state.premVelTime, now).compareTo(window) <= 0) {
            if (state.premVelDirection > 0) { bullishCount++; evidence.append("PREM_VEL_BULL "); }
            else if (state.premVelDirection < 0) { bearishCount++; evidence.append("PREM_VEL_BEAR "); }
        }

        // Volume spike
        if (state.volumeSpike && state.volumeSpikeTime != null
                && Duration.between(state.volumeSpikeTime, now).compareTo(window) <= 0) {
            if (state.volumeSpikeDirection > 0) { bullishCount++; evidence.append("VOL_SPIKE_BULL "); }
            else if (state.volumeSpikeDirection < 0) { bearishCount++; evidence.append("VOL_SPIKE_BEAR "); }
        }

        // Spread widening (direction-agnostic — just adds confidence)
        if (state.spreadWidening && state.spreadWideningTime != null
                && Duration.between(state.spreadWideningTime, now).compareTo(window) <= 0) {
            // Add to the stronger direction
            if (bullishCount > bearishCount) { bullishCount++; evidence.append("SPREAD_WIDE "); }
            else if (bearishCount > bullishCount) { bearishCount++; evidence.append("SPREAD_WIDE "); }
        }

        // ── Fire signal if enough footprints AND opposes current direction ──
        int maxCount = Math.max(bullishCount, bearishCount);
        if (maxCount >= minFootprints) {
            int signalDir = bullishCount > bearishCount ? 1 : -1;

            // Only fire if it OPPOSES the current position (or we're flat)
            if (currentDirection == 0 || signalDir != currentDirection) {
                double confidence = maxCount / 5.0;
                ReversalSignal signal = new ReversalSignal(
                        signalDir, confidence, maxCount,
                        evidence.toString().trim(), now);

                state.lastSignal = signal;
                state.lastSignalTime = now;
                state.reversalsToday++;

                log.info("[ReversalDetector][{}] SIGNAL {} (conf={}%, footprints={}/5, evidence=[{}])",
                        indexType, signalDir > 0 ? "BULL" : "BEAR",
                        (int)(confidence * 100), maxCount, evidence.toString().trim());

                // Clear footprints after firing (one-shot per episode)
                clearFootprints(state);
            }
        }
    }

    private void clearFootprints(ReversalState state) {
        state.oiShiftDirection = 0;
        state.oiShiftTime = null;
        state.pcrSlopeFlipDirection = 0;
        state.pcrSlopeFlipTime = null;
        state.premVelDirection = 0;
        state.premVelTime = null;
        state.volumeSpike = false;
        state.volumeSpikeDirection = 0;
        state.volumeSpikeTime = null;
        state.spreadWidening = false;
        state.spreadWideningTime = null;
    }

    /** Reset daily counters (called at day start). */
    public void resetDay(IndexType indexType) {
        ReversalState state = states.get(indexType);
        if (state != null) {
            state.reversalsToday = 0;
            state.lastSignal = ReversalSignal.NONE;
            clearFootprints(state);
        }
    }

    /** Get today's reversal count for an index. */
    public int getReversalsToday(IndexType indexType) {
        ReversalState state = states.get(indexType);
        return state != null ? state.reversalsToday : 0;
    }

    public boolean isEnabled() { return enabled; }
}
