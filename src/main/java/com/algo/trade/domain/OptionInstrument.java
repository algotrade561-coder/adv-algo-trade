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
     * Update 5-minute high/low tracking, aligned to wall-clock 5-minute boundaries
     * (09:15, 09:20, 09:25 ...). Clock-alignment ensures the snapshot scheduler
     * (also on a 5-min cadence) always captures values from a mid-window period
     * rather than from a just-reset window — fixing the "always zero" bug where
     * the snapshot fired at the same instant as the rolling reset.
     */
    public void updateHigh5mLow5m(double price) {
        long now = System.currentTimeMillis();
        long boundary = clockAligned5mBoundaryMs(now);
        if (boundary != last5mResetMs) {
            // New 5-min window started — reset to current price as both high and low
            high5m = price;
            low5m = price;
            last5mResetMs = boundary;
        } else {
            if (price > high5m) high5m = price;
            if (price < low5m || low5m == 0) low5m = price;
        }
    }

    /**
     * Returns the epoch-ms of the start of the current 5-minute clock boundary in IST
     * (e.g. 09:15:00, 09:20:00, 09:25:00 ...).
     */
    private static long clockAligned5mBoundaryMs(long nowMs) {
        java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(nowMs)
                .atZone(java.time.ZoneId.of("Asia/Kolkata"));
        int alignedMinute = (zdt.getMinute() / 5) * 5;
        return zdt.withMinute(alignedMinute).withSecond(0).withNano(0)
                .toInstant().toEpochMilli();
    }

    // ── OI Time-Series Ring Buffer (1-minute slots, 5 slots = 5-min lookback) ──
    private static final int OI_HISTORY_SLOTS = 5;
    private final long[] oiHistory = new long[OI_HISTORY_SLOTS];
    private final long[] oiHistoryTimestamps = new long[OI_HISTORY_SLOTS];
    private volatile int oiHistoryIndex = 0;
    private volatile long lastOiSampleMs = 0;

    /** True when no OI sample has ever been written (all timestamps are zero). */
    public boolean isOiRingBufferEmpty() {
        for (long ts : oiHistoryTimestamps) {
            if (ts > 0) return false;
        }
        return true;
    }

    /**
     * Write a single OI anchor entry directly into the ring buffer at the given timestamp,
     * bypassing the 60-second gate. Used by OiRestFallbackService to back-date a baseline
     * so that getOiChangeSince() has an immediate reference point after first REST injection.
     * Does NOT update lastOiSampleMs — the normal sampleOiIfDue() can still run afterwards.
     */
    public void seedOiRingBuffer(long oi, long timestampMs) {
        if (oi <= 0 || timestampMs <= 0) return;
        oiHistory[oiHistoryIndex] = oi;
        oiHistoryTimestamps[oiHistoryIndex] = timestampMs;
        oiHistoryIndex = (oiHistoryIndex + 1) % OI_HISTORY_SLOTS;
    }

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
     * TRUE windowed OI change (2026-07-01, FAST-OI). Unlike {@link #getOiChangeSince(int)}, which
     * picks the OLDEST sample beyond the cutoff — and therefore, with a full 5×60s ring buffer,
     * always measures ~5 minutes of change regardless of the argument — this returns the change
     * versus the sample whose age is CLOSEST to {@code windowSec}. That gives a genuine trailing
     * window (e.g. 60s → change vs the ~60s-old sample). Ring-buffer resolution is 60s, so the
     * finest meaningful window is ~60s; sub-minute windows all collapse onto the newest sample.
     *
     * <p>Empirical basis: ATM-band OI refreshes on a ~57s exchange heartbeat, so a trailing 60s
     * window reads a non-zero delta ~71% of the session while a 10s window is blind ~84% of it
     * (data/tuning/atm-microstructure-2026-06-24.csv). 60s is the coverage knee.</p>
     *
     * @param windowSec desired lookback in seconds.
     * @return {@code openInterest - oi(nearest sample to now-windowSec)}, or 0 if no usable sample.
     */
    public long getOiChangeSinceSeconds(int windowSec) {
        if (windowSec <= 0) return 0;
        if (openInterest <= 0) return 0;
        long target = System.currentTimeMillis() - (windowSec * 1000L);
        long bestOi = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < OI_HISTORY_SLOTS; i++) {
            if (oiHistoryTimestamps[i] > 0 && oiHistory[i] > 0) {
                long diff = Math.abs(oiHistoryTimestamps[i] - target);
                if (diff < bestDiff) {
                    bestDiff = diff;
                    bestOi = oiHistory[i];
                }
            }
        }
        if (bestOi <= 0) return 0;
        return openInterest - bestOi;
    }

    /**
     * True when a real OI reference sample exists older than the {@code minutes} cutoff
     * (i.e. {@link #getOiChangeSince} is backed by an actual baseline, not warm-up).
     *
     * <p>DATA-2 (2026-06-20): {@code getOiChangeSince} returns {@code 0} both when OI is
     * genuinely flat AND when no baseline has been captured yet (opening warm-up). Callers
     * that need to tell "available &amp; flat" apart from "not yet available" use this.</p>
     */
    public boolean hasOiBaseline(int minutes) {
        if (minutes < 1 || minutes > OI_HISTORY_SLOTS) return false;
        if (openInterest <= 0) return false;
        long cutoff = System.currentTimeMillis() - (minutes * 60_000L);
        for (int i = 0; i < OI_HISTORY_SLOTS; i++) {
            if (oiHistoryTimestamps[i] > 0 && oiHistoryTimestamps[i] <= cutoff && oiHistory[i] > 0) {
                return true;
            }
        }
        return false;
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
