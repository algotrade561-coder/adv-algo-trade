package com.algo.trade.tuning;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.StrategyType;
import java.time.Instant;
import java.util.Map;

/**
 * Canonical tuning event — the single shape every strategy emits for the unified
 * tuning capture pipeline.
 *
 * <h2>Design</h2>
 * Six concrete event types share a common context (when, which strategy, which index,
 * how to correlate with related events) plus type-specific top-level fields where
 * those fields are cross-strategy universal. Strategy-specific quality fields go in
 * the {@link #attributes()} sidecar map and are serialized by the recorder
 * (Phase 1, Commit 3) as flat "hot" CSV columns declared by each
 * {@code TuningCaptureAdapter}, with the remainder rolled into a JSON
 * {@code attr_extra} column.
 *
 * <h2>Why sealed</h2>
 * Java 21 sealed interfaces give us exhaustive switch coverage in the recorder /
 * analyzer without an abstract class hierarchy. Adding a seventh event type requires
 * updating exactly two places: this {@code permits} clause and
 * {@link TuningEventType}.
 *
 * <h2>References</h2>
 * Design: {@code important/SIGNAL_CAPTURE_TUNING_REDESIGN.md} §3, §4.1.<br>
 * Implementation plan: {@code important/SIGNAL_TUNING_IMPLEMENTATION_PLAN.md}
 * Phase 1, Commit 1.
 */
public sealed interface TuningEvent
        permits EvaluationEvent, SignalEvent, ExecutionEvent, LegEvent,
                ExitEvent, ForwardCheckpointEvent, ShadowGateEvent {

    /** Type tag — drives CSV file routing in the recorder. */
    TuningEventType type();

    /** Market-time at which the event happened (e.g. signal-fire timestamp). */
    Instant eventTime();

    /** Wall-clock at which this row was actually written. Useful for clock-skew diagnostics. */
    Instant recordedAt();

    /** Strategy that emitted this event. */
    StrategyType strategy();

    /** Underlying index this event relates to. */
    IndexType index();

    /**
     * Stable identifier grouping related events:
     * <ul>
     *   <li>{@link SignalEvent}, {@link ExecutionEvent}, {@link ExitEvent},
     *       {@link ForwardCheckpointEvent}, {@link ShadowGateEvent} — the
     *       {@code decisionKey} of the signal these events relate to.</li>
     *   <li>{@link EvaluationEvent} — the episode id when episode-deduped via
     *       {@code EpisodeAggregator}, otherwise a fresh id per evaluation.</li>
     * </ul>
     */
    String correlationKey();

    /**
     * Strategy-specific sidecar data. The recorder reads from this when serializing;
     * the adapter contract (Phase 2, Commit 1) declares which keys are "hot" (rendered
     * as flat CSV columns) versus cold (rolled into a single JSON {@code attr_extra}
     * column).
     *
     * <p>May be empty; never {@code null}.</p>
     */
    Map<String, Object> attributes();
}
