package com.algo.trade.backtest;

import java.util.List;
import java.util.Map;

/**
 * Aggregated result of a spread strategy backtest run.
 * Contains all trades, computed metrics, and signal/rejection statistics.
 */
public record SpreadBacktestResult(
        List<SpreadBacktestTrade> trades,
        BacktestMetrics metrics,
        int totalSignals,
        int rejectedSignals,
        Map<String, Integer> rejectionReasons,
        Map<String, String> strategySpecificMetrics
) {
    public SpreadBacktestResult {
        trades = List.copyOf(trades == null ? List.of() : trades);
        rejectionReasons = Map.copyOf(rejectionReasons == null ? Map.of() : rejectionReasons);
        strategySpecificMetrics = Map.copyOf(strategySpecificMetrics == null ? Map.of() : strategySpecificMetrics);
    }
}
