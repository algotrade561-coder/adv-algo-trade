package com.algo.trade.execution.exit;

/**
 * Single-leg trailing stop implementation to avoid duplicate exits.
 */
public enum TrailingMode {
    /** {@link com.algo.trade.execution.TrailingStopService} price-level trail */
    PRICE,
    /** Percent drawdown from peak via {@link com.algo.trade.strategy.DynamicExitManager} */
    ATR_PERCENT;

    public static TrailingMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return PRICE;
        }
        try {
            return TrailingMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return PRICE;
        }
    }
}
