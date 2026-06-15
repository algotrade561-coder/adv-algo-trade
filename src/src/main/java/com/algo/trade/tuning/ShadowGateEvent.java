package com.algo.trade.tuning;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.StrategyType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One row per (signal, shadow-gate) pair. Shadow gates are A/B-tested confirmation
 * rules — the gate evaluates at signal time and records its verdict, but the gate's
 * decision does <strong>not</strong> change production trading behavior unless the
 * gate has been explicitly promoted to ACTIVE (see {@code ML_AI_EXTENSION_PLAN.md}
 * for ML-driven gates; rule-based promotion is documented in the design's confirmation-
 * effectiveness section).
 *
 * <p>{@link #correlationKey()} is the {@code decisionKey} of the signal this gate
 * evaluated against.</p>
 *
 * <p>{@link #gateName()} is the identifier used across the analyzer:
 * {@code "confirm_momentumDecelerating"} for rule gates, {@code "model:win_proba:v1"}
 * for ML gates.</p>
 *
 * <p>{@link #bandValue()} is optional — used for non-binary gates (model scores,
 * numeric thresholds). May be {@code null} for purely binary rule gates.</p>
 */
public record ShadowGateEvent(
        Instant eventTime,
        Instant recordedAt,
        StrategyType strategy,
        IndexType index,
        String correlationKey,
        String gateName,
        boolean passed,
        Double bandValue,
        Map<String, Object> attributes
) implements TuningEvent {

    public ShadowGateEvent {
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(gateName, "gateName");
        Objects.requireNonNull(attributes, "attributes (use Map.of() for empty)");
    }

    @Override
    public TuningEventType type() {
        return TuningEventType.SHADOW_GATE;
    }
}
