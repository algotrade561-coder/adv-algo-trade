package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.domain.IndexType;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * V3 OPERATOR — Kelly-restrained position sizer.
 *
 * <p>Combines four factors into a single conviction multiplier, capped at 1.5×:</p>
 * <pre>
 *   conviction = gatesScore × patternScore × timeWindowMultiplier × volRegimeMultiplier
 *
 *   lots = max(1, round(baseLots × min(1.5, conviction)))
 * </pre>
 *
 * <p>The 1.5× cap is the hard quality gate that survives all conviction levels — a
 * "perfect" signal sizes at +50% of baseline, not 5×. This is the survival-over-optimization
 * principle.</p>
 */
@Component
public class ConvictionSizer {

    /** §3.7: optional source-quality lot multiplier. Null-safe; no-op (1.0) unless enabled + warmed. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.risk.SourcePerformanceTracker sourcePerformanceTracker;

    /**
     * Internal raw-conviction ceiling — before the hard lot cap is applied. Values
     * above this still resolve to {@code maxLotsPerTrade}. The intent is to give
     * the sizer headroom (1.5×) so the strongest setups reach the cap, while the
     * absolute lot count is bounded by {@code maxLotsPerTrade} from GlobalConfig.
     */
    public static final double MAX_MULTIPLIER = 1.5;

    /** Signal-strength → lots calibration (2026-07-02). Conviction at/below {@code convMin} sizes at the
     *  BASELINE; conviction at/above {@code convStrong} sizes at the FULL {@code maxLotsPerTrade} ceiling;
     *  linear in between. Replaces the old {@code conviction / MAX_MULTIPLIER} normalization — because real
     *  conviction is a product of sub-1.0 factors and tops out ~1.1, dividing by 1.5 made the ceiling
     *  UNREACHABLE (even a perfect signal only reached ~73% of the headroom). Tunable live via application.yml. */
    @org.springframework.beans.factory.annotation.Value("${trading.v3.sizing.conviction-min:0.50}")
    private double convMin;

    @org.springframework.beans.factory.annotation.Value("${trading.v3.sizing.conviction-strong:0.90}")
    private double convStrong;

    /** Result of a sizing decision — carries the breakdown for end-of-day validation. */
    public record SizingResult(
            int lots,
            double convictionRaw,
            double convictionCapped,
            double gatesScore,
            double patternScore,
            double timeWindowMultiplier,
            double volRegimeMultiplier,
            String breakdown
    ) {}

    /**
     * Compute the lots to enter given the entry context.
     *
     * <p><b>Units:</b> {@code baseLotCount} and the returned {@code lots} are both in
     * <i>lot count</i> (not share count). Callers convert to shares by multiplying
     * the returned lot count by {@code indexType.lotSize()} before sending to the
     * broker. This guarantees the broker only ever sees quantities that are exact
     * multiples of the contract lot size.</p>
     *
     * <p><b>Cap:</b> The final lot count is hard-capped at {@code maxLotsPerTrade}
     * (sourced from GlobalConfig). Strong-conviction setups grow towards the cap;
     * weak ones float near 1. A baseline of {@code baseLotCount=1} means: minimum
     * one lot, scale up to maxLotsPerTrade for high-conviction signals.</p>
     *
     * @param ix              index (used only for log context)
     * @param baseLotCount    baseline lot count (typically 1) — the conviction
     *                        multiplier scales this up; lots is then capped at
     *                        {@code maxLotsPerTrade}
     * @param maxLotsPerTrade hard ceiling on lot count, from GlobalConfig
     * @param gatesPassed     how many of the 4 operator gates passed (0–4)
     * @param requiredGates   the gate-count required for this time window
     * @param pattern         the OI pattern detected
     * @param mode            the time-of-day mode
     * @param regimes         active regime tags
     * @param ivPercentile    current IV percentile (0–100)
     */
    public SizingResult size(IndexType ix, int baseLotCount, int maxLotsPerTrade,
                              int gatesPassed, int requiredGates,
                              OiPattern pattern, TimeOfDayMode mode,
                              Set<Regime> regimes, double ivPercentile) {
        // gatesScore: scales 0.6 at threshold, 1.0 at all-four. Floor 0.
        // Fixed formula: linearly interpolate from 0.6 at `requiredGates` to 1.0 at 4.
        // This guarantees 4/4 → 1.0 regardless of how many were required, so the
        // strongest signal at the easiest window still gets the full multiplier.
        double gatesScore;
        if (gatesPassed < requiredGates) {
            gatesScore = 0;
        } else if (gatesPassed >= 4) {
            gatesScore = 1.0;                              // 4/4 always = 1.0
        } else if (requiredGates >= 4) {
            gatesScore = 1.0;                              // required==passed==4
        } else {
            // Linear: 0.6 at requiredGates, 1.0 at 4 ⇒ slope = 0.4/(4-required)
            double slope = 0.4 / (4 - requiredGates);
            gatesScore = 0.6 + (gatesPassed - requiredGates) * slope;
        }
        gatesScore = Math.min(1.0, gatesScore);

        double patternScore = pattern.qualityScore();
        double timeMult = mode.sizeMultiplier();

        // Vol regime multiplier:
        //   HIGH_VOL → 0.85 (each trade carries more risk)
        //   LOW_VOL  → 1.0  (smaller swings, normal sizing OK)
        //   NORMAL   → 1.0
        // IV pct > 80 → 0.7 (long premium expensive)
        // IV pct < 30 (vol about to expand) → 1.1
        double volMult = 1.0;
        if (regimes.contains(Regime.HIGH_VOL)) volMult *= 0.85;
        if (ivPercentile > 80) volMult *= 0.7;
        else if (ivPercentile < 30) volMult *= 1.1;

        double convictionRaw = gatesScore * patternScore * timeMult * volMult;
        double convictionCapped = Math.min(MAX_MULTIPLIER, convictionRaw);

        // Lot-count formula:
        //   raw   = baseLotCount × convictionCapped
        //   lots  = clamp(round(raw), 1, maxLotsPerTrade)
        // With baseLotCount=1 and MAX_MULTIPLIER=1.5, a top-conviction signal
        // would resolve to round(1 × 1.5) = 2 lots. To let the strongest setups
        // actually reach the maxLotsPerTrade ceiling (e.g. 10), the convictionCapped
        // factor scales the *headroom* between the baseline and the cap:
        //   lots = baseLotCount + round((maxLotsPerTrade - baseLotCount) × (convictionCapped / MAX_MULTIPLIER))
        // This guarantees:
        //   convictionCapped = 0           → 1 lot (minimum)
        //   convictionCapped = MAX_MULTIPLIER (1.5) → maxLotsPerTrade (full cap)
        //   convictionCapped = 0.75 (mid)   → ~midway between baseline and cap
        int safeMax = Math.max(1, maxLotsPerTrade);
        int safeBase = Math.max(1, Math.min(baseLotCount, safeMax));
        int headroom = safeMax - safeBase;
        // Signal-strength scaling: 0 at convMin (weak → baseline), 1 at convStrong (strong → FULL ceiling),
        // linear between. This lets a genuinely strong signal actually reach maxLotsPerTrade.
        double lo = convMin, hi = (convStrong > convMin ? convStrong : convMin + 0.01);
        double strength = (convictionCapped - lo) / (hi - lo);
        if (strength < 0) strength = 0;
        else if (strength > 1) strength = 1;
        int scaled = safeBase + (int) Math.round(headroom * strength);
        // §3.7: down/up-weight by this source's recent realized quality (1.0 no-op unless enabled).
        double srcMult = sourcePerformanceTracker != null
                ? sourcePerformanceTracker.lotMultiplier("oi_momentum") : 1.0;
        if (srcMult != 1.0) scaled = (int) Math.round(scaled * srcMult);
        int lots = Math.max(1, Math.min(safeMax, scaled));
        // If gates failed entirely, lots = 0 → no entry
        if (gatesScore == 0 || patternScore == 0 || timeMult == 0) lots = 0;

        String breakdown = String.format(
                "gates=%.2f × pattern=%.2f × time=%.2f × vol=%.2f = %.2f (capped %.2f, strength %.2f in [%.2f..%.2f]) → %d lots (base=%d, max=%d)",
                gatesScore, patternScore, timeMult, volMult,
                convictionRaw, convictionCapped, strength, convMin, convStrong, lots, safeBase, safeMax);

        return new SizingResult(lots, convictionRaw, convictionCapped,
                gatesScore, patternScore, timeMult, volMult, breakdown);
    }
}
