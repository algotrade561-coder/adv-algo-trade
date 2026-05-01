package com.algo.trade.execution;

import com.algo.trade.domain.TradeStatus;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

/**
 * Fail-Safe Square-off Daemon — independent watchdog.
 *
 * Runs every minute after 15:10 IST. If any open trades remain after 15:20,
 * it forces them closed even if the main AlgoTradeExecution has crashed.
 *
 * This is a safety net — the primary forced exit is in ExecutionEngine at 15:15.
 */
@Component
public class FailSafeSquareoffDaemon {

    private static final Logger log = LoggerFactory.getLogger(FailSafeSquareoffDaemon.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime FAILSAFE_TIME = LocalTime.of(15, 20);

    private final TradeRepository tradeRepository;
    private final ExecutionEngine executionEngine;
    private final TelegramAlertService alertService;
    private final com.algo.trade.marketdata.MarketDataService marketDataService;
    private final com.algo.trade.persistence.OrderRepository orderRepository;
    private final com.algo.trade.broker.BrokerClient brokerClient;
    private final com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public FailSafeSquareoffDaemon(TradeRepository tradeRepository,
                                    ExecutionEngine executionEngine,
                                    TelegramAlertService alertService,
                                    com.algo.trade.marketdata.MarketDataService marketDataService,
                                    com.algo.trade.persistence.OrderRepository orderRepository,
                                    com.algo.trade.broker.BrokerClient brokerClient,
                                    com.algo.trade.monitoring.ErrorEventService errorEventService) {
        this.tradeRepository = tradeRepository;
        this.executionEngine = executionEngine;
        this.alertService = alertService;
        this.marketDataService = marketDataService;
        this.orderRepository = orderRepository;
        this.brokerClient = brokerClient;
        this.errorEventService = errorEventService;
    }

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) schedulerRegistry.register("failSafe", "FailSafe EOD square-off (cron 15:*)", 0, this::check);
    }

    @Scheduled(cron = "0 * 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void check() {
        // Note: FailSafe does NOT check schedulerRegistry.isEnabled() — it's a safety net that must always run
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(FAILSAFE_TIME)) return;

        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN);
        if (openTrades.isEmpty()) return;

        log.warn("[FailSafe] {} open trades remain after {} — forcing close", openTrades.size(), FAILSAFE_TIME);
        alertService.systemAlert("🚨 FailSafe: " + openTrades.size() + " open trades after " + FAILSAFE_TIME + " — forcing close");

        // Cancel all pending limit orders first — prevent overnight broker exposure
        try {
            var pendingOrders = orderRepository.findByStatusIn(
                    List.of(com.algo.trade.domain.OrderStatus.OPEN, com.algo.trade.domain.OrderStatus.NEW));
            for (var order : pendingOrders) {
                try {
                    if (order.getBrokerOrderId() != null && !order.getBrokerOrderId().isBlank()) {
                        brokerClient.cancelOrder(order.getBrokerOrderId());
                        order.setStatus(com.algo.trade.domain.OrderStatus.CANCELLED);
                        orderRepository.save(order);
                        log.warn("[FailSafe] Cancelled pending order: clientOrderId={} brokerOrderId={}",
                                order.getClientOrderId(), order.getBrokerOrderId());
                    }
                } catch (Exception e) {
                    log.error("[FailSafe] Failed to cancel order {}: {}", order.getClientOrderId(), e.getMessage());
                    errorEventService.high("FailSafe", "Failed to cancel order " + order.getClientOrderId() + ": " + e.getMessage(), e);
                }
            }
            if (!pendingOrders.isEmpty()) {
                alertService.systemAlert("🚨 FailSafe: Cancelled " + pendingOrders.size() + " pending limit orders");
            }
        } catch (Exception e) {
            log.error("[FailSafe] Order cancellation sweep failed: {}", e.getMessage());
            errorEventService.critical("FailSafe", "Order cancellation sweep failed: " + e.getMessage(), e);
        }

        List<String> failures = new java.util.ArrayList<>();
        for (TradeEntity trade : openTrades) {
            try {
                // Use live market price, not stale entry price
                BigDecimal exitPrice = marketDataService.quote(trade.getInstrumentKey())
                        .map(q -> q.lastPrice())
                        .filter(p -> p != null && p.signum() > 0)
                        .orElse(trade.getEntryPrice()); // fallback to entry price if no quote
                executionEngine.closeTrade(trade.getTradeId(), exitPrice, "FailSafe square-off 15:20");
                log.warn("[FailSafe] Force-closed: tradeId={} instrument={} exitPrice={}", trade.getTradeId(), trade.getInstrumentKey(), exitPrice);
            } catch (Exception e) {
                log.error("[FailSafe] Failed to close trade {}: {}", trade.getTradeId(), e.getMessage());
                errorEventService.critical("FailSafe", "Failed to close trade " + trade.getTradeId() + ": " + e.getMessage(), e);
                failures.add(trade.getTradeId() + " (" + trade.getInstrumentKey() + "): " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            alertService.systemAlert("🚨 CRITICAL: FailSafe partial failure! " + failures.size()
                    + " trades NOT closed:\n" + String.join("\n", failures));
            log.error("[FailSafe] Partial square-off failure: {} trades not closed: {}", failures.size(), failures);
        }
        if (schedulerRegistry != null) schedulerRegistry.recordRun("failSafe");
    }
}
