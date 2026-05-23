package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator Accumulation Detector
 *
 * Detects institutional/operator footprints in the option chain BEFORE price breakouts.
 * Uses cumulative OI delta since market open to identify:
 *
 *  1. SUPPORT BUILD  — Heavy PE writing below spot (operators absorbing downside; bullish)
 *  2. RESISTANCE CAP — Heavy CE writing above spot (operators selling upside; bearish)
 *  3. UNWINDING      — Rapid OI reduction at a strike (operators exiting; reversal signal)
 *
 * Key insight from May 22 analysis:
 *   - At 09:15, PE OI at 23750 = 964,860 and at 23700 = 5,762,575
 *   - By 11:30,  PE OI at 23750 = 6,937,775 (+619%!) and at 23700 = 11,849,695 (+106%)
 *   - This massive support build happened 75+ MINUTES before the 11:46 OI momentum signal
 *   - The current system only sees last-3-minute OI delta — completely blind to this buildup
 *
 * The Operator Score (0 to 100) reflects accumulated pressure:
 *   - Score > 60 = strong operator conviction → lower the entry threshold
 *   - Score > 80 = very high conviction   → can override CASE5_SKIP
 */
@Component
public class OperatorAccumulationDetector {

    private static final Logger log = LoggerFactory.getLogger(OperatorAccumulationDetector.class);

    /** Snapshot of all strike OI at market open (09:15) — set once per day. */
    private final ConcurrentHashMap<IndexType, Map<Integer, StrikeSnapshot>> openingSnapshots = new ConcurrentHashMap<>();

    /** Latest operator analysis per index. */
    private final ConcurrentHashMap<IndexType, OperatorSignal> latestSignals = new ConcurrentHashMap<>();

    /**
     * Holds per-strike OI at a point in time.
     */
    public record StrikeSnapshot(
            int strike,
            long ceOI,
            long peOI,
            double ceIV,
            double peIV,
            Instant capturedAt
    ) {}

    /**
     * The operator signal — updated each time a new chain snapshot arrives.
     */
    public static class OperatorSignal {
        /** +1 = bullish pressure, -1 = bearish pressure, 0 = neutral */
        private volatile int direction = 0;
        /** 0–100 scale of conviction. */
        private volatile int score = 0;
        /** Top strike where PE is being written (support zone). */
        private volatile int peWritingStrike = 0;
        /** Top strike where CE is being written (resistance zone). */
        private volatile int ceWritingStrike = 0;
        /** Total net PE OI added since open across ATM±3 strikes. */
        private volatile long netPeOiBuildup = 0;
        /** Total net CE OI added since open across ATM±3 strikes. */
        private volatile long netCeOiBuildup = 0;
        /** Strikes where PE buildup > 100% since open. */
        private volatile List<Integer> highConvictionPeStrikes = new ArrayList<>();
        /** Strikes where CE buildup > 100% since open. */
        private volatile List<Integer> highConvictionCeStrikes = new ArrayList<>();
        /** When this signal was computed. */
        private volatile Instant computedAt = Instant.EPOCH;
        /** Summary for logging/reporting. */
        private volatile String summary = "NONE";

        public int getDirection() { return direction; }
        public int getScore() { return score; }
        public int getPeWritingStrike() { return peWritingStrike; }
        public int getCeWritingStrike() { return ceWritingStrike; }
        public long getNetPeOiBuildup() { return netPeOiBuildup; }
        public long getNetCeOiBuildup() { return netCeOiBuildup; }
        public List<Integer> getHighConvictionPeStrikes() { return highConvictionPeStrikes; }
        public List<Integer> getHighConvictionCeStrikes() { return highConvictionCeStrikes; }
        public Instant getComputedAt() { return computedAt; }
        public String getSummary() { return summary; }

        /** True if operator signal is fresh (computed within last 12 minutes — covers two 5-min snapshot cycles + jitter). */
        public boolean isFresh() {
            return computedAt.isAfter(Instant.now().minusSeconds(720));
        }

        /** True if the operator signal aligns with a given direction (+1 CE, -1 PE). */
        public boolean alignsWith(int momentumDir) {
            return direction != 0 && direction == momentumDir;
        }

        /** True if conviction is strong enough to override CASE5_SKIP. */
        public boolean canOverrideCase5() {
            return isFresh() && score >= 65;
        }

        /** True if conviction is enough to act as a soft OI confirmation (replaces live OI ticks). */
        public boolean canConfirmOISignal() {
            return isFresh() && score >= 50;
        }

        @Override
        public String toString() {
            return summary;
        }
    }

    /**
     * Register the opening snapshot (called once at 09:15 when the first chain data arrives).
     */
    public void registerOpeningSnapshot(IndexType index, Map<Integer, StrikeSnapshot> strikeMap) {
        if (!openingSnapshots.containsKey(index)) {
            openingSnapshots.put(index, strikeMap);
            log.info("[OperatorDetector] Opening snapshot registered for {} — {} strikes", index, strikeMap.size());
        }
    }

    /**
     * Core method — analyze the current chain snapshot against the opening snapshot.
     * Called every 5 minutes when a new chain snapshot is captured.
     *
     * @param index       The index being analyzed.
     * @param currentSnap Map of strike → current OI snapshot.
     * @param spot        Current spot price.
     * @param atmStrike   Current ATM strike.
     */
    public OperatorSignal analyze(IndexType index, Map<Integer, StrikeSnapshot> currentSnap, double spot, int atmStrike) {
        Map<Integer, StrikeSnapshot> opening = openingSnapshots.get(index);
        if (opening == null || opening.isEmpty()) {
            log.debug("[OperatorDetector] No opening snapshot for {} — cannot analyze", index);
            OperatorSignal empty = new OperatorSignal();
            latestSignals.put(index, empty);
            return empty;
        }

        int interval = index.strikeInterval();
        int lookbackStrikes = 4; // ATM ± 4 strikes = 500pt range for NIFTY

        long totalPeDelta = 0;
        long totalCeDelta = 0;
        long maxPeAbsDelta = 0;
        long maxCeAbsDelta = 0;
        int maxPeStrike = 0;
        int maxCeStrike = 0;
        int highConvPeCount = 0;
        int highConvCeCount = 0;
        List<Integer> convPeStrikes = new ArrayList<>();
        List<Integer> convCeStrikes = new ArrayList<>();

        // Asymmetric scan: look at PE writing BELOW spot (support), CE writing ABOVE spot (resistance)
        for (int i = -lookbackStrikes; i <= lookbackStrikes; i++) {
            int strike = atmStrike + (i * interval);
            StrikeSnapshot open = opening.get(strike);
            StrikeSnapshot curr = currentSnap.get(strike);
            if (open == null || curr == null) continue;

            long ceDelta = curr.ceOI() - open.ceOI();
            long peDelta = curr.peOI() - open.peOI();

            // Accumulate directional OI
            totalCeDelta += ceDelta;
            totalPeDelta += peDelta;

            // Track max CE buildup (resistance strikes — above ATM)
            if (i >= 0 && ceDelta > maxCeAbsDelta) {
                maxCeAbsDelta = ceDelta;
                maxCeStrike = strike;
            }

            // Track max PE buildup (support strikes — at or below ATM)
            if (i <= 1 && peDelta > maxPeAbsDelta) {
                maxPeAbsDelta = peDelta;
                maxPeStrike = strike;
            }

            // High conviction: PE buildup > 100% of opening OI (massive accumulation)
            if (peDelta > 0 && open.peOI() > 100_000) {
                double pePct = (double) peDelta / open.peOI() * 100;
                if (pePct >= 80.0) {
                    highConvPeCount++;
                    convPeStrikes.add(strike);
                }
            }

            // High conviction: CE buildup > 80% of opening OI
            if (ceDelta > 0 && open.ceOI() > 100_000) {
                double cePct = (double) ceDelta / open.ceOI() * 100;
                if (cePct >= 80.0) {
                    highConvCeCount++;
                    convCeStrikes.add(strike);
                }
            }
        }

        OperatorSignal signal = new OperatorSignal();
        signal.netPeOiBuildup = totalPeDelta;
        signal.netCeOiBuildup = totalCeDelta;
        signal.peWritingStrike = maxPeStrike;
        signal.ceWritingStrike = maxCeStrike;
        signal.highConvictionPeStrikes = convPeStrikes;
        signal.highConvictionCeStrikes = convCeStrikes;
        signal.computedAt = Instant.now();

        // ---- Score computation ----
        // Base score from net OI imbalance ratio
        long netBias = totalPeDelta - totalCeDelta; // Positive = more PE writing = bullish
        long totalOiMoved = Math.abs(totalPeDelta) + Math.abs(totalCeDelta);

        int baseScore = 0;
        if (totalOiMoved > 1_000_000) {
            double biasRatio = totalOiMoved > 0 ? (double) Math.abs(netBias) / totalOiMoved : 0;
            baseScore = (int) Math.min(50, biasRatio * 100); // 0-50 from imbalance
        }

        // Bonus for high conviction strikes
        int convBonus = Math.min(30, (highConvPeCount + highConvCeCount) * 8);

        // Bonus for magnitude (> 5M net moved)
        int magnitudeBonus = totalOiMoved > 5_000_000 ? 15 : (totalOiMoved > 2_000_000 ? 8 : 0);

        signal.score = Math.min(100, baseScore + convBonus + magnitudeBonus);

        // Direction: if PE buildup dominates → bullish; CE buildup dominates → bearish
        if (netBias > 500_000 && totalPeDelta > totalCeDelta) {
            signal.direction = 1; // bullish — operators building support / buying puts as hedge
        } else if (netBias < -500_000 && totalCeDelta > totalPeDelta) {
            signal.direction = -1; // bearish — operators building CE resistance
        } else {
            signal.direction = 0;
        }

        // Summary string
        signal.summary = String.format(
                "OPR[%s] dir=%+d score=%d | PE_build=%+,d CE_build=%+,d | HiConvPE=%s HiConvCE=%s",
                index, signal.direction, signal.score,
                totalPeDelta, totalCeDelta,
                convPeStrikes, convCeStrikes
        );

        latestSignals.put(index, signal);
        log.info("[OperatorDetector] {}", signal.summary);
        return signal;
    }

    /**
     * Get the latest operator signal for an index.
     * Returns an empty (score=0) signal if none available.
     */
    public OperatorSignal getSignal(IndexType index) {
        return latestSignals.getOrDefault(index, new OperatorSignal());
    }

    /**
     * Reset opening snapshot at start of each trading day.
     */
    public void resetForNewDay() {
        openingSnapshots.clear();
        latestSignals.clear();
        log.info("[OperatorDetector] Reset for new trading day");
    }
}
