package com.kiteapioptions.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kiteapioptions.domain.ExecutionMode;
import com.kiteapioptions.domain.MarketDataMode;
import com.kiteapioptions.domain.TradingMode;
import org.junit.jupiter.api.Test;

class ConfigurationValidatorTest {

    @Test
    void rejectsLiveTradingFlagOutsideLiveMode() {
        var properties = new TradingProperties(TradingMode.PAPER, MarketDataMode.MOCK, ExecutionMode.PAPER,
                true, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> new ConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trading.live-trading-enabled=true requires trading.mode=LIVE");
    }

    @Test
    void rejectsZerodhaExecutionWhenLiveTradingFlagIsDisabled() {
        var properties = new TradingProperties(TradingMode.LIVE, MarketDataMode.ZERODHA, ExecutionMode.ZERODHA,
                false, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> new ConfigurationValidator(properties).validate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("execution-mode=ZERODHA requires trading.live-trading-enabled=true");
    }

    @Test
    void acceptsLiveModeWithZerodhaExecutionWhenLiveTradingFlagIsEnabled() {
        var properties = new TradingProperties(TradingMode.LIVE, MarketDataMode.ZERODHA, ExecutionMode.ZERODHA,
                true, null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatCode(() -> new ConfigurationValidator(properties).validate()).doesNotThrowAnyException();
    }
}
