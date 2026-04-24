package com.algo.trade.backtest;

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
        Map<String, BigDecimal> dailyPnl,
        int totalSignals,
        int rejectedSignals,
        int skippedWhileInTrade,
        BigDecimal profitFactor,
        BigDecimal avgHoldMinutes,
        int maxConsecutiveWins,
        int maxConsecutiveLosses,
        BigDecimal riskRewardRatio,
        BigDecimal signalConversionPercent
) {
    /** Backward-compatible constructor (no signal counts or advanced metrics). */
    public BacktestMetrics(
            int totalTrades,
            BigDecimal winRatePercent,
            BigDecimal averageWin,
            BigDecimal averageLoss,
            BigDecimal expectancy,
            BigDecimal maxDrawdown,
            BigDecimal cumulativePnl,
            Map<String, BigDecimal> dailyPnl
    ) {
        this(totalTrades, winRatePercent, averageWin, averageLoss, expectancy, maxDrawdown,
                cumulativePnl, dailyPnl, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
    }
}
