package com.algo.trade.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.execution.TrailingStopService;
import com.algo.trade.indicator.BreakoutDetector;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.indicator.OiChangeTracker;
import com.algo.trade.indicator.VolatilityFilter;
import com.algo.trade.indicator.VolumeSpikeDetector;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.strategy.OptionChainAnalyzer;
import com.algo.trade.strategy.RuleBasedOptionsStrategy;
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
                com.algo.trade.domain.Timeframe.ONE_MINUTE,
                tempDir.resolve("missing.csv").toString(),
                tempDir.resolve("reports").toString(),
                "NFO:NIFTY-MOCK-ATM-CE",
                180,
                75));
        Path inputPath = BacktestDataFileResolver.forSelection(properties.backtest().csvImportPath(),
                com.algo.trade.domain.UnderlyingSymbol.NIFTY, com.algo.trade.domain.OptionType.CE,
                com.algo.trade.domain.Timeframe.ONE_MINUTE);
        Path underlyingPath = BacktestDataFileResolver.forTimeframe(properties.backtest().csvImportPath(),
                com.algo.trade.domain.Timeframe.ONE_MINUTE);
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
        Files.writeString(underlyingPath, """
                timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest
                2026-04-12T03:50:00Z,NSE:NIFTY 50,ONE_MINUTE,24100,24105,24095,24102,100,0
                2026-04-12T03:51:00Z,NSE:NIFTY 50,ONE_MINUTE,24102,24108,24100,24105,120,0
                2026-04-12T03:52:00Z,NSE:NIFTY 50,ONE_MINUTE,24105,24112,24103,24109,140,0
                2026-04-12T03:53:00Z,NSE:NIFTY 50,ONE_MINUTE,24109,24116,24108,24113,160,0
                2026-04-12T03:54:00Z,NSE:NIFTY 50,ONE_MINUTE,24113,24120,24111,24117,180,0
                2026-04-12T03:55:00Z,NSE:NIFTY 50,ONE_MINUTE,24117,24124,24115,24121,220,0
                2026-04-12T03:56:00Z,NSE:NIFTY 50,ONE_MINUTE,24121,24128,24119,24125,260,0
                """);
        RuleBasedOptionsStrategy strategy = new RuleBasedOptionsStrategy(properties, new VwapIndicator(),
                new com.algo.trade.indicator.EmaIndicator(),
                new VolumeSpikeDetector(), new BreakoutDetector(), new VolatilityFilter(),
                new OiChangeTracker(), new OptionChainAnalyzer(), null);
        var ema = new EmaIndicator();
        BacktestEngine engine = new BacktestEngine(properties, strategy, new TrailingStopService(properties),
                new com.algo.trade.strategy.ScalpingStrategy(ema),
                new com.algo.trade.strategy.VolatilityBreakoutStrategy());

        BacktestRunResult result = engine.run();

        assertThat(result.metrics().totalTrades()).isGreaterThanOrEqualTo(0);
        assertThat(result.metricsCsv()).exists();
        assertThat(result.tradesCsv()).exists();
        assertThat(result.equityCurveCsv()).exists();
        assertThat(result.reportHtml()).exists();
    }

    @Test
    void rejectsBacktestWhenUnderlyingSeriesIsMissing() throws IOException {
        TradingProperties properties = new TradingProperties(null, false, null, null, null, null, null,
                null, null, null, null, null, new TradingProperties.Backtest(
                java.time.LocalDate.of(2026, 4, 12),
                java.time.LocalDate.of(2026, 4, 12),
                com.algo.trade.domain.Timeframe.ONE_MINUTE,
                tempDir.resolve("missing.csv").toString(),
                tempDir.resolve("reports").toString(),
                "NFO:NIFTY-MOCK-ATM-CE",
                180,
                75));
        Path inputPath = BacktestDataFileResolver.forSelection(properties.backtest().csvImportPath(),
                com.algo.trade.domain.UnderlyingSymbol.NIFTY, com.algo.trade.domain.OptionType.CE,
                com.algo.trade.domain.Timeframe.ONE_MINUTE);
        Files.writeString(inputPath, """
                timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest
                2026-04-12T03:50:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,100,101,99,100,1000,1000
                2026-04-12T03:51:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,100,102,99,101,1100,1000
                """);
        RuleBasedOptionsStrategy strategy = new RuleBasedOptionsStrategy(properties, new VwapIndicator(),
                new com.algo.trade.indicator.EmaIndicator(),
                new VolumeSpikeDetector(), new BreakoutDetector(), new VolatilityFilter(),
                new OiChangeTracker(), new OptionChainAnalyzer(), null);
        var ema2 = new EmaIndicator();
        BacktestEngine engine = new BacktestEngine(properties, strategy, new TrailingStopService(properties),
                new com.algo.trade.strategy.ScalpingStrategy(ema2),
                new com.algo.trade.strategy.VolatilityBreakoutStrategy());

        assertThatThrownBy(engine::run)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("underlying CSV input is missing");
    }
}
