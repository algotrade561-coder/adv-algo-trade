package com.algo.trade.commodity;

import org.springframework.stereotype.Component;

/**
 * Builds a {@link CrudeContext} snapshot from the live {@link OilPriceTracker}.
 *
 * <p>Single read entry-point so all consumers (CSV recorder today; possibly strategies later)
 * receive a consistent, atomic view of crude signals.
 */
@Component
public class CrudeContextProvider {

    private final OilPriceTracker tracker;

    public CrudeContextProvider(OilPriceTracker tracker) {
        this.tracker = tracker;
    }

    /**
     * Returns the current crude snapshot or {@link CrudeContext#unavailable()} when no tick has arrived.
     */
    public CrudeContext current() {
        if (!tracker.isDataAvailable()) {
            return CrudeContext.unavailable();
        }
        return new CrudeContext(
                true,
                tracker.getCurrentPriceINR(),
                tracker.getCurrentPriceUSD(),
                tracker.getPreviousDayCloseINR(),
                tracker.getTodayOpenINR(),
                tracker.getDailyChangePct(),
                tracker.getOvernightGapPct(),
                tracker.getLast30MinChangePct(),
                tracker.getRegime(),
                tracker.getMomentum(),
                tracker.isOvernightShock(CrudeContext.OVERNIGHT_SHOCK_THRESHOLD_PCT),
                tracker.isIntradayShock(CrudeContext.INTRADAY_SHOCK_THRESHOLD_PCT)
        );
    }
}
