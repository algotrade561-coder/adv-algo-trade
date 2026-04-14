package com.kiteapioptions.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OptionType;
import org.junit.jupiter.api.Test;

class AlgoTradingSchedulerTest {

    @Test
    void enablesOnlyConfiguredLiveOptionTypes() {
        var properties = new TradingProperties(null, false, null, null, null, null, null, null, null, null, null,
                null, null);

        assertThat(AlgoTradingScheduler.optionTypeEnabled(properties, OptionType.PE)).isTrue();
        assertThat(AlgoTradingScheduler.optionTypeEnabled(properties, OptionType.CE)).isFalse();
    }
}
