package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * OHLCV candle used by live signal generation and backtesting.
 */
public record Candle(
        String instrumentKey,
        Instant timestamp,
        Timeframe timeframe,
        BigDecimal open,
        BigDecimal high,
        BigDecimal low,
        BigDecimal close,
        long volume,
        long openInterest
) {
    public Candle {
        Validation.notBlank(instrumentKey, "instrumentKey");
        Validation.notNull(timestamp, "timestamp");
        Validation.notNull(timeframe, "timeframe");
        Validation.nonNegative(open, "open");
        Validation.nonNegative(high, "high");
        Validation.nonNegative(low, "low");
        Validation.nonNegative(close, "close");
        if (volume < 0 || openInterest < 0) {
            throw new IllegalArgumentException("volume and openInterest must be non-negative");
        }
    }
}
