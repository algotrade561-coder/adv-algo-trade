package com.algo.trade.strategy;

/**
 * Marker interface for strategy components that can evaluate entry conditions.
 * Used by the execution pipeline to discover and invoke strategies.
 */
public interface StrategyEvaluator {
    String strategyName();
    boolean isEnabled();
}
