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

    // Greeks — computed from Black-Scholes
    private volatile double impliedVolatility;
    private volatile double delta;
    private volatile double gamma;
    private volatile double theta;
    private volatile double vega;

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
}
