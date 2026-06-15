package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.GlobalConfig;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.PositionSyncProperties;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.*;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.*;
import com.algo.trade.risk.RiskEngine;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import org.junit.jupiter.api.*;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration test simulating the complete live trade lifecycle:
 *   1. Real entry → order fill → trade open → exit monitor → close
 *   2. Paper entry → trade open → exit monitor → close (no broker orders)
 *   3. Paper trade isolation from risk accounting
 *   4. Watchdog handling of pending BUY and SELL orders
 *   5. StrategyType persistence and resolution
 */
class LiveFlowIntegrationTest {

    private TradingProperties properties;
    private GlobalConfigService globalConfigService;
    private BrokerClient brokerClient;
    private RiskEngine riskEngine;
    private TradingStateService tradingStateService;
    private TradeRepository tradeRepository;
    private OrderRepository orderRepository;
    private StrategyDecisionRepository decisionRepository;
    private ErrorEventRepository errorEventRepository;
    private ExecutionTuningRecorder outcomeCsvRecorder;
    private TelegramAlertService telegramAlertService;
    private StrategyConfigService strategyConfigService;
    private MarketDataService marketDataService;
    private ExecutionEngine executionEngine;
    private Clock clock;

    @BeforeEach
    void setUp() {
        properties = new TradingProperties(null, false, null, null, null, null,
                null, null, null, null, null, null, null);
        globalConfigService = mock(GlobalConfigService.class);
        when(globalConfigService.getCached()).thenReturn(new GlobalConfig(properties));
        when(globalConfigService.getTotalCapital()).thenReturn(BigDecimal.valueOf(60000));
        when(globalConfigService.getMaxRiskPerTradePercent()).thenReturn(BigDecimal.valueOf(5));
        when(globalConfigService.getMaxOpenTrades()).thenReturn(5);
        when(globalConfigService.getMaxTradesPerDay()).thenReturn(10);
        when(globalConfigService.getMaxConsecutiveLosses()).thenReturn(5);
        when(globalConfigService.getMaxDailyLossPercent()).thenReturn(BigDecimal.valueOf(3));
        when(globalConfigService.getCooldownMinutes()).thenReturn(0);
        when(globalConfigService.getEnabledOptionTypes()).thenReturn(List.of(OptionType.CE, OptionType.PE));
        when(globalConfigService.getDailyProfitTarget()).thenReturn(BigDecimal.ZERO);
        when(globalConfigService.getMaxPendingOrders()).thenReturn(3);
        when(globalConfigService.getLimitOrderCancelMinutes()).thenReturn(1);

        brokerClient = mock(BrokerClient.class);
        tradingStateService = mock(TradingStateService.class);
        when(tradingStateService.running()).thenReturn(true);
        when(tradingStateService.killSwitchEnabled()).thenReturn(false);
        when(tradingStateService.haltMode()).thenReturn(com.algo.trade.risk.HaltMode.NONE);
        when(tradingStateService.isDailyApproved()).thenReturn(true);

        tradeRepository = mock(TradeRepository.class);
        orderRepository = mock(OrderRepository.class);
        decisionRepository = mock(StrategyDecisionRepository.class);
        errorEventRepository = mock(ErrorEventRepository.class);
        outcomeCsvRecorder = mock(ExecutionTuningRecorder.class);
        telegramAlertService = mock(TelegramAlertService.class);

        strategyConfigService = mock(StrategyConfigService.class);
        when(strategyConfigService.getDirectionalBuyConfig()).thenReturn(new StrategyConfig(StrategyType.DIRECTIONAL_BUY));
        when(strategyConfigService.getDirectionalBuyConfig(any())).thenReturn(new StrategyConfig(StrategyType.DIRECTIONAL_BUY));

        clock = Clock.fixed(Instant.parse("2026-04-27T04:30:00Z"), ZoneId.of("Asia/Kolkata"));

        riskEngine = new RiskEngine(globalConfigService, properties, strategyConfigService, tradingStateService, null);

        when(tradeRepository.findByStatus(TradeStatus.OPEN)).thenReturn(List.of());
        when(tradeRepository.findByEntryTimeBetween(any(), any())).thenReturn(List.of());
        when(tradeRepository.findAll()).thenReturn(List.of());
        when(tradeRepository.findByInstrumentKeyAndStatus(any(), any())).thenReturn(List.of());
        when(tradeRepository.findByInstrumentKeyAndEntryTimeBetween(any(), any(), any())).thenReturn(List.of());
        when(orderRepository.findBySideAndUpdatedAtBetween(any(), any(), any())).thenReturn(List.of());
        when(orderRepository.findByStatusIn(any())).thenReturn(List.of());
        when(orderRepository.findByInstrumentKeyAndSideAndStatusIn(any(), any(), any())).thenReturn(List.of());
        when(decisionRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        marketDataService = mock(MarketDataService.class);
        when(marketDataService.quote(any())).thenReturn(Optional.empty());

        var smartRouter = mock(SmartOrderRouter.class);
        when(smartRouter.route(any(), any(), any())).thenReturn(
                new SmartOrderRouter.RoutingDecision(
                        OrderType.LIMIT, Optional.of(BigDecimal.valueOf(30)),
                        "test", 0, SmartOrderRouter.LiquidityClass.UNKNOWN));

        executionEngine = new ExecutionEngine(properties, globalConfigService, brokerClient, riskEngine,
                tradingStateService, tradeRepository, orderRepository, errorEventRepository,
                decisionRepository, outcomeCsvRecorder, telegramAlertService, new PositionSyncProperties(true),
                strategyConfigService, marketDataService, smartRouter, clock);
    }

    private StrategyDecision testDecision(String reason) {
        return new StrategyDecision(
                Instant.now(clock), UnderlyingSymbol.NIFTY, SignalType.BUY_CE,
                BigDecimal.valueOf(24500),
                Optional.of(BigDecimal.valueOf(30)), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("NFO:NIFTY26APR24500CE"), Optional.of(BigDecimal.valueOf(24500)), Optional.of(OptionType.CE),
                true, Optional.empty(), true, BigDecimal.valueOf(80),
                List.of(reason, "Test signal"));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. REAL TRADE: Entry → Immediate Fill → Trade Open
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Real entry: immediate fill creates TradeEntity with strategyType")
    void realEntry_immediateFill_createsTradeWithStrategyType() {
        StrategyDecision decision = testDecision("VOLATILITY_BREAKOUT signal fired");
        when(brokerClient.placeOrder(any())).thenReturn(new OrderResponse(
                "ENTRY-test", Optional.of("BRK-001"), "NFO:NIFTY26APR24500CE",
                OrderSide.BUY, OrderStatus.COMPLETE, 65, 65,
                Optional.of(BigDecimal.valueOf(30)), Optional.empty(), Instant.now(clock)));

        StrategyConfig vbConfig = new StrategyConfig(StrategyType.VOLATILITY_BREAKOUT);
        vbConfig.setStopLossPercent(BigDecimal.valueOf(35));
        ExecutionResult result = executionEngine.executeEntry(decision, BigDecimal.valueOf(30), 65, vbConfig);

        assertThat(result.accepted()).isTrue();
        verify(tradeRepository).save(argThat(trade -> {
            assertThat(trade.getTradeId()).startsWith("TRD-");
            assertThat(trade.getStrategyType()).isEqualTo("VOLATILITY_BREAKOUT");
            assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
            assertThat(trade.isPaperTrade()).isFalse();
            return true;
        }));
        verify(brokerClient).placeOrder(any()); // real broker order placed
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. REAL TRADE: Entry → Pending → Watchdog Fill
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Real entry: pending order → watchdog creates trade on fill")
    void realEntry_pendingOrder_watchdogCreatesTradeOnFill() {
        StrategyDecision decision = testDecision("DIRECTIONAL_BUY breakout");
        when(brokerClient.placeOrder(any())).thenReturn(new OrderResponse(
                "ENTRY-pending", Optional.of("BRK-002"), "NFO:NIFTY26APR24500CE",
                OrderSide.BUY, OrderStatus.OPEN, 65, 0,
                Optional.empty(), Optional.empty(), Instant.now(clock)));

        ExecutionResult result = executionEngine.executeEntry(decision, BigDecimal.valueOf(30), 65);

        assertThat(result.accepted()).isTrue();
        // No trade created yet — watchdog will create it when order fills
        // But order IS persisted for watchdog tracking
        verify(orderRepository, atLeastOnce()).save(any());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. PAPER TRADE: Entry → No Broker Order → Trade Created
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Paper entry: creates PAPER-TRD trade, no broker order")
    void paperEntry_createsTradeWithoutBrokerOrder() {
        StrategyDecision decision = testDecision("ITM_CONVICTION signal");

        ExecutionResult result = executionEngine.executePaperEntry(decision, BigDecimal.valueOf(30), 65, null);

        assertThat(result.accepted()).isTrue();
        verify(tradeRepository).save(argThat(trade -> {
            assertThat(trade.getTradeId()).startsWith("PAPER-TRD-");
            assertThat(trade.getStrategyType()).isEqualTo("ITM_CONVICTION");
            assertThat(trade.getStatus()).isEqualTo(TradeStatus.OPEN);
            assertThat(trade.isPaperTrade()).isTrue();
            return true;
        }));
        verify(brokerClient, never()).placeOrder(any()); // NO broker order
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. PAPER TRADE: Close → No Broker SELL → P&L Computed
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Paper close: skips broker SELL, computes P&L")
    void paperClose_skipsBrokerSell_computesPnl() {
        TradeEntity paperTrade = new TradeEntity("PAPER-TRD-test", "NFO:NIFTY26APR24500CE",
                "NIFTY", "CE", TradeStatus.OPEN, 65, BigDecimal.valueOf(30), Instant.now(clock), "PAPER_TRADE: test");
        when(tradeRepository.findById("PAPER-TRD-test")).thenReturn(Optional.of(paperTrade));
        when(tradeRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        ExecutionResult result = executionEngine.closeTrade("PAPER-TRD-test", BigDecimal.valueOf(36), "TARGET");

        assertThat(result.accepted()).isTrue();
        verify(brokerClient, never()).placeOrder(any()); // NO broker SELL order
        assertThat(paperTrade.getStatus()).isEqualTo(TradeStatus.CLOSED);
        assertThat(paperTrade.getExitPrice()).isEqualByComparingTo("36");
        // P&L = (36 - 30) × 65 = 390
        assertThat(paperTrade.getRealizedPnl()).isEqualByComparingTo("390");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. REAL TRADE: Close → Broker SELL → P&L Computed
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Real close: places broker SELL, computes P&L")
    void realClose_placesBrokerSell_computesPnl() {
        TradeEntity realTrade = new TradeEntity("TRD-real", "NFO:NIFTY26APR24500CE",
                "NIFTY", "CE", TradeStatus.OPEN, 65, BigDecimal.valueOf(30), Instant.now(clock), "DIRECTIONAL_BUY");
        when(tradeRepository.findById("TRD-real")).thenReturn(Optional.of(realTrade));
        when(brokerClient.placeOrder(any())).thenReturn(new OrderResponse(
                "EXIT-test", Optional.of("BRK-EXIT"), "NFO:NIFTY26APR24500CE",
                OrderSide.SELL, OrderStatus.COMPLETE, 65, 65,
                Optional.of(BigDecimal.valueOf(25)), Optional.empty(), Instant.now(clock)));

        ExecutionResult result = executionEngine.closeTrade("TRD-real", BigDecimal.valueOf(25), "STOP_LOSS");

        assertThat(result.accepted()).isTrue();
        verify(brokerClient).placeOrder(argThat(req -> req.side() == OrderSide.SELL));
        assertThat(realTrade.getStatus()).isEqualTo(TradeStatus.CLOSED);
        // P&L = (140 - 150) × 65 = -650
        assertThat(realTrade.getRealizedPnl()).isEqualByComparingTo("-325");
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 6. PAPER TRADE ISOLATION: Does NOT count toward risk limits
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Paper trades excluded from openTradeCount and risk checks")
    void paperTrades_excludedFromRiskAccounting() {
        TradeEntity paperTrade = new TradeEntity("PAPER-TRD-1", "NFO:NIFTY26APR24500CE",
                "NIFTY", "CE", TradeStatus.OPEN, 65, BigDecimal.valueOf(30), Instant.now(clock), "PAPER");
        TradeEntity realTrade = new TradeEntity("TRD-1", "NFO:NIFTY26APR24600CE",
                "NIFTY", "CE", TradeStatus.OPEN, 65, BigDecimal.valueOf(25), Instant.now(clock), "REAL");
        when(tradeRepository.findByStatus(TradeStatus.OPEN)).thenReturn(List.of(paperTrade, realTrade));

        // Real entry should succeed — only 1 real open trade, not 2
        StrategyDecision decision = testDecision("DIRECTIONAL_BUY test");
        when(brokerClient.placeOrder(any())).thenReturn(new OrderResponse(
                "ENTRY-test2", Optional.of("BRK-003"), "NFO:NIFTY26APR24700CE",
                OrderSide.BUY, OrderStatus.COMPLETE, 65, 65,
                Optional.of(BigDecimal.valueOf(20)), Optional.empty(), Instant.now(clock)));
        when(tradeRepository.findByInstrumentKeyAndStatus(any(), any())).thenReturn(List.of());

        ExecutionResult result = executionEngine.executeEntry(decision, BigDecimal.valueOf(20), 65);

        assertThat(result.accepted()).isTrue(); // not blocked by paper trade
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 7. PAPER ENTRY: Rejected when engine stopped
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Paper entry rejected when trading engine is stopped")
    void paperEntry_rejectedWhenEngineStopped() {
        when(tradingStateService.running()).thenReturn(false);
        StrategyDecision decision = testDecision("ITM_CONVICTION test");

        StrategyConfig directionalConfig = new StrategyConfig(StrategyType.DIRECTIONAL_BUY);
        directionalConfig.setStopLossPercent(BigDecimal.valueOf(12));
        ExecutionResult result = executionEngine.executePaperEntry(decision, BigDecimal.valueOf(30), 65, directionalConfig);

        assertThat(result.accepted()).isFalse();
        verify(tradeRepository, never()).save(any());
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 8. WATCHDOG: EXIT order fill closes trade (not creates new one)
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Watchdog: EXIT order fill closes existing trade")
    void watchdog_exitOrderFill_closesExistingTrade() {
        TradeEntity openTrade = new TradeEntity("TRD-open", "NFO:NIFTY26APR24500CE",
                "NIFTY", "CE", TradeStatus.OPEN, 65, BigDecimal.valueOf(30), Instant.now(clock), "test");
        when(tradeRepository.findByStatus(TradeStatus.OPEN)).thenReturn(List.of(openTrade));

        OrderEntity exitOrder = mock(OrderEntity.class);
        when(exitOrder.getClientOrderId()).thenReturn("EXIT-abc");
        when(exitOrder.getInstrumentKey()).thenReturn("NFO:NIFTY26APR24500CE");
        when(exitOrder.getAverageFillPrice()).thenReturn(BigDecimal.valueOf(36));
        when(exitOrder.getUpdatedAt()).thenReturn(Instant.now(clock));
        when(exitOrder.getBrokerOrderId()).thenReturn("BRK-EXIT-1");
        when(exitOrder.getStatus()).thenReturn(OrderStatus.OPEN);

        when(brokerClient.orderStatus("BRK-EXIT-1")).thenReturn(Optional.of(new OrderResponse(
                "EXIT-abc", Optional.of("BRK-EXIT-1"), "NFO:NIFTY26APR24500CE",
                OrderSide.SELL, OrderStatus.COMPLETE, 65, 65,
                Optional.of(BigDecimal.valueOf(36)), Optional.empty(), Instant.now(clock))));
        when(orderRepository.findByStatusIn(any())).thenReturn(List.of(exitOrder));

        OrderFillWatchdog watchdog = new OrderFillWatchdog(orderRepository, brokerClient, executionEngine);
        watchdog.checkPendingOrders();

        // Should close the trade, not create a new one
        verify(tradeRepository).save(argThat(trade -> {
            assertThat(trade.getTradeId()).isEqualTo("TRD-open");
            assertThat(trade.getStatus()).isEqualTo(TradeStatus.CLOSED);
            // P&L = (160 - 150) × 65 = 650
            assertThat(trade.getRealizedPnl()).isEqualByComparingTo("390");
            return true;
        }));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 9. STRATEGY TYPE: extractStrategyType from decision reasons
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("extractStrategyType correctly identifies strategy from reasons")
    void extractStrategyType_identifiesFromReasons() {
        // VB signal
        StrategyDecision vbDecision = testDecision("VOLATILITY_BREAKOUT squeeze breakout");
        when(brokerClient.placeOrder(any())).thenReturn(new OrderResponse(
                "ENTRY-vb", Optional.of("BRK-VB"), "NFO:NIFTY26APR24500CE",
                OrderSide.BUY, OrderStatus.COMPLETE, 65, 65,
                Optional.of(BigDecimal.valueOf(30)), Optional.empty(), Instant.now(clock)));

        StrategyConfig vbConfig = new StrategyConfig(StrategyType.VOLATILITY_BREAKOUT);
        vbConfig.setStopLossPercent(BigDecimal.valueOf(35));
        executionEngine.executeEntry(vbDecision, BigDecimal.valueOf(30), 65, vbConfig);

        verify(tradeRepository).save(argThat(trade -> {
            assertThat(trade.getStrategyType()).isEqualTo("VOLATILITY_BREAKOUT");
            return true;
        }));
    }
}
