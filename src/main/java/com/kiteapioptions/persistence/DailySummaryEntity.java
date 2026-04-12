package com.kiteapioptions.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
public class DailySummaryEntity {

    @Id
    private LocalDate tradingDate;
    private int trades;
    private BigDecimal realizedPnl;
    private BigDecimal unrealizedPnl;

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
    public BigDecimal getRealizedPnl() { return realizedPnl; }
    public BigDecimal getUnrealizedPnl() { return unrealizedPnl; }
}
