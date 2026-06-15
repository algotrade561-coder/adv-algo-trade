package com.algo.trade.domain;

import java.math.BigDecimal;

/** Equity segment margin availability from the broker. */
public record MarginSnapshot(
        BigDecimal availableCash,
        BigDecimal utilisedDebits,
        BigDecimal netAvailable
) {
    public MarginSnapshot {
        availableCash = availableCash == null ? BigDecimal.ZERO : availableCash;
        utilisedDebits = utilisedDebits == null ? BigDecimal.ZERO : utilisedDebits;
        netAvailable = netAvailable == null ? BigDecimal.ZERO : netAvailable;
    }
}
