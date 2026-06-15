package com.algo.trade.marketdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class MarketDataServiceWarmupTest {

    @Test
    void previousTradingDaySkipsWeekend() {
        LocalDate monday = LocalDate.of(2026, 5, 18);
        assertEquals(LocalDate.of(2026, 5, 15), MarketDataService.previousTradingDay(monday));
    }

    @Test
    void warmupHistoricalRequestSpansPriorSession() {
        BrokerClient broker = Mockito.mock(BrokerClient.class);
        LiveInstrumentCache cache = Mockito.mock(LiveInstrumentCache.class);
        when(broker.historicalCandles(any())).thenReturn(List.of(
                new Candle("NSE:256265", Instant.now(), Timeframe.FIVE_MINUTE,
                        BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, 0, 0)));

        MarketDataService service = new MarketDataService(broker, cache);
        service.historicalCandles("NSE:256265", Timeframe.FIVE_MINUTE);

        ArgumentCaptor<HistoricalDataRequest> captor = ArgumentCaptor.forClass(HistoricalDataRequest.class);
        verify(broker).historicalCandles(captor.capture());
        HistoricalDataRequest req = captor.getValue();
        ZoneId ist = ZoneId.of("Asia/Kolkata");
        LocalDate today = LocalDate.now(ist);
        Instant expectedFrom = MarketDataService.previousTradingDay(today)
                .atTime(9, 15).atZone(ist).toInstant();
        assertEquals(expectedFrom, req.from());
    }
}
