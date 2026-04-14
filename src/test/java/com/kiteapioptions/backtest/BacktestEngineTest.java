package com.kiteapioptions.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.execution.TrailingStopService;
import com.kiteapioptions.indicator.BreakoutDetector;
import com.kiteapioptions.indicator.OiChangeTracker;
import com.kiteapioptions.indicator.VolatilityFilter;
import com.kiteapioptions.indicator.VolumeSpikeDetector;
import com.kiteapioptions.indicator.VwapIndicator;
import com.kiteapioptions.strategy.OptionChainAnalyzer;
import com.kiteapioptions.strategy.RuleBasedOptionsStrategy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BacktestEngineTest {

    @TempDir
    Path tempDir;

    @Test
    void runsBacktestAndExportsCsvFiles() throws IOException {
        TradingProperties properties = new TradingProperties(null, false, null, null, null, null, null,
                null, null, null, null, null, new TradingProperties.Backtest(
                java.time.LocalDate.of(2026, 4, 12),
                java.time.LocalDate.of(2026, 4, 12),
                com.kiteapioptions.domain.Timeframe.ONE_MINUTE,
                tempDir.resolve("missing.csv").toString(),
                tempDir.resolve("reports").toString(),
                "NFO:NIFTY-MOCK-ATM-CE",
                180,
                75));
        Path inputPath = BacktestDataFileResolver.forSelection(properties.backtest().csvImportPath(),
                com.kiteapioptions.domain.UnderlyingSymbol.NIFTY, com.kiteapioptions.domain.OptionType.CE,
                com.kiteapioptions.domain.Timeframe.ONE_MINUTE);
        Files.writeString(inputPath, """
                timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest
                2026-04-12T03:50:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,100,101,99,100,1000,1000
                2026-04-12T03:51:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,100,102,99,101,1100,1000
                2026-04-12T03:52:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,101,103,100,102,1200,1000
                2026-04-12T03:53:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,102,104,101,103,1300,1000
                2026-04-12T03:54:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,103,105,102,104,1400,1000
                2026-04-12T03:55:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,104,106,103,105,5000,1000
                2026-04-12T03:56:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,105,107,104,106,5200,1000
                """);
        RuleBasedOptionsStrategy strategy = new RuleBasedOptionsStrategy(properties, new VwapIndicator(),
                new com.kiteapioptions.indicator.EmaIndicator(),
                new VolumeSpikeDetector(), new BreakoutDetector(), new VolatilityFilter(),
                new OiChangeTracker(), new OptionChainAnalyzer(), null);
        BacktestEngine engine = new BacktestEngine(properties, strategy, new TrailingStopService(properties));

        BacktestRunResult result = engine.run();

        assertThat(result.metrics().totalTrades()).isGreaterThanOrEqualTo(0);
        assertThat(result.metricsCsv()).exists();
        assertThat(result.tradesCsv()).exists();
        assertThat(result.equityCurveCsv()).exists();
        assertThat(result.reportHtml()).exists();
    }
}
