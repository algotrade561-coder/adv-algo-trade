package com.kiteapioptions.backtest;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record BacktestRunResult(
        String id,
        Instant createdAt,
        BacktestMetrics metrics,
        List<BacktestTrade> trades,
        List<EquityCurvePoint> equityCurve,
        Path outputDirectory,
        Path tradesCsv,
        Path metricsCsv,
        Path equityCurveCsv,
        Path reportHtml
) {
}
