package com.kiteapioptions.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class BacktestResultEntity {

    @Id
    private String id;
    private Instant createdAt;
    private int totalTrades;
    private BigDecimal winRatePercent;
    private BigDecimal expectancy;
    private BigDecimal maxDrawdown;
    private BigDecimal cumulativePnl;
    @Column(length = 1024)
    private String outputPath;

    protected BacktestResultEntity() {
    }

    public BacktestResultEntity(String id, Instant createdAt, int totalTrades, BigDecimal cumulativePnl, String outputPath) {
        this(id, createdAt, totalTrades, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, cumulativePnl, outputPath);
    }

    public BacktestResultEntity(String id, Instant createdAt, int totalTrades, BigDecimal winRatePercent,
                                BigDecimal expectancy, BigDecimal maxDrawdown, BigDecimal cumulativePnl,
                                String outputPath) {
        this.id = id;
        this.createdAt = createdAt;
        this.totalTrades = totalTrades;
        this.winRatePercent = winRatePercent;
        this.expectancy = expectancy;
        this.maxDrawdown = maxDrawdown;
        this.cumulativePnl = cumulativePnl;
        this.outputPath = outputPath;
    }

    public String getId() { return id; }
    public Instant getCreatedAt() { return createdAt; }
    public int getTotalTrades() { return totalTrades; }
    public BigDecimal getWinRatePercent() { return winRatePercent; }
    public BigDecimal getExpectancy() { return expectancy; }
    public BigDecimal getMaxDrawdown() { return maxDrawdown; }
    public BigDecimal getCumulativePnl() { return cumulativePnl; }
    public String getOutputPath() { return outputPath; }
}
