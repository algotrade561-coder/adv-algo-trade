package com.algo.trade.marketdata;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.UnderlyingSymbol;
import org.junit.jupiter.api.Test;

class KiteInstrumentCsvParserTest {

    private final KiteInstrumentCsvParser parser = new KiteInstrumentCsvParser();

    @Test
    void parsesZerodhaOptionInstrumentCsv() {
        String csv = """
                instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
                12345,123,NIFTY24APR24000CE,NIFTY,0,2026-04-30,24000,0.05,75,CE,NFO-OPT,NFO
                """;

        var instruments = parser.parse(csv);

        assertThat(instruments).hasSize(1);
        var instrument = instruments.getFirst();
        assertThat(instrument.instrumentKey()).isEqualTo("NFO:NIFTY24APR24000CE");
        assertThat(instrument.underlying()).contains(UnderlyingSymbol.NIFTY);
        assertThat(instrument.optionType()).contains(OptionType.CE);
        assertThat(instrument.tradable()).isTrue();
    }

    @Test
    void fallsBackToTradingSymbolWhenNameIsBlank() {
        String csv = """
                instrument_token,exchange_token,tradingsymbol,name,last_price,expiry,strike,tick_size,lot_size,instrument_type,segment,exchange
                218804229,854704,BANKEX26APR63000PE,,0,2026-04-30,63000,0.05,30,PE,BFO-OPT,BFO
                """;

        var instruments = parser.parse(csv);

        assertThat(instruments).hasSize(1);
        var instrument = instruments.getFirst();
        assertThat(instrument.name()).isEqualTo("BANKEX26APR63000PE");
        assertThat(instrument.instrumentKey()).isEqualTo("BFO:BANKEX26APR63000PE");
        assertThat(instrument.optionType()).contains(OptionType.PE);
    }
}
