package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;

public record Position(
        String instrumentKey,
        int quantity,
        BigDecimal averagePrice,
        BigDecimal lastPrice,
        BigDecimal unrealizedPnl,
        String productType   // MIS, CNC, NRML — null for backward compatibility
) {
    /** Backward-compatible constructor without productType. */
    public Position(String instrumentKey, int quantity, BigDecimal averagePrice,
                    BigDecimal lastPrice, BigDecimal unrealizedPnl) {
        this(instrumentKey, quantity, averagePrice, lastPrice, unrealizedPnl, null);
    }

    public Position {
        Validation.notBlank(instrumentKey, "instrumentKey");
        Validation.nonNegative(averagePrice, "averagePrice");
        Validation.nonNegative(lastPrice, "lastPrice");
        Validation.notNull(unrealizedPnl, "unrealizedPnl");
    }
}
