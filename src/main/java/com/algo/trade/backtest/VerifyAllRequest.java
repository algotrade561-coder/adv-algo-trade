package com.algo.trade.backtest;

import com.algo.trade.domain.UnderlyingSymbol;

import java.time.LocalDate;

/**
 * Request for the comprehensive verify-all endpoint.
 * Defaults: last 3 months of data for NIFTY.
 */
public record VerifyAllRequest(
        LocalDate from,
        LocalDate to,
        UnderlyingSymbol underlying
) {

    /** Default: 3 months before today → today, NIFTY. */
    public static VerifyAllRequest defaults() {
        LocalDate today = LocalDate.now();
        return new VerifyAllRequest(today.minusMonths(3), today, UnderlyingSymbol.NIFTY);
    }

    /** Fill in nulls with sensible defaults. */
    public VerifyAllRequest withDefaults() {
        LocalDate effectiveTo = to != null ? to : LocalDate.now();
        LocalDate effectiveFrom = from != null ? from : effectiveTo.minusMonths(3);
        UnderlyingSymbol effectiveUnderlying = underlying != null ? underlying : UnderlyingSymbol.NIFTY;
        return new VerifyAllRequest(effectiveFrom, effectiveTo, effectiveUnderlying);
    }
}
