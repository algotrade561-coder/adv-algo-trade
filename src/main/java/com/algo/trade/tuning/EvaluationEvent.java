package com.algo.trade.tuning;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.StrategyType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One row per strategy evaluation. For HIGH-cadence strategies (e.g. OI Momentum at
 * 1 Hz) these are episode-deduped via {@code EpisodeAggregator} before being emitted,
 * so a tight five-minute reject burst with the same blocker typically produces one
 * event row rather than 300.
 *
 * <p>{@link #outcome()} is the universal funnel marker (FIRED / SKIPPED / BLOCKED).
 * {@link #blocker()} carries the normalized reason token when the outcome is BLOCKED;
 * it is {@code null} for FIRED and SKIPPED. {@link #episodeTickCount()} reports how
 * many raw ticks collapsed into this row; {@code 1} when no dedup is in play.</p>
 *
 * <p>Strategy-specific gate booleans, scores, and other diagnostics live in
 * {@link #attributes()}.</p>
 */
public record EvaluationEvent(
        Instant eventTime,
        Instant recordedAt,
        StrategyType strategy,
        IndexType index,
        String correlationKey,
        EvaluationOutcome outcome,
        String blocker,
        int episodeTickCount,
        Map<String, Object> attributes
) implements TuningEvent {

    public EvaluationEvent {
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(attributes, "attributes (use Map.of() for empty)");
        if (episodeTickCount < 1) {
            throw new IllegalArgumentException("episodeTickCount must be >= 1, got " + episodeTickCount);
        }
        if (outcome != EvaluationOutcome.BLOCKED && blocker != null) {
            throw new IllegalArgumentException("blocker must be null when outcome != BLOCKED");
        }
    }

    @Override
    public TuningEventType type() {
        return TuningEventType.EVALUATION;
    }
}
