package com.algo.trade.domain;

/**
 * Zerodha order variety — determines the API endpoint and order lifecycle.
 *
 * <ul>
 *   <li>{@link #REGULAR} — Standard intraday/positional order (market hours only)</li>
 *   <li>{@link #AMO} — After Market Order (queued for next open)</li>
 * </ul>
 */
public enum OrderVariety {
    REGULAR,
    AMO;

    /** Zerodha API path segment for this variety. */
    public String apiPath() {
        return switch (this) {
            case AMO -> "/orders/amo";
            case REGULAR -> "/orders/regular";
        };
    }

    /** Tolerant parse — null/blank/unknown → REGULAR. */
    public static OrderVariety fromString(String s) {
        if (s == null || s.isBlank()) return REGULAR;
        try {
            return OrderVariety.valueOf(s.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return REGULAR;
        }
    }
}
