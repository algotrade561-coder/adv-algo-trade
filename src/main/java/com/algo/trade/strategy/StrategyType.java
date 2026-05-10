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
    GAP_AND_GO("Gap & Go",
            "Buys CE/PE in the first 30 min when the first session candle shows a strong directional gap — momentum continuation", false),
    REVERSAL_BUY("Reversal Buy",
            "RSI(14) mean reversion: buys CE when oversold (<30), PE when overbought (>70) — fades extreme intraday moves", false),
    OI_SHIFT_TRAP("OI Shift Trap",
            "Detects call/put writer exposure near the current price — approaching a heavy-OI strike triggers a short-cover squeeze", false),
    EXPIRY_GAMMA("Expiry Gamma",
            "Exploits ATM gamma on expiry day — buys in the direction of momentum between 13:00 and 14:30 for explosive premium moves", false),
    EXPIRY_REVERSAL("Expiry Reversal",
            "Near expiry (0-1 days), fades a sharp intraday spike — buys against the move expecting pin or reversal to key strike", false),

    MOMENTUM("Momentum",
            "Rate-of-change momentum with acceleration, EMA trend alignment, and volume confirmation — rides sustained directional moves", false),

    // ── Spread Strategies (involve selling legs — DISABLED by default) ─────────
    BULL_CALL_SPREAD("Bull Call Spread",
            "Buy ATM CE + Sell OTM CE — net debit, capped risk and reward", true),
    BEAR_PUT_SPREAD("Bear Put Spread",
            "Buy ATM PE + Sell OTM PE — net debit, capped risk and reward", true),
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
            "Buy ATM CE + Sell ATM PE (or inverse) — replicate futures with options", true),
    ITM_CONVICTION("ITM Conviction",
            "Compares ITM vs ATM option strength via ATP-LTP differential — order flow signal", false),

    PREMIUM_SCALP("Premium Scalp",
            "Intraday delta-neutral short premium mean reversion — sells ATM straddle when premium spikes above rolling average, buys back on contraction. Multiple re-entries per day.", true),

    BREAKOUT_REENTRY("Breakout Re-Entry",
            "Bollinger Band squeeze breakout with band-walk re-entries — buys ATM/ITM options on breakout above upper band with VWAP confirmation, re-enters on pullback to middle band. Max 3 entries per direction.", false);

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

    /** Multi-leg strategies managed by SpreadPositionExitMonitor — not by LivePositionExitMonitor. */
    public boolean isSpreadStrategy() {
        return switch (this) {
            case BULL_CALL_SPREAD, BEAR_PUT_SPREAD, LONG_STRADDLE, LONG_STRANGLE,
                 SHORT_STRADDLE, SHORT_STRANGLE, IRON_CONDOR, BUTTERFLY,
                 CALENDAR_SPREAD, DIAGONAL_SPREAD, JADE_LIZARD, SYNTHETIC_FUTURES,
                 PREMIUM_SCALP -> true;
            default -> false;
        };
    }
}
