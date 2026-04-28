package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
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

    /** Prevents two monitors from placing duplicate broker SELL orders for the same trade. */
    private final Set<String> closingInProgress = ConcurrentHashMap.newKeySet();

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
    private final Clock clock;

    @Autowired
    public ExecutionEngine(TradingProperties properties, GlobalConfigService globalConfigService, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                           TradeRepository tradeRepository, OrderRepository orderRepository,
                           ErrorEventRepository errorEventRepository,
                           StrategyDecisionRepository decisionRepository,
                           ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder,
                           TelegramAlertService telegramAlertService,
                           com.algo.trade.config.PositionSyncProperties positionSyncProperties) {
        this(properties, globalConfigService, brokerClient, riskEngine, tradingStateService, tradeRepository, orderRepository, errorEventRepository, decisionRepository,
                executionOutcomeCsvRecorder, telegramAlertService, positionSyncProperties, Clock.systemUTC());
    }

    ExecutionEngine(TradingProperties properties, GlobalConfigService globalConfigService, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                    TradeRepository tradeRepository, OrderRepository orderRepository,
                    ErrorEventRepository errorEventRepository,
                    StrategyDecisionRepository decisionRepository, ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder,
                    TelegramAlertService telegramAlertService,
                    com.algo.trade.config.PositionSyncProperties positionSyncProperties, Clock clock) {
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
        this.clock = clock;
    }

    @Transactional
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize) {
        return executeEntry(decision, optionPremium, lotSize, null);
    }

    /**
     * Execute entry with optional per-strategy stop-loss percent for position sizing.
     * If stopLossPercent is null, falls back to directional buy config SL.
     */
    @Transactional
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize, BigDecimal stopLossPercent) {
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
        StrategyDecisionEntity savedDecision = persistDecision(decision);
        if (!tradingStateService.running()) {
            log.warn("Entry execution rejected: trading engine is stopped");
            List<String> reasons = List.of("Trading engine is stopped");
            updateExecutionStage(savedDecision, "TRADING_STOPPED", reasons.getFirst());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "TRADING_STOPPED", false,
                    null, null, null, null, reasons);
            telegramAlertService.entryRejected(decision, optionPremium, "TRADING_STOPPED", reasons);
            return ExecutionResult.rejected(reasons);
        }
        List<String> orderGuardRejections = orderGuardRejections(decision, optionPremium);
        if (!orderGuardRejections.isEmpty()) {
            log.warn("Entry execution rejected by order guard: reasons={}", orderGuardRejections);
            updateExecutionStage(savedDecision, "ORDER_GUARD_REJECTED", String.join("; ", orderGuardRejections));
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_GUARD_REJECTED", false,
                    null, null, null, null, orderGuardRejections);
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
                    null, null, null, null, risk.reasons());
            telegramAlertService.entryRejected(decision, optionPremium, "RISK_REJECTED", risk.reasons());
            return ExecutionResult.rejected(risk.reasons());
        }

        var sizing = stopLossPercent != null
                ? riskEngine.calculateQuantity(optionPremium, lotSize, stopLossPercent)
                : riskEngine.calculateQuantity(optionPremium, lotSize);
        if (!sizing.allowed()) {
            log.warn("Entry execution rejected by position sizing: reason={}, riskAmount={}, estimatedCost={}",
                    sizing.reason(), sizing.riskAmount(), sizing.estimatedCost());
            List<String> reasons = List.of(sizing.reason());
            updateExecutionStage(savedDecision, "SIZING_REJECTED", sizing.reason());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "SIZING_REJECTED", false,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), null, reasons);
            telegramAlertService.entryRejected(decision, optionPremium, "SIZING_REJECTED", reasons);
            return ExecutionResult.rejected(reasons);
        }
        log.info("Entry sizing accepted: quantity={}, riskAmount={}, estimatedCost={}, reason={}",
                sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), sizing.reason());

        String clientOrderId = "ENTRY-" + UUID.randomUUID();
        OrderRequest orderRequest = new OrderRequest(clientOrderId, decision.selectedInstrumentKey().orElseThrow(),
                OrderSide.BUY, OrderType.LIMIT, ProductType.MIS, sizing.quantity(), Optional.of(optionPremium), "strategy-entry");
        log.info("Placing entry order: clientOrderId={}, instrument={}, side={}, orderType={}, product={}, quantity={}",
                orderRequest.clientOrderId(), orderRequest.instrumentKey(), orderRequest.side(),
                orderRequest.orderType(), orderRequest.productType(), orderRequest.quantity());
        OrderResponse order;
        try {
            order = brokerClient.placeOrder(orderRequest);
            persistOrderWithSignalTime(order, decision.timestamp(), optionPremium, extractStrategyType(decision));
            log.info("Entry order response: clientOrderId={}, brokerOrderId={}, status={}, requestedQuantity={}, filledQuantity={}, averageFillPrice={}, rejectionReason={}",
                    order.clientOrderId(), order.brokerOrderId().orElse(""), order.status(), order.requestedQuantity(),
                    order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(""));
        } catch (RuntimeException ex) {
            return rejectBrokerFailure(savedDecision, decision, optionPremium, lotSize, sizing.quantity(), sizing.riskAmount(),
                    sizing.estimatedCost(), orderRequest.clientOrderId(), ex);
        }

        // Limit order in book — watchdog will poll for fill and create TradeEntity
        if (order.status() == OrderStatus.OPEN || order.status() == OrderStatus.NEW) {
            log.info("Entry limit order placed — OrderFillWatchdog will track: clientOrderId={}, brokerOrderId={}",
                    order.clientOrderId(), order.brokerOrderId().orElse(""));
            List<String> reasons = List.of("Limit order placed — awaiting fill");
            updateExecutionStage(savedDecision, "ORDER_OPEN", "brokerOrderId=" + order.brokerOrderId().orElse(""));
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_OPEN", false,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons);
            return ExecutionResult.accepted(order, reasons);
        }

        if (order.status() == OrderStatus.COMPLETE) {
            BigDecimal fillPrice = order.averageFillPrice().orElse(optionPremium);
            String tradeId = "TRD-" + UUID.randomUUID();
            TradeEntity tradeEntity = new TradeEntity(tradeId, order.instrumentKey(),
                    decision.underlying().name(), decision.optionType().orElseThrow().name(), TradeStatus.OPEN,
                    order.filledQuantity(), fillPrice, Instant.now(clock), String.join("; ", decision.reasons()));
            tradeEntity.setStrategyType(extractStrategyType(decision));
            tradeRepository.save(tradeEntity);
            log.info("Entry trade opened: tradeId={}, instrument={}, quantity={}, entryPrice={}",
                    tradeId, order.instrumentKey(), order.filledQuantity(), fillPrice);
            List<String> reasons = List.of("Entry order filled and trade journal updated");
            updateExecutionStage(savedDecision, "ORDER_FILLED", "tradeId=" + tradeId);
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_FILLED", true,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons);
            telegramAlertService.entryOrderFilled(decision, optionPremium, sizing.quantity(), sizing.estimatedCost(), order);
            return ExecutionResult.accepted(order, reasons);
        }
        log.warn("Entry order not filled: clientOrderId={}, status={}, reason={}",
                order.clientOrderId(), order.status(), order.rejectionReason().orElse("Entry order was not filled"));
        List<String> reasons = List.of(order.rejectionReason().orElse("Entry order was not filled"));
        updateExecutionStage(savedDecision, "ORDER_NOT_FILLED", reasons.getFirst());
        executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_NOT_FILLED", false,
                sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons);
        telegramAlertService.orderNotFilled(decision, optionPremium, sizing.quantity(), order, reasons);
        return ExecutionResult.rejected(reasons);
    }

    /**
     * Execute a paper trade entry — creates a TradeEntity with simulated fill at current market price.
     * No broker order is placed. The trade is managed by existing exit monitors (SL/target/trailing/maxHold)
     * and closed via closeTrade which detects the "PAPER-" prefix and skips the broker exit order.
     */
    @Transactional
    public ExecutionResult executePaperEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize, BigDecimal stopLossPercent) {
        log.info("PAPER entry requested: signalType={}, underlying={}, instrument={}, premium={}",
                decision.signalType(), decision.underlying(),
                decision.selectedInstrumentKey().orElse(""), optionPremium);

        StrategyDecisionEntity savedDecision = persistDecision(decision);

        var sizing = stopLossPercent != null
                ? riskEngine.calculateQuantity(optionPremium, lotSize, stopLossPercent)
                : riskEngine.calculateQuantity(optionPremium, lotSize);
        if (!sizing.allowed()) {
            updateExecutionStage(savedDecision, "PAPER_SIZING_REJECTED", sizing.reason());
            return ExecutionResult.rejected(List.of(sizing.reason()));
        }

        String tradeId = "PAPER-TRD-" + UUID.randomUUID();
        String instrumentKey = decision.selectedInstrumentKey().orElse("UNKNOWN");
        TradeEntity trade = new TradeEntity(tradeId, instrumentKey,
                decision.underlying().name(), decision.optionType().map(Enum::name).orElse("CE"),
                TradeStatus.OPEN, sizing.quantity(), optionPremium, Instant.now(clock),
                "PAPER_TRADE [" + decision.signalType().name() + "]: " + String.join("; ", decision.reasons()));
        // Extract strategy type from decision reasons
        trade.setStrategyType(extractStrategyType(decision));
        tradeRepository.save(trade);

        updateExecutionStage(savedDecision, "PAPER_FILLED", "tradeId=" + tradeId);
        log.info("PAPER trade opened: tradeId={}, instrument={}, qty={}, entryPrice={}",
                tradeId, instrumentKey, sizing.quantity(), optionPremium);

        OrderResponse syntheticOrder = new OrderResponse(
                "PAPER-" + UUID.randomUUID(), Optional.empty(), instrumentKey,
                OrderSide.BUY, OrderStatus.COMPLETE, sizing.quantity(), sizing.quantity(),
                Optional.of(optionPremium), Optional.empty(), Instant.now(clock));
        executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "PAPER_FILLED", true,
                sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), syntheticOrder,
                List.of("Paper trade opened — exit managed by live monitors"));

        return ExecutionResult.accepted(syntheticOrder, List.of("Paper trade opened"));
    }

    @Transactional
    public ExecutionResult closeTrade(String tradeId, BigDecimal lastPrice, String reason) {
        if (!closingInProgress.add(tradeId)) {
            log.warn("Close already in progress for tradeId={} reason={} — duplicate suppressed", tradeId, reason);
            return ExecutionResult.rejected(List.of("Close already in progress"));
        }
        try {
            return doCloseTrade(tradeId, lastPrice, reason);
        } finally {
            closingInProgress.remove(tradeId);
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
            // Determine P&L direction: SELL entries profit when price drops, BUY entries profit when price rises
            boolean isShortEntry = trade.getEntryReason() != null
                    && (trade.getEntryReason().contains("[SELL_CE]")
                        || trade.getEntryReason().contains("[SELL_PE]"));
            BigDecimal realizedPnl = isShortEntry
                    ? trade.getEntryPrice().subtract(lastPrice).multiply(BigDecimal.valueOf(trade.getQuantity()))
                    : lastPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(trade.getQuantity()));
            trade.close(lastPrice, Instant.now(clock), realizedPnl, "PAPER_EXIT: " + reason);
            tradeRepository.save(trade);
            log.info("PAPER trade closed: tradeId={}, exitPrice={}, realizedPnl={}, reason={}",
                    tradeId, lastPrice, realizedPnl, reason);
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
                        OrderSide.SELL, OrderType.LIMIT, ProductType.MIS, trade.getQuantity(), Optional.of(lastPrice),
                        "strategy-exit")
                : new OrderRequest("EXIT-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        OrderSide.SELL, OrderType.MARKET, ProductType.MIS, trade.getQuantity(), Optional.empty(),
                        "strategy-exit-market");
        log.info("Placing exit order: tradeId={}, clientOrderId={}, instrument={}, quantity={}",
                tradeId, orderRequest.clientOrderId(), orderRequest.instrumentKey(), orderRequest.quantity());
        OrderResponse order;
        try {
            order = brokerClient.placeOrder(orderRequest);
        } catch (RuntimeException ex) {
            // Retry once with MARKET order if LIMIT fails
            log.warn("Exit LIMIT order failed for tradeId={}, retrying with MARKET order: {}", tradeId, ex.getMessage());
            telegramAlertService.systemAlert("⚠️ Exit LIMIT failed for " + trade.getInstrumentKey() + " — retrying MARKET order");
            try {
                OrderRequest marketRequest = new OrderRequest("EXIT-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        OrderSide.SELL, OrderType.MARKET, ProductType.MIS, trade.getQuantity(), Optional.empty(),
                        "strategy-exit-market-retry");
                order = brokerClient.placeOrder(marketRequest);
            } catch (RuntimeException retryEx) {
                log.error("Exit MARKET retry also failed for tradeId={}: {}", tradeId, retryEx.getMessage());
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
        BigDecimal realizedPnl = exitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(trade.getQuantity()));
        trade.close(exitPrice, Instant.now(clock), realizedPnl, reason);
        tradeRepository.save(trade);
        log.info("Trade closed: tradeId={}, exitPrice={}, realizedPnl={}, reason={}", tradeId, exitPrice, realizedPnl, reason);
        telegramAlertService.tradeClosed(tradeId, trade.getInstrumentKey(), trade.getQuantity(),
                trade.getEntryPrice(), exitPrice, realizedPnl, reason, order);
        return ExecutionResult.accepted(order, List.of("Exit order filled and trade journal updated"));
    }

    @Transactional
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
            BigDecimal partialPnl = lastPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(partialQuantity));
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
                OrderSide.SELL, OrderType.LIMIT, ProductType.MIS, partialQuantity, Optional.of(lastPrice),
                "partial-exit-" + layerReason.toLowerCase());
        OrderResponse order;
        try {
            order = brokerClient.placeOrder(orderRequest);
        } catch (RuntimeException ex) {
            log.warn("Partial exit LIMIT failed for tradeId={}, retrying MARKET: {}", tradeId, ex.getMessage());
            try {
                OrderRequest marketReq = new OrderRequest("PARTIAL-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        OrderSide.SELL, OrderType.MARKET, ProductType.MIS, partialQuantity, Optional.empty(),
                        "partial-exit-market-" + layerReason.toLowerCase());
                order = brokerClient.placeOrder(marketReq);
            } catch (RuntimeException retryEx) {
                log.error("Partial exit MARKET retry failed for tradeId={}: {}", tradeId, retryEx.getMessage());
                return ExecutionResult.rejected(List.of("Partial exit failed after retry: " + retryEx.getMessage()));
            }
        }
        persistOrder(order);
        if (order.status() != OrderStatus.COMPLETE) {
            log.warn("Partial exit order not filled: tradeId={}, status={}", tradeId, order.status());
            return ExecutionResult.rejected(List.of("Partial exit order not filled: " + order.status()));
        }
        BigDecimal exitPrice = order.averageFillPrice().orElse(lastPrice);
        BigDecimal partialPnl = exitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(partialQuantity));
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
        TradeEntity trade = new TradeEntity(tradeId, instrumentKey, underlying, optionType,
                TradeStatus.OPEN, filledQty, fillPrice, orderEntity.getUpdatedAt(), entryReason);
        // Use strategy type stored on the order entity at placement time
        if (orderEntity.getStrategyType() != null && !orderEntity.getStrategyType().isBlank()) {
            trade.setStrategyType(orderEntity.getStrategyType());
        }
        tradeRepository.save(trade);

        // Update order entity with final status
        orderEntity.setStatus(OrderStatus.COMPLETE);
        orderEntity.setFilledQuantity(filledQty);
        orderEntity.setAverageFillPrice(fillPrice);
        orderRepository.save(orderEntity);

        log.info("Watchdog opened trade from filled order: tradeId={}, clientOrderId={}, instrument={}, qty={}, price={}",
                tradeId, orderEntity.getClientOrderId(), instrumentKey, filledQty, fillPrice);
        telegramAlertService.systemAlert("Limit order filled (watchdog)"
                + System.lineSeparator() + "Trade: " + tradeId
                + System.lineSeparator() + "Instrument: " + instrumentKey
                + System.lineSeparator() + "Qty: " + filledQty
                + System.lineSeparator() + "Price: " + fillPrice);
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
            RuntimeException ex
    ) {
        String message = exceptionMessage(ex);
        log.warn("Entry order placement failed: clientOrderId={}, instrument={}, message={}",
                clientOrderId, decision.selectedInstrumentKey().orElse(""), message, ex);
        errorEventRepository.save(new ErrorEventEntity(Instant.now(clock), "ExecutionEngine", message));
        List<String> reasons = List.of(message);
        updateExecutionStage(savedDecision, "BROKER_ERROR", message);
        executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "BROKER_ERROR", false,
                quantity, riskAmount, estimatedCost,
                new OrderResponse(clientOrderId, Optional.empty(), decision.selectedInstrumentKey().orElse(""),
                        OrderSide.BUY, OrderStatus.REJECTED, quantity == null ? 0 : quantity, 0,
                        Optional.empty(), Optional.of(message), Instant.now(clock)),
                reasons);
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
        return (int) tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> positionSyncProperties.manageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
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

    /** Extract strategy type name from a StrategyDecision's reasons list. */
    private String extractStrategyType(StrategyDecision decision) {
        for (String reason : decision.reasons()) {
            String upper = reason.toUpperCase();
            for (com.algo.trade.strategy.StrategyType type : com.algo.trade.strategy.StrategyType.values()) {
                if (upper.contains(type.name())) return type.name();
            }
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
                .map(TradeEntity::getRealizedPnl)
                .filter(p -> p != null)
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
        for (TradeEntity trade : recentClosed) {
            if (trade.getRealizedPnl().signum() < 0) {
                losses++;
            } else {
                break;
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

        int buyOrdersToday = buyOrdersToday();
        if (buyOrdersToday >= globalConfigService.getMaxOrdersPerDay()) {
            rejections.add("Max buy orders per day reached");
        }

        boolean existingOpenBuyOrder = !orderRepository.findByInstrumentKeyAndSideAndStatusIn(
                instrumentKey,
                OrderSide.BUY.name(),
                List.of(OrderStatus.NEW, OrderStatus.OPEN)
        ).isEmpty();
        if (existingOpenBuyOrder) {
            rejections.add("Open buy order already exists for instrument: " + instrumentKey);
        }

        boolean existingOpenTrade = tradeRepository.findByInstrumentKeyAndStatus(instrumentKey, TradeStatus.OPEN).stream()
                .anyMatch(t -> !t.isPaperTrade());
        if (existingOpenTrade) {
            rejections.add("Open trade already exists for instrument: " + instrumentKey);
        }

        tradeRepository.findByInstrumentKeyAndEntryTimeBetween(instrumentKey, todayStart(), tomorrowStart()).stream()
                .map(TradeEntity::getEntryPrice)
                .filter(previousPrice -> previousPrice != null && previousPrice.signum() > 0 && optionPremium != null)
                .filter(previousPrice -> priceMovePercent(previousPrice, optionPremium)
                        .compareTo(globalConfigService.getSameInstrumentReentryMinPriceMovePercent()) < 0)
                .findFirst()
                .ifPresent(previousPrice -> rejections.add("Same instrument already traded today without required price move: "
                        + instrumentKey));

        if (globalConfigService.getCooldownMinutes() > 0) {
            Instant cooldownStart = clock.instant().minus(Duration.ofMinutes(globalConfigService.getCooldownMinutes()));
            if (!tradeRepository.findByInstrumentKeyAndEntryTimeBetween(instrumentKey, cooldownStart, clock.instant()).isEmpty()) {
                rejections.add("Cooldown period not elapsed for instrument: " + instrumentKey);
            }
        }

        return rejections;
    }

    private int buyOrdersToday() {
        return orderRepository.findBySideAndUpdatedAtBetween(OrderSide.BUY.name(), todayStart(), tomorrowStart()).size();
    }

    private BigDecimal priceMovePercent(BigDecimal previousPrice, BigDecimal currentPrice) {
        return currentPrice.subtract(previousPrice).abs()
                .multiply(BigDecimal.valueOf(100), MATH_CONTEXT)
                .divide(previousPrice, MATH_CONTEXT);
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
