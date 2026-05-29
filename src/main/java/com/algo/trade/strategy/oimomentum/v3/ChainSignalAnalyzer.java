package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3 OPERATOR — Chain-wide OI confluence analyzer.
 *
 * <p>Detects six patterns: WRITER_SQUEEZE / PE_SQUEEZE / PE_SUPPORT / CE_RESISTANCE /
 * TRAP_APPROACH (P1b: per-strike imbalance with spot moving toward it) and
 * WRITER_REVERSAL (P1b: dominant-side flip vs an opening baseline established at
 * 09:20 IST). Falls back to CONFLICTING / INSUFFICIENT.</p>
 *
 * <p>Holds per-index opening baselines; otherwise stateless and thread-safe.</p>
 */
@Component
public class ChainSignalAnalyzer {

    /** Min |sumΔ| (in OI contracts) to call a SQUEEZE pattern. */
    public static final long SIGNIFICANCE = 500_000L;

    /** Ratio by which one side's flow must exceed the other to be "dominant". */
    public static final double DOMINANCE = 1.5;

    /** Min absolute OI for any single strike to be a trap-approach candidate. */
    public static final long TRAP_MIN_ABSOLUTE_OI = 50_000L;

    /** Trap-imbalance ratio threshold (trapped side OI / opposite side OI). */
    public static final double TRAP_IMBALANCE_RATIO = 1.8;

    /** Max distance of trap strike from spot (as % of spot) to count as "approaching". */
    public static final double TRAP_PROXIMITY_PCT = 0.50;

    /** Min strength ratio for a flip to count as WRITER_REVERSAL. */
    public static final double REVERSAL_FLIP_STRENGTH = 1.2;

    /** Per-index opening baseline (captured on first call after 09:15 IST). */
    private final Map<IndexType, BaselineSnapshot> openingBaselines = new ConcurrentHashMap<>();

    /** Tracks last spot per index so we can decide "moving toward strike". */
    private final Map<IndexType, Double> lastSpotForTrend = new ConcurrentHashMap<>();

    /** Snapshot of opening OI direction kept for reversal detection. */
    private record BaselineSnapshot(int direction, double strength, Instant capturedAt) {}

    /**
     * Compute the chain-wide OI signal for the given snapshot.
     *
     * @param ix       index type (used for ATM computation)
     * @param snapshot chain snapshot (must have ≥ 7 strikes around ATM)
     * @return OiSignal with named pattern + support/resistance strikes
     */
    public OiSignal analyze(IndexType ix, ChainSnapshot snapshot) {
        if (snapshot == null || snapshot.strikes() == null || snapshot.strikes().size() < 7) {
            return OiSignal.EMPTY;
        }
        int atm = snapshot.atmStrike();
        long sumCeChg = 0;
        long sumPeChg = 0;
        long maxCeBuild = Long.MIN_VALUE;
        long maxPeBuild = Long.MIN_VALUE;
        int maxCeStrike = 0;
        int maxPeStrike = 0;

        for (ChainSnapshot.StrikeData s : snapshot.strikes()) {
            long ceChg = s.ceOiChange();
            long peChg = s.peOiChange();
            sumCeChg += ceChg;
            sumPeChg += peChg;
            // Track largest CE build at-or-above ATM (forming resistance)
            if (s.strike() >= atm && ceChg > maxCeBuild) {
                maxCeBuild = ceChg;
                maxCeStrike = s.strike();
            }
            // Track largest PE build at-or-below ATM (forming support)
            if (s.strike() <= atm && peChg > maxPeBuild) {
                maxPeBuild = peChg;
                maxPeStrike = s.strike();
            }
        }
        if (maxCeBuild == Long.MIN_VALUE) maxCeBuild = 0;
        if (maxPeBuild == Long.MIN_VALUE) maxPeBuild = 0;

        // ── P1b: TRAP_APPROACH — single-strike imbalance with spot approaching ──
        // Scan strikes for high CE/PE imbalance (writers caught at a strike); if spot
        // is moving toward that strike, emit TRAP_APPROACH biased toward the trap dir.
        double spot = snapshot.spot();
        Double lastSpot = lastSpotForTrend.put(ix, spot);
        int spotMoveDir = (lastSpot != null && spot > lastSpot) ? +1
                : (lastSpot != null && spot < lastSpot) ? -1 : 0;
        OiSignal trap = detectTrapApproach(snapshot, atm, spot, spotMoveDir,
                sumCeChg, sumPeChg, maxCeBuild, maxPeBuild, maxCeStrike, maxPeStrike);
        if (trap != null) return trap;

        // BULL WRITER_SQUEEZE: heavy CE unwind + PE growing
        if (sumCeChg < -SIGNIFICANCE && sumPeChg > 0) {
            double strength = Math.abs(sumCeChg) / (double) Math.max(sumPeChg, 1);
            recordOpeningBaseline(ix, +1, strength);
            return new OiSignal(+1, OiPattern.WRITER_SQUEEZE.name(),
                    strength, maxPeStrike, maxCeStrike,
                    sumCeChg, sumPeChg, maxCeBuild, maxPeBuild);
        }
        // BEAR PE_SQUEEZE: heavy PE unwind + CE growing
        if (sumPeChg < -SIGNIFICANCE && sumCeChg > 0) {
            double strength = Math.abs(sumPeChg) / (double) Math.max(sumCeChg, 1);
            recordOpeningBaseline(ix, -1, strength);
            return new OiSignal(-1, OiPattern.PE_SQUEEZE.name(),
                    strength, maxPeStrike, maxCeStrike,
                    sumCeChg, sumPeChg, maxCeBuild, maxPeBuild);
        }
        // BULL PE_SUPPORT: PE building dominant + max PE build at-or-below ATM
        if (sumPeChg > 0 && sumPeChg > DOMINANCE * Math.abs(sumCeChg) && maxPeStrike <= atm) {
            double strength = sumPeChg / (double) Math.max(Math.abs(sumCeChg), 1);
            // P1b: check for WRITER_REVERSAL vs opening baseline before emitting PE_SUPPORT
            OiSignal rev = checkReversal(ix, +1, strength, sumCeChg, sumPeChg, maxCeBuild,
                    maxPeBuild, maxPeStrike, maxCeStrike);
            if (rev != null) return rev;
            recordOpeningBaseline(ix, +1, strength);
            return new OiSignal(+1, OiPattern.PE_SUPPORT.name(),
                    strength, maxPeStrike, maxCeStrike,
                    sumCeChg, sumPeChg, maxCeBuild, maxPeBuild);
        }
        // BEAR CE_RESISTANCE: CE building dominant + max CE build at-or-above ATM
        if (sumCeChg > 0 && sumCeChg > DOMINANCE * Math.abs(sumPeChg) && maxCeStrike >= atm) {
            double strength = sumCeChg / (double) Math.max(Math.abs(sumPeChg), 1);
            // P1b: check for WRITER_REVERSAL vs opening baseline before emitting CE_RESISTANCE
            OiSignal rev = checkReversal(ix, -1, strength, sumCeChg, sumPeChg, maxCeBuild,
                    maxPeBuild, maxPeStrike, maxCeStrike);
            if (rev != null) return rev;
            recordOpeningBaseline(ix, -1, strength);
            return new OiSignal(-1, OiPattern.CE_RESISTANCE.name(),
                    strength, maxPeStrike, maxCeStrike,
                    sumCeChg, sumPeChg, maxCeBuild, maxPeBuild);
        }
        // Conflicting: both sides building significantly → SKIP
        if (sumCeChg > 0 && sumPeChg > 0
                && Math.min(sumCeChg, sumPeChg) > SIGNIFICANCE * 0.5) {
            return new OiSignal(0, OiPattern.CONFLICTING.name(),
                    1.0, maxPeStrike, maxCeStrike,
                    sumCeChg, sumPeChg, maxCeBuild, maxPeBuild);
        }
        // No clear signal
        return new OiSignal(0, OiPattern.INSUFFICIENT.name(),
                0, 0, 0, sumCeChg, sumPeChg, maxCeBuild, maxPeBuild);
    }

    /**
     * P1b TRAP_APPROACH detector — finds a single strike with extreme CE/PE imbalance
     * AND positive OI build on the trapped side AND spot is moving toward that strike.
     *
     * @param spotMoveDir +1 if spot rising vs previous tick, -1 falling, 0 unknown
     * @return TRAP_APPROACH OiSignal or null if no trap candidate
     */
    private OiSignal detectTrapApproach(ChainSnapshot snap, int atm, double spot,
                                          int spotMoveDir, long sumCeChg, long sumPeChg,
                                          long maxCeBuild, long maxPeBuild,
                                          int maxCeStrike, int maxPeStrike) {
        if (spotMoveDir == 0) return null;

        int bestStrike = 0;
        double bestImbalance = 0;
        int trapDirection = 0;
        long bestTrappedOi = 0;
        for (ChainSnapshot.StrikeData s : snap.strikes()) {
            double proximityPct = Math.abs(s.strike() - spot) / spot * 100.0;
            if (proximityPct > TRAP_PROXIMITY_PCT) continue;

            // Spot rising → look for trapped CE writers at strikes ABOVE spot
            if (spotMoveDir > 0 && s.strike() >= atm) {
                if (s.ceOI() < TRAP_MIN_ABSOLUTE_OI) continue;
                if (s.ceOiChange() < 3_000) continue;
                double imb = s.ceOI() / (double) Math.max(s.peOI(), 1);
                if (imb >= TRAP_IMBALANCE_RATIO && imb > bestImbalance) {
                    bestImbalance = imb;
                    bestStrike = s.strike();
                    trapDirection = +1;        // squeeze direction = up toward trapped CE
                    bestTrappedOi = s.ceOI();
                }
            }
            // Spot falling → look for trapped PE writers at strikes BELOW spot
            if (spotMoveDir < 0 && s.strike() <= atm) {
                if (s.peOI() < TRAP_MIN_ABSOLUTE_OI) continue;
                if (s.peOiChange() < 3_000) continue;
                double imb = s.peOI() / (double) Math.max(s.ceOI(), 1);
                if (imb >= TRAP_IMBALANCE_RATIO && imb > bestImbalance) {
                    bestImbalance = imb;
                    bestStrike = s.strike();
                    trapDirection = -1;
                    bestTrappedOi = s.peOI();
                }
            }
        }
        if (bestStrike == 0) return null;

        // Trap support/resistance strikes for the signal: place the trap strike in
        // the direction matching the squeeze, max-build strike on the opposite side.
        int support = trapDirection > 0 ? maxPeStrike : bestStrike;
        int resistance = trapDirection > 0 ? bestStrike : maxCeStrike;
        return new OiSignal(trapDirection, OiPattern.TRAP_APPROACH.name(),
                bestImbalance, support, resistance,
                sumCeChg, sumPeChg, maxCeBuild, maxPeBuild);
    }

    /**
     * P1b: capture an opening baseline at 09:15–09:25 IST for later reversal detection.
     * Only sets the baseline if none exists yet for the current trading day.
     */
    private void recordOpeningBaseline(IndexType ix, int direction, double strength) {
        Instant now = Instant.now();
        BaselineSnapshot existing = openingBaselines.get(ix);
        if (existing == null || Duration.between(existing.capturedAt(), now).toHours() >= 8) {
            // Stale (next day) or absent → record fresh baseline.
            openingBaselines.put(ix, new BaselineSnapshot(direction, strength, now));
        }
    }

    /**
     * P1b WRITER_REVERSAL detector: compare current direction to the opening baseline.
     * If the dominant writing side has FLIPPED vs morning baseline and is stronger by
     * REVERSAL_FLIP_STRENGTH, emit a WRITER_REVERSAL signal in the new direction.
     */
    private OiSignal checkReversal(IndexType ix, int currentDir, double currentStrength,
                                     long sumCeChg, long sumPeChg, long maxCeBuild, long maxPeBuild,
                                     int supportStrike, int resistanceStrike) {
        BaselineSnapshot baseline = openingBaselines.get(ix);
        if (baseline == null) return null;
        if (baseline.direction() == 0 || baseline.direction() == currentDir) return null;
        if (currentStrength < REVERSAL_FLIP_STRENGTH) return null;
        // It's a genuine flip with material strength — emit WRITER_REVERSAL.
        // The "winning" side becomes the new direction; clear baseline so we don't
        // re-emit reversal on every tick.
        openingBaselines.put(ix, new BaselineSnapshot(currentDir, currentStrength, Instant.now()));
        return new OiSignal(currentDir, OiPattern.WRITER_REVERSAL.name(),
                currentStrength, supportStrike, resistanceStrike,
                sumCeChg, sumPeChg, maxCeBuild, maxPeBuild);
    }
}
