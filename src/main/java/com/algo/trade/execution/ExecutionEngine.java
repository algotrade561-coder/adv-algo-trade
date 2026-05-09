package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.domain.OrderType;
import com.algo.trade.domain.ProductType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import com.algo.trade.persistence.ErrorEventEntity;
import com.algo.trade.persistence.ErrorEventRepository;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.risk.RiskEngine;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts approved strategy entry decisions into broker orders and journal records.
 */
@Service
public class ExecutionEngine {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);
    /** NSE/BSE F&O tick size — all option prices must be multiples of ₹0.05. */
    private static final BigDecimal TICK_SIZE = new BigDecimal("0.05");

    /** Prevents two monitors from placing duplicate broker SELL orders for the same trade. */
    private final Set<String> closingInProgress = ConcurrentHashMap.newKeySet();
    /**
     * Counts concurrent entry executions in flight (order placed but trade not yet created).
     * Used together with max-open-trades to prevent over-entry.
     * Value = number of pending LIMIT orders that haven't filled/cancelled yet.
     */
    private final java.util.concurrent.atomic.AtomicInteger entriesInFlight = new java.util.concurrent.atomic.AtomicInteger(0);

    /**
     * Rejection circuit breaker — auto-halts entries after consecutive broker rejections.
     * Prevents infinite order placement when broker keeps rejecting (tick size, margin, rate limit).
     * Resets on any successful order placement. Halt persists until user resumes from UI.
     */
    private static final int MAX_CONSECUTIVE_REJECTIONS = 3;
    private final java.util.concurrent.atomic.AtomicInteger consecutiveRejections = new java.util.concurrent.atomic.AtomicInteger(0);

    private final TradingProperties properties;
    private final GlobalConfigService globalConfigService;
    private final BrokerClient brokerClient;
    private final RiskEngine riskEngine;
    private final TradingStateService tradingStateService;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final ErrorEventRepository errorEventRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder;
    private final TelegramAlertService telegramAlertService;
    private final com.algo.trade.config.PositionSyncProperties positionSyncProperties;
    private final StrategyConfigService strategyConfigService;
    private final MarketDataService marketDataService;
    private final SmartOrderRouter smartOrderRouter;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    @Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @Autowired
    public ExecutionEngine(TradingProperties properties, GlobalConfigService globalConfigService, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                           TradeRepository tradeRepository, OrderRepository orderRepository,
                           ErrorEventRepository errorEventRepository,
                           StrategyDecisionRepository decisionRepository,
                           ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder,
                           TelegramAlertService telegramAlertService,
                           com.algo.trade.config.PositionSyncProperties positionSyncProperties,
                           StrategyConfigService strategyConfigService,
                           MarketDataService marketDataService,
                           SmartOrderRouter smartOrderRouter) {
        this(properties, globalConfigService, brokerClient, riskEngine, tradingStateService, tradeRepository, orderRepository, errorEventRepository, decisionRepository,
                executionOutcomeCsvRecorder, telegramAlertService, positionSyncProperties, strategyConfigService, marketDataService, smartOrderRouter, Clock.systemUTC());
    }

    ExecutionEngine(TradingProperties properties, GlobalConfigService globalConfigService, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                    TradeRepository tradeRepository, OrderRepository orderRepository,
                    ErrorEventRepository errorEventRepository,
                    StrategyDecisionRepository decisionRepository, ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder,
                    TelegramAlertService telegramAlertService,
                    com.algo.trade.config.PositionSyncProperties positionSyncProperties, StrategyConfigService strategyConfigService,
                    MarketDataService marketDataService, SmartOrderRouter smartOrderRouter, Clock clock) {
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.brokerClient = brokerClient;
        this.riskEngine = riskEngine;
        this.tradingStateService = tradingStateService;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.errorEventRepository = errorEventRepository;
        this.decisionRepository = decisionRepository;
        this.executionOutcomeCsvRecorder = executionOutcomeCsvRecorder;
        this.telegramAlertService = telegramAlertService;
        this.positionSyncProperties = positionSyncProperties;
        this.strategyConfigService = strategyConfigService;
        this.marketDataService = marketDataService;
        this.smartOrderRouter = smartOrderRouter;
        this.clock = clock;
    }

    @Transactional
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize) {
        return executeEntry(decision, optionPremium, lotSize, (StrategyConfig) null);
    }

    /**
     * Execute entry with optional per-strategy config for position sizing and CSV recording.
     * If strategyConfig is null, falls back to directional buy config.
     */
    @Transactional(timeout = 30) // 30-second timeout prevents indefinite lock holding during slow broker I/O
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize, StrategyConfig strategyConfig) {
        return executeEntry(decision, optionPremium, lotSize, strategyConfig, null);
    }

    /**
     * Environment metadata captured at entry time for post-trade analysis.
     * Write-once: set on TradeEntity at creation, never modified after.
     */
    public record EnvironmentMetadata(int environmentScore, String environmentBreakdown, String sessionWindow) {}

    /**
     * Execute entry with optional per-strategy config and environment metadata.
     * Environment metadata (score, breakdown, session) is persisted on the TradeEntity for post-trade analysis.
     */
    @Transactional(timeout = 30)
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize,
                                         StrategyConfig strategyConfig, EnvironmentMetadata envMetadata) {
        StrategyConfig effectiveConfig = strategyConfig != null ? strategyConfig : strategyConfigService.getDirectionalBuyConfig(decision.underlying().name());
        BigDecimal stopLossPercent = effectiveConfig.getStopLossPercent();
        log.info("Entry execution requested: signalType={}, underlying={}, instrument={}, optionType={}, premium={}, lotSize={}, running={}, killSwitch={}",
                decision.signalType(),
                decision.underlying(),
                decision.selectedInstrumentKey().orElse(""),
                decision.optionType().map(Enum::name).orElse(""),
                optionPremium,
                lotSize,
                tradingStateService.running(),
                tradingStateService.killSwitchEnabled());
        if (optionPremium == null || optionPremium.signum() <= 0) {
            log.warn("Entry execution rejected: optionPremium is zero or null for instrument={}",
                    decision.selectedInstrumentKey().orElse(""));
            return ExecutionResult.rejected(List.of("Option premium is zero or unavailable — cannot place order"));
        }
        // Rejection circuit breaker — soft halt is activated by trackBrokerRejection()
        // and persists until user resumes from UI. No need for a separate check here —
        // tradingStateService.running() + haltMode covers it. But we add an explicit
        // early return with a clear message for diagnostics.
        if (tradingStateService.haltMode() != com.algo.trade.risk.HaltMode.NONE) {
            log.warn("Entry execution rejected: halt mode active ({})", tradingStateService.haltMode());
            return ExecutionResult.rejected(List.of("Entry halted: " + tradingStateService.haltMode()
                    + " — resume from UI to continue trading"));
        }
        StrategyDecisionEntity savedDecision = persistDecision(decision);
        if (!tradingStateService.running()) {
            log.warn("Entry execution rejected: trading engine is stopped");
            List<String> reasons = List.of("Trading engine is stopped");
            updateExecutionStage(savedDecision, "TRADING_STOPPED", reasons.getFirst());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "TRADING_STOPPED", false,
                    null, null, null, null, reasons, effectiveConfig);
            telegramAlertService.entryRejected(decision, optionPremium, "TRADING_STOPPED", reasons);
            return ExecutionResult.rejected(reasons);
        }
        List<String> orderGuardRejections = orderGuardRejections(decision, optionPremium);
        if (!orderGuardRejections.isEmpty()) {
            log.warn("Entry execution rejected by order guard: reasons={}", orderGuardRejections);
            updateExecutionStage(savedDecision, "ORDER_GUARD_REJECTED", String.join("; ", orderGuardRejections));
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_GUARD_REJECTED", false,
                    null, null, null, null, orderGuardRejections, effectiveConfig);
            telegramAlertService.entryRejected(decision, optionPremium, "ORDER_GUARD_REJECTED", orderGuardRejections);
            return ExecutionResult.rejected(orderGuardRejections);
        }

        int openTradeCount = openTradeCount();
        int tradesToday = tradesToday();
        BigDecimal dailyPnl = dailyPnl();
        int consecutiveLosses = consecutiveLosses();
        log.info("Entry risk context: openTradeCount={}, tradesToday={}, dailyPnl={}, consecutiveLosses={}",
                openTradeCount, tradesToday, dailyPnl, consecutiveLosses);
        var risk = riskEngine.evaluateEntry(decision, openTradeCount, tradesToday, dailyPnl,
                consecutiveLosses, tradingStateService.killSwitchEnabled());
        if (!risk.allowed()) {
            log.warn("Entry execution rejected by risk engine: reasons={}", risk.reasons());
            updateExecutionStage(savedDecision, "RISK_REJECTED", String.join("; ", risk.reasons()));
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "RISK_REJECTED", false,
                    null, null, null, null, risk.reasons(), effectiveConfig);
            telegramAlertService.entryRejected(decision, optionPremium, "RISK_REJECTED", risk.reasons());
            return ExecutionResult.rejected(risk.reasons());
        }

        var sizing = riskEngine.calculateQuantity(optionPremium, lotSize, stopLossPercent);
        if (!sizing.allowed()) {
            log.warn("Entry execution rejected by position sizing: reason={}, riskAmount={}, estimatedCost={}",
                    sizing.reason(), sizing.riskAmount(), sizing.estimatedCost());
            List<String> reasons = List.of(sizing.reason());
            updateExecutionStage(savedDecision, "SIZING_REJECTED", sizing.reason());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "SIZING_REJECTED", false,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), null, reasons, effectiveConfig);
            telegramAlertService.entryRejected(decision, optionPremium, "SIZING_REJECTED", reasons);
            return ExecutionResult.rejected(reasons);
        }
        log.info("Entry sizing accepted: quantity={}, riskAmount={}, estimatedCost={}, reason={}",
                sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), sizing.reason());

        if (!entryAllowed(openTradeCount)) {
            log.warn("Entry execution rejected: open trades ({}) + in-flight entries ({}) >= max open trades ({})",
                    openTradeCount, entriesInFlight.get(), globalConfigService.getMaxOpenTrades());
            List<String> reasons = List.of("Max open trades reached (including " + entriesInFlight.get() + " pending limit orders)");
            updateExecutionStage(savedDecision, "CONCURRENT_ENTRY_BLOCKED", reasons.getFirst());
            return ExecutionResult.rejected(reasons);
        }
        entriesInFlight.incrementAndGet();
        boolean releaseEntryInFlight = true; // default: release in finally. Set to false for pending limit orders.
        try {
            String clientOrderId = "ENTRY-" + UUID.randomUUID();
            // SmartOrderRouter decides MARKET vs LIMIT based on liquidity
            SmartOrderRouter.RoutingDecision routing = smartOrderRouter.route(
                    decision.selectedInstrumentKey().orElseThrow(), OrderSide.BUY, optionPremium);
            OrderRequest orderRequest = new OrderRequest(clientOrderId, decision.selectedInstrumentKey().orElseThrow(),
                    OrderSide.BUY, routing.orderType(), ProductType.MIS, sizing.quantity(),
                    routing.limitPrice().or(() -> Optional.of(optionPremium)), "strategy-entry");
            log.info("Placing entry order: clientOrderId={}, instrument={}, side={}, orderType={}, product={}, quantity={}, routing={}",
                    orderRequest.clientOrderId(), orderRequest.instrumentKey(), orderRequest.side(),
                    orderRequest.orderType(), orderRequest.productType(), orderRequest.quantity(), routing.reason());
            OrderResponse order;
            try {
                order = placeOrderWithRetry(orderRequest, 2);
                persistOrderWithSignalTime(order, decision.timestamp(), optionPremium, extractStrategyType(decision));
                log.info("Entry order response: clientOrderId={}, brokerOrderId={}, status={}, requestedQuantity={}, filledQuantity={}, averageFillPrice={}, rejectionReason={}",
                        order.clientOrderId(), order.brokerOrderId().orElse(""), order.status(), order.requestedQuantity(),
                        order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(""));
            } catch (RuntimeException ex) {
                return rejectBrokerFailure(savedDecision, decision, optionPremium, lotSize, sizing.quantity(), sizing.riskAmount(),
                        sizing.estimatedCost(), orderRequest.clientOrderId(), ex, effectiveConfig);
            }

            // Limit order in book — watchdog will poll for fill and create TradeEntity
            // IMPORTANT: Do NOT release entryInFlight here — keep it held until the order
            // fills, cancels, or expires. This prevents duplicate entries from concurrent scans.
            // The OrderFillWatchdog or the auto-cancel timer will release it.
            if (order.status() == OrderStatus.OPEN || order.status() == OrderStatus.NEW) {
                consecutiveRejections.set(0); // order accepted by broker — reset circuit breaker
                log.info("Entry limit order placed — OrderFillWatchdog will track: clientOrderId={}, brokerOrderId={} (entryInFlight held)",
                        order.clientOrderId(), order.brokerOrderId().orElse(""));
                List<String> reasons = List.of("Limit order placed — awaiting fill");
                updateExecutionStage(savedDecision, "ORDER_OPEN", "brokerOrderId=" + order.brokerOrderId().orElse(""));
                executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_OPEN", false,
                        sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons, effectiveConfig);
                telegramAlertService.systemAlert("📋 Limit order placed — awaiting fill"
                        + System.lineSeparator() + "Signal: " + decision.signalType()
                        + System.lineSeparator() + "Instrument: " + order.instrumentKey()
                        + System.lineSeparator() + "Quantity: " + sizing.quantity()
                        + System.lineSeparator() + "Limit price: ₹" + optionPremium
                        + System.lineSeparator() + "Broker order: " + order.brokerOrderId().orElse(""));
                // Schedule a safety release of entriesInFlight after (cancelMinutes + 1) minutes
                int cancelMinutes = globalConfigService.getLimitOrderCancelMinutes();
                scheduleEntryInFlightRelease(cancelMinutes + 1);
                releaseEntryInFlight = false; // tell finally block NOT to decrement
                return ExecutionResult.accepted(order, reasons);
            }

            if (order.status() == OrderStatus.COMPLETE) {
                consecutiveRejections.set(0); // order filled — reset circuit breaker
                BigDecimal fillPrice = order.averageFillPrice().orElse(optionPremium);
                String tradeId = "TRD-" + UUID.randomUUID();
                TradeEntity tradeEntity = new TradeEntity(tradeId, order.instrumentKey(),
                        decision.underlying().name(), decision.optionType().orElseThrow().name(), TradeStatus.OPEN,
                        order.filledQuantity(), fillPrice, Instant.now(clock), String.join("; ", decision.reasons()));
                tradeEntity.setStrategyType(extractStrategyType(decision));
                tradeEntity.setAppliedTrailingStopActivationPercent(effectiveConfig.getTrailingStopActivationPercent());
                tradeEntity.setAppliedTrailingGapPercent(effectiveConfig.getTrailingGapPercent());
                if (envMetadata != null) {
                    tradeEntity.setEnvironmentScore(envMetadata.environmentScore());
                    tradeEntity.setEnvironmentBreakdown(envMetadata.environmentBreakdown());
                    tradeEntity.setEntrySessionWindow(envMetadata.sessionWindow());
                }
                tradeRepository.save(tradeEntity);
                tradingStateService.recordTradeEntry();
                log.info("Entry trade opened: tradeId={}, instrument={}, quantity={}, entryPrice={}",
                        tradeId, order.instrumentKey(), order.filledQuantity(), fillPrice);
                List<String> reasons = List.of("Entry order filled and trade journal updated");
                updateExecutionStage(savedDecision, "ORDER_FILLED", "tradeId=" + tradeId);
                executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_FILLED", true,
                        sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons, effectiveConfig);
                telegramAlertService.entryOrderFilled(decision, optionPremium, sizing.quantity(), sizing.estimatedCost(), order);
                return ExecutionResult.accepted(order, reasons);
            }
            log.warn("Entry order not filled: clientOrderId={}, status={}, reason={}",
                    order.clientOrderId(), order.status(), order.rejectionReason().orElse("Entry order was not filled"));
            trackBrokerRejection(order.rejectionReason().orElse("unknown"));
            List<String> reasons = List.of(order.rejectionReason().orElse("Entry order was not filled"));
            updateExecutionStage(savedDecision, "ORDER_NOT_FILLED", reasons.getFirst());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_NOT_FILLED", false,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons, effectiveConfig);
            telegramAlertService.orderNotFilled(decision, optionPremium, sizing.quantity(), order, reasons);
            return ExecutionResult.rejected(reasons);
        } finally {
            if (releaseEntryInFlight) {
                entriesInFlight.decrementAndGet();
            }
        }
    }

    /**
     * Execute a paper trade entry — creates a TradeEntity with simulated fill at current market price.
     * No broker order is placed. The trade is managed by existing exit monitors (SL/target/trailing/maxHold)
     * and closed via closeTrade which detects the "PAPER-" prefix and skips the broker exit order.
     */
    @Transactional
    public ExecutionResult executePaperEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize, StrategyConfig strategyConfig) {
        StrategyConfig effectiveConfig = strategyConfig != null ? strategyConfig : strategyConfigService.getDirectionalBuyConfig(decision.underlying().name());
        BigDecimal stopLossPercent = effectiveConfig.getStopLossPercent();
        log.info("PAPER entry requested: signalType={}, underlying={}, instrument={}, premium={}",
                decision.signalType(), decision.underlying(),
                decision.selectedInstrumentKey().orElse(""), optionPremium);

        StrategyDecisionEntity savedDecision = persistDecision(decision, true);
        if (!tradingStateService.running()) {
            log.warn("PAPER entry rejected: trading engine is stopped");
            updateExecutionStage(savedDecision, "TRADING_STOPPED", "Trading engine is stopped");
            return ExecutionResult.rejected(List.of("Trading engine is stopped"));
        }

        var sizing = riskEngine.calculateQuantity(optionPremium, lotSize, stopLossPercent);
        if (!sizing.allowed()) {
            updateExecutionStage(savedDecision, "PAPER_SIZING_REJECTED", sizing.reason());
            return ExecutionResult.rejected(List.of(sizing.reason()));
        }

        if (!entryAllowed(0)) {
            log.warn("PAPER entry rejected: max open trades reached (in-flight={})", entriesInFlight.get());
            updateExecutionStage(savedDecision, "CONCURRENT_ENTRY_BLOCKED", "Concurrent entry blocked");
            return ExecutionResult.rejected(List.of("Concurrent entry blocked — max open trades reached"));
        }
        entriesInFlight.incrementAndGet();
        try {
        String tradeId = "PAPER-TRD-" + UUID.randomUUID();
        String instrumentKey = decision.selectedInstrumentKey().orElse("UNKNOWN");
        TradeEntity trade = new TradeEntity(tradeId, instrumentKey,
                decision.underlying().name(), decision.optionType().map(Enum::name).orElse("CE"),
                TradeStatus.OPEN, sizing.quantity(), optionPremium, Instant.now(clock),
                "PAPER_TRADE [" + decision.signalType().name() + "]: " + String.join("; ", decision.reasons()));
        // Extract strategy type from decision reasons
        trade.setStrategyType(extractStrategyType(decision));
        trade.setAppliedTrailingStopActivationPercent(effectiveConfig.getTrailingStopActivationPercent());
        trade.setAppliedTrailingGapPercent(effectiveConfig.getTrailingGapPercent());
        tradeRepository.save(trade);

        updateExecutionStage(savedDecision, "PAPER_FILLED", "tradeId=" + tradeId);
        log.info("PAPER trade opened: tradeId={}, instrument={}, qty={}, entryPrice={}",
                tradeId, instrumentKey, sizing.quantity(), optionPremium);
        // Paper trades don't count toward hourly cap (no broker margin consumed)

        OrderResponse syntheticOrder = new OrderResponse(
                "PAPER-" + UUID.randomUUID(), Optional.empty(), instrumentKey,
                OrderSide.BUY, OrderStatus.COMPLETE, sizing.quantity(), sizing.quantity(),
                Optional.of(optionPremium), Optional.empty(), Instant.now(clock));
        executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "PAPER_FILLED", true,
                sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), syntheticOrder,
                List.of("Paper trade opened — exit managed by live monitors"), effectiveConfig);

        return ExecutionResult.accepted(syntheticOrder, List.of("Paper trade opened"));
        } finally {
            entriesInFlight.decrementAndGet();
        }
    }

    @Transactional(timeout = 30)
    public ExecutionResult closeTrade(String tradeId, BigDecimal lastPrice, String reason) {
        if (!closingInProgress.add(tradeId)) {
            log.warn("Close already in progress for tradeId={} reason={} — duplicate suppressed", tradeId, reason);
            return ExecutionResult.rejected(List.of("Close already in progress"));
        }
        try {
            ExecutionResult result = doCloseTrade(tradeId, lastPrice, reason);
            if (result.accepted()) {
                // Keep tradeId in closingInProgress permanently — prevents any subsequent
                // close attempts from other monitors (scheduled backup, FailSafe, etc.)
                // that may fire before they re-read the CLOSED status from DB.
                log.debug("Trade {} closed successfully — retaining close guard", tradeId);
            } else {
                closingInProgress.remove(tradeId);
            }
            return result;
        } catch (Exception e) {
            closingInProgress.remove(tradeId);
            throw e;
        }
    }

    private ExecutionResult doCloseTrade(String tradeId, BigDecimal lastPrice, String reason) {
        log.info("Close trade requested: tradeId={}, lastPrice={}, reason={}", tradeId, lastPrice, reason);
        TradeEntity trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tradeId: " + tradeId));
        if (trade.getStatus() != TradeStatus.OPEN) {
            log.warn("Close trade rejected: tradeId={}, status={}", tradeId, trade.getStatus());
            return ExecutionResult.rejected(List.of("Trade is not open"));
        }

        // Paper trades: close without broker order — just compute P&L and update DB
        if (tradeId.startsWith("PAPER-")) {
            boolean isShort = isShortEntry(trade);
            BigDecimal realizedPnl = isShort
                    ? trade.getEntryPrice().subtract(lastPrice).multiply(BigDecimal.valueOf(trade.getQuantity()))
                    : lastPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(trade.getQuantity()));
            trade.close(lastPrice, Instant.now(clock), realizedPnl, "PAPER_EXIT: " + reason);
            tradeRepository.save(trade);
            executionOutcomeCsvRecorder.recordExit(trade, lastPrice, realizedPnl, reason, null);
            log.info("PAPER trade closed: tradeId={}, exitPrice={}, realizedPnl={}, reason={}",
                    tradeId, lastPrice, realizedPnl, reason);
            // Paper trade outcomes don't affect rolling win rate for risk gates
            telegramAlertService.systemAlert(String.format(
                    "📝 Paper Trade Closed: %s | Entry ₹%.2f → Exit ₹%.2f | P&L ₹%.2f | %s",
                    trade.getInstrumentKey(), trade.getEntryPrice().doubleValue(),
                    lastPrice.doubleValue(), realizedPnl.doubleValue(), reason));
            return new ExecutionResult(true, Optional.empty(), List.of("Paper trade closed: P&L=" + realizedPnl));
        }

        boolean validLastPrice = lastPrice != null && lastPrice.signum() > 0;
        if (!validLastPrice) {
            log.warn("doCloseTrade: lastPrice is zero/null for tradeId={} instrument={} — using MARKET order",
                    tradeId, trade.getInstrumentKey());
        }
        OrderRequest orderRequest = validLastPrice
                ? new OrderRequest("EXIT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        OrderSide.SELL, OrderType.LIMIT, ProductType.MIS, trade.getQuantity(), Optional.of(roundToTick(lastPrice, OrderSide.SELL)),
                        "exit")
                : new OrderRequest("EXIT-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        OrderSide.SELL, OrderType.MARKET, ProductType.MIS, trade.getQuantity(), Optional.empty(),
                        "exit-mkt");
        log.info("Placing exit order: tradeId={}, clientOrderId={}, instrument={}, quantity={}",
                tradeId, orderRequest.clientOrderId(), orderRequest.instrumentKey(), orderRequest.quantity());
        OrderResponse order;
        try {
            order = brokerClient.placeOrder(orderRequest);
        } catch (RuntimeException ex) {
            // Retry once with MARKET order if LIMIT fails
            log.warn("Exit LIMIT order failed for tradeId={}, retrying with MARKET order: {}", tradeId, ex.getMessage());
            if (errorEventService != null) errorEventService.high("ExecutionEngine", "Exit LIMIT failed for " + tradeId + " — retrying MARKET: " + ex.getMessage(), ex);
            telegramAlertService.systemAlert("⚠️ Exit LIMIT failed for " + trade.getInstrumentKey() + " — retrying MARKET order");
            try {
                OrderRequest marketRequest = new OrderRequest("EXIT-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        OrderSide.SELL, OrderType.MARKET, ProductType.MIS, trade.getQuantity(), Optional.empty(),
                        "exit-mkt-retry");
                order = brokerClient.placeOrder(marketRequest);
            } catch (RuntimeException retryEx) {
                log.error("Exit MARKET retry also failed for tradeId={}: {}", tradeId, retryEx.getMessage());
                if (errorEventService != null) errorEventService.critical("ExecutionEngine", "Exit MARKET retry failed for " + tradeId + " (" + trade.getInstrumentKey() + "): " + retryEx.getMessage(), retryEx);
                telegramAlertService.systemAlert("🚨 URGENT: Exit failed for " + trade.getInstrumentKey()
                        + " — POSITION STILL OPEN! Manual intervention required.");
                return ExecutionResult.rejected(List.of("Exit order failed after retry: " + retryEx.getMessage()));
            }
        }
        persistOrder(order);
        log.info("Exit order response: clientOrderId={}, brokerOrderId={}, status={}, filledQuantity={}, averageFillPrice={}, rejectionReason={}",
                order.clientOrderId(), order.brokerOrderId().orElse(""), order.status(), order.filledQuantity(),
                order.averageFillPrice().orElse(null), order.rejectionReason().orElse(""));
        if (order.status() != OrderStatus.COMPLETE) {
            // Order is pending — watchdog will track it
            if (order.status() == OrderStatus.OPEN || order.status() == OrderStatus.NEW) {
                log.info("Exit order pending — watchdog will track: tradeId={}, clientOrderId={}", tradeId, order.clientOrderId());
                return ExecutionResult.accepted(order, List.of("Exit order pending — watchdog tracking"));
            }
            log.warn("Exit order not filled: tradeId={}, status={}, reason={}",
                    tradeId, order.status(), order.rejectionReason().orElse("Exit order was not filled"));
            telegramAlertService.systemAlert("⚠️ Exit order rejected for " + trade.getInstrumentKey()
                    + " — " + order.rejectionReason().orElse("unknown reason"));
            return ExecutionResult.rejected(List.of(order.rejectionReason().orElse("Exit order was not filled")));
        }

        BigDecimal exitPrice = order.averageFillPrice().orElse(lastPrice);
        boolean isShort = isShortEntry(trade);
        BigDecimal realizedPnl = isShort
                ? trade.getEntryPrice().subtract(exitPrice).multiply(BigDecimal.valueOf(trade.getQuantity()))
                : exitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(trade.getQuantity()));
        trade.close(exitPrice, Instant.now(clock), realizedPnl, reason);
        tradeRepository.save(trade);
        executionOutcomeCsvRecorder.recordExit(trade, exitPrice, realizedPnl, reason, order);
        log.info("Trade closed: tradeId={}, exitPrice={}, realizedPnl={}, reason={}", tradeId, exitPrice, realizedPnl, reason);
        tradingStateService.recordTradeOutcome(realizedPnl.signum() > 0);
        telegramAlertService.tradeClosed(tradeId, trade.getInstrumentKey(), trade.getQuantity(),
                trade.getEntryPrice(), exitPrice, realizedPnl, reason, order);
        return ExecutionResult.accepted(order, List.of("Exit order filled and trade journal updated"));
    }

    @Transactional(timeout = 30)
    public ExecutionResult closePartialTrade(String tradeId, int partialQuantity, BigDecimal lastPrice, String layerReason) {
        log.info("Partial close requested: tradeId={}, partialQuantity={}, lastPrice={}, layer={}", tradeId, partialQuantity, lastPrice, layerReason);
        TradeEntity trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tradeId: " + tradeId));
        if (trade.getStatus() != TradeStatus.OPEN) {
            log.warn("Partial close rejected: tradeId={}, status={}", tradeId, trade.getStatus());
            return ExecutionResult.rejected(List.of("Trade is not open"));
        }
        if (partialQuantity <= 0 || partialQuantity >= trade.getQuantity()) {
            log.warn("Partial close quantity invalid ({}), falling back to full close: tradeId={}", partialQuantity, tradeId);
            return closeTrade(tradeId, lastPrice, layerReason);
        }

        if (trade.isPaperTrade()) {
            boolean isShort = isShortEntry(trade);
            BigDecimal partialPnl = isShort
                    ? trade.getEntryPrice().subtract(lastPrice).multiply(BigDecimal.valueOf(partialQuantity))
                    : lastPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(partialQuantity));
            trade.partialClose(partialQuantity, partialPnl, layerReason);
            tradeRepository.save(trade);
            log.info("PAPER partial close: tradeId={}, layer={}, qty={}, price={}, partialPnl={}, remainingQty={}",
                    tradeId, layerReason, partialQuantity, lastPrice, partialPnl, trade.getQuantity());
            telegramAlertService.systemAlert(String.format(
                    "📊 Partial Profit Booked (%s): %s | %d lots @ ₹%.2f | P&L ₹%.2f | Remaining: %d lots",
                    layerReason, trade.getInstrumentKey(), partialQuantity, lastPrice.doubleValue(),
                    partialPnl.doubleValue(), trade.getQuantity()));
            return new ExecutionResult(true, Optional.empty(), List.of("Paper partial close: layer=" + layerReason + " pnl=" + partialPnl));
        }

        OrderRequest orderRequest = new OrderRequest("PARTIAL-" + UUID.randomUUID(), trade.getInstrumentKey(),
                OrderSide.SELL, OrderType.LIMIT, ProductType.MIS, partialQuantity, Optional.of(roundToTick(lastPrice, OrderSide.SELL)),
                "partial-exit");
        OrderResponse order;
        try {
            order = brokerClient.placeOrder(orderRequest);
        } catch (RuntimeException ex) {
            log.warn("Partial exit LIMIT failed for tradeId={}, retrying MARKET: {}", tradeId, ex.getMessage());
            if (errorEventService != null) errorEventService.high("ExecutionEngine", "Partial exit LIMIT failed for " + tradeId + " — retrying MARKET: " + ex.getMessage(), ex);
            try {
                OrderRequest marketReq = new OrderRequest("PARTIAL-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        OrderSide.SELL, OrderType.MARKET, ProductType.MIS, partialQuantity, Optional.empty(),
                        "partial-exit-mkt");
                order = brokerClient.placeOrder(marketReq);
            } catch (RuntimeException retryEx) {
                log.error("Partial exit MARKET retry failed for tradeId={}: {}", tradeId, retryEx.getMessage());
                if (errorEventService != null) errorEventService.critical("ExecutionEngine", "Partial exit retry failed for " + tradeId + ": " + retryEx.getMessage(), retryEx);
                return ExecutionResult.rejected(List.of("Partial exit failed after retry: " + retryEx.getMessage()));
            }
        }
        persistOrder(order);
        if (order.status() != OrderStatus.COMPLETE) {
            log.warn("Partial exit order not filled: tradeId={}, status={}", tradeId, order.status());
            return ExecutionResult.rejected(List.of("Partial exit order not filled: " + order.status()));
        }
        BigDecimal exitPrice = order.averageFillPrice().orElse(lastPrice);
        boolean isShort = isShortEntry(trade);
        BigDecimal partialPnl = isShort
                ? trade.getEntryPrice().subtract(exitPrice).multiply(BigDecimal.valueOf(partialQuantity))
                : exitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(partialQuantity));
        trade.partialClose(partialQuantity, partialPnl, layerReason);
        tradeRepository.save(trade);
        log.info("Partial close filled: tradeId={}, layer={}, qty={}, exitPrice={}, partialPnl={}, remainingQty={}",
                tradeId, layerReason, partialQuantity, exitPrice, partialPnl, trade.getQuantity());
        telegramAlertService.systemAlert(String.format(
                "📊 Partial Profit Booked (%s): %s | %d lots @ ₹%.2f | P&L ₹%.2f | Remaining: %d lots",
                layerReason, trade.getInstrumentKey(), partialQuantity, exitPrice.doubleValue(),
                partialPnl.doubleValue(), trade.getQuantity()));
        return ExecutionResult.accepted(order, List.of("Partial close: layer=" + layerReason + " qty=" + partialQuantity));
    }

    private StrategyDecisionEntity persistDecision(StrategyDecision decision) {
        return persistDecision(decision, false);
    }

    private StrategyDecisionEntity persistDecision(StrategyDecision decision, boolean paperTrade) {
        log.info("Persisting strategy decision: timestamp={}, underlying={}, signalType={}, instrument={}, reasons={}",
                decision.timestamp(), decision.underlying(), decision.signalType(),
                decision.selectedInstrumentKey().orElse(""), decision.reasons());
        StrategyDecisionEntity entity = new StrategyDecisionEntity(decision.timestamp(), decision.underlying().name(),
                decision.signalType().name(), decision.underlyingPrice(), decision.optionPrice().orElse(null),
                decision.optionOpenInterest().orElse(null), decision.lotSize().orElse(null),
                decision.lotPrice().orElse(null),
                decision.selectedInstrumentKey().orElse(null), decision.selectedStrike().orElse(null),
                decision.optionType().map(Enum::name).orElse(null), decision.vwapConditionPassed(),
                decision.imbalance().orElse(null), decision.volumeSpike(), decision.confidenceScore(),
                String.join("; ", decision.reasons()));
        entity.setStrategyType(extractStrategyType(decision));
        entity.setPaperTrade(paperTrade);
        return decisionRepository.save(entity);
    }

    private void updateExecutionStage(StrategyDecisionEntity entity, String stage, String reason) {
        if (entity != null) {
            entity.setExecutionStage(stage);
            entity.setExecutionReason(reason);
            decisionRepository.save(entity);
        }
    }

    private void persistOrder(OrderResponse order) {
        OrderEntity entity = new OrderEntity(order.clientOrderId(), order.brokerOrderId().orElse(null),
                order.instrumentKey(), order.side().name(), order.status(), order.requestedQuantity(),
                order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(null),
                order.updatedAt());
        entity.setOrderPlacedAt(Instant.now(clock));
        orderRepository.save(entity);
    }

    private void persistOrderWithSignalTime(OrderResponse order, Instant signalTimestamp, BigDecimal limitPrice, String strategyType) {
        OrderEntity entity = new OrderEntity(order.clientOrderId(), order.brokerOrderId().orElse(null),
                order.instrumentKey(), order.side().name(), order.status(), order.requestedQuantity(),
                order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(null),
                order.updatedAt());
        entity.setSignalTimestamp(signalTimestamp);
        entity.setOrderPlacedAt(Instant.now(clock));
        entity.setStrategyType(strategyType);
        if (order.averageFillPrice().isPresent() && limitPrice != null) {
            entity.setSlippage(order.averageFillPrice().get().subtract(limitPrice).abs());
        }
        orderRepository.save(entity);
    }

    /**
     * Called by OrderFillWatchdog when a pending limit order fills.
     * Creates the TradeEntity so exit monitors (max hold, trailing stop, forced exit) can manage it.
     */
    @Transactional
    public void openTradeFromFilledOrder(OrderEntity orderEntity) {
        String tradeId = "TRD-" + UUID.randomUUID();
        String instrumentKey = orderEntity.getInstrumentKey();
        if (orderEntity.getAverageFillPrice() == null || orderEntity.getAverageFillPrice().signum() <= 0) {
            log.error("openTradeFromFilledOrder skipped: fill price is null/zero for clientOrderId={} instrument={}",
                    orderEntity.getClientOrderId(), instrumentKey);
            if (errorEventService != null) errorEventService.critical("ExecutionEngine", "Fill price missing for " + instrumentKey + " order=" + orderEntity.getClientOrderId() + " — trade NOT opened");
            telegramAlertService.systemAlert("🚨 Watchdog: fill price missing for " + instrumentKey
                    + " order=" + orderEntity.getClientOrderId() + " — trade NOT opened, manual review needed");
            return;
        }
        BigDecimal fillPrice = orderEntity.getAverageFillPrice();
        int filledQty = orderEntity.getFilledQuantity();

        // Derive underlying and option type from instrument key using UnderlyingSymbol enum
        String underlying = extractUnderlyingFromKey(instrumentKey);
        String optionType = instrumentKey.toUpperCase().contains("PE") ? "PE" : "CE";

        String entryReason = "Limit order filled (watchdog) [" + (orderEntity.getStrategyType() != null ? orderEntity.getStrategyType() : "UNKNOWN") + "]: " + orderEntity.getClientOrderId();
        // Use current time as entry time for orphaned orders discovered after restart.
        // The original fill time (orderEntity.getUpdatedAt()) may be minutes/hours old,
        // which would immediately trigger maxHoldTime exit. Using Instant.now() gives
        // the trade a fresh hold timer from the moment the system becomes aware of it.
        Instant entryTime = Instant.now(clock);
        TradeEntity trade = new TradeEntity(tradeId, instrumentKey, underlying, optionType,
                TradeStatus.OPEN, filledQty, fillPrice, entryTime, entryReason);
        // Use strategy type stored on the order entity at placement time
        if (orderEntity.getStrategyType() != null && !orderEntity.getStrategyType().isBlank()) {
            trade.setStrategyType(orderEntity.getStrategyType());
            // Set trailing stop params from the strategy config so exit monitors use consistent values
            try {
                StrategyConfig entryConfig = strategyConfigService.getConfig(
                        com.algo.trade.strategy.StrategyType.valueOf(orderEntity.getStrategyType()), underlying);
                trade.setAppliedTrailingStopActivationPercent(entryConfig.getTrailingStopActivationPercent());
                trade.setAppliedTrailingGapPercent(entryConfig.getTrailingGapPercent());
            } catch (IllegalArgumentException ignored) {
                log.debug("Unknown strategy type on order {}: {} — trailing params not set",
                        orderEntity.getClientOrderId(), orderEntity.getStrategyType());
            }
        }
        tradeRepository.save(trade);

        // Mark order as materialized to prevent duplicate trade creation
        orderEntity.setTradeMaterialized(true);

        // Update order entity with final status
        orderEntity.setStatus(OrderStatus.COMPLETE);
        orderEntity.setFilledQuantity(filledQty);
        orderEntity.setAverageFillPrice(fillPrice);
        orderRepository.save(orderEntity);

        log.info("Watchdog opened trade from filled order: tradeId={}, clientOrderId={}, instrument={}, qty={}, price={}",
                tradeId, orderEntity.getClientOrderId(), instrumentKey, filledQty, fillPrice);
        tradingStateService.recordTradeEntry();
        // Release the entry gate — the limit order has been converted to a trade
        releaseEntryInFlightGate();
        telegramAlertService.systemAlert("Limit order filled (watchdog)"
                + System.lineSeparator() + "Trade: " + tradeId
                + System.lineSeparator() + "Instrument: " + instrumentKey
                + System.lineSeparator() + "Qty: " + filledQty
                + System.lineSeparator() + "Price: " + fillPrice);
    }

    /**
     * Place order with retry on transient failures.
     * Before retrying, checks if the order already exists in the broker (idempotency).
     * Max retries with exponential backoff (500ms, 1000ms).
     */
    private OrderResponse placeOrderWithRetry(OrderRequest request, int maxRetries) {
        RuntimeException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return brokerClient.placeOrder(request);
            } catch (RuntimeException ex) {
                lastException = ex;
                String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                // Only retry on transient failures (timeout, connection reset)
                boolean transient_ = msg.contains("timeout") || msg.contains("timed out")
                        || msg.contains("connection reset") || msg.contains("connection refused")
                        || msg.contains("socket") || msg.contains("eof");
                if (!transient_ || attempt >= maxRetries) {
                    throw ex; // non-transient or exhausted retries — propagate
                }
                // Idempotency check: verify the order wasn't actually placed despite the error
                try {
                    List<com.algo.trade.domain.Position> positions = brokerClient.positions();
                    boolean alreadyHasPosition = positions.stream()
                            .anyMatch(p -> p.instrumentKey().equals(request.instrumentKey()) && p.quantity() > 0);
                    if (alreadyHasPosition) {
                        log.warn("Broker retry aborted: position already exists for {} — order likely went through despite error",
                                request.instrumentKey());
                        throw ex; // don't retry — the order was placed
                    }
                } catch (Exception posEx) {
                    log.debug("Idempotency check failed: {}", posEx.getMessage());
                    // Can't verify — don't retry to be safe
                    throw ex;
                }
                long backoffMs = 500L * (attempt + 1);
                log.warn("Broker order failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt + 1, maxRetries + 1, backoffMs, ex.getMessage());
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ex; }
            }
        }
        throw lastException;
    }

    /**
     * Release one entry-in-flight slot. Called by OrderFillWatchdog after a pending limit order
     * fills, cancels, or expires. Also called by the safety timer.
     */
    public void releaseEntryInFlightGate() {
        int prev = entriesInFlight.getAndUpdate(v -> Math.max(0, v - 1));
        if (prev > 0) {
            log.info("entriesInFlight decremented: {} → {}", prev, prev - 1);
        }
    }

    /**
     * Schedule a safety release of one entriesInFlight slot after the given minutes.
     * Prevents permanent counter leak if OrderFillWatchdog fails to process the order.
     */
    private void scheduleEntryInFlightRelease(int minutes) {
        Thread.ofVirtual().name("entry-gate-safety-release").start(() -> {
            try {
                Thread.sleep(java.time.Duration.ofMinutes(minutes));
                int prev = entriesInFlight.getAndUpdate(v -> Math.max(0, v - 1));
                if (prev > 0) {
                    log.warn("entriesInFlight safety release after {}min: {} → {} — watchdog may have missed the order",
                            minutes, prev, prev - 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Check if a new entry is allowed given current open trades and in-flight entries.
     * openTradeCount already includes pending orders from DB, so we only add
     * entriesInFlight for orders placed in THIS JVM session that haven't been
     * persisted to the order table yet (the brief window between broker response
     * and DB write).
     */
    private boolean entryAllowed(int openTradeCount) {
        int maxOpen = globalConfigService.getMaxOpenTrades();
        int maxPending = globalConfigService.getMaxPendingOrders();
        // Count open trades + ALL pending orders (OPEN/NEW in DB) + in-flight orders not yet in DB.
        // This prevents multiple entries within a single scan cycle from exceeding maxOpenTrades.
        int pendingFromDb = orderRepository.findByStatusIn(
                List.of(com.algo.trade.domain.OrderStatus.OPEN, com.algo.trade.domain.OrderStatus.NEW)).size();
        int effectiveInFlight = Math.max(0, entriesInFlight.get() - pendingFromDb);

        // Total positions = open trades + pending orders + in-flight (not yet in DB)
        int totalPositions = openTradeCount + pendingFromDb + effectiveInFlight;
        if (totalPositions >= maxOpen) return false;
        if (pendingFromDb >= maxPending) return false;
        return true;
    }

    private ExecutionResult rejectBrokerFailure(
            StrategyDecisionEntity savedDecision,
            StrategyDecision decision,
            BigDecimal optionPremium,
            int lotSize,
            Integer quantity,
            BigDecimal riskAmount,
            BigDecimal estimatedCost,
            String clientOrderId,
            RuntimeException ex,
            StrategyConfig strategyConfig
    ) {
        String message = exceptionMessage(ex);
        log.warn("Entry order placement failed: clientOrderId={}, instrument={}, message={}",
                clientOrderId, decision.selectedInstrumentKey().orElse(""), message, ex);
        trackBrokerRejection(message);
        errorEventRepository.save(new ErrorEventEntity(Instant.now(clock), "ExecutionEngine",
                message != null && message.length() > 4000 ? message.substring(0, 4000) : message));
        List<String> reasons = List.of(message);
        updateExecutionStage(savedDecision, "BROKER_ERROR", message);
        executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "BROKER_ERROR", false,
                quantity, riskAmount, estimatedCost,
                new OrderResponse(clientOrderId, Optional.empty(), decision.selectedInstrumentKey().orElse(""),
                        OrderSide.BUY, OrderStatus.REJECTED, quantity == null ? 0 : quantity, 0,
                        Optional.empty(), Optional.of(message), Instant.now(clock)),
                reasons, strategyConfig);
        telegramAlertService.entryRejected(decision, optionPremium, "BROKER_ERROR", reasons);
        return ExecutionResult.rejected(reasons);
    }

    private String exceptionMessage(RuntimeException ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message;
    }

    private int openTradeCount() {
        int openTrades = (int) tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> positionSyncProperties.manageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .count();
        // Also count pending limit orders as "open" — they'll become trades when filled
        int pendingOrders = orderRepository.findByStatusIn(
                List.of(com.algo.trade.domain.OrderStatus.OPEN, com.algo.trade.domain.OrderStatus.NEW)).size();
        return openTrades + pendingOrders;
    }

    /** Effective open position count including pending orders — for external callers. */
    public int effectiveOpenTradeCount() {
        return openTradeCount();
    }

    /** Count open (non-paper) trades for a given strategy type — used by per-strategy position limit gate. */
    public long countOpenTradesForStrategy(String strategyType) {
        return tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> strategyType.equals(t.getStrategyType()))
                .count();
    }

    /** Find open trades by instrument key — used by OrderFillWatchdog for exit order fills. */
    public List<TradeEntity> findOpenTradesByInstrument(String instrumentKey) {
        return tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> instrumentKey.equals(t.getInstrumentKey()))
                .toList();
    }

    /** Save a trade entity — used by OrderFillWatchdog when closing trades from exit fills. */
    public void saveTradeEntity(TradeEntity trade) {
        tradeRepository.save(trade);
    }

    /** Populate entry Greeks on a trade from live option data. Called by scheduler after trade creation. */
    public void populateEntryGreeks(String tradeId, Double delta, Double gamma, Double theta, Double iv) {
        tradeRepository.findById(tradeId).ifPresent(trade -> {
            trade.setEntryDelta(delta);
            trade.setEntryGamma(gamma);
            trade.setEntryTheta(theta);
            trade.setEntryIV(iv);
            tradeRepository.save(trade);
            log.debug("Entry Greeks populated: tradeId={} delta={} gamma={} theta={} iv={}",
                    tradeId, delta, gamma, theta, iv);
        });
    }

    /**
     * Determine if a trade is a short entry (SELL side) based on strategy type.
     * Uses strategyType field first, falls back to entryReason parsing.
     */
    private boolean isShortEntry(TradeEntity trade) {
        // Check strategy type — selling strategies have short entries
        if (trade.getStrategyType() != null && !trade.getStrategyType().isBlank()) {
            try {
                return com.algo.trade.strategy.StrategyType.valueOf(trade.getStrategyType()).isSellingStrategy();
            } catch (IllegalArgumentException ignored) {}
        }
        // Fallback: check entry reason for SELL signal markers
        String reason = trade.getEntryReason();
        return reason != null && (reason.contains("[SELL_CE]") || reason.contains("[SELL_PE]")
                || reason.contains("SELL_CE") || reason.contains("SELL_PE"));
    }

    /** Extract strategy type name from a StrategyDecision's reasons list. */
    private String extractStrategyType(StrategyDecision decision) {
        for (String reason : decision.reasons()) {
            // Skip common phrases that contain strategy names as substrings
            // "RSI momentum gate" contains "MOMENTUM" but isn't a MOMENTUM strategy signal
            if (reason.toLowerCase().contains("rsi momentum")) continue;
            if (reason.toLowerCase().contains("breakout condition")) continue;
            if (reason.toLowerCase().contains("breakout confirmation")) continue;

            String upper = reason.toUpperCase().replace(" ", "_").replace("-", "_").replace("&", "AND");
            // Check longest names first to avoid partial matches
            for (com.algo.trade.strategy.StrategyType type : com.algo.trade.strategy.StrategyType.values()) {
                if (upper.contains(type.name())) return type.name();
            }
            if (upper.contains("ITM") && upper.contains("CONVICTION")) return "ITM_CONVICTION";
            if (upper.contains("GAP") && upper.contains("GO")) return "GAP_AND_GO";
        }
        // Fallback: derive from signal type
        return decision.signalType().name().startsWith("BUY_") ? "DIRECTIONAL_BUY" : "UNKNOWN";
    }

    /** Extract underlying symbol from instrument key using enum matching. */
    private static String extractUnderlyingFromKey(String instrumentKey) {
        if (instrumentKey == null) return "NIFTY";
        String upper = instrumentKey.toUpperCase();
        // Check longer names first to avoid BANKNIFTY matching NIFTY
        if (upper.contains("MIDCPNIFTY")) return "MIDCPNIFTY";
        if (upper.contains("FINNIFTY")) return "FINNIFTY";
        if (upper.contains("BANKNIFTY")) return "BANKNIFTY";
        if (upper.contains("SENSEX")) return "SENSEX";
        if (upper.contains("NIFTY")) return "NIFTY";
        return "NIFTY";
    }

    private int tradesToday() {
        return (int) tradeRepository.findByEntryTimeBetween(todayStart(), tomorrowStart()).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> positionSyncProperties.manageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .count();
    }

    private BigDecimal dailyPnl() {
        return tradeRepository.findByEntryTimeBetween(todayStart(), tomorrowStart()).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> positionSyncProperties.manageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .map(t -> {
                    BigDecimal booked = t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO;
                    if (t.getStatus() == TradeStatus.OPEN) {
                        BigDecimal currentPrice = marketDataService.quote(t.getInstrumentKey())
                                .map(com.algo.trade.domain.Quote::lastPrice)
                                .filter(p -> p != null && p.signum() > 0)
                                .orElse(null);
                        if (currentPrice != null) {
                            BigDecimal unrealized = currentPrice.subtract(t.getEntryPrice())
                                    .multiply(BigDecimal.valueOf(t.getQuantity()));
                            return booked.add(unrealized);
                        }
                    }
                    return booked;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int consecutiveLosses() {
        // Use bounded query instead of findAll() to avoid full table scan
        List<TradeEntity> recentClosed = tradeRepository.findByEntryTimeBetween(
                        todayStart().minus(Duration.ofDays(30)), tomorrowStart()).stream()
                .filter(trade -> trade.getStatus() == TradeStatus.CLOSED)
                .filter(trade -> !trade.isPaperTrade())
                .filter(trade -> positionSyncProperties.manageSyncedTrades() || !trade.getTradeId().startsWith("SYNC-"))
                .sorted((a, b) -> b.getEntryTime().compareTo(a.getEntryTime()))
                .limit(20) // only need to check recent trades
                .toList();
        int losses = 0;
        int consecutiveWins = 0;
        for (TradeEntity trade : recentClosed) {
            if (trade.getRealizedPnl().signum() < 0) {
                if (consecutiveWins < 2) {
                    // Need 2 consecutive winners to truly clear the loss streak
                    losses++;
                    consecutiveWins = 0;
                } else {
                    break; // 2+ consecutive winners — streak is genuinely broken
                }
            } else {
                if (losses == 0) break; // no losses to count
                consecutiveWins++;
            }
        }
        return losses;
    }

    private List<String> orderGuardRejections(StrategyDecision decision, BigDecimal optionPremium) {
        List<String> rejections = new ArrayList<>();
        String instrumentKey = decision.selectedInstrumentKey().orElse("");
        if (instrumentKey.isBlank()) {
            return rejections;
        }

        // 1. Duplicate open order check — prevent placing another order for the same instrument
        boolean existingOpenBuyOrder = !orderRepository.findByInstrumentKeyAndSideAndStatusIn(
                instrumentKey,
                OrderSide.BUY.name(),
                List.of(OrderStatus.NEW, OrderStatus.OPEN)
        ).isEmpty();
        if (existingOpenBuyOrder) {
            rejections.add("Open buy order already exists for instrument: " + instrumentKey);
        }

        // 3. Existing open trade check — prevent doubling up on the same instrument
        boolean existingOpenTrade = tradeRepository.findByInstrumentKeyAndStatus(instrumentKey, TradeStatus.OPEN).stream()
                .anyMatch(t -> !t.isPaperTrade());
        if (existingOpenTrade) {
            rejections.add("Open trade already exists for instrument: " + instrumentKey);
        }

        // 4. Per-underlying open trade limit — prevent concentration on a single underlying
        //    Max 2 open live trades per underlying (or maxOpenTrades if smaller)
        String underlying = decision.underlying().name();
        long openTradesForUnderlying = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> underlying.equals(t.getUnderlying()))
                .count();
        // Also count pending BUY orders for the same underlying
        long pendingOrdersForUnderlying = orderRepository.findByStatusIn(
                List.of(OrderStatus.OPEN, OrderStatus.NEW)).stream()
                .filter(o -> OrderSide.BUY.name().equals(o.getSide()))
                .filter(o -> o.getInstrumentKey() != null && o.getInstrumentKey().toUpperCase().contains(underlying))
                .count();
        long totalOpenForUnderlying = openTradesForUnderlying + pendingOrdersForUnderlying;
        int maxPerUnderlying = Math.min(2, globalConfigService.getMaxOpenTrades());
        if (totalOpenForUnderlying >= maxPerUnderlying) {
            rejections.add("Max open trades per underlying reached for " + underlying
                    + " (" + totalOpenForUnderlying + "/" + maxPerUnderlying
                    + ", trades=" + openTradesForUnderlying + " pending=" + pendingOrdersForUnderlying + ")");
        }

        // 5. Per-underlying max entry premium cap — applies to ALL option buying strategies
        if (underlyingConfigService != null && optionPremium != null && optionPremium.signum() > 0) {
            java.math.BigDecimal maxPremium = underlyingConfigService.getMaxEntryPremium(decision.underlying());
            if (maxPremium.signum() > 0 && optionPremium.compareTo(maxPremium) > 0) {
                rejections.add("Premium ₹" + optionPremium.setScale(0, java.math.RoundingMode.HALF_UP)
                        + " exceeds max ₹" + maxPremium.setScale(0, java.math.RoundingMode.HALF_UP)
                        + " for " + decision.underlying()
                        + " — option too expensive for directional buy");
            }
        }

        // 5b. Direction flip cooldown — block CE↔PE flip on same underlying within cooldown window
        int directionFlipCooldown = 60; // minutes — TODO: make configurable via GlobalConfig
        if (decision.optionType().isPresent()) {
            String oppositeType = decision.optionType().get() == com.algo.trade.domain.OptionType.CE ? "PE" : "CE";
            Instant flipWindow = clock.instant().minus(Duration.ofMinutes(directionFlipCooldown));
            boolean recentOppositeEntry = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> !t.isPaperTrade())
                    .filter(t -> decision.underlying().name().equals(t.getUnderlying()))
                    .filter(t -> oppositeType.equals(t.getOptionType()))
                    .filter(t -> t.getEntryTime() != null && t.getEntryTime().isAfter(flipWindow))
                    .findAny().isPresent();
            // Also check recently closed trades (within the flip window)
            if (!recentOppositeEntry) {
                recentOppositeEntry = tradeRepository.findByEntryTimeBetween(flipWindow, clock.instant()).stream()
                        .filter(t -> !t.isPaperTrade())
                        .filter(t -> decision.underlying().name().equals(t.getUnderlying()))
                        .filter(t -> oppositeType.equals(t.getOptionType()))
                        .findAny().isPresent();
            }
            if (recentOppositeEntry) {
                rejections.add("Direction flip cooldown: " + decision.optionType().get()
                        + " blocked — opposite " + oppositeType + " trade within last " + directionFlipCooldown + " min for " + decision.underlying());
            }
        }

        // 6. Cooldown check — include both trades AND orders placed within cooldown window
        if (globalConfigService.getCooldownMinutes() > 0) {
            Instant cooldownStart = clock.instant().minus(Duration.ofMinutes(globalConfigService.getCooldownMinutes()));
            // Check trades
            boolean tradeCooldown = !tradeRepository.findByInstrumentKeyAndEntryTimeBetween(
                    instrumentKey, cooldownStart, clock.instant()).isEmpty();
            // Check orders (any BUY order placed within cooldown, regardless of status)
            boolean orderCooldown = orderRepository.findByInstrumentKeyAndSideAndStatusIn(
                    instrumentKey, OrderSide.BUY.name(),
                    List.of(OrderStatus.COMPLETE, OrderStatus.OPEN, OrderStatus.NEW, OrderStatus.CANCELLED)).stream()
                    .anyMatch(o -> o.getOrderPlacedAt() != null && o.getOrderPlacedAt().isAfter(cooldownStart));
            if (tradeCooldown || orderCooldown) {
                rejections.add("Cooldown period not elapsed for instrument: " + instrumentKey
                        + " (cooldown=" + globalConfigService.getCooldownMinutes() + "min)");
            }
        }

        return rejections;
    }



    /**
     * Round price to the nearest valid tick size (₹0.05 for NSE/BSE F&O).
     * BUY: round UP to nearest tick (willing to pay more for fill)
     * SELL: round DOWN to nearest tick (willing to accept less for fill)
     */
    static BigDecimal roundToTick(BigDecimal price, OrderSide side) {
        if (price == null || price.signum() <= 0) return price;
        java.math.RoundingMode mode = side == OrderSide.BUY
                ? java.math.RoundingMode.UP
                : java.math.RoundingMode.DOWN;
        return price.divide(TICK_SIZE, 0, mode).multiply(TICK_SIZE).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Track broker rejection and trigger soft halt if too many consecutive rejections.
     * After MAX_CONSECUTIVE_REJECTIONS (3), activates soft halt via TradingStateService.
     * Halt persists until user resumes from UI. Sends Telegram alert when halt activates.
     */
    private void trackBrokerRejection(String reason) {
        int count = consecutiveRejections.incrementAndGet();
        log.warn("Broker rejection #{}: {}", count, reason);
        if (count >= MAX_CONSECUTIVE_REJECTIONS && tradingStateService.haltMode() == com.algo.trade.risk.HaltMode.NONE) {
            String haltReason = count + " consecutive broker rejections. Last: " + reason;
            tradingStateService.softHalt(haltReason);
            log.error("REJECTION CIRCUIT BREAKER: {} — soft halt activated. Resume from UI to continue.", haltReason);
            telegramAlertService.systemAlert(String.format(
                    "🛑 Entry HALTED: %d consecutive broker rejections — resume from UI to continue\nLast rejection: %s",
                    count, reason));
            if (errorEventService != null) {
                errorEventService.critical("ExecutionEngine",
                        "Rejection circuit breaker: " + haltReason + " — soft halt activated");
            }
        }
    }

    /** Get consecutive rejection count. For diagnostics. */
    public int getConsecutiveRejections() {
        return consecutiveRejections.get();
    }

    /** Reset rejection counter — called when user resumes from halt via UI. */
    public void resetRejectionCounter() {
        int prev = consecutiveRejections.getAndSet(0);
        if (prev > 0) {
            log.info("Rejection counter reset: {} → 0 (user resumed from halt)", prev);
        }
    }

    private Instant todayStart() {
        ZoneId zoneId = properties.timezone();
        return LocalDate.ofInstant(clock.instant(), zoneId).atStartOfDay(zoneId).toInstant();
    }

    private Instant tomorrowStart() {
        ZoneId zoneId = properties.timezone();
        return LocalDate.ofInstant(clock.instant(), zoneId).plusDays(1).atStartOfDay(zoneId).toInstant();
    }
}
