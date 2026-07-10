package com.algo.trade.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.domain.Position;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PositionSynchronizerTest {

    private BrokerClient brokerClient;
    private TradeRepository tradeRepository;
    private KiteAccessTokenStore tokenStore;
    private com.algo.trade.marketdata.MarketDataService marketDataService;
    private PositionSynchronizer synchronizer;

    @BeforeEach
    void setUp() {
        brokerClient = mock(BrokerClient.class);
        tradeRepository = mock(TradeRepository.class);
        tokenStore = mock(KiteAccessTokenStore.class);
        marketDataService = mock(com.algo.trade.marketdata.MarketDataService.class);
        var telegramAlertService = mock(com.algo.trade.notification.TelegramAlertService.class);
        var errorEventService = mock(com.algo.trade.monitoring.ErrorEventService.class);
        synchronizer = new PositionSynchronizer(brokerClient, tradeRepository, mock(com.algo.trade.persistence.OrderRepository.class), tokenStore, marketDataService, telegramAlertService, errorEventService);
        // Inject SchedulerRegistry mock via reflection (field is @Autowired, not in constructor)
        var schedulerRegistry = mock(com.algo.trade.monitoring.SchedulerRegistry.class);
        when(schedulerRegistry.isEnabled(any())).thenReturn(true);
        try {
            var field = PositionSynchronizer.class.getDeclaredField("schedulerRegistry");
            field.setAccessible(true);
            field.set(synchronizer, schedulerRegistry);
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject SchedulerRegistry mock", e);
        }
        // Pin the late-day import cutoff clock to mid-session (10:00 IST) so the
        // "skip imports after 15:00" guard is deterministic regardless of run time.
        try {
            java.time.ZoneId ist = java.time.ZoneId.of("Asia/Kolkata");
            java.time.Instant tenAmIst = java.time.LocalDate.now(ist).atTime(10, 0).atZone(ist).toInstant();
            var clockField = PositionSynchronizer.class.getDeclaredField("importClock");
            clockField.setAccessible(true);
            clockField.set(synchronizer, java.time.Clock.fixed(tenAmIst, ist));
        } catch (Exception e) {
            throw new RuntimeException("Failed to inject importClock", e);
        }
    }

    @Test
    void skipsWhenBrokerSessionNotActive() {
        when(tokenStore.authenticated()).thenReturn(false);

        synchronizer.syncPositions();

        verifyNoInteractions(brokerClient);
        verifyNoInteractions(tradeRepository);
    }

    @Test
    void createsTradeForBrokerPositionNotInDb() {
        when(tokenStore.authenticated()).thenReturn(true);
        when(brokerClient.positions()).thenReturn(List.of(
                new Position("NIFTY26JAN24500CE", 75, BigDecimal.valueOf(120), BigDecimal.valueOf(130), BigDecimal.TEN)
        ));
        when(tradeRepository.findByStatus(TradeStatus.OPEN)).thenReturn(List.of());
        // Mock broker orders to return a matching untracked BUY order
        when(brokerClient.orders()).thenReturn(List.of(
                new com.algo.trade.domain.OrderResponse(
                        "client-1", java.util.Optional.of("broker-1"),
                        "NIFTY26JAN24500CE", com.algo.trade.domain.OrderSide.BUY,
                        com.algo.trade.domain.OrderStatus.COMPLETE, 75, 75,
                        java.util.Optional.of(BigDecimal.valueOf(120)),
                        java.util.Optional.empty(), Instant.now())
        ));

        synchronizer.syncPositions();

        ArgumentCaptor<TradeEntity> captor = ArgumentCaptor.forClass(TradeEntity.class);
        verify(tradeRepository).save(captor.capture());
        TradeEntity created = captor.getValue();
        assertThat(created.getInstrumentKey()).isEqualTo("NIFTY26JAN24500CE");
        assertThat(created.getQuantity()).isEqualTo(75);
        assertThat(created.getEntryPrice()).isEqualByComparingTo(BigDecimal.valueOf(120));
        assertThat(created.getStatus()).isEqualTo(TradeStatus.OPEN);
        assertThat(created.getEntryReason()).contains("position-sync");
        assertThat(created.getTradeId()).startsWith("SYNC-");
        // P0-2: synced trade must be owner-stamped (default user in single-user mode), never null.
        assertThat(created.getUserId()).isEqualTo(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID);
    }

    @Test
    void closesDbTradeNotInBrokerPositions() {
        when(tokenStore.authenticated()).thenReturn(true);
        when(brokerClient.positions()).thenReturn(List.of());
        when(brokerClient.orders()).thenReturn(List.of()); // No orders in history

        TradeEntity openTrade = new TradeEntity(
                "T-001", "NIFTY26JAN24500CE", "NIFTY", "CE",
                TradeStatus.OPEN, 75, BigDecimal.valueOf(100), Instant.now(), "test-entry"
        );
        when(tradeRepository.findByStatus(TradeStatus.OPEN)).thenReturn(List.of(openTrade));
        when(tradeRepository.findById("T-001")).thenReturn(java.util.Optional.of(openTrade));

        synchronizer.syncPositions();

        ArgumentCaptor<TradeEntity> captor = ArgumentCaptor.forClass(TradeEntity.class);
        verify(tradeRepository).save(captor.capture());
        TradeEntity closed = captor.getValue();
        assertThat(closed.getStatus()).isEqualTo(TradeStatus.CLOSED);
        assertThat(closed.getExitReason()).contains("position-sync");
    }

    @Test
    void matchingPositionsProduceNoChanges() {
        when(tokenStore.authenticated()).thenReturn(true);
        when(brokerClient.positions()).thenReturn(List.of(
                new Position("NIFTY26JAN24500CE", 75, BigDecimal.valueOf(120), BigDecimal.valueOf(130), BigDecimal.TEN)
        ));

        TradeEntity openTrade = new TradeEntity(
                "T-001", "NIFTY26JAN24500CE", "NIFTY", "CE",
                TradeStatus.OPEN, 75, BigDecimal.valueOf(120), Instant.now(), "test-entry"
        );
        when(tradeRepository.findByStatus(TradeStatus.OPEN)).thenReturn(List.of(openTrade));

        synchronizer.syncPositions();

        verify(tradeRepository, never()).save(any());
    }

    @Test
    void ignoresZeroQuantityBrokerPositions() {
        when(tokenStore.authenticated()).thenReturn(true);
        when(brokerClient.positions()).thenReturn(List.of(
                new Position("NIFTY26JAN24500CE", 0, BigDecimal.valueOf(120), BigDecimal.valueOf(130), BigDecimal.ZERO)
        ));
        when(tradeRepository.findByStatus(TradeStatus.OPEN)).thenReturn(List.of());

        synchronizer.syncPositions();

        verify(tradeRepository, never()).save(any());
    }

    @Test
    void extractUnderlyingFromInstrumentKey() {
        assertThat(PositionSynchronizer.extractUnderlying("NIFTY26JAN24500CE")).isEqualTo("NIFTY");
        assertThat(PositionSynchronizer.extractUnderlying("BANKNIFTY26FEB45000PE")).isEqualTo("BANKNIFTY");
        assertThat(PositionSynchronizer.extractUnderlying("")).isEqualTo("UNKNOWN");
        assertThat(PositionSynchronizer.extractUnderlying(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void extractOptionTypeFromInstrumentKey() {
        assertThat(PositionSynchronizer.extractOptionType("NIFTY26JAN24500CE")).isEqualTo("CE");
        assertThat(PositionSynchronizer.extractOptionType("BANKNIFTY26FEB45000PE")).isEqualTo("PE");
        assertThat(PositionSynchronizer.extractOptionType("NIFTY")).isEqualTo("UNKNOWN");
        assertThat(PositionSynchronizer.extractOptionType(null)).isEqualTo("UNKNOWN");
    }

    @Test
    void handlesExceptionFromBrokerGracefully() {
        when(tokenStore.authenticated()).thenReturn(true);
        when(brokerClient.positions()).thenThrow(new RuntimeException("connection refused"));

        // Should not throw
        synchronizer.syncPositions();

        verify(tradeRepository, never()).save(any());
    }
}
