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

    NIFTY("NIFTY", "NFO", 65, 50, DayOfWeek.TUESDAY, true, 256265L),
    // BANKNIFTY weekly options were discontinued; monthly-only.
    BANKNIFTY("BANKNIFTY", "NFO", 30, 100, DayOfWeek.TUESDAY, false, 260105L),
    SENSEX("SENSEX", "BFO", 20, 100, DayOfWeek.THURSDAY, true, 265L),
    // FINNIFTY / MIDCPNIFTY weekly expiries can be discontinuous; treat as monthly-only unless explicitly enabled.
    FINNIFTY("FINNIFTY", "NFO", 60, 50, DayOfWeek.TUESDAY, false, 257801L),
    MIDCPNIFTY("MIDCPNIFTY", "NFO", 120, 25, DayOfWeek.TUESDAY, false, 288009L);

    private final String underlyingSymbol;
    private final String exchange;
    private final int lotSize;
    private final int strikeInterval;
    private final DayOfWeek expiryDay;
    private final boolean hasWeeklyExpiry;
    private final long spotToken; // Kite instrument token for spot price

    IndexType(String underlyingSymbol, String exchange, int lotSize,
              int strikeInterval, DayOfWeek expiryDay, boolean hasWeeklyExpiry, long spotToken) {
        this.underlyingSymbol = underlyingSymbol;
        this.exchange = exchange;
        this.lotSize = lotSize;
        this.strikeInterval = strikeInterval;
        this.expiryDay = expiryDay;
        this.hasWeeklyExpiry = hasWeeklyExpiry;
        this.spotToken = spotToken;
    }

    public String underlyingSymbol() { return underlyingSymbol; }
    public String exchange() { return exchange; }
    public int lotSize() { return lotSize; }
    public int strikeInterval() { return strikeInterval; }
    public DayOfWeek expiryDay() { return expiryDay; }
    public boolean hasWeeklyExpiry() { return hasWeeklyExpiry; }
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
            case SENSEX -> SENSEX;
            case FINNIFTY -> FINNIFTY;
            case MIDCPNIFTY -> MIDCPNIFTY;
        };
    }

    /** Resolve IndexType by underlying symbol name (case-insensitive). Falls back to NIFTY. */
    public static IndexType fromName(String name) {
        if (name == null) return NIFTY;
        for (IndexType it : values()) {
            if (it.underlyingSymbol().equalsIgnoreCase(name)) return it;
        }
        return NIFTY;
    }
}
