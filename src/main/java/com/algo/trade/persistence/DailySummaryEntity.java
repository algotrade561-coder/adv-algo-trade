package com.algo.trade.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class DailySummaryEntity {

    @Id
    private LocalDate tradingDate;
    private int trades;
    private int wins;
    private int losses;
    private int signalsGenerated;
    private int signalsRejected;
    private int scansExecuted;
    private BigDecimal realizedPnl;
    private BigDecimal unrealizedPnl;
    private BigDecimal maxDrawdown;
    private String topStrategy;

    protected DailySummaryEntity() {
    }

    public DailySummaryEntity(LocalDate tradingDate, int trades, BigDecimal realizedPnl, BigDecimal unrealizedPnl) {
        this.tradingDate = tradingDate;
        this.trades = trades;
        this.realizedPnl = realizedPnl;
        this.unrealizedPnl = unrealizedPnl;
    }

    public LocalDate getTradingDate() { return tradingDate; }
    public int getTrades() { return trades; }
    public int getWins() { return wins; }
    public int getLosses() { return losses; }
    public int getSignalsGenerated() { return signalsGenerated; }
    public int getSignalsRejected() { return signalsRejected; }
    public int getScansExecuted() { return scansExecuted; }
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
    public BigDecimal getMaxDrawdown() { return maxDrawdown; }
    public String getTopStrategy() { return topStrategy; }

    public void setTrades(int v) { this.trades = v; }
    public void setWins(int v) { this.wins = v; }
    public void setLosses(int v) { this.losses = v; }
    public void setSignalsGenerated(int v) { this.signalsGenerated = v; }
    public void setSignalsRejected(int v) { this.signalsRejected = v; }
    public void setScansExecuted(int v) { this.scansExecuted = v; }
    public void setRealizedPnl(BigDecimal v) { this.realizedPnl = v; }
    public void setUnrealizedPnl(BigDecimal v) { this.unrealizedPnl = v; }
    public void setMaxDrawdown(BigDecimal v) { this.maxDrawdown = v; }
    public void setTopStrategy(String v) { this.topStrategy = v; }
}
