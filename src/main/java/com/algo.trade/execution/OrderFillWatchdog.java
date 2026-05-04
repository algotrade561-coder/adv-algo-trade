package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Background watchdog that polls broker for pending limit order fills.
 * Runs every 2 seconds. When a pending order transitions to COMPLETE,
 * it creates the TradeEntity via ExecutionEngine so exit monitors
 * (max hold, trailing stop, forced exit) can manage the position.
 */
@Service
public class OrderFillWatchdog {

    private static final Logger log = LoggerFactory.getLogger(OrderFillWatchdog.class);

    private final OrderRepository orderRepository;
    private final BrokerClient brokerClient;
    private final ExecutionEngine executionEngine;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    /** Prevents concurrent watchdog runs from creating duplicate trades. */
    private final java.util.concurrent.atomic.AtomicBoolean checkInProgress =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Tracks orders currently being processed to prevent duplicate handling. */
    private final java.util.Set<String> processingOrders = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public OrderFillWatchdog(OrderRepository orderRepository, BrokerClient brokerClient,
                             ExecutionEngine executionEngine) {
        this.orderRepository = orderRepository;
        this.brokerClient = brokerClient;
        this.executionEngine = executionEngine;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void checkPendingOrders() {
        if (!checkInProgress.compareAndSet(false, true)) {
            log.debug("OrderFillWatchdog: previous check still running, skipping");
            return;
        }
        if (schedulerRegistry != null) schedulerRegistry.recordRun("orderFillWatchdog");
        try {
            List<OrderEntity> pending = orderRepository.findByStatusIn(
                    List.of(OrderStatus.OPEN, OrderStatus.NEW));
            if (pending.isEmpty()) {
                // Safety net: check for COMPLETE BUY orders that have no matching trade
                reconcileOrphanedFilledOrders();
                return;
            }

            log.debug("OrderFillWatchdog checking {} pending orders", pending.size());

            for (OrderEntity order : pending) {
                try {
                    checkOrder(order);
                } catch (Exception ex) {
                    log.warn("OrderFillWatchdog failed for order {}: {}", order.getClientOrderId(), ex.getMessage());
                }
            }
        } finally {
            checkInProgress.set(false);
        }
    }

    /**
     * Safety net: find COMPLETE BUY orders from today that have no matching TradeEntity.
     * Only creates a trade if the broker still has an open position for that instrument.
     */
    private void reconcileOrphanedFilledOrders() {
        try {
            java.time.Instant todayStart = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                    .atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant();
            List<OrderEntity> filledToday = orderRepository.findBySideAndUpdatedAtBetween(
                    com.algo.trade.domain.OrderSide.BUY.name(), todayStart, java.time.Instant.now());

            if (filledToday.stream().noneMatch(o -> o.getStatus() == OrderStatus.COMPLETE && o.getFilledQuantity() > 0)) {
                return; // nothing to reconcile
            }

            // Fetch current broker positions to verify the position still exists
            List<com.algo.trade.domain.Position> brokerPositions;
            try {
                brokerPositions = brokerClient.positions();
            } catch (Exception ex) {
                return; // can't verify — skip reconciliation
            }
            java.util.Set<String> activeInstruments = brokerPositions.stream()
                    .filter(p -> p.quantity() > 0)
                    .map(com.algo.trade.domain.Position::instrumentKey)
                    .collect(java.util.stream.Collectors.toSet());

            for (OrderEntity order : filledToday) {
                if (order.getStatus() != OrderStatus.COMPLETE) continue;
                if (order.getClientOrderId().startsWith("EXIT-")) continue;
                if (order.getFilledQuantity() <= 0) continue;
                if (order.getAverageFillPrice() == null || order.getAverageFillPrice().signum() <= 0) continue;

                String instrumentKey = order.getInstrumentKey();

                // Only create trade if broker still has an open position for this instrument
                if (!activeInstruments.contains(instrumentKey)) continue;

                // Check if a trade already exists for this instrument
                boolean tradeExists = !executionEngine.findOpenTradesByInstrument(instrumentKey).isEmpty();

                if (!tradeExists) {
                    log.warn("OrderFillWatchdog: orphaned filled order — creating trade: clientOrderId={}, instrument={}, price={}",
                            order.getClientOrderId(), instrumentKey, order.getAverageFillPrice());
                    executionEngine.openTradeFromFilledOrder(order);
                }
            }
        } catch (Exception ex) {
            log.warn("OrderFillWatchdog reconciliation failed: {}", ex.getMessage());
        }
    }

    private static final long MAX_FILL_WAIT_MINUTES = 1; // fallback if GlobalConfig unavailable

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.config.GlobalConfigService globalConfigService;

    private long getMaxFillWaitMinutes() {
        return globalConfigService != null ? globalConfigService.getLimitOrderCancelMinutes() : MAX_FILL_WAIT_MINUTES;
    }

    private void checkOrder(OrderEntity order) {
        String brokerOrderId = order.getBrokerOrderId();
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            log.debug("OrderFillWatchdog skipping order without broker ID: {}", order.getClientOrderId());
            return;
        }

        // Auto-cancel stale unfilled orders after MAX_FILL_WAIT_MINUTES
        if (order.getOrderPlacedAt() != null) {
            long waitMinutes = java.time.Duration.between(order.getOrderPlacedAt(), java.time.Instant.now()).toMinutes();
            if (waitMinutes >= getMaxFillWaitMinutes()) {
                log.warn("OrderFillWatchdog: order expired after {}min — cancelling: clientOrderId={}, brokerOrderId={}",
                        waitMinutes, order.getClientOrderId(), brokerOrderId);
                try {
                    brokerClient.cancelOrder(brokerOrderId);
                } catch (Exception ex) {
                    log.warn("OrderFillWatchdog: cancel failed for {}: {}", brokerOrderId, ex.getMessage());
                }
                order.setStatus(OrderStatus.CANCELLED);
                orderRepository.save(order);
                // Release entry gate — the order is dead, allow new entries
                executionEngine.releaseEntryInFlightGate();
                return;
            }
        }

        Optional<OrderResponse> polled = brokerClient.orderStatus(brokerOrderId);
        if (polled.isEmpty()) {
            log.debug("OrderFillWatchdog: no status returned for brokerOrderId={}", brokerOrderId);
            return;
        }

        OrderResponse latest = polled.get();

        if (latest.status() == OrderStatus.COMPLETE && latest.filledQuantity() > 0) {
            log.info("OrderFillWatchdog: order filled! clientOrderId={}, brokerOrderId={}, filledQty={}, avgPrice={}",
                    order.getClientOrderId(), brokerOrderId, latest.filledQuantity(),
                    latest.averageFillPrice().orElse(null));

            order.setStatus(OrderStatus.COMPLETE);
            order.setFilledQuantity(latest.filledQuantity());
            order.setAverageFillPrice(latest.averageFillPrice().orElse(null));
            order.setUpdatedAt(latest.updatedAt());
            orderRepository.save(order);

            // Only create TradeEntity for BUY (entry) orders.
            // SELL (exit) orders need to close the existing trade instead.
            if (order.getClientOrderId().startsWith("EXIT-")) {
                closeTradeFromFilledExitOrder(order);
            } else {
                // Guard against duplicate trade creation if watchdog runs twice before DB commits
                if (!processingOrders.add(order.getClientOrderId())) {
                    log.debug("OrderFillWatchdog: order {} already being processed, skipping", order.getClientOrderId());
                    return;
                }
                try {
                    // Double-check: does a trade already exist for this instrument?
                    if (!executionEngine.findOpenTradesByInstrument(order.getInstrumentKey()).isEmpty()) {
                        log.info("OrderFillWatchdog: trade already exists for instrument {} — skipping duplicate creation",
                                order.getInstrumentKey());
                        return;
                    }
                    executionEngine.openTradeFromFilledOrder(order);
                } finally {
                    processingOrders.remove(order.getClientOrderId());
                }
            }

        } else if (latest.status() == OrderStatus.REJECTED || latest.status() == OrderStatus.CANCELLED) {
            log.info("OrderFillWatchdog: order terminal — clientOrderId={}, status={}, reason={}",
                    order.getClientOrderId(), latest.status(), latest.rejectionReason().orElse(""));
            order.setStatus(latest.status());
            order.setUpdatedAt(latest.updatedAt());
            orderRepository.save(order);
            // Release entry gate — the order is dead, allow new entries
            executionEngine.releaseEntryInFlightGate();
        }
    }

    /**
     * Close the matching open trade when a pending SELL (exit) order fills.
     * Finds the open trade by instrument key and computes realized P&L.
     */
    private void closeTradeFromFilledExitOrder(OrderEntity exitOrder) {
        String instrumentKey = exitOrder.getInstrumentKey();
        BigDecimal exitPrice = exitOrder.getAverageFillPrice() != null
                ? exitOrder.getAverageFillPrice() : BigDecimal.ZERO;

        // Find the open trade for this instrument
        var openTrades = executionEngine.findOpenTradesByInstrument(instrumentKey);
        if (openTrades.isEmpty()) {
            log.warn("OrderFillWatchdog: exit order filled but no open trade found for instrument {}",
                    instrumentKey);
            return;
        }

        var trade = openTrades.getFirst();
        BigDecimal realizedPnl = exitPrice.subtract(trade.getEntryPrice())
                .multiply(BigDecimal.valueOf(trade.getQuantity()));
        trade.close(exitPrice, exitOrder.getUpdatedAt(), realizedPnl, "Watchdog: exit order filled");
        // Save via executionEngine's repository access
        executionEngine.saveTradeEntity(trade);

        log.info("OrderFillWatchdog: closed trade from exit fill — tradeId={}, instrument={}, exitPrice={}, pnl={}",
                trade.getTradeId(), instrumentKey, exitPrice, realizedPnl);
    }
}
