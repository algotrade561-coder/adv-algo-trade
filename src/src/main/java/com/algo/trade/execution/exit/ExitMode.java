package com.algo.trade.execution.exit;

/**
 * How stop-loss and target percentages are derived at runtime.
 * <ul>
 *   <li>{@link #CONFIG} — strategy / global config only</li>
 *   <li>{@link #ATR} — volatility-adjusted only (when candle data exists)</li>
 *   <li>{@link #HYBRID} — SL uses the wider of config vs ATR; target uses the tighter</li>
 * </ul>
 */
public enum ExitMode {
    CONFIG,
    ATR,
    HYBRID;

    public static ExitMode fromString(String value) {
        if (value == null || value.isBlank()) {
            return HYBRID;
        }
        try {
            return ExitMode.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return HYBRID;
        }
    }
}
