package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.PcrCalculator;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * PCR Momentum Reversal Strategy — detects when PCR drops from a high peak
 * and generates BUY_PE signals.
 *
 * <p><b>Observed pattern (Jun 11, 2026):</b></p>
 * <ul>
 *   <li>PCR climbs to 1.98 → heavy put writing = strong support = Sensex rallies from 73,500 → 74,400</li>
 *   <li>PCR starts declining (put writers unwinding) → support eroding → market reverses down</li>
 *   <li>Sensex falls back from 74,400 → 73,800 as PCR drops from 1.98 → ~1.4</li>
 * </ul>
 *
 * <p><b>Signal Logic:</b></p>
 * <ol>
 *   <li>Track intraday PCR peak per index</li>
 *   <li>When PCR was above HIGH_PCR_THRESHOLD (1.5+) and starts declining:</li>
 *   <li>If PCR drops ≥ MIN_DROP_FROM_PEAK (0.15 points) → BUY_PE signal (bearish reversal)</li>
 *   <li>Confidence scales with drop magnitude and speed of decline</li>
 *   <li>Signal is only generated once per peak (no repeated signals on continued decline)</li>
 * </ol>
 *
 * <p><b>Complementary to existing OperatorIntentEngine:</b>
 * The existing system uses PCR level for bias (+25 bull if PCR > 1.2). This strategy
 * adds the PCR <i>rate-of-change from peak</i> as a reversal trigger — a different signal.</p>
 */
@Component
public class PcrMomentumReversalStrategy implements com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(PcrMomentumReversalStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final PcrCalculator pcrCalculator;
    private final MarketGuard marketGuard;
    private final ExpiryCalendar expiryCalendar;

    // ── Configuration ─────────────────────────────────────────────────────

    /** PCR level above which we start tracking for a drop (put-heavy = bullish support). */
    @Value("${trading.pcr-reversal.high-pcr-threshold:1.5}")
    private double highPcrThreshold;

    /** Minimum PCR drop from peak to generate a BUY_PE signal. */
    @Value("${trading.pcr-reversal.min-drop-from-peak:0.15}")
    private double minDropFromPeak;

    /** PCR must have been at peak for at least this many consecutive readings to confirm support. */
    @Value("${trading.pcr-reversal.min-peak-readings:2}")
    private int minPeakReadings;

    /** Minimum PCR decline rate (points/min over 5min) to confirm active unwinding. */
    @Value("${trading.pcr-reversal.min-decline-rate:0.02}")
    private double minDeclineRate;

    /** Maximum number of PE signals per day (avoid over-trading on sustained decline). */
    @Value("${trading.pcr-reversal.max-signals-per-day:3}")
    private int maxSignalsPerDay;

    /** Master switch. */
    @Value("${trading.pcr-reversal.enabled:true}")
    private boolean enabled;

    /** Cooldown between signals (minutes). */
    @Value("${trading.pcr-reversal.cooldown-minutes:15}")
    private int cooldownMinutes;

    // ── Per-index intraday state ──────────────────────────────────────────

    private final Map<IndexType, PcrReversalState> states = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.notification.TelegramAlertService alertService;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DailyBehaviorLogLoader behaviorLog;

    public PcrMomentumReversalStrategy(PcrCalculator pcrCalculator, MarketGuard marketGuard,
                                       ExpiryCalendar expiryCalendar) {
        this.pcrCalculator = pcrCalculator;
        this.marketGuard = marketGuard;
        this.expiryCalendar = expiryCalendar;
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Evaluate whether a PCR reversal signal is active for a given index.
     * Called by the execution pipeline or strategy orchestrator.
     *
     * @return Optional signal if PCR reversal detected, empty otherwise.
     */
    public Optional<PcrReversalSignal> evaluate(IndexType indexType) {
        if (!enabled) return Optional.empty();
        if (!isMarketHours()) return Optional.empty();

        PcrReversalState state = states.computeIfAbsent(indexType, k -> new PcrReversalState());
        // Per-index PCR — each index's state tracks ITS OWN option chain
        double currentPcr = pcrCalculator.getPcr(indexType);

        if (currentPcr <= 0) return Optional.empty();

        // Update state with latest PCR (thresholds passed in so yml tuning applies everywhere)
        state.updatePcr(currentPcr, highPcrThreshold);

        // Check if signal conditions are met
        return checkForSignal(indexType, state, currentPcr);
    }

    /**
     * Get the current state for diagnostics / API.
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("currentPcrNifty", String.format("%.3f", pcrCalculator.getPcr(IndexType.NIFTY)));
        status.put("currentPcrBankNifty", String.format("%.3f", pcrCalculator.getPcr(IndexType.BANKNIFTY)));
        status.put("currentPcrSensex", String.format("%.3f", pcrCalculator.getPcr(IndexType.SENSEX)));
        status.put("highPcrThreshold", highPcrThreshold);
        status.put("minDropFromPeak", minDropFromPeak);
        for (Map.Entry<IndexType, PcrReversalState> entry : states.entrySet()) {
            PcrReversalState s = entry.getValue();
            Map<String, Object> indexStatus = new LinkedHashMap<>();
            indexStatus.put("peakPcr", String.format("%.3f", s.peakPcr));
            indexStatus.put("peakReadings", s.peakReadings);
            indexStatus.put("currentDrop", String.format("%.3f", s.peakPcr - s.lastPcr));
            indexStatus.put("phase", s.phase.name());
            indexStatus.put("signalsToday", s.signalsToday);
            indexStatus.put("lastSignalTime", s.lastSignalTime != null ? s.lastSignalTime.toString() : "none");
            status.put(entry.getKey().name(), indexStatus);
        }
        return status;
    }

    /**
     * Check if a PCR reversal signal is currently active (for use by other strategies).
     */
    public boolean isPcrDecliningFromHigh(IndexType indexType) {
        PcrReversalState state = states.get(indexType);
        if (state == null) return false;
        return state.phase == ReversalPhase.DECLINING;
    }

    /**
     * Get the magnitude of PCR drop from peak (0 if not declining).
     */
    public double getPcrDropFromPeak(IndexType indexType) {
        PcrReversalState state = states.get(indexType);
        if (state == null || state.phase != ReversalPhase.DECLINING) return 0;
        return state.peakPcr - state.lastPcr;
    }

    // ── Scheduled PCR check ───────────────────────────────────────────────

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) {
            schedulerRegistry.register("pcrMomentumReversal",
                    "PCR momentum reversal detection (every 2min)", 120_000, this::scheduledCheck);
        }
    }

    @Scheduled(fixedDelay = 120_000, initialDelay = 45_000)
    public void scheduledCheck() {
        if (!enabled) return;
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("pcrMomentumReversal")) return;
        if (!isMarketHours()) return;

        // Evaluate for all active indices
        for (IndexType idx : IndexType.values()) {
            if (!idx.hasWeeklyExpiry() && idx != IndexType.BANKNIFTY) continue;
            Optional<PcrReversalSignal> signal = evaluate(idx);
            signal.ifPresent(s -> {
                log.warn("[PcrMomentumReversal] \uD83D\uDD34 BUY_PE signal on {}: PCR dropped from {} to {} (drop={}). "
                                + "Put writers unwinding — bearish reversal expected.",
                        idx, String.format("%.3f", s.peakPcr), String.format("%.3f", s.currentPcr),
                        String.format("%.3f", s.dropFromPeak));
                if (alertService != null) {
                    alertService.systemAlert(String.format(
                            "🔴 PCR REVERSAL — %s\nPCR peak: %.2f → now: %.2f (drop: %.2f)\n"
                                    + "Put writers unwinding — BUY PE recommended\nConfidence: %d%%",
                            idx, s.peakPcr, s.currentPcr, s.dropFromPeak, s.confidence));
                }
            });
        }
    }

    /**
     * Reset at start of day.
     */
    public void resetDaily() {
        states.clear();
        log.info("[PcrMomentumReversal] Daily state reset.");
    }

    // ── Internal Logic ────────────────────────────────────────────────────

    private Optional<PcrReversalSignal> checkForSignal(IndexType indexType, PcrReversalState state, double currentPcr) {

        // Phase: ACCUMULATING → PCR is high and building (bullish support)
        if (state.phase == ReversalPhase.ACCUMULATING) {
            if (currentPcr < state.peakPcr - minDropFromPeak) {
                // PCR has dropped enough — transition to DECLINING
                state.phase = ReversalPhase.DECLINING;
                log.info("[PcrMomentumReversal] {} DECLINING: PCR dropped from peak {} to {}",
                        indexType, String.format("%.3f", state.peakPcr), String.format("%.3f", currentPcr));
            }
            return Optional.empty();
        }

        // Phase: DECLINING → PCR is falling from high; check if signal should fire
        if (state.phase == ReversalPhase.DECLINING) {
            double drop = state.peakPcr - currentPcr;

            // Guard: max signals per day
            if (state.signalsToday >= maxSignalsPerDay) return Optional.empty();

            // Guard: cooldown
            if (state.lastSignalTime != null) {
                long minutesSince = java.time.Duration.between(state.lastSignalTime, Instant.now()).toMinutes();
                if (minutesSince < cooldownMinutes) return Optional.empty();
            }

            // Guard: minimum drop must be met
            if (drop < minDropFromPeak) return Optional.empty();

            // Guard: must have had enough readings at the peak to confirm it was real support
            if (state.peakReadings < minPeakReadings) return Optional.empty();

            // Calculate decline rate from recent history
            double declineRate = state.computeDeclineRate();
            if (declineRate < minDeclineRate) return Optional.empty();

            // ── Signal confirmed ──
            int confidence = computeConfidence(state.peakPcr, currentPcr, drop, declineRate);

            state.signalsToday++;
            state.lastSignalTime = Instant.now();
            state.lastSignalPcr = currentPcr;

            // After signal, move to SIGNAL_FIRED to avoid repeated signals for this peak
            state.phase = ReversalPhase.SIGNAL_FIRED;

            return Optional.of(new PcrReversalSignal(
                    indexType, state.peakPcr, currentPcr, drop, declineRate, confidence, Instant.now()));
        }

        // Phase: SIGNAL_FIRED → wait for PCR to either recover or drop further for next signal
        if (state.phase == ReversalPhase.SIGNAL_FIRED) {
            // If PCR drops further by another minDropFromPeak, allow another signal
            double additionalDrop = state.lastSignalPcr - currentPcr;
            if (additionalDrop >= minDropFromPeak) {
                state.phase = ReversalPhase.DECLINING;
                state.lastSignalPcr = currentPcr;
                // Re-evaluate immediately
                return checkForSignal(indexType, state, currentPcr);
            }
            // If PCR recovers above threshold, reset to watching
            if (currentPcr >= highPcrThreshold) {
                state.phase = ReversalPhase.ACCUMULATING;
                state.peakPcr = currentPcr;
                state.peakReadings = 1;
            }
        }

        return Optional.empty();
    }

    private int computeConfidence(double peakPcr, double currentPcr, double drop, double declineRate) {
        int confidence = 55; // base

        // Higher peak = stronger support was formed = stronger reversal signal
        if (peakPcr >= 2.0) confidence += 15;
        else if (peakPcr >= 1.8) confidence += 10;
        else if (peakPcr >= 1.6) confidence += 5;

        // Larger drop = more aggressive unwinding
        if (drop >= 0.4) confidence += 15;
        else if (drop >= 0.3) confidence += 10;
        else if (drop >= 0.2) confidence += 5;

        // Faster decline = urgent selling
        if (declineRate >= 0.05) confidence += 10;
        else if (declineRate >= 0.03) confidence += 5;

        // Scale by historical data confidence (82% from 4 occurrences in Mar-Jun data)
        // If data says this pattern is 82% reliable, scale final confidence by 0.82
        if (behaviorLog != null && behaviorLog.isLoaded()) {
            int dataConfidence = behaviorLog.getPatternConfidence("pcrUnwindReversal");
            if (dataConfidence > 0) {
                confidence = (int) (confidence * dataConfidence / 100.0);
            }
        }

        return Math.min(95, confidence);
    }

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.of(9, 20)) && now.isBefore(LocalTime.of(15, 15));
    }

    // ── State Management ──────────────────────────────────────────────────

    enum ReversalPhase {
        /** PCR below threshold — not tracking. */
        WATCHING,
        /** PCR above threshold and rising/stable — support building. */
        ACCUMULATING,
        /** PCR dropping from peak — unwinding detected. */
        DECLINING,
        /** Signal fired for this peak — waiting for cooldown or further drop. */
        SIGNAL_FIRED
    }

    static class PcrReversalState {
        volatile ReversalPhase phase = ReversalPhase.WATCHING;
        volatile double peakPcr = 0;
        volatile double lastPcr = 0;
        volatile double lastSignalPcr = 0;
        volatile int peakReadings = 0;
        volatile int signalsToday = 0;
        volatile Instant lastSignalTime = null;

        // Rolling PCR samples for decline rate calculation
        private final Deque<PcrSample> recentSamples = new ArrayDeque<>();
        private static final int MAX_SAMPLES = 10;

        void updatePcr(double pcr, double enterThreshold) {
            lastPcr = pcr;
            recentSamples.addLast(new PcrSample(Instant.now(), pcr));
            while (recentSamples.size() > MAX_SAMPLES) recentSamples.pollFirst();

            switch (phase) {
                case WATCHING:
                    if (pcr >= enterThreshold) {
                        phase = ReversalPhase.ACCUMULATING;
                        peakPcr = pcr;
                        peakReadings = 1;
                    }
                    break;

                case ACCUMULATING:
                    if (pcr >= peakPcr) {
                        peakPcr = pcr;
                        peakReadings++;
                    }
                    // If PCR drops far back below threshold (80% of entry), reset tracking
                    // (1.2 at the default 1.5 threshold — same behavior as before, now tunable)
                    if (pcr < enterThreshold * 0.8) {
                        phase = ReversalPhase.WATCHING;
                        peakPcr = 0;
                        peakReadings = 0;
                    }
                    break;

                case DECLINING:
                case SIGNAL_FIRED:
                    // If PCR recovers substantially, reset tracking
                    if (pcr > peakPcr * 0.95) {
                        phase = ReversalPhase.ACCUMULATING;
                        peakPcr = pcr;
                        peakReadings = 1;
                    }
                    break;
            }
        }

        /** Compute decline rate in PCR points per minute over recent samples. */
        double computeDeclineRate() {
            if (recentSamples.size() < 2) return 0;
            PcrSample first = recentSamples.peekFirst();
            PcrSample last = recentSamples.peekLast();
            if (first == null || last == null) return 0;
            long minutes = java.time.Duration.between(first.time, last.time).toMinutes();
            if (minutes <= 0) return 0;
            double change = first.pcr - last.pcr; // positive = declining
            return change / minutes;
        }

        record PcrSample(Instant time, double pcr) {}
    }

    // ── Signal Result ─────────────────────────────────────────────────────

    /**
     * Represents a PCR reversal signal — indicates put writers unwinding
     * and market likely to reverse downward.
     */
    public record PcrReversalSignal(
            IndexType indexType,
            double peakPcr,
            double currentPcr,
            double dropFromPeak,
            double declineRate,
            int confidence,
            Instant timestamp
    ) {
        public SignalType signalType() { return SignalType.BUY_PE; }

        public String reason() {
            return String.format("PCR_MOMENTUM_REVERSAL: peak=%.2f→now=%.2f (drop=%.2f, rate=%.3f/min) — "
                            + "put writers unwinding, bearish reversal",
                    peakPcr, currentPcr, dropFromPeak, declineRate);
        }
    }
}
