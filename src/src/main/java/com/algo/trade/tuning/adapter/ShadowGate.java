package com.algo.trade.tuning.adapter;

import java.util.Objects;

/**
 * Declaration of a shadow gate that a strategy emits via {@code ShadowGateEvent}.
 * Shadow gates are A/B-tested confirmation rules — the gate evaluates at signal
 * time and logs its verdict, but the gate's decision does <strong>not</strong>
 * affect production trading unless explicitly promoted to ACTIVE (Phase 2 promotion
 * requires 30+ days of measured shadow data; ML gates use the same path — see
 * {@code ML_AI_EXTENSION_PLAN.md}).
 *
 * <p>The analyzer (Phase 6) uses these declarations to render the confirmation-
 * effectiveness ranker section in the EOD report — for each declared gate it
 * computes "what would the win rate / MAE / PnL look like if we'd required this
 * gate," ranking all gates (and combinations) by improvement.</p>
 *
 * @param name stable identifier used in the {@code ShadowGateEvent.gateName} column
 *             — e.g. {@code "confirm_oiStillBuilding"}, {@code "model:win_proba:v1"}
 * @param description human-readable description shown in the report UI
 * @param isMlGate {@code true} for ML-driven gates (score-based), {@code false} for
 *                rule-based boolean gates. ML gates carry a numeric {@code bandValue}
 *                in the event; rule gates set it to {@code null}.
 */
public record ShadowGate(String name, String description, boolean isMlGate) {

    public ShadowGate {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /** Convenience constructor for a rule-based (boolean) gate. */
    public static ShadowGate rule(String name, String description) {
        return new ShadowGate(name, description, false);
    }

    /** Convenience constructor for an ML-based (scored) gate. */
    public static ShadowGate ml(String name, String description) {
        return new ShadowGate(name, description, true);
    }
}
