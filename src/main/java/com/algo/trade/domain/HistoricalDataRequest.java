package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.time.Instant;

public record HistoricalDataRequest(
        String instrumentKey,
        Instant from,
        Instant to,
        Timeframe timeframe,
        boolean includeOpenInterest
) {
    public HistoricalDataRequest {
        Validation.notBlank(instrumentKey, "instrumentKey");
        Validation.notNull(from, "from");
        Validation.notNull(to, "to");
        Validation.notNull(timeframe, "timeframe");
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
    }
}
