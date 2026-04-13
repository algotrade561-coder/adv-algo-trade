package com.kiteapioptions.execution;

import com.kiteapioptions.broker.BrokerClient;
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
import com.kiteapioptions.risk.RiskEngine;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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

    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);

    private final BrokerClient brokerClient;
    private final RiskEngine riskEngine;
    private final TradingStateService tradingStateService;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final Clock clock;

    @Autowired
    public ExecutionEngine(BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                           TradeRepository tradeRepository, OrderRepository orderRepository,
                           StrategyDecisionRepository decisionRepository) {
        this(brokerClient, riskEngine, tradingStateService, tradeRepository, orderRepository, decisionRepository,
                Clock.systemUTC());
    }

    ExecutionEngine(BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                    TradeRepository tradeRepository, OrderRepository orderRepository,
                    StrategyDecisionRepository decisionRepository, Clock clock) {
        this.brokerClient = brokerClient;
        this.riskEngine = riskEngine;
        this.tradingStateService = tradingStateService;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.decisionRepository = decisionRepository;
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
            return ExecutionResult.rejected(List.of("Trading engine is stopped"));
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
            return ExecutionResult.rejected(risk.reasons());
        }

        var sizing = riskEngine.calculateQuantity(optionPremium, lotSize);
        if (!sizing.allowed()) {
            log.warn("Entry execution rejected by position sizing: reason={}, riskAmount={}, estimatedCost={}",
                    sizing.reason(), sizing.riskAmount(), sizing.estimatedCost());
            return ExecutionResult.rejected(List.of(sizing.reason()));
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
            return ExecutionResult.accepted(order, List.of("Entry order filled and trade journal updated"));
        }
        log.warn("Entry order not filled: clientOrderId={}, status={}, reason={}",
                order.clientOrderId(), order.status(), order.rejectionReason().orElse("Entry order was not filled"));
        return ExecutionResult.rejected(List.of(order.rejectionReason().orElse("Entry order was not filled")));
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
                String.join("; ", decision.reasons())));
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
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(clock);
        return tradeRepository.findByEntryTimeBetween(today.atStartOfDay(zoneId).toInstant(),
                today.plusDays(1).atStartOfDay(zoneId).toInstant()).size();
    }

    private BigDecimal dailyPnl() {
        ZoneId zoneId = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(clock);
        return tradeRepository.findByEntryTimeBetween(today.atStartOfDay(zoneId).toInstant(),
                        today.plusDays(1).atStartOfDay(zoneId).toInstant()).stream()
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
}
