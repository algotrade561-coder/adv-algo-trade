package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
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

    // ── Capitulation-flip (2026-07-07) ─────────────────────────────────────────────────────────
    // The #1 expiry gap: on a genuine operator move the DOMINANT cumulative side (e.g. PE writers on a
    // bullish-biased day) capitulates — its OI unwinds fast — while the OPPOSING side builds and price
    // moves AGAINST the old cumulative direction. The cumulative-since-open bias (below) is slow to
    // reflect this because the opening writers still dominate the running total, so the detector kept
    // emitting the STALE direction into the flip. This override watches a trailing RECENT window (not
    // since-open) and, only on a triple-AND (dominant unwind% + opposing build + real price move) held
    // for confirm-ticks consecutive windows, emits the FLIPPED direction and clears the radar's
    // persistence hysteresis so the new side can lock. The cumulative base logic is untouched — on a
    // trend day the dominant side keeps building (never unwinds), so this never fires and trend days are
    // byte-identical. Flag-gated (enabled=false → inert). Triple-AND + confirm-ticks are the anti-noise
    // guard. Signal-driven — NO clock/time-window condition.
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.capitulation-flip.enabled:true}")
    private boolean capFlipEnabled = true;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.capitulation-flip.recent-window-sec:120}")
    private int capFlipRecentWindowSec = 120;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.capitulation-flip.unwind-min-pct:30}")
    private double capFlipUnwindMinPct = 30.0;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.capitulation-flip.opposing-build-min:750000}")
    private long capFlipOpposingBuildMin = 750_000L;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.capitulation-flip.confirm-ticks:2}")
    private int capFlipConfirmTicks = 2;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.capitulation-flip.min-price-move-pct:0.12}")
    private double capFlipMinPriceMovePct = 0.12;

    // ── DYNAMIC OI-shift wiring (2026-07-08) ─────────────────────────────────────────────────────
    // Optional collaborator: null (unit tests) or enabled=false → every operator threshold below stays at
    // its exact legacy fixed value. Shares the single kill-switch oi-momentum.dynamic-floor.enabled.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DynamicOiFloor dynamicOiFloor;

    /** SLOT 4: opposing-build required as a % of the OPPOSING band's own OI (clamp-low at the legacy 750k). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.capitulation-flip.opposing-build-pct:1.5}")
    private double capFlipOpposingBuildPct = 1.5;

    /** SLOT 5: reference band OI (ATM±6, both sides) the legacy score floors were tuned against (~fresh day). */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.dynamic.band-ref-oi:125000000}")
    private long opBandRefOi = 125_000_000L;

    /** SLOT 5: max multiple the operator score floors may be scaled UP on a high-OI (expiry) day. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.operator.dynamic.scale-max:8.0}")
    private double opScaleMax = 8.0;

    /** Reference-OI floor: the dominant side's band OI at the recent-window start must clear this before
     *  an unwind % is trusted, so a partially-populated warm-up band can't spike a spurious flip. */
    private static final long CAP_FLIP_MIN_REF_OI = 500_000L;

    /** Radar whose persistence lock is cleared on a flip. Optional so unit tests / minimal wirings work. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private OperatorIntentRadar operatorIntentRadar;

    /** Trailing per-index band-OI history for the recent-window flip probe (oldest-first). */
    private final ConcurrentHashMap<IndexType, Deque<RecentSample>> recentHistory = new ConcurrentHashMap<>();
    /** Consecutive-window confirmation counter per index for the flip. */
    private final ConcurrentHashMap<IndexType, Integer> flipConfirm = new ConcurrentHashMap<>();

    /** One trailing sample: band-aggregate CE/PE OI (ATM±lookback) + spot at a point in time. */
    private record RecentSample(Instant at, long ceBand, long peBand, double spot) {}

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
        /**
         * True when the direction on THIS signal is the result of a capitulation-flip override
         * (dominant-side writer unwind + opposing build + confirmed price move against the old
         * direction), not the raw cumulative-since-open bias. Consumers use this as one trigger
         * of the signal-driven "operator move" mode. Reset to false on every analyze() that does
         * not fire a flip, so it only reads true while the flip conditions genuinely hold.
         */
        private volatile boolean capitulationFlip = false;

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
        public boolean isCapitulationFlip() { return capitulationFlip; }

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
        int lookbackStrikes = 6; // ATM ± 6 strikes (expanded from ±4 to catch deeper operator footprints)

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

        // Recent-window band aggregates (current snapshot, ATM±lookback) for the capitulation-flip probe.
        // Summed over whatever strikes are present in currentSnap (independent of the opening baseline) so
        // a trailing recent-window delta is well-defined even for strikes absent from the 09:15 snapshot.
        long curCeBand = 0;
        long curPeBand = 0;

        // Asymmetric scan: look at PE writing BELOW spot (support), CE writing ABOVE spot (resistance)
        for (int i = -lookbackStrikes; i <= lookbackStrikes; i++) {
            int strike = atmStrike + (i * interval);
            StrikeSnapshot curr = currentSnap.get(strike);
            if (curr != null) {
                curCeBand += curr.ceOI();
                curPeBand += curr.peOI();
            }
            StrikeSnapshot open = opening.get(strike);
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

        // SLOT 5 (2026-07-08): the operator score/direction floors are DYNAMIC — scaled UP on high-OI days
        // so a fixed 1M/5M/2M/500k (tuned on a ~fresh-week band) doesn't over-fire on an expiry band that
        // carries ~5× the parked OI. bandScale = clamp(curBand / band-ref, 1.0, scale-max): it is 1.0 on a
        // fresh day (byte-identical to legacy) and rises toward ~5× on expiry. Clamped LOW at 1.0 so the
        // dynamic path can only RAISE the bar (never become more permissive than legacy) — the safe
        // direction. Warm-up (curBand==0) / dynamic-floor-off → scale 1.0 = exact legacy.
        long curBand = curCeBand + curPeBand;
        boolean opDyn = dynamicOiFloor != null && dynamicOiFloor.isEnabled() && curBand > 0 && opBandRefOi > 0;
        double bandScale = opDyn ? Math.max(1.0, Math.min(opScaleMax, (double) curBand / opBandRefOi)) : 1.0;
        long baseGate = Math.round(1_000_000L * bandScale);
        long magHiGate = Math.round(5_000_000L * bandScale);
        long magLoGate = Math.round(2_000_000L * bandScale);
        long dirFloor = Math.round(500_000L * bandScale);

        int baseScore = 0;
        if (totalOiMoved > baseGate) {
            double biasRatio = totalOiMoved > 0 ? (double) Math.abs(netBias) / totalOiMoved : 0;
            baseScore = (int) Math.min(70, biasRatio * 140); // 0-70 from imbalance (raised from 50)
        }

        // Bonus for high conviction strikes
        int convBonus = Math.min(30, (highConvPeCount + highConvCeCount) * 8);

        // Bonus for magnitude (> 5M net moved, band-scaled)
        int magnitudeBonus = totalOiMoved > magHiGate ? 15 : (totalOiMoved > magLoGate ? 8 : 0);

        signal.score = Math.min(100, baseScore + convBonus + magnitudeBonus);

        // Direction: if PE buildup dominates → bullish; CE buildup dominates → bearish (netBias floor band-scaled)
        if (netBias > dirFloor && totalPeDelta > totalCeDelta) {
            signal.direction = 1; // bullish — operators building support / buying puts as hedge
        } else if (netBias < -dirFloor && totalCeDelta > totalPeDelta) {
            signal.direction = -1; // bearish — operators building CE resistance
        } else {
            signal.direction = 0;
        }

        // ── Capitulation-flip override (signal-driven, NO clock) ────────────────────────────────
        // Runs AFTER the cumulative direction above (which stays byte-identical). Watches a trailing
        // recent window: if the DOMINANT cumulative side is unwinding fast AND the opposing side is
        // building AND spot has moved against the old direction — held confirm-ticks windows — emit the
        // FLIPPED direction and clear the radar persistence lock. Only mutates direction when it fires.
        boolean flipFired = false;
        if (capFlipEnabled && signal.direction != 0) {
            Deque<RecentSample> hist = recentHistory.computeIfAbsent(index, k -> new ArrayDeque<>());
            RecentSample cur = new RecentSample(signal.computedAt, curCeBand, curPeBand, spot);

            // Reference = newest prior sample at least recent-window-sec old (iterating oldest→newest).
            RecentSample ref = null;
            for (RecentSample s : hist) {
                if (Duration.between(s.at(), cur.at()).getSeconds() >= capFlipRecentWindowSec) {
                    ref = s;
                } else {
                    break;
                }
            }

            if (ref != null) {
                long refDominantOi;
                double dominantUnwindPct;
                long opposingBuild;
                boolean spotAgainst;
                int flippedDir;
                double spotMovePct = ref.spot() > 0 ? (cur.spot() - ref.spot()) / ref.spot() * 100.0 : 0.0;
                long refOpposingBand;
                if (signal.direction == 1) {
                    // Old bias bullish → dominant written side = PE. Flip bearish on PE unwind + CE build + spot down.
                    refDominantOi = ref.peBand();
                    dominantUnwindPct = refDominantOi > 0 ? (double) (ref.peBand() - cur.peBand()) / refDominantOi * 100.0 : 0.0;
                    opposingBuild = cur.ceBand() - ref.ceBand();
                    refOpposingBand = ref.ceBand();
                    spotAgainst = spotMovePct <= -capFlipMinPriceMovePct;
                    flippedDir = -1;
                } else {
                    // Old bias bearish → dominant written side = CE. Flip bullish on CE unwind + PE build + spot up.
                    refDominantOi = ref.ceBand();
                    dominantUnwindPct = refDominantOi > 0 ? (double) (ref.ceBand() - cur.ceBand()) / refDominantOi * 100.0 : 0.0;
                    opposingBuild = cur.peBand() - ref.peBand();
                    refOpposingBand = ref.peBand();
                    spotAgainst = spotMovePct >= capFlipMinPriceMovePct;
                    flippedDir = 1;
                }
                // SLOT 4 (2026-07-08): the opposing-build magnitude is DYNAMIC — required as a % of the
                // OPPOSING band's own OI (mirrors the already-normalized dominantUnwindPct), not a fixed 750k
                // that is ~p92 of a fresh-day minute but below p10 of an expiry minute. Clamp-LOW at the
                // legacy 750k so it can only RAISE the bar on a high-OI day (never fire more easily than
                // today). Dynamic-floor-off / no opposing band → legacy fixed 750k.
                long opposingBuildFloor = capFlipOpposingBuildMin;
                if (dynamicOiFloor != null && dynamicOiFloor.isEnabled() && refOpposingBand > 0) {
                    long pctFloor = Math.round(capFlipOpposingBuildPct / 100.0 * refOpposingBand);
                    opposingBuildFloor = Math.max(capFlipOpposingBuildMin, pctFloor);
                }
                boolean cond = refDominantOi >= CAP_FLIP_MIN_REF_OI
                        && dominantUnwindPct >= capFlipUnwindMinPct
                        && opposingBuild >= opposingBuildFloor
                        && spotAgainst;
                int cc = cond ? flipConfirm.getOrDefault(index, 0) + 1 : 0;
                flipConfirm.put(index, cc);
                if (cond && cc >= capFlipConfirmTicks) {
                    int oldDir = signal.direction;
                    signal.direction = flippedDir;
                    flipFired = true;
                    if (operatorIntentRadar != null) {
                        try { operatorIntentRadar.resetPersistenceLock(index); }
                        catch (Exception ignore) { /* radar reset best-effort */ }
                    }
                    log.info("[OperatorDetector][{}] CAPITULATION_FLIP {}→{} | dominantUnwind={}% opposingBuild={} spotMove={}% confirm={}/{}",
                            index, oldDir, flippedDir,
                            String.format("%.1f", dominantUnwindPct), opposingBuild,
                            String.format("%.2f", spotMovePct), cc, capFlipConfirmTicks);
                }
            }

            hist.addLast(cur);
            // Trim history older than 5× the window (bounds memory; well beyond the reference reach-back).
            long maxAgeSec = (long) capFlipRecentWindowSec * 5L;
            while (!hist.isEmpty()
                    && Duration.between(hist.peekFirst().at(), cur.at()).getSeconds() > maxAgeSec) {
                hist.removeFirst();
            }
        }
        signal.capitulationFlip = flipFired;

        // Summary string
        signal.summary = String.format(
                "OPR[%s] dir=%+d score=%d | PE_build=%+,d CE_build=%+,d | HiConvPE=%s HiConvCE=%s",
                index, signal.direction, signal.score,
                totalPeDelta, totalCeDelta,
                convPeStrikes, convCeStrikes
        );
        if (flipFired) {
            signal.summary += " [CAPITULATION_FLIP]";
        }

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
        recentHistory.clear();
        flipConfirm.clear();
        log.info("[OperatorDetector] Reset for new trading day");
    }
}
