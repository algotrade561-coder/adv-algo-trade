package com.algo.trade.domain;

import java.time.LocalDate;

/**
 * A single option contract enriched with live market data from the tick feed.
 * Updated on every tick via InstrumentCache.
 */
public class OptionInstrument {

    private final long instrumentToken;
    private final String tradingSymbol;
    private final String exchange;
    private final IndexType indexType;
    private final int strikePrice;
    private final String optionType; // CE or PE
    private final LocalDate expiry;
    private final int lotSize;

    // Live market data — updated from tick feed
    private volatile double lastPrice;
    private volatile double openPrice;
    private volatile double highPrice;
    private volatile double lowPrice;
    private volatile double closePrice;
    private volatile long volume;
    private volatile long openInterest;
    private volatile long prevOpenInterest;
    private volatile double bestBid;
    private volatile double bestAsk;
    private volatile long bestBidQty;
    private volatile long bestAskQty;
    
    // 5-minute high/low tracking (for chain snapshots)
    private volatile double high5m;
    private volatile double low5m;
    private volatile long last5mResetMs;

    // Greeks — computed from Black-Scholes
    private volatile double impliedVolatility;
    private volatile double delta;
    private volatile double gamma;
    private volatile double theta;
    private volatile double vega;
    private volatile long lastTickTimeMs;

    public OptionInstrument(long instrumentToken, String tradingSymbol, String exchange,
                             IndexType indexType, int strikePrice, String optionType,
                             LocalDate expiry, int lotSize) {
        this.instrumentToken = instrumentToken;
        this.tradingSymbol = tradingSymbol;
        this.exchange = exchange;
        this.indexType = indexType;
        this.strikePrice = strikePrice;
        this.optionType = optionType;
        this.expiry = expiry;
        this.lotSize = lotSize;
    }

    public boolean isCE() { return "CE".equals(optionType); }
    public boolean isPE() { return "PE".equals(optionType); }

    public long getOiChange() {
        return prevOpenInterest > 0 ? openInterest - prevOpenInterest : 0;
    }

    public double getBidAskSpread() {
        return bestAsk > 0 && bestBid > 0 ? bestAsk - bestBid : Double.MAX_VALUE;
    }

    public double getBidAskSpreadPercent() {
        double mid = (bestBid + bestAsk) / 2;
        return mid > 0 ? (getBidAskSpread() / mid) * 100 : Double.MAX_VALUE;
    }

    // Getters
    public long getInstrumentToken() { return instrumentToken; }
    public String getTradingSymbol() { return tradingSymbol; }
    public String getExchange() { return exchange; }
    public IndexType getIndexType() { return indexType; }
    public int getStrikePrice() { return strikePrice; }
    public String getOptionType() { return optionType; }
    public LocalDate getExpiry() { return expiry; }
    public int getLotSize() { return lotSize; }
    public double getLastPrice() { return lastPrice; }
    public double getOpenPrice() { return openPrice; }
    public double getHighPrice() { return highPrice; }
    public double getLowPrice() { return lowPrice; }
    public double getClosePrice() { return closePrice; }
    public long getVolume() { return volume; }
    public long getOpenInterest() { return openInterest; }
    public long getPrevOpenInterest() { return prevOpenInterest; }
    public double getBestBid() { return bestBid; }
    public double getBestAsk() { return bestAsk; }
    public long getBestBidQty() { return bestBidQty; }
    public long getBestAskQty() { return bestAskQty; }
    public double getImpliedVolatility() { return impliedVolatility; }
    public double getDelta() { return delta; }
    public double getGamma() { return gamma; }
    public double getTheta() { return theta; }
    public double getVega() { return vega; }

    // Setters for live updates
    public void setLastPrice(double v) { this.lastPrice = v; }
    public void setOpenPrice(double v) { this.openPrice = v; }
    public void setHighPrice(double v) { this.highPrice = v; }
    public void setLowPrice(double v) { this.lowPrice = v; }
    public void setClosePrice(double v) { this.closePrice = v; }
    public void setVolume(long v) { this.volume = v; }
    public void setOpenInterest(long v) { this.openInterest = v; }
    public void setPrevOpenInterest(long v) { this.prevOpenInterest = v; }
    public void setBestBid(double v) { this.bestBid = v; }
    public void setBestAsk(double v) { this.bestAsk = v; }
    public void setBestBidQty(long v) { this.bestBidQty = v; }
    public void setBestAskQty(long v) { this.bestAskQty = v; }
    public void setImpliedVolatility(double v) { this.impliedVolatility = v; }
    public void setDelta(double v) { this.delta = v; }
    public void setGamma(double v) { this.gamma = v; }
    public void setTheta(double v) { this.theta = v; }
    public void setVega(double v) { this.vega = v; }
    public long getLastTickTimeMs() { return lastTickTimeMs; }
    public void setLastTickTimeMs(long v) { this.lastTickTimeMs = v; }
    /** Wall-clock ms of the last OI ring-buffer sample (via sampleOiIfDue). */
    public long getLastOiSampleMs() { return lastOiSampleMs; }
    
    // 5-minute high/low getters and setters
    public double getHigh5m() { return high5m; }
    public double getLow5m() { return low5m; }
    public void setHigh5m(double v) { this.high5m = v; }
    public void setLow5m(double v) { this.low5m = v; }
    public long getLast5mResetMs() { return last5mResetMs; }
    public void setLast5mResetMs(long v) { this.last5mResetMs = v; }
    
    /**
     * Update 5-minute high/low tracking.
     * Call this on every tick to maintain rolling 5-minute high/low.
     */
    public void updateHigh5mLow5m(double price) {
        long now = System.currentTimeMillis();
        // Reset every 5 minutes
        if (now - last5mResetMs > 300_000) { // 5 minutes
            high5m = price;
            low5m = price;
            last5mResetMs = now;
        } else {
            if (price > high5m) high5m = price;
            if (price < low5m || low5m == 0) low5m = price;
        }
    }

    // ── OI Time-Series Ring Buffer (1-minute slots, 5 slots = 5-min lookback) ──
    private static final int OI_HISTORY_SLOTS = 5;
    private final long[] oiHistory = new long[OI_HISTORY_SLOTS];
    private final long[] oiHistoryTimestamps = new long[OI_HISTORY_SLOTS];
    private volatile int oiHistoryIndex = 0;
    private volatile long lastOiSampleMs = 0;

    /**
     * Record OI sample every 60 seconds (called from updateOptionMarketData flow).
     * Maintains a 5-slot ring buffer giving 1-5 minute OI lookback.
     */
    public void sampleOiIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastOiSampleMs < 60_000) return; // Sample once per minute
        if (openInterest <= 0) return;
        lastOiSampleMs = now;
        oiHistory[oiHistoryIndex] = openInterest;
        oiHistoryTimestamps[oiHistoryIndex] = now;
        oiHistoryIndex = (oiHistoryIndex + 1) % OI_HISTORY_SLOTS;
    }

    /**
     * Get OI change over the last N minutes (1-5).
     * Returns 0 if insufficient history.
     */
    public long getOiChangeSince(int minutes) {
        if (minutes < 1 || minutes > OI_HISTORY_SLOTS) return 0;
        if (openInterest <= 0) return 0;
        long now = System.currentTimeMillis();
        long cutoff = now - (minutes * 60_000L);
        // Find the oldest sample within the requested window
        long oldestOi = 0;
        long oldestTime = Long.MAX_VALUE;
        for (int i = 0; i < OI_HISTORY_SLOTS; i++) {
            if (oiHistoryTimestamps[i] > 0 && oiHistoryTimestamps[i] <= cutoff) {
                if (oiHistoryTimestamps[i] < oldestTime) {
                    oldestTime = oiHistoryTimestamps[i];
                    oldestOi = oiHistory[i];
                }
            }
        }
        if (oldestOi <= 0) return 0;
        return openInterest - oldestOi;
    }

    /**
     * Get OI change percentage over the last N minutes.
     */
    public double getOiChangePercentSince(int minutes) {
        long change = getOiChangeSince(minutes);
        if (change == 0) return 0;
        // Use the oldest sample as base
        long now = System.currentTimeMillis();
        long cutoff = now - (minutes * 60_000L);
        for (int i = 0; i < OI_HISTORY_SLOTS; i++) {
            if (oiHistoryTimestamps[i] > 0 && oiHistoryTimestamps[i] <= cutoff && oiHistory[i] > 0) {
                return ((double) change / oiHistory[i]) * 100;
            }
        }
        return 0;
    }
}
