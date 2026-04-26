package com.algo.trade.domain;

import java.time.DayOfWeek;

/**
 * Supported index option instruments with lot sizes, strike intervals,
 * expiry days, and exchange info.
 *
 * Lot sizes updated per NSE circular effective Jan 2026.
 * Expiry schedule updated per NSE circular effective Sep 1, 2025:
 *   All NSE indices → Tuesday. BSE SENSEX → Thursday.
 */
public enum IndexType {

    NIFTY("NIFTY", "NFO", 65, 50, DayOfWeek.TUESDAY, 256265L),
    BANKNIFTY("BANKNIFTY", "NFO", 30, 100, DayOfWeek.TUESDAY, 260105L),
    SENSEX("SENSEX", "BFO", 20, 100, DayOfWeek.THURSDAY, 265L),
    FINNIFTY("FINNIFTY", "NFO", 60, 50, DayOfWeek.TUESDAY, 257801L),
    MIDCPNIFTY("MIDCPNIFTY", "NFO", 120, 25, DayOfWeek.TUESDAY, 288009L);

    private final String underlyingSymbol;
    private final String exchange;
    private final int lotSize;
    private final int strikeInterval;
    private final DayOfWeek expiryDay;
    private final long spotToken; // Kite instrument token for spot price

    IndexType(String underlyingSymbol, String exchange, int lotSize,
              int strikeInterval, DayOfWeek expiryDay, long spotToken) {
        this.underlyingSymbol = underlyingSymbol;
        this.exchange = exchange;
        this.lotSize = lotSize;
        this.strikeInterval = strikeInterval;
        this.expiryDay = expiryDay;
        this.spotToken = spotToken;
    }

    public String underlyingSymbol() { return underlyingSymbol; }
    public String exchange() { return exchange; }
    public int lotSize() { return lotSize; }
    public int strikeInterval() { return strikeInterval; }
    public DayOfWeek expiryDay() { return expiryDay; }
    public long spotToken() { return spotToken; }
    public boolean isBSE() { return "BFO".equals(exchange); }

    /** Round a price to the nearest ATM strike. */
    public int roundToATM(double price) {
        return (int)(Math.round(price / strikeInterval) * strikeInterval);
    }

    /** Map from UnderlyingSymbol to IndexType. */
    public static IndexType from(UnderlyingSymbol symbol) {
        return switch (symbol) {
            case NIFTY -> NIFTY;
            case BANKNIFTY -> BANKNIFTY;
            default -> throw new IllegalArgumentException("No IndexType mapping for " + symbol);
        };
    }
}
