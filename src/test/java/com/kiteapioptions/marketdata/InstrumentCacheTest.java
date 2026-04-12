package com.kiteapioptions.marketdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.domain.Instrument;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.UnderlyingSymbol;
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
}
