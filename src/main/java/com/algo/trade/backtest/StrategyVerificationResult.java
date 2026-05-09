package com.algo.trade.backtest;

import com.algo.trade.strategy.StrategyType;

import java.util.List;
import java.util.Map;

/**
 * Per-strategy verification result.
 * Status is one of: "ok", "error", "no-data", "skipped".
 */
public record StrategyVerificationResult(
        StrategyType strategyType,
        String status,
        BacktestMetrics metrics,
        List<BacktestTrade> sampleTrades,
        int entrySignals,
        int rejectedSignals,
        Map<String, Integer> rejectionReasons,
        Map<String, String> strategySpecificMetrics,
        String errorMessage
) {

    public StrategyVerificationResult {
        sampleTrades = sampleTrades == null ? List.of() : List.copyOf(sampleTrades);
        rejectionReasons = rejectionReasons == null ? Map.of() : Map.copyOf(rejectionReasons);
        strategySpecificMetrics = strategySpecificMetrics == null ? Map.of() : Map.copyOf(strategySpecificMetrics);
    }
}
