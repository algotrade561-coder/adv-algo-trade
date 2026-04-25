package com.algo.trade.strategy;

/**
 * All supported strategy types.
 * BUYING strategies are enabled by default.
 * SELLING strategies are disabled by default — require explicit user opt-in.
 */
public enum StrategyType {

    // ── Option Buying (enabled by default) ───────────────────────────────────
    DIRECTIONAL_BUY("Directional Buy",
            "VWAP + breakout + volume + OI + IV rank — the core backtested strategy", false),
    VOLATILITY_BREAKOUT("Volatility Breakout",
            "Bollinger Band squeeze breakout — buys when IV is cheap and market is coiled", false),
    EVENT_DRIVEN_BUY("Event-Driven Buy",
            "Buy straddle 1-2 days before RBI/budget/earnings when IV is low", false),
    SCALPING("Scalping",
            "EMA 9/21 crossover with 2-candle confirmation, 30-min max hold", false),

    // ── Spread Strategies (buying, defined risk) ──────────────────────────────
    BULL_CALL_SPREAD("Bull Call Spread",
            "Buy ATM CE + Sell OTM CE — net debit, capped risk and reward", false),
    BEAR_PUT_SPREAD("Bear Put Spread",
            "Buy ATM PE + Sell OTM PE — net debit, capped risk and reward", false),
    LONG_STRADDLE("Long Straddle",
            "Buy ATM CE + PE — profits from big moves in either direction", false),
    LONG_STRANGLE("Long Strangle",
            "Buy OTM CE + PE — cheaper than straddle, needs bigger move", false),

    // ── Option Selling (DISABLED by default — requires explicit enable) ───────
    SHORT_STRADDLE("Short Straddle",
            "Sell ATM CE + PE — profits from low volatility and time decay", true),
    SHORT_STRANGLE("Short Strangle",
            "Sell OTM CE + PE — wider profit range than straddle", true),
    IRON_CONDOR("Iron Condor",
            "Short strangle + hedges — defined risk selling, best in range-bound markets", true),
    BUTTERFLY("Butterfly",
            "Buy wings, sell middle — low cost, high reward if market stays near strike", true),
    CALENDAR_SPREAD("Calendar Spread",
            "Buy far expiry, sell near expiry — theta decay play", true),
    DIAGONAL_SPREAD("Diagonal Spread",
            "Buy far expiry ATM, sell near expiry OTM — directional + theta", true),
    JADE_LIZARD("Jade Lizard",
            "Sell OTM CE + PE, buy far OTM PE hedge — premium collection with downside protection", true),
    SYNTHETIC_FUTURES("Synthetic Futures",
            "Buy ATM CE + Sell ATM PE (or inverse) — replicate futures with options", false);

    private final String displayName;
    private final String description;
    private final boolean sellingStrategy;

    StrategyType(String displayName, String description, boolean sellingStrategy) {
        this.displayName = displayName;
        this.description = description;
        this.sellingStrategy = sellingStrategy;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
    public boolean isSellingStrategy() { return sellingStrategy; }
    public boolean isDefaultEnabled() { return !sellingStrategy; }
}
