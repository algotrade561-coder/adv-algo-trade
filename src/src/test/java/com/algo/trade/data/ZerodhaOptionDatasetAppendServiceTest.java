package com.algo.trade.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.TradingMode;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.InstrumentCache;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ZerodhaOptionDatasetAppendServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void appendsByContractFileUsingGlobalDatafeedsContractName() throws Exception {
        BrokerClient brokerClient = mock(BrokerClient.class);
        Instrument instrument = new Instrument(
                1L,
                "NFO",
                "NIFTY2642124450CE",
                "NIFTY",
                Optional.of(UnderlyingSymbol.NIFTY),
                Optional.of(LocalDate.of(2026, 4, 21)),
                Optional.of(new BigDecimal("24450.0")),
                Optional.of(OptionType.CE),
                75,
                new BigDecimal("0.05"),
                true
        );
        when(brokerClient.downloadInstruments()).thenReturn(List.of(instrument));
        when(brokerClient.historicalCandles(any(HistoricalDataRequest.class))).thenReturn(List.of(
                new Candle("NFO:NIFTY2642124450CE", Instant.parse("2026-04-17T03:45:00Z"),
                        Timeframe.ONE_MINUTE, new BigDecimal("100.00"), new BigDecimal("101.00"),
                        new BigDecimal("99.00"), new BigDecimal("100.50"), 1000L, 10L)
        ));
        InstrumentCache instrumentCache = new InstrumentCache(brokerClient);
        TradingProperties properties = new TradingProperties(TradingMode.PAPER, false,
                ZoneId.of("Asia/Kolkata"), null, null, null, null, null, null, null, null, null);
        ZerodhaOptionDatasetAppendService service =
                new ZerodhaOptionDatasetAppendService(brokerClient, instrumentCache, properties);

        service.append(new ZerodhaOptionDatasetAppendService.AppendRequest(
                UnderlyingSymbol.NIFTY,
                Timeframe.ONE_MINUTE,
                LocalDate.of(2026, 4, 17),
                LocalDate.of(2026, 4, 17),
                tempDir.toString(),
                LocalDate.of(2026, 4, 21),
                List.of(OptionType.CE),
                new BigDecimal("24400"),
                new BigDecimal("24500"),
                null,
                null,
                null,
                false
        ));

        Path contractPath = tempDir.resolve("by-contract").resolve("NIFTY21APR2624450CE.NFO.csv");
        assertThat(contractPath).exists();
        assertThat(Files.readAllLines(contractPath)).contains(
                "2026-04-17T03:45:00Z,NIFTY21APR2624450CE.NFO,ONE_MINUTE,100.00,101.00,99.00,100.50,1000,10"
        );
    }
}
