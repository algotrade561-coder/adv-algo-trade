package com.algo.trade.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.config.GlobalConfigService;
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
    private final GlobalConfigService globalConfigService = mock(GlobalConfigService.class);
    private final TradingStateService tradingStateService;
    private final RiskEngine riskEngine;

    RiskEngineTest() {
        StrategyConfig directionalBuyConfig = new StrategyConfig(StrategyType.DIRECTIONAL_BUY);
        directionalBuyConfig.setStopLossPercent(BigDecimal.valueOf(12));
        when(mockConfigService.getDirectionalBuyConfig()).thenReturn(directionalBuyConfig);
        TradingProperties props = new TradingProperties(null, false, null, null,
                null, null, null, null, null, null, null, null, null);
        // Set up GlobalConfigService mock with default risk values
        when(globalConfigService.getMaxOpenTrades()).thenReturn(1);
        when(globalConfigService.getMaxTradesPerDay()).thenReturn(6);
        when(globalConfigService.getMaxConsecutiveLosses()).thenReturn(2);
        when(globalConfigService.getTotalCapital()).thenReturn(BigDecimal.valueOf(300_000));
        when(globalConfigService.getMaxRiskPerTradePercent()).thenReturn(BigDecimal.valueOf(1.2));
        when(globalConfigService.getMaxDailyLossPercent()).thenReturn(BigDecimal.valueOf(3));
        when(globalConfigService.getDailyProfitTarget()).thenReturn(BigDecimal.ZERO);
        tradingStateService = new TradingStateService(props, globalConfigService, mock(com.algo.trade.underlying.UnderlyingConfigService.class));
        riskEngine = new RiskEngine(globalConfigService, props, mockConfigService, tradingStateService, null);
    }

    @Test
    void sizesQuantityByRiskAndLotSize() {
        PositionSizingResult result = riskEngine.calculateQuantity(BigDecimal.valueOf(100), 75);

        assertThat(result.allowed()).isTrue();
        assertThat(result.quantity()).isEqualTo(300);
        assertThat(result.estimatedCost()).isEqualByComparingTo(BigDecimal.valueOf(30_000));
    }

    @Test
    void capsRealLotsNotFoldedBlocks() {
        // P0-1: maxLotsPerTrade must bound REAL lots. With contractLot=65 and desiredLots=5,
        // the result must be exactly 5 lots = 325 units — NOT maxLots×desiredLots×contractLot (=1625).
        when(globalConfigService.getMaxLotsPerTrade()).thenReturn(5);
        PositionSizingResult result = riskEngine.calculateQuantity(
                BigDecimal.valueOf(10), 65, 5, BigDecimal.valueOf(12));

        assertThat(result.allowed()).isTrue();
        assertThat(result.quantity()).isEqualTo(325);
        assertThat(result.quantity() % 65).isZero();
    }

    @Test
    void respectsDesiredLotsConviction() {
        // desiredLots (strategy conviction) caps below both risk budget and maxLotsPerTrade.
        when(globalConfigService.getMaxLotsPerTrade()).thenReturn(10);
        PositionSizingResult result = riskEngine.calculateQuantity(
                BigDecimal.valueOf(10), 65, 2, BigDecimal.valueOf(12));

        assertThat(result.allowed()).isTrue();
        assertThat(result.quantity()).isEqualTo(130); // 2 lots × 65
    }

    @Test
    void enforcesNotionalCeiling() {
        // P0-1 defense-in-depth: a single trade's notional cannot exceed 50% of capital.
        when(globalConfigService.getMaxLotsPerTrade()).thenReturn(0); // no lot cap
        when(globalConfigService.getMaxRiskPerTradePercent()).thenReturn(BigDecimal.valueOf(50));
        PositionSizingResult result = riskEngine.calculateQuantity(
                BigDecimal.valueOf(300), 65, 0, BigDecimal.valueOf(12));

        assertThat(result.allowed()).isTrue();
        assertThat(result.estimatedCost().doubleValue()).isLessThanOrEqualTo(150_000.0);
        assertThat(result.quantity() % 65).isZero();
    }

    @Test
    void rejectsWhenMaxOpenTradesLimitIsReached() {
        RiskCheckResult result = riskEngine.evaluateEntry(buyDecision(), 1, 0, BigDecimal.ZERO, 0, false);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasons()).anyMatch(r -> r.contains("Max open trades limit reached"));
    }

    @Test
    void allowsEntryWhenBelowMaxOpenTrades() {
        // Default maxOpenTrades=1, openTradeCount=0 → allowed
        RiskCheckResult result = riskEngine.evaluateEntry(buyDecision(), 0, 0, BigDecimal.ZERO, 0, false);

        assertThat(result.allowed()).isTrue();
    }

    @Test
    void allowsMultipleOpenTradesWhenConfigured() {
        when(mockConfigService.getDirectionalBuyConfig()).thenReturn(new StrategyConfig(StrategyType.DIRECTIONAL_BUY));
        TradingProperties multiTradeProps = new TradingProperties(null, false, null, null,
                null, null, null, null,
                new TradingProperties.Risk(BigDecimal.valueOf(300_000), BigDecimal.valueOf(1.2), BigDecimal.valueOf(3),
                        6, 6, 2, 3, BigDecimal.TEN, 10, BigDecimal.ZERO),
                null, null, null, null);
        GlobalConfigService multiGlobalConfig = mock(GlobalConfigService.class);
        when(multiGlobalConfig.getMaxOpenTrades()).thenReturn(3);
        when(multiGlobalConfig.getMaxTradesPerDay()).thenReturn(6);
        when(multiGlobalConfig.getMaxConsecutiveLosses()).thenReturn(2);
        when(multiGlobalConfig.getTotalCapital()).thenReturn(BigDecimal.valueOf(300_000));
        when(multiGlobalConfig.getMaxRiskPerTradePercent()).thenReturn(BigDecimal.valueOf(1.2));
        when(multiGlobalConfig.getMaxDailyLossPercent()).thenReturn(BigDecimal.valueOf(3));
        when(multiGlobalConfig.getDailyProfitTarget()).thenReturn(BigDecimal.ZERO);
        TradingStateService multiTradingState = new TradingStateService(multiTradeProps, multiGlobalConfig, mock(com.algo.trade.underlying.UnderlyingConfigService.class));
        multiTradingState.start();
        multiTradingState.approveToday();
        RiskEngine multiRiskEngine = new RiskEngine(multiGlobalConfig, multiTradeProps, mockConfigService, multiTradingState, null);

        // 2 open trades, max is 3 → allowed
        RiskCheckResult allowed = multiRiskEngine.evaluateEntry(buyDecision(), 2, 2, BigDecimal.ZERO, 0, false);
        assertThat(allowed.allowed()).isTrue();

        // 3 open trades, max is 3 → rejected
        RiskCheckResult rejected = multiRiskEngine.evaluateEntry(buyDecision(), 3, 3, BigDecimal.ZERO, 0, false);
        assertThat(rejected.allowed()).isFalse();
        assertThat(rejected.reasons()).anyMatch(r -> r.contains("Max open trades limit reached (3/3)"));
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
