package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
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

    private void checkOrder(OrderEntity order) {
        String brokerOrderId = order.getBrokerOrderId();
        if (brokerOrderId == null || brokerOrderId.isBlank()) {
            log.debug("OrderFillWatchdog skipping order without broker ID: {}", order.getClientOrderId());
            return;
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

            executionEngine.openTradeFromFilledOrder(order);

        } else if (latest.status() == OrderStatus.REJECTED || latest.status() == OrderStatus.CANCELLED) {
            log.info("OrderFillWatchdog: order terminal — clientOrderId={}, status={}, reason={}",
                    order.getClientOrderId(), latest.status(), latest.rejectionReason().orElse(""));
            order.setStatus(latest.status());
            order.setUpdatedAt(latest.updatedAt());
            orderRepository.save(order);
        }
    }
}
