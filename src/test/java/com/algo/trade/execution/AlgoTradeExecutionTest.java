package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.marketdata.*;
import com.algo.trade.strategy.StrategyConfigService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class AlgoTradeExecutionTest {

    @Test
    void computesPremiumCapUsingLiveInstrumentLotSize() {
        var globalConfigService = mock(GlobalConfigService.class);
        var strategyConfigService = mock(StrategyConfigService.class);
        var properties = mock(TradingProperties.class);
        var instrumentCache = mock(InstrumentCache.class);
        var marketDataService = mock(MarketDataService.class);
        var liveInstrumentCache = mock(LiveInstrumentCache.class);

        when(globalConfigService.getTotalCapital()).thenReturn(BigDecimal.valueOf(300_000));
        when(globalConfigService.getMaxRiskPerTradePercent()).thenReturn(BigDecimal.valueOf(1.2));

        // Create a minimal StrategyConfig mock for the directional buy config
        var dirConfig = new com.algo.trade.strategy.StrategyConfig(com.algo.trade.strategy.StrategyType.DIRECTIONAL_BUY);
        dirConfig.setStopLossPercent(BigDecimal.valueOf(12));
        when(strategyConfigService.getDirectionalBuyConfig("NIFTY")).thenReturn(dirConfig);

        var builder = new ScanContextBuilder(properties, globalConfigService, strategyConfigService,
                instrumentCache, marketDataService, liveInstrumentCache,
                new com.algo.trade.strategy.RegimeAwareStrikeSelector(),
                mock(com.algo.trade.underlying.UnderlyingConfigService.class));

        assertThat(builder.maxTradablePremium(com.algo.trade.domain.UnderlyingSymbol.NIFTY, 75)).isEqualByComparingTo("400");
        assertThat(builder.maxTradablePremium(com.algo.trade.domain.UnderlyingSymbol.NIFTY, 65)).isEqualByComparingTo("461.5384615384615");
        assertThat(builder.maxTradablePremium(com.algo.trade.domain.UnderlyingSymbol.NIFTY, 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
