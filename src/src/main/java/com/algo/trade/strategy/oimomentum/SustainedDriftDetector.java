package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * D2 — SUSTAINED_DRIFT detector.
 *
 * <p>Sister to CASE 0 ({@link Case0OiLedDetector}). Both fire <em>without</em> a
 * price breakout, but they target opposite micro-structures:</p>
 *
 * <ul>
 *   <li><b>CASE 0</b> — tight coil + heavy operator pressure → pre-breakout entry.</li>
 *   <li><b>D2 (this)</b> — spot has been <em>slowly drifting</em> in one direction
 *       for an hour with operator chain agreement → join the move post-coil-break,
 *       pre-momentum-spike.</li>
 * </ul>
 *
 * <p><b>Trigger rule</b> (from {@code OI_MOMENTUM_NEW_DETECTORS_VALIDATION.md}):</p>
 * <ol>
 *   <li>Absolute 60-min spot drift ≥ {@code sustainedDriftMinPct} (default 0.20%).</li>
 *   <li>Operator framework score ≥ {@code sustainedDriftOpScoreMin} (default 50).</li>
 *   <li>Operator direction is non-zero AND points the same way as the 60-min drift.</li>
 * </ol>
 *
 * <p><b>Empirical performance</b> (12-day replay, NIFTY + SENSEX, 5-min snapshots):
 * 121 fires, <b>69.6% 60-min win rate</b> (highest of any detector tested),
 * 58.1% 30m win, mean +0.080% 60m forward move, ~5 fires/day/index.</p>
 *
 * <p>Designed specifically for slow-grind days like 1 June 2026 where the legacy
 * CASE 1-5 breakout matrix never triggers because price drifts without ever
 * breaking a clean 5M/15M/30M range. CASE 0 also misses those days because no
 * sustained coil under 0.10% develops during the drift.</p>
 */
@Component
public class SustainedDriftDetector {

    private static final Logger log = LoggerFactory.getLogger(SustainedDriftDetector.class);

    private final OperatorFrameworkService operatorFrameworkService;
    private final TickMomentumDetector momentumDetector;

    public SustainedDriftDetector(OperatorFrameworkService operatorFrameworkService,
                                  TickMomentumDetector momentumDetector) {
        this.operatorFrameworkService = operatorFrameworkService;
        this.momentumDetector = momentumDetector;
    }

    /** Outcome of a D2 evaluation — captured into diagnostics + tune CSV. */
    public record Decision(
            boolean fires,
            int direction,           // +1 (drift up), -1 (drift down), 0 not firing
            int opScore,
            double driftPct,         // signed 60-min drift as % of current spot
            int driftMinutes,        // actual window measured (may be < 60 at session start)
            String reason            // "ok" when firing; reject token otherwise
    ) {
        public static Decision skip(String reason, int opScore, double driftPct) {
            return new Decision(false, 0, opScore, driftPct, 0, reason);
        }
        public static Decision fire(int direction, int opScore, double driftPct,
                                    int driftMinutes) {
            return new Decision(true, direction, opScore, driftPct, driftMinutes, "ok");
        }
    }

    /**
     * Evaluate D2 for the given index using current operator + spot history.
     *
     * @param index   index to evaluate
     * @param config  current OIMomentumConfig (thresholds + enable flag)
     * @return Decision (may be a skip; never null)
     */
    public Decision evaluate(IndexType index, OIMomentumConfig config) {
        if (config == null || !config.isSustainedDriftEnabled()) {
            return Decision.skip("disabled", 0, 0);
        }
        if (operatorFrameworkService == null || momentumDetector == null) {
            return Decision.skip("not_wired", 0, 0);
        }

        OperatorAccumulationDetector.OperatorSignal sig =
                operatorFrameworkService.getOperatorSignal(index);
        int opScore = sig == null ? 0 : sig.getScore();
        int opDir = sig == null ? 0 : sig.getDirection();
        if (sig == null || !sig.isFresh()) {
            return Decision.skip("op_signal_stale", opScore, 0);
        }
        if (opDir == 0) {
            return Decision.skip("op_dir_neutral", opScore, 0);
        }
        if (opScore < config.getSustainedDriftOpScoreMin()) {
            return Decision.skip(
                    String.format("op_score_below_%d", config.getSustainedDriftOpScoreMin()),
                    opScore, 0);
        }

        // True endpoint-to-endpoint drift over the configured window. The legacy
        // implementation approximated drift as distance from the rolling-window
        // midpoint, which structurally reports only ~half the real move on a clean
        // one-way trend: on 2026-06-12's afternoon rally the midpoint read ~0.13%
        // (below the 0.20% gate) and D2 never fired all day despite a textbook
        // sustained drift. We now measure (spot − spot@windowStart) directly via
        // the detector's dedicated drift history.
        int windowMin = config.getSustainedDriftWindowMinutes();
        TickMomentumDetector.DriftSample drift =
                momentumDetector.getSignedDriftPct(index, windowMin);
        if (!drift.valid()) {
            return Decision.skip("spot_history_unavailable", opScore, 0);
        }
        double signedDriftPct = drift.driftPct();
        int measuredMin = drift.windowMinutesMeasured();

        double absDrift = Math.abs(signedDriftPct);
        double minDrift = config.getSustainedDriftMinPct();
        if (absDrift < minDrift) {
            return Decision.skip(
                    String.format("drift_below_%.3f%%_abs=%.3f", minDrift, absDrift),
                    opScore, signedDriftPct);
        }

        int driftDir = signedDriftPct > 0 ? +1 : -1;
        if (driftDir != opDir) {
            return Decision.skip(
                    String.format("drift_dir_%+d_vs_op_dir_%+d", driftDir, opDir),
                    opScore, signedDriftPct);
        }

        if (log.isInfoEnabled()) {
            log.info("[D2_DRIFT][{}] FIRE dir={} opScore={} drift={}% window={}min(measured)",
                    index, opDir, opScore, String.format("%+.3f", signedDriftPct), measuredMin);
        }
        return Decision.fire(opDir, opScore, signedDriftPct, measuredMin);
    }
}
