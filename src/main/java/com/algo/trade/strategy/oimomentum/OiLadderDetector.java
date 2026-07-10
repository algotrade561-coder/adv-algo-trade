package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.ExpiryCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OI Ladder Detector — detects sequential OI builds across adjacent strikes (staircase pattern).
 *
 * <p>Operators often don't dump a large OI position in one shot. Instead, they build positions
 * in a staircase: incrementally adding OI at progressively higher (bullish) or lower (bearish)
 * strikes over 5–15 minutes. This reveals directional intent BEFORE the price move.</p>
 *
 * <h2>Detection Logic</h2>
 * <ol>
 *   <li>Sample ATM ±5 strike OI every 60 seconds</li>
 *   <li>Identify "step" events: new OI buildup at a strike higher/lower than the previous active buildup</li>
 *   <li>Track steps in a rolling 15-min window</li>
 *   <li>Signal fires when ≥3 consecutive steps in the same direction (bullish/bearish ladder)</li>
 * </ol>
 *
 * <h2>Output</h2>
 * Provides a directional signal (+1 bullish ladder / -1 bearish ladder) with confidence
 * based on ladder depth (3 steps = 0.6, 4 = 0.8, 5+ = 1.0). Fed into DecisionAggregator
 * as an additional operator footprint.
 */
@Component
public class OiLadderDetector {

    private static final Logger log = LoggerFactory.getLogger(OiLadderDetector.class);

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;

    @Value("${oi-momentum.oi-ladder.enabled:true}")
    private boolean enabled;

    @Value("${oi-momentum.oi-ladder.min-steps:3}")
    private int minSteps;

    @Value("${oi-momentum.oi-ladder.window-minutes:15}")
    private int windowMinutes;

    /** Minimum OI increase at a strike to count as a "step" (% of total ATM OI). */
    @Value("${oi-momentum.oi-ladder.step-threshold-pct:5}")
    private double stepThresholdPct;

    private final Map<IndexType, LadderState> states = new ConcurrentHashMap<>();

    public record LadderSignal(
            int direction,       // +1 bullish (stepping up), -1 bearish (stepping down), 0 none
            double confidence,   // 0.6–1.0 based on step count
            int steps,           // number of consecutive ladder steps detected
            String detail        // diagnostic
    ) {
        public static final LadderSignal NONE = new LadderSignal(0, 0, 0, "");
        public boolean isActive() { return direction != 0 && confidence >= 0.5; }
    }

    private static class LadderState {
        // Previous OI snapshot per strike
        final Map<Integer, Long> prevCeOi = new HashMap<>();
        final Map<Integer, Long> prevPeOi = new HashMap<>();
        Instant lastSampleTime = null;

        // Rolling ladder steps (time-bounded)
        final Deque<LadderStep> steps = new ArrayDeque<>();

        // Cached signal
        volatile LadderSignal lastSignal = LadderSignal.NONE;
        volatile Instant lastSignalTime = Instant.EPOCH;
    }

    private record LadderStep(int strike, int direction, double magnitude, Instant time) {}

    public OiLadderDetector(LiveInstrumentCache liveInstrumentCache,
                            ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    // ── Public API ──────────────────────────────────────────────────────

    public LadderSignal getSignal(IndexType indexType) {
        if (!enabled) return LadderSignal.NONE;
        LadderState state = states.get(indexType);
        if (state == null) return LadderSignal.NONE;
        // Signal valid for 5 minutes
        if (state.lastSignal.isActive()
                && Duration.between(state.lastSignalTime, Instant.now()).toMinutes() < 5) {
            return state.lastSignal;
        }
        return LadderSignal.NONE;
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Sample — called every ~60 seconds. Compares current strike OI to previous snapshot,
     * detects "steps" (new OI buildup at progressively higher/lower strikes), and evaluates
     * whether enough steps form a ladder.
     */
    public void sample(IndexType indexType) {
        if (!enabled) return;
        LadderState state = states.computeIfAbsent(indexType, k -> new LadderState());
        Instant now = Instant.now();

        // Rate limit: only sample once per 30 seconds minimum
        if (state.lastSampleTime != null
                && Duration.between(state.lastSampleTime, now).getSeconds() < 30) {
            return;
        }

        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);

        // Sample OI at ATM ±5 strikes
        Map<Integer, Long> currentCeOi = new HashMap<>();
        Map<Integer, Long> currentPeOi = new HashMap<>();
        long totalOi = 0;

        for (int offset = -5; offset <= 5; offset++) {
            int strike = atm + (offset * interval);
            var ceOpt = liveInstrumentCache.getOption(indexType, strike, "CE", expiry);
            var peOpt = liveInstrumentCache.getOption(indexType, strike, "PE", expiry);
            long ceOi = ceOpt.map(o -> o.getOpenInterest()).orElse(0L);
            long peOi = peOpt.map(o -> o.getOpenInterest()).orElse(0L);
            currentCeOi.put(strike, ceOi);
            currentPeOi.put(strike, peOi);
            totalOi += ceOi + peOi;
        }

        if (totalOi <= 0 || state.prevCeOi.isEmpty()) {
            // First sample or no OI data — save and return
            state.prevCeOi.putAll(currentCeOi);
            state.prevPeOi.putAll(currentPeOi);
            state.lastSampleTime = now;
            return;
        }

        // Detect OI buildup steps
        double stepThreshold = totalOi * stepThresholdPct / 100.0;

        for (int offset = -5; offset <= 5; offset++) {
            int strike = atm + (offset * interval);
            long prevCe = state.prevCeOi.getOrDefault(strike, 0L);
            long prevPe = state.prevPeOi.getOrDefault(strike, 0L);
            long curCe = currentCeOi.getOrDefault(strike, 0L);
            long curPe = currentPeOi.getOrDefault(strike, 0L);

            long ceDelta = curCe - prevCe;
            long peDelta = curPe - prevPe;

            // Bullish ladder step: put buildup at higher strikes (operators writing puts = bullish)
            // OR call buildup at higher strikes (operators buying calls = bullish)
            if (offset > 0 && (peDelta > stepThreshold || ceDelta > stepThreshold)) {
                double mag = Math.max(peDelta, ceDelta) / (double) totalOi * 100;
                state.steps.addLast(new LadderStep(strike, 1, mag, now));
            }
            // Bearish ladder step: call buildup at lower strikes (operators writing calls = bearish)
            // OR put buildup at lower strikes (operators buying puts = bearish)
            if (offset < 0 && (ceDelta > stepThreshold || peDelta > stepThreshold)) {
                double mag = Math.max(ceDelta, peDelta) / (double) totalOi * 100;
                state.steps.addLast(new LadderStep(strike, -1, mag, now));
            }
        }

        // Expire old steps outside the window
        Instant windowStart = now.minus(Duration.ofMinutes(windowMinutes));
        while (!state.steps.isEmpty() && state.steps.peekFirst().time().isBefore(windowStart)) {
            state.steps.pollFirst();
        }

        // Evaluate: count consecutive steps in same direction
        int bullSteps = 0, bearSteps = 0;
        for (LadderStep step : state.steps) {
            if (step.direction() > 0) bullSteps++;
            else if (step.direction() < 0) bearSteps++;
        }

        int maxSteps = Math.max(bullSteps, bearSteps);
        if (maxSteps >= minSteps) {
            int dir = bullSteps > bearSteps ? 1 : -1;
            double confidence = Math.min(1.0, 0.4 + maxSteps * 0.15);
            String detail = String.format("%s_LADDER(%d steps in %dmin, atm=%d)",
                    dir > 0 ? "BULL" : "BEAR", maxSteps, windowMinutes, atm);

            state.lastSignal = new LadderSignal(dir, confidence, maxSteps, detail);
            state.lastSignalTime = now;

            log.info("[OiLadder][{}] {} (steps={}, conf={}%)",
                    indexType, detail, maxSteps, (int)(confidence * 100));
        }

        // Save current as previous
        state.prevCeOi.clear();
        state.prevCeOi.putAll(currentCeOi);
        state.prevPeOi.clear();
        state.prevPeOi.putAll(currentPeOi);
        state.lastSampleTime = now;
    }
}
