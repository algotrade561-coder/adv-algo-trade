package com.algo.trade.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class BacktestDataFileResolverTest {

    @Test
    void appendsTimeframeToConfiguredCsvImportPath() {
        assertThat(BacktestDataFileResolver.forTimeframe("backtest/input.csv", Timeframe.ONE_MINUTE))
                .isEqualTo(Path.of("backtest", "input-one-minute.csv"));
        assertThat(BacktestDataFileResolver.forTimeframe("backtest/input.csv", Timeframe.FIVE_MINUTE))
                .isEqualTo(Path.of("backtest", "input-five-minute.csv"));
    }

    @Test
    void appendsUnderlyingOptionTypeAndTimeframeToConfiguredCsvImportPath() {
        assertThat(BacktestDataFileResolver.forSelection("backtest/input.csv", UnderlyingSymbol.NIFTY,
                OptionType.CE, Timeframe.ONE_MINUTE))
                .isEqualTo(Path.of("backtest", "input-nifty-ce-one-minute.csv"));
        assertThat(BacktestDataFileResolver.forSelection("backtest/input.csv", UnderlyingSymbol.NIFTY,
                OptionType.PE, Timeframe.FIVE_MINUTE))
                .isEqualTo(Path.of("backtest", "input-nifty-pe-five-minute.csv"));
    }

    @Test
    void appendsTokenAndTimeframeToConfiguredCsvImportPath() {
        assertThat(BacktestDataFileResolver.forToken("backtest/input.csv", "256265", Timeframe.ONE_MINUTE))
                .isEqualTo(Path.of("backtest", "input-token-256265-one-minute.csv"));
    }
}
