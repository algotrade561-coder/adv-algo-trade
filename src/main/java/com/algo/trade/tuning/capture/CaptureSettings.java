package com.algo.trade.tuning.capture;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;

/**
 * Immutable snapshot of one strategy's capture configuration.
 *
 * <p>{@link CaptureToggleService} hands these to callers (recorder, adapter bridge,
 * UI controller) so that downstream code never holds a reference to a mutable JPA
 * entity. A new snapshot is published on every DB refresh (30s default).</p>
 */
public record CaptureSettings(
        StrategyType strategy,
        boolean captureEnabled,
        boolean captureEvaluations,
        boolean captureSignals,
        boolean captureExecutions,
        boolean captureExits,
        boolean captureForward,
        boolean captureShadow,
        int episodeWindowSec,
        String notes
) {

    /** Returns true when the master toggle is on AND the specific event type is enabled. */
    public boolean isEnabledFor(TuningEventType type) {
        if (!captureEnabled) {
            return false;
        }
        return switch (type) {
            case EVALUATION       -> captureEvaluations;
            case SIGNAL           -> captureSignals;
            case EXECUTION        -> captureExecutions;
            case EXIT             -> captureExits;
            case FORWARD_CHECKPOINT -> captureForward;
            case SHADOW_GATE      -> captureShadow;
            case LEG              -> captureExecutions;   // legs piggyback on the execution toggle
        };
    }

    /** Default-ON snapshot used as a fallback when no DB row exists for a strategy.
     *  As of 4 Jun 2026 capture is on by default so that fresh deployments accumulate
     *  tuning data from day one; the operator can disable per strategy via the UI.
     *  Method name kept as {@code defaultOff} for source-compat with existing callers. */
    public static CaptureSettings defaultOff(StrategyType strategy) {
        return new CaptureSettings(strategy, true, true, true, true, true, true, true, 60, null);
    }

    public static CaptureSettings from(TuningCaptureConfigEntity e) {
        return new CaptureSettings(
                e.getStrategy(),
                e.isCaptureEnabled(),
                e.isCaptureEvaluations(),
                e.isCaptureSignals(),
                e.isCaptureExecutions(),
                e.isCaptureExits(),
                e.isCaptureForward(),
                e.isCaptureShadow(),
                e.getEpisodeWindowSec(),
                e.getNotes()
        );
    }
}
