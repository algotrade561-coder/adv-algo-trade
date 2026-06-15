package com.algo.trade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class InstrumentCacheTest {

    @Test
    void refreshesAndFindsOptionContract() {
        BrokerClient brokerClient = mock(BrokerClient.class);
        LocalDate expiry = LocalDate.of(2026, 4, 30);
        Instrument option = new Instrument(1, "NFO", "NIFTY24APR24000CE", "NIFTY",
                Optional.of(UnderlyingSymbol.NIFTY), Optional.of(expiry), Optional.of(BigDecimal.valueOf(24_000)),
                Optional.of(OptionType.CE), 75, BigDecimal.valueOf(0.05), true);
        when(brokerClient.downloadInstruments()).thenReturn(List.of(option));

        InstrumentCache cache = new InstrumentCache(brokerClient);
        cache.refresh();

        assertThat(cache.nearestExpiry(UnderlyingSymbol.NIFTY, LocalDate.of(2026, 4, 12))).contains(expiry);
        assertThat(cache.findOption(UnderlyingSymbol.NIFTY, expiry, BigDecimal.valueOf(24_000), OptionType.CE))
                .contains(option);
    }

    @Test
    void prefersWeeklyExpiryWhenConfigured() {
        BrokerClient brokerClient = mock(BrokerClient.class);
        Instrument monthly = new Instrument(1, "NFO", "NIFTY26APR24450CE", "NIFTY",
                Optional.of(UnderlyingSymbol.NIFTY), Optional.of(LocalDate.of(2026, 4, 30)),
                Optional.of(BigDecimal.valueOf(24_450)), Optional.of(OptionType.CE), 65,
                BigDecimal.valueOf(0.05), true);
        Instrument weekly = new Instrument(2, "NFO", "NIFTY2650524450CE", "NIFTY",
                Optional.of(UnderlyingSymbol.NIFTY), Optional.of(LocalDate.of(2026, 5, 5)),
                Optional.of(BigDecimal.valueOf(24_450)), Optional.of(OptionType.CE), 65,
                BigDecimal.valueOf(0.05), true);
        Instrument mayMonthly = new Instrument(3, "NFO", "NIFTY26MAY24450CE", "NIFTY",
                Optional.of(UnderlyingSymbol.NIFTY), Optional.of(LocalDate.of(2026, 5, 28)),
                Optional.of(BigDecimal.valueOf(24_450)), Optional.of(OptionType.CE), 65,
                BigDecimal.valueOf(0.05), true);
        when(brokerClient.downloadInstruments()).thenReturn(List.of(monthly, weekly, mayMonthly));

        InstrumentCache cache = new InstrumentCache(brokerClient);
        cache.refresh();

        assertThat(cache.nearestExpiry(UnderlyingSymbol.NIFTY, LocalDate.of(2026, 4, 22), "NEAREST_WEEKLY"))
                .contains(LocalDate.of(2026, 5, 5));
        assertThat(cache.nearestExpiry(UnderlyingSymbol.NIFTY, LocalDate.of(2026, 4, 22), "NEAREST_MONTHLY"))
                .contains(LocalDate.of(2026, 4, 30));
    }
}
