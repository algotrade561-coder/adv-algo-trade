package com.algo.trade.tuning.adapter;

import com.algo.trade.strategy.StrategyType;
import java.util.List;

/**
 * Per-strategy declarative metadata that drives the unified tuning capture pipeline.
 *
 * <h2>What an adapter declares</h2>
 * <ol>
 *   <li>Which {@link StrategyType} it serves.</li>
 *   <li>Its evaluation {@link CadenceHint} — HIGH / MEDIUM / LOW.</li>
 *   <li>The default {@code episode_window_sec} value used when {@code CaptureToggleService}
 *       seeds the strategy's DB row for the first time. Most strategies should return
 *       60; HIGH-cadence strategies may tune higher after measurement.</li>
 *   <li>{@link BucketDimension}s — the analyzer auto-generates per-bucket breakdowns
 *       from this list. No per-strategy analyzer code needed for standard breakdowns.</li>
 *   <li>{@link ShadowGate}s — confirmation rules / ML scores logged for A/B analysis.</li>
 *   <li>How to normalize raw blocker strings into stable tokens (for episode dedup
 *       and analyzer grouping).</li>
 * </ol>
 *
 * <h2>What an adapter does NOT do</h2>
 * The adapter is pure metadata. It does not construct {@code TuningEvent} instances —
 * the strategy code itself emits events via
 * {@link com.algo.trade.tuning.recorder.TuningEventRecorder#record}. This keeps the
 * adapter a thin declarative class (~30 lines for most strategies) and avoids coupling
 * the framework to each strategy's signal-context object shapes.
 *
 * <h2>Registration</h2>
 * Adapters are discovered as Spring {@code @Component}s by
 * {@link TuningCaptureAdapterRegistry}. On {@code @PostConstruct}, the registry seeds
 * a DB row in {@code tuning_capture_config} for each adapter (defaulting to OFF) using
 * the adapter's {@link #defaultEpisodeWindowSec()}. The UI capture-toggle page then
 * automatically renders the new strategy without any UI code change.
 *
 * <h2>References</h2>
 * <ul>
 *   <li>Design: {@code important/SIGNAL_CAPTURE_TUNING_REDESIGN.md} § 4.4.</li>
 *   <li>Phase 2 carry-overs: {@code SIGNAL_TUNING_IMPLEMENTATION_PLAN.md} § 3.0.</li>
 * </ul>
 */
public interface TuningCaptureAdapter {

    /** The strategy this adapter serves. */
    StrategyType strategy();

    /** Evaluation cadence — drives episode dedup defaults and analyzer behaviour. */
    CadenceHint cadence();

    /**
     * Default {@code episode_window_sec} when the strategy's DB row is seeded for the
     * first time. Most strategies should return {@code 60} — see the per-cadence
     * recommendation table in {@code SIGNAL_TUNING_IMPLEMENTATION_PLAN.md} § 3.0.1.
     *
     * <p><strong>Only used on initial seeding.</strong> If a user has already saved a
     * custom value via the UI, that value is preserved on every subsequent adapter
     * (re)registration.</p>
     */
    default int defaultEpisodeWindowSec() {
        return 60;
    }

    /**
     * Declared bucket dimensions for the analyzer's auto-generated breakdowns.
     * Return an empty list if the strategy has no useful bucketing dimensions yet —
     * the analyzer will fall back to flat counts.
     */
    default List<BucketDimension> bucketDimensions() {
        return List.of();
    }

    /**
     * Declared shadow gates for the analyzer's confirmation-effectiveness ranker.
     * Return an empty list if the strategy has no A/B confirmation rules.
     */
    default List<ShadowGate> shadowGates() {
        return List.of();
    }

    /**
     * Normalizes a raw blocker reason string into a stable token used for episode
     * dedup keys and analyzer grouping. Default returns the input unchanged; strategies
     * with rich/varying blocker strings should override (e.g. strip trailing IDs,
     * collapse case-related variants).
     */
    default String normalizeBlocker(String raw) {
        return raw;
    }
}
