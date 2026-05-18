package com.algo.trade.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algo.trade.domain.ExecutionMode;
import com.algo.trade.domain.MarketDataMode;
import com.algo.trade.domain.TradingMode;
import java.time.LocalTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ConfigurationValidatorTest {

    private GlobalConfigService globalConfigService;

    @BeforeEach
    void setUp() {
        globalConfigService = mock(GlobalConfigService.class);
        when(globalConfigService.getFailSafeSquareoffTime()).thenReturn(LocalTime.of(15, 20));
    }

    @Test
    void rejectsLiveTradingFlagOutsideLiveMode() {
        var properties = new TradingProperties(TradingMode.PAPER, MarketDataMode.MOCK, ExecutionMode.PAPER,
                true, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> new ConfigurationValidator(properties, globalConfigService).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trading.live-trading-enabled=true requires trading.mode=LIVE");
    }

    @Test
    void rejectsZerodhaExecutionWhenLiveTradingFlagIsDisabled() {
        var properties = new TradingProperties(TradingMode.LIVE, MarketDataMode.ZERODHA, ExecutionMode.ZERODHA,
                false, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> new ConfigurationValidator(properties, globalConfigService).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution-mode=ZERODHA requires trading.live-trading-enabled=true");
    }

    @Test
    void acceptsLiveModeWithZerodhaExecutionWhenLiveTradingFlagIsEnabled() {
        var properties = new TradingProperties(TradingMode.LIVE, MarketDataMode.ZERODHA, ExecutionMode.ZERODHA,
                true, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatCode(() -> new ConfigurationValidator(properties, globalConfigService).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsFailSafeBeforeForcedExit() {
        when(globalConfigService.getFailSafeSquareoffTime()).thenReturn(LocalTime.of(15, 10));
        var properties = new TradingProperties(TradingMode.LIVE, MarketDataMode.ZERODHA, ExecutionMode.ZERODHA,
                true, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> new ConfigurationValidator(properties, globalConfigService).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("fail-safe-squareoff-time must be after forced-exit-time");
    }

    @Test
    void rejectsFailSafeAtOrAfterMarketClose() {
        when(globalConfigService.getFailSafeSquareoffTime()).thenReturn(LocalTime.of(15, 30));
        var properties = new TradingProperties(TradingMode.LIVE, MarketDataMode.ZERODHA, ExecutionMode.ZERODHA,
                true, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> new ConfigurationValidator(properties, globalConfigService).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be before market close");
    }
}
