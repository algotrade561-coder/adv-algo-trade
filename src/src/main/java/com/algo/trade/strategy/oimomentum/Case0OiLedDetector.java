package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.oimomentum.v3.MarketContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * CASE 0 — OI-led entry detector.
 *
 * <p>The legacy CASE 1–5 matrix requires a price breakout (from
 * {@link TickMomentumDetector#detect}) before any entry is even evaluated. That
 * misses the high-quality setups where operators have visibly tilted the chain
 * for 30+ minutes but spot is still coiling. The May 22 root case (PE OI at
 * 23750 +619% by 11:30, momentum signal only at 11:46 entering 25 pts late)
 * is the canonical example.</p>
 *
 * <p><b>Trigger conditions</b> (all required, calibrated from the 12-day
 * replay against actual chain snapshots — see
 * {@code OI_MOMENTUM_EMPIRICAL_REPLAY_RESULTS.md}):</p>
 * <ol>
 *   <li>Operator framework score ≥ {@code case0OpScoreThreshold} (default 80).</li>
 *   <li>Operator direction is non-zero.</li>
 *   <li>Spot 20-min range as % of spot ≤ {@code case0CoilMaxPct} (default 0.10%).</li>
 *   <li>PCR slope over 5 min agrees with the operator direction in magnitude
 *       at least {@code case0PcrSlopeMinAbs} (default 0.02).</li>
 * </ol>
 *
 * <p>Empirical performance at strict thresholds: 12 fires across 12 days × 2
 * indices, 91% 30-min win rate, mean +0.103% spot move. Zero overlap with
 * baseline CASE 1–5 fires — purely additive, catches setups the breakout-first
 * detector cannot see.</p>
 *
 * <p>Output: {@link Decision} with direction, op_score, coil range, PCR slope, and
 * the timestamps used for forensic review.</p>
 */
@Component
public class Case0OiLedDetector {

    private static final Logger log = LoggerFactory.getLogger(Case0OiLedDetector.class);

    private final OperatorFrameworkService operatorFrameworkService;
    private final TickMomentumDetector momentumDetector;

    /**
     * V3 service that already tracks PCR history. Optional — when not wired (e.g. in
     * unit tests that only inject Case0OiLedDetector standalone), the detector
     * gracefully degrades to "PCR slope unavailable" → CASE 0 cannot fire.
     */
    @Autowired(required = false)
    private MarketContextService marketContext;

    /**
     * R3 — Adaptive CASE 0 reads VIX from MarketGuard to decide between the strict
     * tier (high-VIX) and the loosened low-VIX tier. Optional so tests can construct
     * without the full Spring context.
     */
    @Autowired(required = false)
    private com.algo.trade.risk.MarketGuard marketGuard;

    public Case0OiLedDetector(OperatorFrameworkService operatorFrameworkService,
                              TickMomentumDetector momentumDetector) {
        this.operatorFrameworkService = operatorFrameworkService;
        this.momentumDetector = momentumDetector;
    }

    /** Outcome of a CASE 0 evaluation — captured into LegacyDetectionRecord. */
    public record Decision(
            boolean fires,
            int direction,            // +1, -1, or 0 (when not firing)
            int opScore,
            double rangePct,          // 20-min coil range as %
            double pcrSlope5Min,
            String reason             // human-readable; "ok" when firing
    ) {
        public static Decision skip(String reason, int opScore, double rangePct,
                                    double pcrSlope) {
            return new Decision(false, 0, opScore, rangePct, pcrSlope, reason);
        }
        public static Decision fire(int direction, int opScore, double rangePct,
                                    double pcrSlope) {
            return new Decision(true, direction, opScore, rangePct, pcrSlope, "ok");
        }
    }

    /**
     * Evaluate CASE 0 for the given index using current operator + spot + PCR state.
     *
     * @param index   index to evaluate
     * @param config  current OIMomentumConfig (thresholds + enable flag)
     * @return Decision (may be a skip; never null)
     */
    public Decision evaluate(IndexType index, OIMomentumConfig config) {
        if (config == null || !config.isCase0Enabled()) {
            return Decision.skip("disabled", 0, 0, 0);
        }
        if (operatorFrameworkService == null) {
            return Decision.skip("no_operator_framework", 0, 0, 0);
        }

        OperatorAccumulationDetector.OperatorSignal sig =
                operatorFrameworkService.getOperatorSignal(index);

        // Need a fresh, directional operator signal (any tier).
        int opScore = sig == null ? 0 : sig.getScore();
        int opDir = sig == null ? 0 : sig.getDirection();
        if (sig == null || !sig.isFresh()) {
            return Decision.skip("op_signal_stale", opScore, 0, 0);
        }
        if (opDir == 0) {
            return Decision.skip("op_dir_neutral", opScore, 0, 0);
        }

        // R3 — Adaptive tier selection (29 May 2026 — replay-validated 82% 60m win).
        // If VIX is below the low-VIX threshold AND the low-VIX tier is enabled,
        // use the looser thresholds. Otherwise use the strict (original) thresholds.
        double currentVix = (marketGuard != null) ? marketGuard.getCurrentVix() : 0.0;
        boolean lowVixActive = config.isCase0LowVixEnabled()
                && currentVix > 0 && currentVix < config.getCase0LowVixVixThreshold();
        int effectiveOpThr = lowVixActive
                ? config.getCase0LowVixOpScoreThreshold() : config.getCase0OpScoreThreshold();
        double effectiveCoilMax = lowVixActive
                ? config.getCase0LowVixCoilMaxPct() : config.getCase0CoilMaxPct();
        String tier = lowVixActive ? "LOW_VIX" : "STRICT";

        if (opScore < effectiveOpThr) {
            return Decision.skip(String.format("op_score_below_%d_tier=%s", effectiveOpThr, tier),
                    opScore, 0, 0);
        }

        // Coil check using the tier-appropriate threshold.
        double rangePct = compute20MinRangePct(index);
        if (Double.isNaN(rangePct)) {
            return Decision.skip("range_unavailable", opScore, 0, 0);
        }
        if (rangePct > effectiveCoilMax) {
            return Decision.skip(String.format("not_coiled_range=%.3f%%_tier=%s", rangePct, tier),
                    opScore, rangePct, 0);
        }

        // PCR slope agrees with operator direction (both tiers).
        double pcrSlope = (marketContext != null) ? marketContext.pcrSlope5Min(index) : 0.0;
        double need = config.getCase0PcrSlopeMinAbs();
        boolean pcrAgrees =
                (opDir > 0 && pcrSlope >= +need)
             || (opDir < 0 && pcrSlope <= -need);
        if (!pcrAgrees) {
            return Decision.skip(String.format("pcr_slope_disagrees=%.3f_tier=%s", pcrSlope, tier),
                    opScore, rangePct, pcrSlope);
        }

        if (log.isInfoEnabled()) {
            log.info("[CASE0][{}] FIRE tier={} dir={} opScore={} vix={} range20m={}% pcrSlope5m={}",
                    index, tier, opDir, opScore, String.format("%.2f", currentVix),
                    String.format("%.3f", rangePct), String.format("%+.3f", pcrSlope));
        }
        return Decision.fire(opDir, opScore, rangePct, pcrSlope);
    }

    /**
     * 20-minute spot range as % of current spot. Uses {@link TickMomentumDetector}'s
     * rolling high/low (per-second tick samples). Returns {@code NaN} if either
     * bound is unavailable (e.g. JVM just started, no warm-up).
     */
    private double compute20MinRangePct(IndexType index) {
        double high = momentumDetector.getRollingHighInWindow(index, 20);
        double low = momentumDetector.getRollingLowInWindow(index, 20);
        if (high <= 0 || low <= 0 || high < low) {
            return Double.NaN;
        }
        double spot = momentumDetector.getSpot(index);
        if (spot <= 0) return Double.NaN;
        return (high - low) / spot * 100.0;
    }
}
