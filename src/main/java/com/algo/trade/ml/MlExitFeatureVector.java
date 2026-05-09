package com.algo.trade.ml;

/**
 * Feature vector extracted from each exit evaluation for ML exit scoring.
 * Captures the market state at the moment the exit monitor evaluates a trade.
 * Used to train a model that predicts "should I exit now or hold?"
 */
public record MlExitFeatureVector(
        // Position state
        double profitPercent,          // current P&L %
        double peakProfitPercent,      // highest P&L % seen since entry
        double drawdownFromPeak,       // peakProfitPercent - profitPercent
        double holdMinutes,            // minutes since entry
        double entryPrice,             // entry premium
        double currentPrice,           // current premium

        // Market context
        double vixLevel,               // current VIX
        double atr,                    // 14-period ATR on underlying
        double daysToExpiry,           // DTE for the option

        // IV dynamics
        double entryIV,                // IV at entry time
        double currentIV,              // IV now
        double ivChangePercent,        // (currentIV - entryIV) / entryIV * 100

        // Trailing stop state
        double trailingStopActive,     // 1 if trailing stop has activated, 0 otherwise
        double trailingStopDistance,    // % distance from current price to trailing stop (0 if not active)

        // Strategy context
        double strategyType,           // encoded strategy type (0=DIRECTIONAL_BUY, 1=SCALPING, etc.)
        double optionType,             // 0=CE, 1=PE
        double underlying,             // 0=NIFTY, 1=BANKNIFTY, 2=SENSEX

        // Time context
        double minutesSinceOpen,       // minutes since 09:15
        double isExpiryDay,            // 1 if expiry day, 0 otherwise

        // Liquidity
        double bidAskSpreadPercent,    // bid-ask spread as % of premium

        // System exit decision
        double systemExitTriggered,    // 1 if system decided to exit this evaluation, 0 if hold
        double systemExitReason        // encoded exit reason (0=HOLD, 1=SL, 2=TARGET, 3=TRAILING, 4=TIME, etc.)
) {

    public static final String[] FEATURE_NAMES = {
            "profitPercent", "peakProfitPercent", "drawdownFromPeak", "holdMinutes",
            "entryPrice", "currentPrice",
            "vixLevel", "atr", "daysToExpiry",
            "entryIV", "currentIV", "ivChangePercent",
            "trailingStopActive", "trailingStopDistance",
            "strategyType", "optionType", "underlying",
            "minutesSinceOpen", "isExpiryDay",
            "bidAskSpreadPercent",
            "systemExitTriggered", "systemExitReason"
    };

    public double[] toArray() {
        return new double[]{
                profitPercent, peakProfitPercent, drawdownFromPeak, holdMinutes,
                entryPrice, currentPrice,
                vixLevel, atr, daysToExpiry,
                entryIV, currentIV, ivChangePercent,
                trailingStopActive, trailingStopDistance,
                strategyType, optionType, underlying,
                minutesSinceOpen, isExpiryDay,
                bidAskSpreadPercent,
                systemExitTriggered, systemExitReason
        };
    }

    public static int featureCount() { return FEATURE_NAMES.length; }
}
