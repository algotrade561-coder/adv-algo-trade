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
import java.util.UUID;
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
    private final Clock clock;

    @Autowired
    public ExecutionEngine(TradingProperties properties, GlobalConfigService globalConfigService, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                           TradeRepository tradeRepository, OrderRepository orderRepository,
                           ErrorEventRepository errorEventRepository,
                           StrategyDecisionRepository decisionRepository,
                           ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder,
                           TelegramAlertService telegramAlertService) {
        this(properties, globalConfigService, brokerClient, riskEngine, tradingStateService, tradeRepository, orderRepository, errorEventRepository, decisionRepository,
                executionOutcomeCsvRecorder, telegramAlertService, Clock.systemUTC());
    }

    ExecutionEngine(TradingProperties properties, GlobalConfigService globalConfigService, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                    TradeRepository tradeRepository, OrderRepository orderRepository,
                    ErrorEventRepository errorEventRepository,
                    StrategyDecisionRepository decisionRepository, ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder,
                    TelegramAlertService telegramAlertService, Clock clock) {
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
            persistOrderWithSignalTime(order, decision.timestamp(), optionPremium);
            log.info("Entry order response: clientOrderId={}, brokerOrderId={}, status={}, requestedQuantity={}, filledQuantity={}, averageFillPrice={}, rejectionReason={}",
                    order.clientOrderId(), order.brokerOrderId().orElse(""), order.status(), order.requestedQuantity(),
                    order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(""));
        } catch (RuntimeException ex) {
            return rejectBrokerFailure(decision, optionPremium, lotSize, sizing.quantity(), sizing.riskAmount(),
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
            tradeRepository.save(new TradeEntity(tradeId, order.instrumentKey(),
                    decision.underlying().name(), decision.optionType().orElseThrow().name(), TradeStatus.OPEN,
                    order.filledQuantity(), fillPrice, Instant.now(clock), String.join("; ", decision.reasons())));
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

    @Transactional
    public ExecutionResult closeTrade(String tradeId, BigDecimal lastPrice, String reason) {
        log.info("Close trade requested: tradeId={}, lastPrice={}, reason={}", tradeId, lastPrice, reason);
        TradeEntity trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tradeId: " + tradeId));
        if (trade.getStatus() != TradeStatus.OPEN) {
            log.warn("Close trade rejected: tradeId={}, status={}", tradeId, trade.getStatus());
            return ExecutionResult.rejected(List.of("Trade is not open"));
        }

        OrderRequest orderRequest = new OrderRequest("EXIT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                OrderSide.SELL, OrderType.LIMIT, ProductType.MIS, trade.getQuantity(), Optional.of(lastPrice),
                "strategy-exit");
        log.info("Placing exit order: tradeId={}, clientOrderId={}, instrument={}, quantity={}",
                tradeId, orderRequest.clientOrderId(), orderRequest.instrumentKey(), orderRequest.quantity());
        OrderResponse order = brokerClient.placeOrder(orderRequest);
        persistOrder(order);
        log.info("Exit order response: clientOrderId={}, brokerOrderId={}, status={}, filledQuantity={}, averageFillPrice={}, rejectionReason={}",
                order.clientOrderId(), order.brokerOrderId().orElse(""), order.status(), order.filledQuantity(),
                order.averageFillPrice().orElse(null), order.rejectionReason().orElse(""));
        if (order.status() != OrderStatus.COMPLETE) {
            log.warn("Exit order not filled: tradeId={}, status={}, reason={}",
                    tradeId, order.status(), order.rejectionReason().orElse("Exit order was not filled"));
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

    private StrategyDecisionEntity persistDecision(StrategyDecision decision) {
        log.info("Persisting strategy decision: timestamp={}, underlying={}, signalType={}, instrument={}, reasons={}",
                decision.timestamp(), decision.underlying(), decision.signalType(),
                decision.selectedInstrumentKey().orElse(""), decision.reasons());
        return decisionRepository.save(new StrategyDecisionEntity(decision.timestamp(), decision.underlying().name(),
                decision.signalType().name(), decision.underlyingPrice(), decision.optionPrice().orElse(null),
                decision.optionOpenInterest().orElse(null), decision.lotSize().orElse(null),
                decision.lotPrice().orElse(null),
                decision.selectedInstrumentKey().orElse(null), decision.selectedStrike().orElse(null),
                decision.optionType().map(Enum::name).orElse(null), decision.vwapConditionPassed(),
                decision.imbalance().orElse(null), decision.volumeSpike(), decision.confidenceScore(),
                String.join("; ", decision.reasons())));
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

    private void persistOrderWithSignalTime(OrderResponse order, Instant signalTimestamp, BigDecimal limitPrice) {
        OrderEntity entity = new OrderEntity(order.clientOrderId(), order.brokerOrderId().orElse(null),
                order.instrumentKey(), order.side().name(), order.status(), order.requestedQuantity(),
                order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(null),
                order.updatedAt());
        entity.setSignalTimestamp(signalTimestamp);
        entity.setOrderPlacedAt(Instant.now(clock));
        // Slippage = |fill price - limit price| (only meaningful when filled)
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
        BigDecimal fillPrice = orderEntity.getAverageFillPrice() != null
                ? orderEntity.getAverageFillPrice() : BigDecimal.ZERO;
        int filledQty = orderEntity.getFilledQuantity();

        // Derive underlying and option type from instrument key
        String underlying = instrumentKey.contains("NIFTY") && !instrumentKey.contains("BANKNIFTY")
                ? "NIFTY" : instrumentKey.contains("BANKNIFTY") ? "BANKNIFTY" : "NIFTY";
        String optionType = instrumentKey.toUpperCase().contains("PE") ? "PE" : "CE";

        TradeEntity trade = new TradeEntity(tradeId, instrumentKey, underlying, optionType,
                TradeStatus.OPEN, filledQty, fillPrice, orderEntity.getUpdatedAt(),
                "Limit order filled (watchdog): " + orderEntity.getClientOrderId());
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
        return tradeRepository.findByStatus(TradeStatus.OPEN).size();
    }

    private int tradesToday() {
        return tradeRepository.findByEntryTimeBetween(todayStart(), tomorrowStart()).size();
    }

    private BigDecimal dailyPnl() {
        return tradeRepository.findByEntryTimeBetween(todayStart(), tomorrowStart()).stream()
                .map(TradeEntity::getRealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int consecutiveLosses() {
        List<TradeEntity> closedTrades = tradeRepository.findAll().stream()
                .filter(trade -> trade.getStatus() == TradeStatus.CLOSED)
                .sorted((a, b) -> b.getEntryTime().compareTo(a.getEntryTime()))
                .toList();
        int losses = 0;
        for (TradeEntity trade : closedTrades) {
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

        boolean existingOpenTrade = !tradeRepository.findByInstrumentKeyAndStatus(instrumentKey, TradeStatus.OPEN).isEmpty();
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
