package com.kiteapioptions.execution;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OrderRequest;
import com.kiteapioptions.domain.OrderResponse;
import com.kiteapioptions.domain.OrderSide;
import com.kiteapioptions.domain.OrderStatus;
import com.kiteapioptions.domain.OrderType;
import com.kiteapioptions.domain.ProductType;
import com.kiteapioptions.domain.StrategyDecision;
import com.kiteapioptions.domain.TradeStatus;
import com.kiteapioptions.persistence.OrderEntity;
import com.kiteapioptions.persistence.OrderRepository;
import com.kiteapioptions.persistence.StrategyDecisionEntity;
import com.kiteapioptions.persistence.StrategyDecisionRepository;
import com.kiteapioptions.persistence.TradeEntity;
import com.kiteapioptions.persistence.TradeRepository;
import com.kiteapioptions.notification.TelegramAlertService;
import com.kiteapioptions.risk.RiskEngine;
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
    private final BrokerClient brokerClient;
    private final RiskEngine riskEngine;
    private final TradingStateService tradingStateService;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder;
    private final TelegramAlertService telegramAlertService;
    private final Clock clock;

    @Autowired
    public ExecutionEngine(TradingProperties properties, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                           TradeRepository tradeRepository, OrderRepository orderRepository,
                           StrategyDecisionRepository decisionRepository,
                           ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder,
                           TelegramAlertService telegramAlertService) {
        this(properties, brokerClient, riskEngine, tradingStateService, tradeRepository, orderRepository, decisionRepository,
                executionOutcomeCsvRecorder, telegramAlertService, Clock.systemUTC());
    }

    ExecutionEngine(TradingProperties properties, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                    TradeRepository tradeRepository, OrderRepository orderRepository,
                    StrategyDecisionRepository decisionRepository, ExecutionOutcomeCsvRecorder executionOutcomeCsvRecorder,
                    TelegramAlertService telegramAlertService, Clock clock) {
        this.properties = properties;
        this.brokerClient = brokerClient;
        this.riskEngine = riskEngine;
        this.tradingStateService = tradingStateService;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.decisionRepository = decisionRepository;
        this.executionOutcomeCsvRecorder = executionOutcomeCsvRecorder;
        this.telegramAlertService = telegramAlertService;
        this.clock = clock;
    }

    @Transactional
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize) {
        log.info("Entry execution requested: signalType={}, underlying={}, instrument={}, optionType={}, premium={}, lotSize={}, running={}, killSwitch={}",
                decision.signalType(),
                decision.underlying(),
                decision.selectedInstrumentKey().orElse(""),
                decision.optionType().map(Enum::name).orElse(""),
                optionPremium,
                lotSize,
                tradingStateService.running(),
                tradingStateService.killSwitchEnabled());
        persistDecision(decision);
        if (!tradingStateService.running()) {
            log.warn("Entry execution rejected: trading engine is stopped");
            List<String> reasons = List.of("Trading engine is stopped");
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "TRADING_STOPPED", false,
                    null, null, null, null, reasons);
            telegramAlertService.entryRejected(decision, optionPremium, "TRADING_STOPPED", reasons);
            return ExecutionResult.rejected(reasons);
        }
        List<String> orderGuardRejections = orderGuardRejections(decision, optionPremium);
        if (!orderGuardRejections.isEmpty()) {
            log.warn("Entry execution rejected by order guard: reasons={}", orderGuardRejections);
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
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "RISK_REJECTED", false,
                    null, null, null, null, risk.reasons());
            telegramAlertService.entryRejected(decision, optionPremium, "RISK_REJECTED", risk.reasons());
            return ExecutionResult.rejected(risk.reasons());
        }

        var sizing = riskEngine.calculateQuantity(optionPremium, lotSize);
        if (!sizing.allowed()) {
            log.warn("Entry execution rejected by position sizing: reason={}, riskAmount={}, estimatedCost={}",
                    sizing.reason(), sizing.riskAmount(), sizing.estimatedCost());
            List<String> reasons = List.of(sizing.reason());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "SIZING_REJECTED", false,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), null, reasons);
            telegramAlertService.entryRejected(decision, optionPremium, "SIZING_REJECTED", reasons);
            return ExecutionResult.rejected(reasons);
        }
        log.info("Entry sizing accepted: quantity={}, riskAmount={}, estimatedCost={}, reason={}",
                sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), sizing.reason());

        String clientOrderId = "ENTRY-" + UUID.randomUUID();
        OrderRequest orderRequest = new OrderRequest(clientOrderId, decision.selectedInstrumentKey().orElseThrow(),
                OrderSide.BUY, OrderType.MARKET, ProductType.MIS, sizing.quantity(), Optional.empty(), "strategy-entry");
        log.info("Placing entry order: clientOrderId={}, instrument={}, side={}, orderType={}, product={}, quantity={}",
                orderRequest.clientOrderId(), orderRequest.instrumentKey(), orderRequest.side(),
                orderRequest.orderType(), orderRequest.productType(), orderRequest.quantity());
        OrderResponse order = brokerClient.placeOrder(orderRequest);
        persistOrder(order);
        log.info("Entry order response: clientOrderId={}, brokerOrderId={}, status={}, requestedQuantity={}, filledQuantity={}, averageFillPrice={}, rejectionReason={}",
                order.clientOrderId(), order.brokerOrderId().orElse(""), order.status(), order.requestedQuantity(),
                order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(""));

        if (order.status() == OrderStatus.COMPLETE) {
            BigDecimal fillPrice = order.averageFillPrice().orElse(optionPremium);
            String tradeId = "TRD-" + UUID.randomUUID();
            tradeRepository.save(new TradeEntity(tradeId, order.instrumentKey(),
                    decision.underlying().name(), decision.optionType().orElseThrow().name(), TradeStatus.OPEN,
                    order.filledQuantity(), fillPrice, Instant.now(clock), String.join("; ", decision.reasons())));
            log.info("Entry trade opened: tradeId={}, instrument={}, quantity={}, entryPrice={}",
                    tradeId, order.instrumentKey(), order.filledQuantity(), fillPrice);
            List<String> reasons = List.of("Entry order filled and trade journal updated");
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_FILLED", true,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons);
            telegramAlertService.entryOrderFilled(decision, optionPremium, sizing.quantity(), sizing.estimatedCost(), order);
            return ExecutionResult.accepted(order, reasons);
        }
        log.warn("Entry order not filled: clientOrderId={}, status={}, reason={}",
                order.clientOrderId(), order.status(), order.rejectionReason().orElse("Entry order was not filled"));
        List<String> reasons = List.of(order.rejectionReason().orElse("Entry order was not filled"));
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
                OrderSide.SELL, OrderType.MARKET, ProductType.MIS, trade.getQuantity(), Optional.empty(),
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
        return ExecutionResult.accepted(order, List.of("Exit order filled and trade journal updated"));
    }

    private void persistDecision(StrategyDecision decision) {
        log.info("Persisting strategy decision: timestamp={}, underlying={}, signalType={}, instrument={}, reasons={}",
                decision.timestamp(), decision.underlying(), decision.signalType(),
                decision.selectedInstrumentKey().orElse(""), decision.reasons());
        decisionRepository.save(new StrategyDecisionEntity(decision.timestamp(), decision.underlying().name(),
                decision.signalType().name(), decision.underlyingPrice(), decision.selectedInstrumentKey().orElse(null),
                decision.selectedStrike().orElse(null), decision.optionType().map(Enum::name).orElse(null),
                decision.vwapConditionPassed(), decision.imbalance().orElse(null), decision.volumeSpike(),
                decision.confidenceScore(), String.join("; ", decision.reasons())));
    }

    private void persistOrder(OrderResponse order) {
        orderRepository.save(new OrderEntity(order.clientOrderId(), order.brokerOrderId().orElse(null),
                order.instrumentKey(), order.side().name(), order.status(), order.requestedQuantity(),
                order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(null),
                order.updatedAt()));
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
        if (buyOrdersToday >= properties.risk().maxOrdersPerDay()) {
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
                        .compareTo(properties.risk().sameInstrumentReentryMinPriceMovePercent()) < 0)
                .findFirst()
                .ifPresent(previousPrice -> rejections.add("Same instrument already traded today without required price move: "
                        + instrumentKey));

        if (properties.risk().cooldownMinutes() > 0) {
            Instant cooldownStart = clock.instant().minus(Duration.ofMinutes(properties.risk().cooldownMinutes()));
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
