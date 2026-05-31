package com.algo.trade.reporting;

import java.time.ZonedDateTime;

/** Thrown when a tuning report is requested during blocked market hours without force. */
public class MarketHoursBlockedException extends RuntimeException {

    private final ZonedDateTime nextEligible;

    public MarketHoursBlockedException(ZonedDateTime nextEligible) {
        super("Tuning report blocked during market hours (09:15–15:30 IST). Next eligible: " + nextEligible);
        this.nextEligible = nextEligible;
    }

    public ZonedDateTime nextEligible() {
        return nextEligible;
    }
}
