package com.kiteapioptions.backtest;

import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.Timeframe;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Reads backtest candle CSV files.
 */
public class CandleCsvReader {

    public List<Candle> read(Path path, Timeframe fallbackTimeframe) throws IOException {
        return Files.readAllLines(path).stream()
                .skip(1)
                .filter(line -> !line.isBlank())
                .map(line -> parse(line, fallbackTimeframe))
                .toList();
    }

    private Candle parse(String line, Timeframe fallbackTimeframe) {
        String[] columns = line.split(",", -1);
        if (columns.length < 8) {
            throw new IllegalArgumentException("Expected candle CSV columns: timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest");
        }
        Timeframe timeframe = columns[2].isBlank() ? fallbackTimeframe : Timeframe.valueOf(columns[2]);
        long openInterest = columns.length > 8 && !columns[8].isBlank() ? Long.parseLong(columns[8]) : 0L;
        return new Candle(columns[1], Instant.parse(columns[0]), timeframe, new BigDecimal(columns[3]),
                new BigDecimal(columns[4]), new BigDecimal(columns[5]), new BigDecimal(columns[6]),
                Long.parseLong(columns[7]), openInterest);
    }
}
