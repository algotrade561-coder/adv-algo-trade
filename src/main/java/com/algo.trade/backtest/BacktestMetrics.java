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

    /**
     * Compute metrics from a list of trades. Used by VerifyAllService to combine
     * CE + PE results for single-leg strategies.
     */
    public static BacktestMetrics compute(java.util.List<BacktestTrade> trades,
                                           int totalSignals, int rejectedSignals) {
        java.math.MathContext MC = java.math.MathContext.DECIMAL64;
        BigDecimal HUNDRED = BigDecimal.valueOf(100);

        BigDecimal cumulativePnl = trades.stream().map(BacktestTrade::pnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        java.util.List<BacktestTrade> winners = trades.stream()
                .filter(t -> t.pnl().signum() > 0).toList();
        java.util.List<BacktestTrade> losers = trades.stream()
                .filter(t -> t.pnl().signum() < 0).toList();

        BigDecimal winRate = trades.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(winners.size()).multiply(HUNDRED, MC)
                .divide(BigDecimal.valueOf(trades.size()), MC);

        BigDecimal averageWin = avgPnl(winners);
        BigDecimal averageLoss = avgPnl(losers);
        BigDecimal expectancy = trades.isEmpty() ? BigDecimal.ZERO
                : cumulativePnl.divide(BigDecimal.valueOf(trades.size()), MC);

        BigDecimal grossWins = winners.stream().map(BacktestTrade::pnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLosses = losers.stream().map(BacktestTrade::pnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        BigDecimal profitFactor = grossLosses.signum() == 0 ? BigDecimal.ZERO
                : grossWins.divide(grossLosses, MC);

        BigDecimal avgHold = trades.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(trades.stream()
                .mapToLong(t -> java.time.Duration.between(t.entryTime(), t.exitTime()).toMinutes())
                .sum()).divide(BigDecimal.valueOf(trades.size()), MC);

        int maxCW = 0, maxCL = 0, cw = 0, cl = 0;
        for (BacktestTrade t : trades) {
            if (t.pnl().signum() > 0) { cw++; cl = 0; maxCW = Math.max(maxCW, cw); }
            else if (t.pnl().signum() < 0) { cl++; cw = 0; maxCL = Math.max(maxCL, cl); }
        }

        BigDecimal rr = averageLoss.abs().signum() == 0 ? BigDecimal.ZERO
                : averageWin.abs().divide(averageLoss.abs(), MC);
        BigDecimal sigConv = totalSignals == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(trades.size()).multiply(HUNDRED, MC)
                .divide(BigDecimal.valueOf(totalSignals), MC);

        // Daily PnL breakdown
        java.util.Map<String, BigDecimal> daily = new java.util.LinkedHashMap<>();
        for (BacktestTrade t : trades) {
            String date = java.time.LocalDate.ofInstant(t.exitTime(),
                    java.time.ZoneId.of("Asia/Kolkata")).toString();
            daily.merge(date, t.pnl(), BigDecimal::add);
        }

        // Max drawdown
        BigDecimal equity = BigDecimal.ZERO, peak = BigDecimal.ZERO, maxDD = BigDecimal.ZERO;
        for (BacktestTrade t : trades) {
            equity = equity.add(t.pnl());
            peak = peak.max(equity);
            maxDD = maxDD.max(peak.subtract(equity));
        }

        return new BacktestMetrics(trades.size(), winRate, averageWin, averageLoss,
                expectancy, maxDD, cumulativePnl, daily, totalSignals, rejectedSignals, 0,
                profitFactor, avgHold, maxCW, maxCL, rr, sigConv);
    }

    private static BigDecimal avgPnl(java.util.List<BacktestTrade> trades) {
        if (trades.isEmpty()) return BigDecimal.ZERO;
        return trades.stream().map(BacktestTrade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(trades.size()), java.math.MathContext.DECIMAL64);
    }
}
