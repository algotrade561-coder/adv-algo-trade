package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.*;
import com.algo.trade.risk.RiskEngine;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ExecutionEngineTest {

    private final TradingProperties properties = new TradingProperties(null, false, null, null,
            null, null, null, null, null, null, null, null, null);
    private final GlobalConfigService globalConfigService = mock(GlobalConfigService.class);
    private final BrokerClient brokerClient = mock(BrokerClient.class);
    private final TradingStateService tradingStateService = new TradingStateService(properties);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final ErrorEventRepository errorEventRepository = mock(ErrorEventRepository.class);
    private final StrategyDecisionRepository decisionRepository = mock(StrategyDecisionRepository.class);
    private final ExecutionOutcomeCsvRecorder outcomeCsvRecorder = mock(ExecutionOutcomeCsvRecorder.class);
    private final TelegramAlertService telegramAlertService = mock(TelegramAlertService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-13T05:00:00Z"), ZoneOffset.UTC);

    private ExecutionEngine executionEngine;

    @BeforeEach
    void setUp() {
        // Set up GlobalConfigService mock with default risk values
        when(globalConfigService.getMaxOrdersPerDay()).thenReturn(6);
        when(globalConfigService.getMaxOpenTrades()).thenReturn(1);
        when(globalConfigService.getMaxTradesPerDay()).thenReturn(6);
        when(globalConfigService.getMaxConsecutiveLosses()).thenReturn(2);
        when(globalConfigService.getTotalCapital()).thenReturn(BigDecimal.valueOf(300_000));
        when(globalConfigService.getMaxRiskPerTradePercent()).thenReturn(BigDecimal.valueOf(1.2));
        when(globalConfigService.getMaxDailyLossPercent()).thenReturn(BigDecimal.valueOf(3));
        when(globalConfigService.getSameInstrumentReentryMinPriceMovePercent()).thenReturn(BigDecimal.TEN);
        when(globalConfigService.getCooldownMinutes()).thenReturn(10);
        when(globalConfigService.getDailyProfitTarget()).thenReturn(BigDecimal.ZERO);

        var mockConfigService = mock(StrategyConfigService.class);
        when(mockConfigService.getDirectionalBuyConfig()).thenReturn(new StrategyConfig(StrategyType.DIRECTIONAL_BUY));
        executionEngine = new ExecutionEngine(properties, globalConfigService, brokerClient, new RiskEngine(globalConfigService, properties, mockConfigService, tradingStateService), tradingStateService,
                tradeRepository, orderRepository, errorEventRepository, decisionRepository, outcomeCsvRecorder,
                telegramAlertService, clock);
        when(tradeRepository.findByStatus(TradeStatus.OPEN)).thenReturn(List.of());
        when(tradeRepository.findByEntryTimeBetween(any(), any())).thenReturn(List.of());
        when(tradeRepository.findAll()).thenReturn(List.of());
        when(tradeRepository.findByInstrumentKeyAndStatus(any(), eq(TradeStatus.OPEN))).thenReturn(List.of());
        when(tradeRepository.findByInstrumentKeyAndEntryTimeBetween(any(), any(), any())).thenReturn(List.of());
        when(orderRepository.findBySideAndUpdatedAtBetween(eq(OrderSide.BUY.name()), any(), any())).thenReturn(List.of());
        when(orderRepository.findByInstrumentKeyAndSideAndStatusIn(any(), eq(OrderSide.BUY.name()), anyCollection()))
                .thenReturn(List.of());
    }

    @Test
    void rejectsEntryWhenTradingStateIsStopped() {
        ExecutionResult result = executionEngine.executeEntry(buyDecision(), BigDecimal.valueOf(100), 75);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasons()).contains("Trading engine is stopped");
        verify(brokerClient, never()).placeOrder(any());
    }

    @Test
    void rejectsEntryWhenPositionSizingCannotBuyOneLot() {
        tradingStateService.start();

        ExecutionResult result = executionEngine.executeEntry(buyDecision(), BigDecimal.valueOf(5000), 75);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasons()).contains("Premium is too high for the risk budget");
        verify(brokerClient, never()).placeOrder(any());
    }

    @Test
    void opensTradeWhenOrderCompletes() {
        tradingStateService.start();
        when(brokerClient.placeOrder(any(OrderRequest.class))).thenReturn(new OrderResponse(
                "ENTRY-1",
                Optional.of("PAPER-ENTRY-1"),
                "NFO:NIFTY24APR24000CE",
                OrderSide.BUY,
                OrderStatus.COMPLETE,
                300,
                300,
                Optional.of(BigDecimal.valueOf(101)),
                Optional.empty(),
                Instant.now(clock)
        ));

        ExecutionResult result = executionEngine.executeEntry(buyDecision(), BigDecimal.valueOf(100), 75);

        assertThat(result.accepted()).isTrue();
        verify(orderRepository).save(any());
        verify(tradeRepository).save(any(TradeEntity.class));
    }

    @Test
    void recordsBrokerFailureAsRejectedEntry() {
        tradingStateService.start();
        when(brokerClient.placeOrder(any(OrderRequest.class))).thenThrow(new IllegalStateException("Broker timeout"));

        ExecutionResult result = executionEngine.executeEntry(buyDecision(), BigDecimal.valueOf(100), 75);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasons()).contains("Broker timeout");
        verify(errorEventRepository).save(any());
        verify(outcomeCsvRecorder).recordEntry(eq(buyDecision()), eq(BigDecimal.valueOf(100)), eq(75),
                eq("BROKER_ERROR"), eq(false), eq(300), any(), any(), any(), eq(List.of("Broker timeout")));
    }

    @Test
    void rejectsEntryWhenSameInstrumentHasOpenTrade() {
        tradingStateService.start();
        when(tradeRepository.findByInstrumentKeyAndStatus("NFO:NIFTY24APR24000CE", TradeStatus.OPEN))
                .thenReturn(List.of(new TradeEntity("T1", "NFO:NIFTY24APR24000CE", "NIFTY", "CE",
                        TradeStatus.OPEN, 75, BigDecimal.valueOf(100), Instant.now(clock), "existing")));

        ExecutionResult result = executionEngine.executeEntry(buyDecision(), BigDecimal.valueOf(100), 75);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasons()).contains("Open trade already exists for instrument: NFO:NIFTY24APR24000CE");
        verify(brokerClient, never()).placeOrder(any());
    }

    @Test
    void sendsTelegramAlertWhenTradeCloses() {
        var trade = new TradeEntity("TRD-1", "NFO:NIFTY24APR24000CE", "NIFTY", "CE",
                TradeStatus.OPEN, 75, BigDecimal.valueOf(100), Instant.now(clock), "entry");
        var exitOrder = new OrderResponse(
                "EXIT-1",
                Optional.of("PAPER-EXIT-1"),
                "NFO:NIFTY24APR24000CE",
                OrderSide.SELL,
                OrderStatus.COMPLETE,
                75,
                75,
                Optional.of(BigDecimal.valueOf(112)),
                Optional.empty(),
                Instant.now(clock)
        );
        when(tradeRepository.findById("TRD-1")).thenReturn(Optional.of(trade));
        when(brokerClient.placeOrder(any(OrderRequest.class))).thenReturn(exitOrder);

        ExecutionResult result = executionEngine.closeTrade("TRD-1", BigDecimal.valueOf(111), "trailing stop");

        assertThat(result.accepted()).isTrue();
        verify(orderRepository).save(any());
        verify(tradeRepository).save(trade);
        verify(telegramAlertService).tradeClosed(
                eq("TRD-1"),
                eq("NFO:NIFTY24APR24000CE"),
                eq(75),
                eq(BigDecimal.valueOf(100)),
                eq(BigDecimal.valueOf(112)),
                eq(BigDecimal.valueOf(900)),
                eq("trailing stop"),
                eq(exitOrder)
        );
    }

    @Test
    void acceptsLimitOrderInOpenStatusForWatchdog() {
        tradingStateService.start();
        when(brokerClient.placeOrder(any(OrderRequest.class))).thenReturn(new OrderResponse(
                "ENTRY-1",
                Optional.of("BROKER-1"),
                "NFO:NIFTY24APR24000CE",
                OrderSide.BUY,
                OrderStatus.OPEN,
                300,
                0,
                Optional.empty(),
                Optional.empty(),
                Instant.now(clock)
        ));

        ExecutionResult result = executionEngine.executeEntry(buyDecision(), BigDecimal.valueOf(100), 75);

        assertThat(result.accepted()).isTrue();
        assertThat(result.reasons()).contains("Limit order placed — awaiting fill");
        verify(orderRepository).save(any());
        // No trade created yet — watchdog will handle it
        verify(tradeRepository, never()).save(any(TradeEntity.class));
    }

    @Test
    void openTradeFromFilledOrderCreatesTradeEntity() {
        OrderEntity orderEntity = new OrderEntity("ENTRY-1", "BROKER-1", "NFO:NIFTY24APR24000CE",
                "BUY", OrderStatus.COMPLETE, 300, 300, BigDecimal.valueOf(101), null, Instant.now(clock));

        executionEngine.openTradeFromFilledOrder(orderEntity);

        verify(tradeRepository).save(any(TradeEntity.class));
        verify(orderRepository).save(orderEntity);
    }

    @Test
    void rejectsEntryWhenPendingOpenOrderExistsForSameInstrument() {
        tradingStateService.start();
        when(orderRepository.findByInstrumentKeyAndSideAndStatusIn(
                eq("NFO:NIFTY24APR24000CE"), eq("BUY"), any()))
                .thenReturn(List.of(new OrderEntity("ENTRY-OLD", "BROKER-OLD", "NFO:NIFTY24APR24000CE",
                        "BUY", OrderStatus.OPEN, 300, 0, null, null, Instant.now(clock))));

        ExecutionResult result = executionEngine.executeEntry(buyDecision(), BigDecimal.valueOf(100), 75);

        assertThat(result.accepted()).isFalse();
        assertThat(result.reasons()).contains("Open buy order already exists for instrument: NFO:NIFTY24APR24000CE");
        verify(brokerClient, never()).placeOrder(any());
    }

    private StrategyDecision buyDecision() {
        return new StrategyDecision(Instant.now(clock), UnderlyingSymbol.NIFTY, SignalType.BUY_CE,
                BigDecimal.valueOf(24_000), Optional.of(BigDecimal.valueOf(100)), Optional.of(125_000L),
                Optional.of(75), Optional.of(BigDecimal.valueOf(7_500)), Optional.of("NFO:NIFTY24APR24000CE"),
                Optional.of(BigDecimal.valueOf(24_000)), Optional.of(OptionType.CE), true,
                Optional.of(BigDecimal.ONE), true, BigDecimal.valueOf(85), List.of("test"));
    }
}
