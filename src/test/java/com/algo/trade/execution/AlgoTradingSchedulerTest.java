package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.OptionType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlgoTradingSchedulerTest {

    @Test
    void computesPremiumCapUsingLiveInstrumentLotSize() {
        var globalConfigService = mock(GlobalConfigService.class);
        when(globalConfigService.getTotalCapital()).thenReturn(BigDecimal.valueOf(300_000));
        when(globalConfigService.getMaxRiskPerTradePercent()).thenReturn(BigDecimal.valueOf(1.2));

        assertThat(AlgoTradingScheduler.maxTradablePremium(globalConfigService, BigDecimal.valueOf(12), 75)).isEqualByComparingTo("400");
        assertThat(AlgoTradingScheduler.maxTradablePremium(globalConfigService, BigDecimal.valueOf(12), 65)).isEqualByComparingTo("461.5384615384615");
        assertThat(AlgoTradingScheduler.maxTradablePremium(globalConfigService, BigDecimal.valueOf(12), 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
