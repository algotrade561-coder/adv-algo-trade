package com.algo.trade.strategy;

/**
 * All supported strategy types.
 * BUYING strategies are enabled by default.
 * SELLING strategies are disabled by default — require explicit user opt-in.
 */
public enum StrategyType {

    // ── Option Buying (enabled by default) ───────────────────────────────────
    DIRECTIONAL_BUY("Directional Buy",
            "VWAP + breakout + volume + OI + IV rank + RSI momentum. Buys ATM CE/PE on confirmed directional moves. Score-based entry (min 70%).", false),
    VOLATILITY_BREAKOUT("Volatility Breakout",
            "Buys when IV rank is low (< 50) and price breaks out of a tight range. 15-min candle evaluation, 60-min max hold.", false),
    EVENT_DRIVEN_BUY("Event-Driven Buy",
            "Buy straddle 1-2 days before RBI MPC dates when IV is low. Calendar-driven — only fires near scheduled events.", false),
    SCALPING("Scalping",
            "EMA 5/13 crossover on 5-min candles with momentum confirmation. Quick entries, 15-min max hold.", false),
    GAP_AND_GO("Gap & Go",
            "Buys CE/PE in the first 25 min (09:20-09:45) when the opening candle shows a strong gap + body + volume confirmation.", false),
    REVERSAL_BUY("Reversal Buy",
            "RSI(14) mean reversion on 15-min candles. Buys CE when oversold, PE when overbought — fades extreme intraday moves.", false),
    OI_SHIFT_TRAP("OI Shift Trap",
            "Detects heavy OI concentration near spot price. When writers are trapped (OI unwinding + spot approaching), buys in squeeze direction.", false),
    EXPIRY_GAMMA("Expiry Gamma",
            "Expiry day only (13:00-14:30). Buys ATM in momentum direction when gamma is high (> 0.008) and spot moved 0.10%+ in 15 min.", false),
    EXPIRY_REVERSAL("Expiry Reversal",
            "Near expiry (0-1 DTE, 10:30-14:00). Fades sharp 0.50%+ intraday spikes — buys opposite direction expecting pin to high-OI strike.", false),

    MOMENTUM("Momentum",
            "Rate-of-change (0.25%+ over 5-min candles) with acceleration confirmation and EMA trend alignment. Rides sustained moves.", false),

    // ── Spread Strategies (involve selling legs — DISABLED by default) ─────────
    BULL_CALL_SPREAD("Bull Call Spread",
            "Buy ATM CE + Sell OTM CE. Entry on EMA9 > EMA21 (bullish). All legs placed with safety guarantees.", true),
    BEAR_PUT_SPREAD("Bear Put Spread",
            "Buy ATM PE + Sell OTM PE. Entry on EMA9 < EMA21 (bearish). All legs placed with safety guarantees.", true),
    LONG_STRADDLE("Long Straddle",
            "Buy ATM CE + PE simultaneously. Entry when IV rank is low (cheap premium). Profits from big moves either direction.", false),
    LONG_STRANGLE("Long Strangle",
            "Buy OTM CE + PE (2 strikes out). Entry when IV rank is low. Cheaper than straddle, needs bigger move.", false),

    // ── Option Selling (DISABLED by default — requires explicit enable) ───────
    SHORT_STRADDLE("Short Straddle",
            "Sell ATM CE + PE. Entry when IV rank > 30. Profits from theta decay in range-bound markets.", true),
    SHORT_STRANGLE("Short Strangle",
            "Sell OTM CE + PE. Entry when IV rank > 50. Wider profit range than straddle.", true),
    IRON_CONDOR("Iron Condor",
            "Sell OTM CE + PE, buy further OTM wings as hedge. Entry when IV rank > 40 and MarketGuard safe. Defined risk, range-bound income.", true),
    BUTTERFLY("Butterfly",
            "Buy wings + sell middle (3 legs). Low cost, high reward if market stays near ATM strike.", true),
    CALENDAR_SPREAD("Calendar Spread",
            "Sell near expiry ATM + buy far expiry ATM. Theta decay play — near leg decays faster.", true),
    DIAGONAL_SPREAD("Diagonal Spread",
            "Buy far expiry ATM + sell near expiry OTM. Directional bias + theta income.", true),
    JADE_LIZARD("Jade Lizard",
            "Sell OTM CE + sell OTM PE + buy far OTM PE hedge. Premium collection with downside protection.", true),
    SYNTHETIC_FUTURES("Synthetic Futures",
            "Buy ATM CE + Sell ATM PE (bullish) or inverse (bearish). Replicates futures P&L with options. Entry on strong EMA divergence.", true),
    ITM_CONVICTION("ITM Conviction",
            "Compares ITM vs ATM option strength via ATP-LTP differential. Order flow signal — requires live WebSocket data.", false),

    OI_MOMENTUM("OI Momentum",
            "1-second live tracking with OI + PCR + Momentum confluence. Targets 15-20 trades/day. Detects 30-min breakouts, event spikes, and OI-confirmed momentum.", false);

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
                 CALENDAR_SPREAD, DIAGONAL_SPREAD, JADE_LIZARD, SYNTHETIC_FUTURES -> true;
            default -> false;
        };
    }
}
