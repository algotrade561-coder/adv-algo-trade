package com.kiteapioptions.backtest;

import java.math.BigDecimal;
import java.util.Map;

public record BacktestMetrics(
        int totalTrades,
        BigDecimal winRatePercent,
        BigDecimal averageWin,
        BigDecimal averageLoss,
        BigDecimal expectancy,
        BigDecimal maxDrawdown,
        BigDecimal cumulativePnl,
        Map<String, BigDecimal> dailyPnl
) {
}
