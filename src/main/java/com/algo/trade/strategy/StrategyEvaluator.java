package com.algo.trade.strategy;

import com.algo.trade.domain.Timeframe;

/**
 * Unified evaluation interface for all strategy implementations.
 * Enables StrategyExecutionPipeline to run any strategy generically.
 */
public interface StrategyEvaluator {

    StrategyType strategyType();

    StrategyDiagnostics.WithSignal evaluate(StrategyContext ctx, StrategyConfig config);

    default Timeframe resolveTimeframe(String configured, Timeframe defaultTf) {
        if (configured == null || configured.isBlank()) return defaultTf;
        try { return Timeframe.valueOf(configured); }
        catch (IllegalArgumentException e) { return defaultTf; }
    }
}
