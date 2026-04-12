package com.kiteapioptions.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.execution.TrailingStopService;
import com.kiteapioptions.indicator.BreakoutDetector;
import com.kiteapioptions.indicator.VolumeSpikeDetector;
import com.kiteapioptions.indicator.VwapIndicator;
import com.kiteapioptions.marketdata.MockMarketDataGenerator;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BacktestEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void runsBacktestAndExportsCsvFiles() {
        TradingProperties properties = new TradingProperties(null, false, null, null, null, null, null,
                null, null, null, null, new TradingProperties.Backtest(
                java.time.LocalDate.of(2026, 4, 12),
                java.time.LocalDate.of(2026, 4, 12),
                com.kiteapioptions.domain.Timeframe.ONE_MINUTE,
                tempDir.resolve("missing.csv").toString(),
                tempDir.resolve("reports").toString()));
        BacktestEngine engine = new BacktestEngine(properties, new MockMarketDataGenerator(), new VwapIndicator(),
                new VolumeSpikeDetector(), new BreakoutDetector(), new TrailingStopService(properties));

        BacktestRunResult result = engine.run();

        assertThat(result.metrics().totalTrades()).isGreaterThanOrEqualTo(0);
        assertThat(result.metricsCsv()).exists();
        assertThat(result.tradesCsv()).exists();
        assertThat(result.equityCurveCsv()).exists();
    }
}
