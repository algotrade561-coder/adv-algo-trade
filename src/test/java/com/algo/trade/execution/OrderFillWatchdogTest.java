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
        when(executionEngine.findOpenTradesByInstrumentForUser("NFO:NIFTY24APR24000CE", null))
                .thenReturn(List.of());

        watchdog.checkPendingOrders();

        verify(orderRepository).save(pending);
        verify(executionEngine).openTradeFromFilledOrder(pending);
    }

    /**
     * Multi-user signal-copy: secondary fill on the same strike must not block primary trade creation.
     * (2026-07-02 incident: primary NIFTY CE sat unarmed for ~9 min because u:8's trade existed.)
     */
    @Test
    void createsSeparateTradesForDifferentUsersOnSameInstrument() {
        OrderEntity primary = new OrderEntity("ENTRY-P", "BROKER-P", "NFO:NIFTY2670724100CE",
                "BUY", OrderStatus.OPEN, 130, 0, null, null, Instant.now());
        primary.setUserId(1L);
        OrderEntity secondary = new OrderEntity("ENTRY-S", "BROKER-S", "NFO:NIFTY2670724100CE",
                "BUY", OrderStatus.OPEN, 130, 0, null, null, Instant.now());
        secondary.setUserId(8L);

        when(orderRepository.findByStatusIn(any())).thenReturn(List.of(primary, secondary));
        when(brokerClient.orderStatus("BROKER-P")).thenReturn(Optional.of(new OrderResponse(
                "ENTRY-P", Optional.of("BROKER-P"), "NFO:NIFTY2670724100CE", OrderSide.BUY,
                OrderStatus.COMPLETE, 130, 130, Optional.of(BigDecimal.valueOf(143.4)),
                Optional.empty(), Instant.now()
        )));
        when(brokerClient.orderStatus("BROKER-S")).thenReturn(Optional.of(new OrderResponse(
                "ENTRY-S", Optional.of("BROKER-S"), "NFO:NIFTY2670724100CE", OrderSide.BUY,
                OrderStatus.COMPLETE, 130, 130, Optional.of(BigDecimal.valueOf(143.4)),
                Optional.empty(), Instant.now()
        )));
        when(executionEngine.findOpenTradesByInstrumentForUser("NFO:NIFTY2670724100CE", 1L))
                .thenReturn(List.of());
        when(executionEngine.findOpenTradesByInstrumentForUser("NFO:NIFTY2670724100CE", 8L))
                .thenReturn(List.of());

        watchdog.checkPendingOrders();

        verify(executionEngine).openTradeFromFilledOrder(primary);
        verify(executionEngine).openTradeFromFilledOrder(secondary);
    }

    @Test
    void marksMaterializedWhenSameUserAlreadyHasOpenTradeOnInstrument() {
        OrderEntity pending = new OrderEntity("ENTRY-DUP", "BROKER-D", "NFO:NIFTY2670724100CE",
                "BUY", OrderStatus.OPEN, 130, 0, null, null, Instant.now());
        pending.setUserId(1L);
        var existing = new com.algo.trade.persistence.TradeEntity(
                "TRD-existing", "NFO:NIFTY2670724100CE", "NIFTY", "CE",
                com.algo.trade.domain.TradeStatus.OPEN, 130, BigDecimal.valueOf(140),
                Instant.now(), "prior");
        when(orderRepository.findByStatusIn(any())).thenReturn(List.of(pending));
        when(brokerClient.orderStatus("BROKER-D")).thenReturn(Optional.of(new OrderResponse(
                "ENTRY-DUP", Optional.of("BROKER-D"), "NFO:NIFTY2670724100CE", OrderSide.BUY,
                OrderStatus.COMPLETE, 130, 130, Optional.of(BigDecimal.valueOf(143.4)),
                Optional.empty(), Instant.now()
        )));
        when(executionEngine.findOpenTradesByInstrumentForUser("NFO:NIFTY2670724100CE", 1L))
                .thenReturn(List.of(existing));

        watchdog.checkPendingOrders();

        verify(executionEngine).markFilledOrderMaterialized(pending, "TRD-existing");
        verify(executionEngine, never()).openTradeFromFilledOrder(pending);
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
