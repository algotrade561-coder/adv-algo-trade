package com.algo.trade.execution;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderFillWatchdogTest {

    private final OrderRepository orderRepository = mock(OrderRepository.class);
    private final BrokerClient brokerClient = mock(BrokerClient.class);
    private final ExecutionEngine executionEngine = mock(ExecutionEngine.class);
    private OrderFillWatchdog watchdog;

    @BeforeEach
    void setUp() {
        watchdog = new OrderFillWatchdog(orderRepository, brokerClient, executionEngine);
    }

    @Test
    void doesNothingWhenNoPendingOrders() {
        when(orderRepository.findByStatusIn(any())).thenReturn(List.of());

        watchdog.checkPendingOrders();

        verify(brokerClient, never()).orderStatus(any());
        verify(executionEngine, never()).openTradeFromFilledOrder(any());
    }

    @Test
    void createsTradeWhenOrderFills() {
        OrderEntity pending = new OrderEntity("ENTRY-1", "BROKER-1", "NFO:NIFTY24APR24000CE",
                "BUY", OrderStatus.OPEN, 300, 0, null, null, Instant.now());
        when(orderRepository.findByStatusIn(any())).thenReturn(List.of(pending));
        when(brokerClient.orderStatus("BROKER-1")).thenReturn(Optional.of(new OrderResponse(
                "ENTRY-1", Optional.of("BROKER-1"), "NFO:NIFTY24APR24000CE", OrderSide.BUY,
                OrderStatus.COMPLETE, 300, 300, Optional.of(BigDecimal.valueOf(101)),
                Optional.empty(), Instant.now()
        )));

        watchdog.checkPendingOrders();

        verify(orderRepository).save(pending);
        verify(executionEngine).openTradeFromFilledOrder(pending);
    }

    @Test
    void updatesStatusWhenOrderRejected() {
        OrderEntity pending = new OrderEntity("ENTRY-2", "BROKER-2", "NFO:NIFTY24APR24000PE",
                "BUY", OrderStatus.OPEN, 65, 0, null, null, Instant.now());
        when(orderRepository.findByStatusIn(any())).thenReturn(List.of(pending));
        when(brokerClient.orderStatus("BROKER-2")).thenReturn(Optional.of(new OrderResponse(
                "ENTRY-2", Optional.of("BROKER-2"), "NFO:NIFTY24APR24000PE", OrderSide.BUY,
                OrderStatus.REJECTED, 65, 0, Optional.empty(),
                Optional.of("Insufficient margin"), Instant.now()
        )));

        watchdog.checkPendingOrders();

        verify(orderRepository).save(pending);
        verify(executionEngine, never()).openTradeFromFilledOrder(any());
    }

    @Test
    void skipsOrderStillOpen() {
        OrderEntity pending = new OrderEntity("ENTRY-3", "BROKER-3", "NFO:NIFTY24APR24000CE",
                "BUY", OrderStatus.OPEN, 300, 0, null, null, Instant.now());
        when(orderRepository.findByStatusIn(any())).thenReturn(List.of(pending));
        when(brokerClient.orderStatus("BROKER-3")).thenReturn(Optional.of(new OrderResponse(
                "ENTRY-3", Optional.of("BROKER-3"), "NFO:NIFTY24APR24000CE", OrderSide.BUY,
                OrderStatus.OPEN, 300, 0, Optional.empty(),
                Optional.empty(), Instant.now()
        )));

        watchdog.checkPendingOrders();

        verify(executionEngine, never()).openTradeFromFilledOrder(any());
        // Order entity should NOT be updated when still OPEN
        verify(orderRepository, never()).save(any());
    }

    @Test
    void skipsOrderWithoutBrokerOrderId() {
        OrderEntity pending = new OrderEntity("ENTRY-4", null, "NFO:NIFTY24APR24000CE",
                "BUY", OrderStatus.OPEN, 300, 0, null, null, Instant.now());
        when(orderRepository.findByStatusIn(any())).thenReturn(List.of(pending));

        watchdog.checkPendingOrders();

        verify(brokerClient, never()).orderStatus(any());
        verify(executionEngine, never()).openTradeFromFilledOrder(any());
    }
}
