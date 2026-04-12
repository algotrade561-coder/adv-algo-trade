package com.kiteapioptions.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.domain.Timeframe;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;

class CandleCsvReaderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsCandleCsv() throws Exception {
        Path csv = tempDir.resolve("candles.csv");
        Files.writeString(csv, """
                timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest
                2026-04-12T03:45:00Z,NFO:NIFTY-MOCK-ATM-CE,ONE_MINUTE,100.00,101.00,99.00,100.50,10000,100000
                """);

        var candles = new CandleCsvReader().read(csv, Timeframe.ONE_MINUTE);

        assertThat(candles).hasSize(1);
        assertThat(candles.getFirst().instrumentKey()).isEqualTo("NFO:NIFTY-MOCK-ATM-CE");
        assertThat(candles.getFirst().volume()).isEqualTo(10_000);
    }
}
