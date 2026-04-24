package com.algo.trade.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RiskEngineTest {

    private final StrategyConfigService mockConfigService = mock(StrategyConfigService.class);
    private final TradingStateService tradingStateService;
    private final RiskEngine riskEngine;

    RiskEngineTest() {
        when(mockConfigService.getDirectionalBuyConfig()).thenReturn(new StrategyConfig(StrategyType.DIRECTIONAL_BUY));
        TradingProperties props = new TradingProperties(null, false, null, null,
                null, null, null, null, null, null, null, null, null);
        tradingStateService = new TradingStateService(props);
        riskEngine = new RiskEngine(props, mockConfigService, tradingStateService);
    }

    @Test
    void sizesQuantityByRiskAndLotSize() {
        PositionSizingResult result = riskEngine.calculateQuantity(BigDecimal.valueOf(100), 75);

        assertThat(result.allowed()).isTrue();
        assertThat(result.quantity()).isEqualTo(300);
        assertThat(result.estimatedCost()).isEqualByComparingTo(BigDecimal.valueOf(30_000));
    }

    @Test
    void rejectsWhenOneOpenTradeLimitIsReached() {
        RiskCheckResult result = riskEngine.evaluateEntry(buyDecision(), 1, 0, BigDecimal.ZERO, 0, false);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasons()).contains("One-open-trade-at-a-time limit reached");
    }

    @Test
    void rejectsWhenKillSwitchIsEnabled() {
        RiskCheckResult result = riskEngine.evaluateEntry(buyDecision(), 0, 0, BigDecimal.ZERO, 0, true);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasons()).contains("Kill switch is enabled");
    }

    private StrategyDecision buyDecision() {
        return new StrategyDecision(Instant.now(), UnderlyingSymbol.NIFTY, SignalType.BUY_CE,
                BigDecimal.valueOf(24_000), Optional.of(BigDecimal.valueOf(100)), Optional.of(125_000L),
                Optional.of(75), Optional.of(BigDecimal.valueOf(7_500)), Optional.of("NFO:NIFTY24APR24000CE"),
                Optional.of(BigDecimal.valueOf(24_000)), Optional.of(OptionType.CE), true,
                Optional.of(BigDecimal.ONE), true, List.of("test"));
    }
}
