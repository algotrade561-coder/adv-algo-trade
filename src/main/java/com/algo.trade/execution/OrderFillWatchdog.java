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

    public OrderFillWatchdog(OrderRepository orderRepository, BrokerClient brokerClient,
                             ExecutionEngine executionEngine) {
        this.orderRepository = orderRepository;
        this.brokerClient = brokerClient;
        this.executionEngine = executionEngine;
    }

    @Scheduled(fixedDelay = 2000, initialDelay = 5000)
    public void checkPendingOrders() {
        List<OrderEntity> pending = orderRepository.findByStatusIn(
                List.of(OrderStatus.OPEN, OrderStatus.NEW));
        if (pending.isEmpty()) return;

        log.debug("OrderFillWatchdog checking {} pending orders", pending.size());

        for (OrderEntity order : pending) {
            try {
                checkOrder(order);
            } catch (Exception ex) {
                log.warn("OrderFillWatchdog failed for order {}: {}", order.getClientOrderId(), ex.getMessage());
            }
        }
    }

    private static final long MAX_FILL_WAIT_MINUTES = 5; // auto-cancel after 5 minutes

    private void checkOrder(OrderEntity order) {
        String brokerOrderId = order.getBrokerOrderId();
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            log.debug("OrderFillWatchdog skipping order without broker ID: {}", order.getClientOrderId());
            return;
        }

        // Auto-cancel stale unfilled orders after MAX_FILL_WAIT_MINUTES
        if (order.getOrderPlacedAt() != null) {
            long waitMinutes = java.time.Duration.between(order.getOrderPlacedAt(), java.time.Instant.now()).toMinutes();
            if (waitMinutes >= MAX_FILL_WAIT_MINUTES) {
                log.warn("OrderFillWatchdog: order expired after {}min — cancelling: clientOrderId={}, brokerOrderId={}",
                        waitMinutes, order.getClientOrderId(), brokerOrderId);
                try {
                    brokerClient.cancelOrder(brokerOrderId);
                } catch (Exception ex) {
                    log.warn("OrderFillWatchdog: cancel failed for {}: {}", brokerOrderId, ex.getMessage());
                }
                order.setStatus(OrderStatus.CANCELLED);
                orderRepository.save(order);
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
                executionEngine.openTradeFromFilledOrder(order);
            }

        } else if (latest.status() == OrderStatus.REJECTED || latest.status() == OrderStatus.CANCELLED) {
            log.info("OrderFillWatchdog: order terminal — clientOrderId={}, status={}, reason={}",
                    order.getClientOrderId(), latest.status(), latest.rejectionReason().orElse(""));
            order.setStatus(latest.status());
            order.setUpdatedAt(latest.updatedAt());
            orderRepository.save(order);
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
