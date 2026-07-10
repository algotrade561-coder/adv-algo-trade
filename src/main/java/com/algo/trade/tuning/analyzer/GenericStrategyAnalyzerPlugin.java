package com.algo.trade.tuning.analyzer;

import com.algo.trade.strategy.StrategyType;
import java.util.List;

/**
 * A <b>cross-strategy</b> analyzer plugin. Unlike {@link TuningAnalyzerPlugin} — which is
 * bound to a single {@link TuningAnalyzerPlugin#strategy()} and only runs for that strategy —
 * the coordinator invokes a generic plugin <em>once per strategy that has captured data</em>,
 * passing the strategy in. Use this for sections whose logic is identical across strategies
 * (e.g. blocker opportunity-cost, missed-opportunity attribution) so every strategy gets them
 * without a per-strategy bean.
 *
 * <h2>Ordering</h2>
 * Same convention as {@link TuningAnalyzerPlugin#order()}: a <b>negative</b> order renders
 * <em>before</em> the standard breakdowns for that strategy; order ≥ 0 renders after, ascending.
 *
 * <h2>Discovery</h2>
 * Implementations are Spring {@code @Component}s, discovered by the coordinator via
 * {@code List<GenericStrategyAnalyzerPlugin>} injection.
 */
public interface GenericStrategyAnalyzerPlugin {

    /** Render order relative to the standard breakdowns (see class doc). Default 100 = after. */
    default int order() {
        return 100;
    }

    /**
     * Returns custom sections for {@code strategy} within the query window. May return an
     * empty list when there's no matching data. Implementations must fail safely — the
     * coordinator also wraps each call in a try/catch so a single bad plugin can't break
     * the report.
     */
    List<AnalyzerSection> customSections(TuningEventQuery query, StrategyType strategy);
}
