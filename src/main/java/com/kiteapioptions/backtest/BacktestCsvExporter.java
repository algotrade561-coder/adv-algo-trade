package com.kiteapioptions.backtest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes backtest metrics, trades, and equity curve CSV outputs.
 */
public class BacktestCsvExporter {

    public void export(BacktestRunResult result) throws IOException {
        Files.createDirectories(result.outputDirectory());
        Files.writeString(result.tradesCsv(), tradesCsv(result));
        Files.writeString(result.metricsCsv(), metricsCsv(result));
        Files.writeString(result.equityCurveCsv(), equityCurveCsv(result));
    }

    private String tradesCsv(BacktestRunResult result) {
        StringBuilder csv = new StringBuilder("tradeId,instrumentKey,entryTime,exitTime,quantity,entryPrice,exitPrice,pnl,entryReason,exitReason\n");
        for (BacktestTrade trade : result.trades()) {
            csv.append(trade.tradeId()).append(',')
                    .append(trade.instrumentKey()).append(',')
                    .append(trade.entryTime()).append(',')
                    .append(trade.exitTime()).append(',')
                    .append(trade.quantity()).append(',')
                    .append(trade.entryPrice()).append(',')
                    .append(trade.exitPrice()).append(',')
                    .append(trade.pnl()).append(',')
                    .append(escape(trade.entryReason())).append(',')
                    .append(escape(trade.exitReason())).append('\n');
        }
        return csv.toString();
    }

    private String metricsCsv(BacktestRunResult result) {
        BacktestMetrics metrics = result.metrics();
        return "metric,value\n"
                + "totalTrades," + metrics.totalTrades() + '\n'
                + "winRatePercent," + metrics.winRatePercent() + '\n'
                + "averageWin," + metrics.averageWin() + '\n'
                + "averageLoss," + metrics.averageLoss() + '\n'
                + "expectancy," + metrics.expectancy() + '\n'
                + "maxDrawdown," + metrics.maxDrawdown() + '\n'
                + "cumulativePnl," + metrics.cumulativePnl() + '\n';
    }

    private String equityCurveCsv(BacktestRunResult result) {
        StringBuilder csv = new StringBuilder("timestamp,equity,drawdown\n");
        for (EquityCurvePoint point : result.equityCurve()) {
            csv.append(point.timestamp()).append(',')
                    .append(point.equity()).append(',')
                    .append(point.drawdown()).append('\n');
        }
        return csv.toString();
    }

    private String escape(String value) {
        return '"' + (value == null ? "" : value.replace("\"", "\"\"")) + '"';
    }
}
