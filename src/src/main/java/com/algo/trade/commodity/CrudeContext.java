package com.algo.trade.commodity;

/**
 * Read-only snapshot of MCX crude oil derived signals at the moment of a strategy evaluation.
 *
 * <p>Consumers must check {@link #available()} before trusting numeric fields — when the WS feed
 * has not yet delivered a tick (or the contract was never resolved), all numerics default to 0
 * and the booleans to {@code false}, which would otherwise be indistinguishable from a calm market.
 *
 * <p>Step 1 of the rollout: this record is written to {@code entry-signals.csv} on every signal
 * evaluation but is <strong>not</strong> wired into any entry filter or scoring path. After
 * collecting several trading days of data, consumers can join the columns back to outcome data
 * (P&amp;L, hit-rate) to decide whether crude actually adds signal.
 *
 * <ul>
 *   <li>{@code dailyChangePct} – (current − prevDayClose) / prevDayClose × 100</li>
 *   <li>{@code overnightGapPct} – (todayOpen − prevDayClose) / prevDayClose × 100 (close-to-open jump)</li>
 *   <li>{@code last30MinChangePct} – pct change over the last ~30 min, from the minute sampler</li>
 *   <li>{@code overnightShock} – {@code |overnightGapPct| ≥ 2.5}</li>
 *   <li>{@code intradayShock} – {@code |last30MinChangePct| ≥ 3.0}</li>
 *   <li>{@code regime} – LOW / NORMAL / HIGH / CRISIS by WTI USD/bbl bucket</li>
 *   <li>{@code momentum} – FALLING / STABLE / RISING / SPIKING</li>
 * </ul>
 */
public record CrudeContext(
        boolean available,
        double priceINR,
        double priceUSD,
        double previousDayCloseINR,
        double todayOpenINR,
        double dailyChangePct,
        double overnightGapPct,
        double last30MinChangePct,
        String regime,
        String momentum,
        boolean overnightShock,
        boolean intradayShock
) {

    public static final double OVERNIGHT_SHOCK_THRESHOLD_PCT = 2.5;
    public static final double INTRADAY_SHOCK_THRESHOLD_PCT = 3.0;

    /** Empty / unavailable snapshot — emitted when no tick has arrived yet. */
    public static CrudeContext unavailable() {
        return new CrudeContext(false, 0, 0, 0, 0, 0, 0, 0, "UNAVAILABLE", "UNAVAILABLE", false, false);
    }
}
