package com.algo.trade.tuning;

/**
 * Outcome of a single strategy evaluation tick.
 *
 * <p>Used as the top-level outcome marker on {@link EvaluationEvent} so the analyzer
 * can reason about evaluation funnels without parsing strategy-specific blockers.</p>
 */
public enum EvaluationOutcome {

    /** Evaluation produced a BUY/SELL decision. */
    FIRED,

    /** Evaluation reached a clean "no setup" conclusion (no gate fired; just nothing to do). */
    SKIPPED,

    /** Evaluation found a setup but a gate vetoed it. {@code blocker} carries the normalized reason. */
    BLOCKED
}
