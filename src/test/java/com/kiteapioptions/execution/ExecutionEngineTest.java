package com.kiteapioptions.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.OrderRequest;
import com.kiteapioptions.domain.OrderResponse;
import com.kiteapioptions.domain.OrderSide;
import com.kiteapioptions.domain.OrderStatus;
import com.kiteapioptions.domain.SignalType;
import com.kiteapioptions.domain.StrategyDecision;
import com.kiteapioptions.domain.TradeStatus;
import com.kiteapioptions.domain.UnderlyingSymbol;
import com.kiteapioptions.notification.TelegramAlertService;
import com.kiteapioptions.persistence.OrderRepository;
import com.kiteapioptions.persistence.StrategyDecisionRepository;
import com.kiteapioptions.persistence.TradeEntity;
import com.kiteapioptions.persistence.TradeRepository;
import com.kiteapioptions.risk.RiskEngine;
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
    private final BrokerClient brokerClient = mock(BrokerClient.class);
    private final TradingStateService tradingStateService = new TradingStateService(properties);
    private final TradeRepository tradeRepository = mock(TradeRepository.class);
    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final StrategyDecisionRepository decisionRepository = mock(StrategyDecisionRepository.class);
    private final ExecutionOutcomeCsvRecorder outcomeCsvRecorder = mock(ExecutionOutcomeCsvRecorder.class);
    private final TelegramAlertService telegramAlertService = mock(TelegramAlertService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-04-13T05:00:00Z"), ZoneOffset.UTC);

    private ExecutionEngine executionEngine;

    @BeforeEach
    void setUp() {
        executionEngine = new ExecutionEngine(properties, brokerClient, new RiskEngine(properties), tradingStateService,
                tradeRepository, orderRepository, decisionRepository, outcomeCsvRecorder, telegramAlertService, clock);
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

    private StrategyDecision buyDecision() {
        return new StrategyDecision(Instant.now(clock), UnderlyingSymbol.NIFTY, SignalType.BUY_CE,
                BigDecimal.valueOf(24_000), Optional.of("NFO:NIFTY24APR24000CE"),
                Optional.of(BigDecimal.valueOf(24_000)), Optional.of(OptionType.CE), true,
                Optional.of(BigDecimal.ONE), true, BigDecimal.valueOf(85), List.of("test"));
    }
}
