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
        synchronizer = new PositionSynchronizer(brokerClient, tradeRepository, tokenStore, marketDataService, telegramAlertService);
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
    }

    @Test
    void closesDbTradeNotInBrokerPositions() {
        when(tokenStore.authenticated()).thenReturn(true);
        when(brokerClient.positions()).thenReturn(List.of());

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
