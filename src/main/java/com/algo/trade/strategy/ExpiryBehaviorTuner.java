package com.algo.trade.strategy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.execution.DailyResettable;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.PcrCalculator;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Expiry Behavior Auto-Tuner — encodes operator-style signals derived from
 * observed Nifty/Sensex daily behavior (May–June 2026).
 *
 * <p>Key patterns observed:</p>
 * <ul>
 *   <li><b>Expiry Pinning (Nifty Tue, Sensex Thu)</b>: On expiry days with low volatility,
 *       index gets pinned near a key CE strike → straddle/strangle selling bias.</li>
 *   <li><b>Sensex Expiry Reversal</b>: Sensex drops &gt;300 pts before noon, then operator-driven
 *       hourly buying rallies → enable gamma scalps after 12 PM, block naked selling.</li>
 *   <li><b>Market Crash Halt</b>: When index falls &gt;1.5% intraday, auto-halt until PCR stabilizes.</li>
 *   <li><b>Momentum Mode</b>: When index rallies &gt;1.0% intraday, allow directional longs.</li>
 * </ul>
 *
 * <p>This service is queried by AlgoFlowOrchestrator and strategy evaluators to
 * determine the current expiry-day "mode" for each underlying.</p>
 */
@Component
public class ExpiryBehaviorTuner implements DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(ExpiryBehaviorTuner.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /**
     * Expiry-day trading modes derived from observed operator behavior.
     */
    public enum ExpiryMode {
        /** Normal day, no special bias. */
        NORMAL,
        /** Expiry pinning detected — favor straddle/strangle selling. */
        EXPIRY_PINNING,
        /** Sensex expiry reversal — drop before noon followed by hourly rallies.
         *  Allow gamma scalps after noon, avoid naked selling. */
        EXPIRY_REVERSAL,
        /** Strong intraday momentum (>1.0%) — allow directional longs. */
        MOMENTUM,
        /** Severe intraday decline (>1.5%) — halt new entries until PCR stabilizes. */
        CRASH_HALT
    }

    private final ExpiryCalendar expiryCalendar;
    private final MarketGuard marketGuard;
    private final PcrCalculator pcrCalculator;

    @Value("${trading.expiry-tuner.enabled:true}")
    private boolean enabled;

    /** Minimum intraday drop (points) on Sensex before noon to trigger reversal mode. */
    @Value("${trading.expiry-tuner.sensex-reversal-drop-points:300}")
    private double sensexReversalDropPoints;

    /** Minimum intraday drop (%) to trigger crash halt. */
    @Value("${trading.expiry-tuner.crash-halt-percent:1.5}")
    private double crashHaltPercent;

    /** Minimum intraday rally (%) to trigger momentum mode. */
    @Value("${trading.expiry-tuner.momentum-rally-percent:1.0}")
    private double momentumRallyPercent;

    /** PCR range considered "stable" for resuming after crash halt. */
    @Value("${trading.expiry-tuner.pcr-stable-low:0.7}")
    private double pcrStableLow;

    @Value("${trading.expiry-tuner.pcr-stable-high:1.4}")
    private double pcrStableHigh;

    /** Time after which gamma scalps are allowed on Sensex expiry reversal days. */
    @Value("${trading.expiry-tuner.reversal-gamma-start:12:00}")
    private String reversalGammaStartTime;

    // ── Per-index intraday state ──────────────────────────────────────────

    private final Map<IndexType, IntradayState> intradayStates = new ConcurrentHashMap<>();

    public ExpiryBehaviorTuner(ExpiryCalendar expiryCalendar, MarketGuard marketGuard,
                               PcrCalculator pcrCalculator) {
        this.expiryCalendar = expiryCalendar;
        this.marketGuard = marketGuard;
        this.pcrCalculator = pcrCalculator;
    }

    /** Injected post-construction by DataDrivenTuningApplicator. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DailyBehaviorLogLoader behaviorLog;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DailyBehaviorRecorder behaviorRecorder;

    /** Candle-based reversal detector — corroborates the tick-based drop detection. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SensexExpiryReversalDetector reversalDetector;

    /**
     * Data-driven confidence for a given mode. Strategies can query this to
     * scale their bias bonus based on how reliable the pattern has been historically.
     *
     * @return 0-100 confidence from historical data, or 75 default if no data loaded
     */
    public int getDataConfidence(ExpiryMode mode) {
        if (behaviorLog == null || !behaviorLog.isLoaded()) return 75;
        return switch (mode) {
            case EXPIRY_PINNING -> behaviorLog.getPatternConfidence("expiryPinning");
            case EXPIRY_REVERSAL -> behaviorLog.getPatternConfidence("sensexExpiryReversal");
            case CRASH_HALT -> behaviorLog.getPatternConfidence("crashHalt");
            case MOMENTUM -> behaviorLog.getPatternConfidence("momentumMode");
            case NORMAL -> 50;
        };
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Determine the current expiry mode for a given underlying.
     * Called by AlgoFlowOrchestrator before strategy evaluation.
     */
    public ExpiryMode currentMode(UnderlyingSymbol underlying) {
        if (!enabled) return ExpiryMode.NORMAL;

        IndexType idx = IndexType.from(underlying);
        IntradayState state = intradayStates.computeIfAbsent(idx, k -> new IntradayState());

        // Priority 1: Crash halt (any day, any index)
        if (isCrashHaltActive(idx, state)) {
            return ExpiryMode.CRASH_HALT;
        }

        // Priority 2: Sensex expiry reversal (Sensex on Thursday only).
        // Active from the moment the drop is detected (tick-based) or the candle-based
        // detector confirms a reversal phase — NOT time-gated, so naked selling is
        // blocked immediately. Gamma scalps remain gated to after reversalGammaStartTime
        // via allowGammaScalps().
        if (idx == IndexType.SENSEX && expiryCalendar.isExpiryDay(idx)) {
            boolean detectorActive = reversalDetector != null && reversalDetector.isReversalActive();
            if (state.reversalDetected || detectorActive) {
                return ExpiryMode.EXPIRY_REVERSAL;
            }
        }

        // Priority 3: Momentum mode (any day, any index with >1% rally)
        if (isMomentumMode(state)) {
            return ExpiryMode.MOMENTUM;
        }

        // Priority 4: Expiry pinning (on expiry day with stable/flat market)
        if (expiryCalendar.isExpiryDay(idx) && isExpiryPinning(idx, state)) {
            return ExpiryMode.EXPIRY_PINNING;
        }

        return ExpiryMode.NORMAL;
    }

    /**
     * Should naked selling be blocked for this underlying right now?
     */
    public boolean blockNakedSelling(UnderlyingSymbol underlying) {
        ExpiryMode mode = currentMode(underlying);
        return mode == ExpiryMode.EXPIRY_REVERSAL || mode == ExpiryMode.CRASH_HALT;
    }

    /**
     * Should gamma scalps be allowed right now?
     */
    public boolean allowGammaScalps(UnderlyingSymbol underlying) {
        ExpiryMode mode = currentMode(underlying);
        if (mode == ExpiryMode.EXPIRY_REVERSAL) {
            LocalTime now = LocalTime.now(IST);
            return now.isAfter(parseTime(reversalGammaStartTime));
        }
        // Gamma scalps are always allowed on expiry day in the normal window
        IndexType idx = IndexType.from(underlying);
        return expiryCalendar.isExpiryDay(idx);
    }

    /**
     * The configured time after which gamma scalps are allowed on Sensex reversal days.
     * Exposed so AlgoFlowOrchestrator's session classification stays in sync with config.
     */
    public LocalTime gammaScalpStartTime() {
        return parseTime(reversalGammaStartTime);
    }

    /**
     * Should straddle/strangle bias be applied?
     */
    public boolean applyStraddleBias(UnderlyingSymbol underlying) {
        ExpiryMode mode = currentMode(underlying);
        return mode == ExpiryMode.EXPIRY_PINNING;
    }

    /**
     * Should directional longs be allowed (momentum mode)?
     */
    public boolean allowDirectionalLongs(UnderlyingSymbol underlying) {
        ExpiryMode mode = currentMode(underlying);
        return mode == ExpiryMode.MOMENTUM;
    }

    /**
     * Get a human-readable description of the current mode for logging/alerts.
     */
    public String modeDescription(UnderlyingSymbol underlying) {
        ExpiryMode mode = currentMode(underlying);
        IndexType idx = IndexType.from(underlying);
        return switch (mode) {
            case NORMAL -> "Normal — no expiry bias";
            case EXPIRY_PINNING -> "Expiry Pinning — straddle/strangle selling bias on " + idx;
            case EXPIRY_REVERSAL -> "Expiry Reversal — Sensex operator-driven rally expected, gamma scalps enabled";
            case MOMENTUM -> "Momentum — directional longs allowed, " + idx + " rallying >" + momentumRallyPercent + "%";
            case CRASH_HALT -> "Crash Halt — " + idx + " down >" + crashHaltPercent + "%, awaiting PCR stabilization";
        };
    }

    // ── Intraday Price Updates ────────────────────────────────────────────

    /**
     * Called by the live feed handler to update intraday prices.
     * This drives the mode detection.
     *
     * @param indexType the index being updated
     * @param openPrice today's open price
     * @param currentPrice latest price
     * @param intradayLow today's intraday low
     */
    public void updatePrice(IndexType indexType, double openPrice, double currentPrice, double intradayLow) {
        IntradayState state = intradayStates.computeIfAbsent(indexType, k -> new IntradayState());
        state.openPrice = openPrice;
        state.currentPrice = currentPrice;
        state.intradayLow = intradayLow;

        if (openPrice <= 0) return;
        state.hasData = true;

        // Feed tick to daily behaviour recorder for end-of-day logging
        if (behaviorRecorder != null) {
            behaviorRecorder.recordTick(indexType, currentPrice);
        }

        double changePct = ((currentPrice - openPrice) / openPrice) * 100.0;
        state.intradayChangePct = changePct;

        double dropFromOpen = openPrice - intradayLow;
        state.maxDropPoints = dropFromOpen;

        // Detect Sensex expiry reversal: drop >300 pts before noon
        if (indexType == IndexType.SENSEX && expiryCalendar.isExpiryDay(indexType)) {
            LocalTime now = LocalTime.now(IST);
            if (!state.reversalDetected && now.isBefore(LocalTime.of(12, 30))
                    && dropFromOpen >= sensexReversalDropPoints) {
                state.reversalDetected = true;
                log.warn("[ExpiryBehaviorTuner] SENSEX expiry reversal detected: drop={} pts before noon. "
                        + "Enabling gamma scalps after {}.", String.format("%.0f", dropFromOpen), reversalGammaStartTime);
            }
        }

        // Detect crash halt
        if (changePct <= -crashHaltPercent && !state.crashHaltTriggered) {
            state.crashHaltTriggered = true;
            log.warn("[ExpiryBehaviorTuner] CRASH HALT triggered for {}: {}% decline from open.",
                    indexType, String.format("%.2f", changePct));
        }

        // Check PCR stabilization to lift crash halt (per-index PCR; NIFTY fallback
        // for indices without their own PCR series, e.g. FINNIFTY/MIDCPNIFTY)
        if (state.crashHaltTriggered) {
            double pcr = pcrCalculator.getPcr(indexType);
            if (pcr <= 0) pcr = pcrCalculator.getPcr();
            // Feed PCR to recorder
            if (behaviorRecorder != null) behaviorRecorder.recordPcr(indexType, pcr);
            if (pcr >= pcrStableLow && pcr <= pcrStableHigh) {
                // PCR has stabilized — lift halt only if price also recovering
                if (changePct > -(crashHaltPercent * 0.7)) {
                    state.crashHaltTriggered = false;
                    log.info("[ExpiryBehaviorTuner] Crash halt LIFTED for {}: PCR={} stabilized, decline eased to {}%.",
                            indexType, String.format("%.2f", pcr), String.format("%.2f", changePct));
                }
            }
        }
    }

    /**
     * Reset all state at start of day (invoked by DailyResetService at midnight).
     */
    @Override
    public void resetDaily() {
        intradayStates.clear();
        log.info("[ExpiryBehaviorTuner] Daily state reset.");
    }

    /**
     * Get the current mode status for all indices (for API/diagnostics).
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new java.util.LinkedHashMap<>();
        status.put("enabled", enabled);
        for (IndexType idx : IndexType.values()) {
            UnderlyingSymbol sym = mapToUnderlying(idx);
            if (sym == null) continue;
            IntradayState state = intradayStates.get(idx);
            Map<String, Object> idxStatus = new java.util.LinkedHashMap<>();
            idxStatus.put("mode", currentMode(sym).name());
            idxStatus.put("isExpiryDay", expiryCalendar.isExpiryDay(idx));
            if (state != null) {
                idxStatus.put("intradayChangePct", String.format("%.2f", state.intradayChangePct));
                idxStatus.put("maxDropPoints", String.format("%.0f", state.maxDropPoints));
                idxStatus.put("reversalDetected", state.reversalDetected);
                idxStatus.put("crashHaltTriggered", state.crashHaltTriggered);
            }
            status.put(idx.name(), idxStatus);
        }
        return status;
    }

    // ── Internal Logic ────────────────────────────────────────────────────

    private boolean isCrashHaltActive(IndexType idx, IntradayState state) {
        return state.crashHaltTriggered;
    }

    private boolean isMomentumMode(IntradayState state) {
        return state.hasData && state.intradayChangePct >= momentumRallyPercent;
    }

    private boolean isExpiryPinning(IndexType idx, IntradayState state) {
        // Expiry pinning: market is moving less than ±0.5% (range-bound near open).
        // Requires real tick data — a zeroed state must NOT register as "flat".
        return state.hasData && Math.abs(state.intradayChangePct) < 0.5;
    }

    private LocalTime parseTime(String timeStr) {
        return LocalTime.parse(timeStr);
    }

    private UnderlyingSymbol mapToUnderlying(IndexType idx) {
        return switch (idx) {
            case NIFTY -> UnderlyingSymbol.NIFTY;
            case BANKNIFTY -> UnderlyingSymbol.BANKNIFTY;
            case SENSEX -> UnderlyingSymbol.SENSEX;
            case FINNIFTY -> UnderlyingSymbol.FINNIFTY;
            case MIDCPNIFTY -> UnderlyingSymbol.MIDCPNIFTY;
        };
    }

    // ── Intraday State Holder ─────────────────────────────────────────────

    private static class IntradayState {
        volatile double openPrice;
        volatile double currentPrice;
        volatile double intradayLow;
        volatile double intradayChangePct;
        volatile double maxDropPoints;
        volatile boolean reversalDetected;
        volatile boolean crashHaltTriggered;
        /** True only after at least one real price update — guards against zeroed-state false positives. */
        volatile boolean hasData;
    }
}
