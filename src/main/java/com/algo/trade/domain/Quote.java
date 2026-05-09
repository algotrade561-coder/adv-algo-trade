package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Last traded market data snapshot for an instrument.
 */
public record Quote(
        String instrumentKey,
        Instant timestamp,
        BigDecimal lastPrice,
        long volume,
        long openInterest,
        Optional<BigDecimal> impliedVolatility,
        Optional<BigDecimal> bid,
        Optional<BigDecimal> ask,
        Optional<BigDecimal> averageTradedPrice
) {
    public Quote {
        Validation.notBlank(instrumentKey, "instrumentKey");
        Validation.notNull(timestamp, "timestamp");
        Validation.nonNegative(lastPrice, "lastPrice");
        if (volume < 0 || openInterest < 0) {
            throw new IllegalArgumentException("volume and openInterest must be non-negative");
        }
        impliedVolatility = impliedVolatility == null ? Optional.empty() : impliedVolatility;
        bid = bid == null ? Optional.empty() : bid;
        ask = ask == null ? Optional.empty() : ask;
        averageTradedPrice = averageTradedPrice == null ? Optional.empty() : averageTradedPrice;
    }

    /** Backward-compatible constructor without ATP. */
    public Quote(String instrumentKey, Instant timestamp, BigDecimal lastPrice,
                 long volume, long openInterest, Optional<BigDecimal> impliedVolatility,
                 Optional<BigDecimal> bid, Optional<BigDecimal> ask) {
        this(instrumentKey, timestamp, lastPrice, volume, openInterest,
                impliedVolatility, bid, ask, Optional.empty());
    }
}
