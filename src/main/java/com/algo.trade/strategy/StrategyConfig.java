package com.algo.trade.strategy;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalTime;

/**
 * Per-strategy configuration stored in DB.
 * Controls enabled/disabled state and key parameters.
 * Selling strategies are disabled by default and require explicit opt-in.
 */
@Entity
@Table(name = "strategy_configs", indexes = {
    @Index(name = "idx_sc_type", columnList = "strategy_type", unique = true)
})
public class StrategyConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true)
    private StrategyType strategyType;

    private boolean enabled = false;
    private String underlying = "NIFTY";
    private int lots = 1;
    private BigDecimal stopLossPercent = BigDecimal.valueOf(30);
    private BigDecimal targetPercent = BigDecimal.valueOf(60);
    private int maxHoldMinutes = 45;
    private int spreadStrikes = 2;
    private int otmStrikes = 2;
    private BigDecimal minCombinedPremium = BigDecimal.valueOf(80);
    private BigDecimal maxIvRankForBuying = BigDecimal.valueOf(40);
    private BigDecimal trailingStopActivationPercent = BigDecimal.valueOf(10);
    private BigDecimal trailingGapPercent = BigDecimal.valueOf(5);
    private int squareoffHour = 15;
    private int squareoffMinute = 15;

    protected StrategyConfig() {}

    public StrategyConfig(StrategyType type) {
        this.strategyType = type;
        this.enabled = type.isDefaultEnabled();
        switch (type) {
            case DIRECTIONAL_BUY -> { stopLossPercent = BigDecimal.valueOf(12); targetPercent = BigDecimal.valueOf(25); maxHoldMinutes = 30; trailingStopActivationPercent = BigDecimal.valueOf(10); trailingGapPercent = BigDecimal.valueOf(5); }
            case VOLATILITY_BREAKOUT -> { stopLossPercent = BigDecimal.valueOf(35); targetPercent = BigDecimal.valueOf(100); maxIvRankForBuying = BigDecimal.valueOf(30); }
            case EVENT_DRIVEN_BUY -> { stopLossPercent = BigDecimal.valueOf(35); targetPercent = BigDecimal.valueOf(80); maxHoldMinutes = 0; maxIvRankForBuying = BigDecimal.valueOf(40); }
            case SCALPING -> { stopLossPercent = BigDecimal.valueOf(20); targetPercent = BigDecimal.valueOf(40); maxHoldMinutes = 30; }
            case BULL_CALL_SPREAD, BEAR_PUT_SPREAD -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(80); }
            case LONG_STRADDLE -> { stopLossPercent = BigDecimal.valueOf(40); targetPercent = BigDecimal.valueOf(60); maxHoldMinutes = 0; maxIvRankForBuying = BigDecimal.valueOf(25); }
            case LONG_STRANGLE -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(80); otmStrikes = 2; maxHoldMinutes = 0; maxIvRankForBuying = BigDecimal.valueOf(30); }
            case SHORT_STRADDLE -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(30); minCombinedPremium = BigDecimal.valueOf(150); }
            case SHORT_STRANGLE -> { stopLossPercent = BigDecimal.valueOf(40); targetPercent = BigDecimal.valueOf(80); otmStrikes = 2; }
            case IRON_CONDOR -> { stopLossPercent = BigDecimal.valueOf(100); targetPercent = BigDecimal.valueOf(50); otmStrikes = 2; spreadStrikes = 4; }
            case BUTTERFLY -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(100); }
            case CALENDAR_SPREAD -> { stopLossPercent = BigDecimal.valueOf(50); targetPercent = BigDecimal.valueOf(30); }
        }
    }

    // Getters
    public Long getId() { return id; }
    public StrategyType getStrategyType() { return strategyType; }
    public boolean isEnabled() { return enabled; }
    public String getUnderlying() { return underlying; }
    public int getLots() { return lots; }
    public BigDecimal getStopLossPercent() { return stopLossPercent; }
    public BigDecimal getTargetPercent() { return targetPercent; }
    public int getMaxHoldMinutes() { return maxHoldMinutes; }
    public int getSpreadStrikes() { return spreadStrikes; }
    public int getOtmStrikes() { return otmStrikes; }
    public BigDecimal getMinCombinedPremium() { return minCombinedPremium; }
    public BigDecimal getMaxIvRankForBuying() { return maxIvRankForBuying; }
    public BigDecimal getTrailingStopActivationPercent() { return trailingStopActivationPercent; }
    public BigDecimal getTrailingGapPercent() { return trailingGapPercent; }
    public int getSquareoffHour() { return squareoffHour; }
    public int getSquareoffMinute() { return squareoffMinute; }

    // Setters
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public void setUnderlying(String underlying) { this.underlying = underlying; }
    public void setLots(int lots) { this.lots = lots; }
    public void setStopLossPercent(BigDecimal v) { this.stopLossPercent = v; }
    public void setTargetPercent(BigDecimal v) { this.targetPercent = v; }
    public void setMaxHoldMinutes(int v) { this.maxHoldMinutes = v; }
    public void setSpreadStrikes(int v) { this.spreadStrikes = v; }
    public void setOtmStrikes(int v) { this.otmStrikes = v; }
    public void setMinCombinedPremium(BigDecimal v) { this.minCombinedPremium = v; }
    public void setMaxIvRankForBuying(BigDecimal v) { this.maxIvRankForBuying = v; }
    public void setTrailingStopActivationPercent(BigDecimal v) { this.trailingStopActivationPercent = v; }
    public void setTrailingGapPercent(BigDecimal v) { this.trailingGapPercent = v; }
    public void setSquareoffHour(int v) { this.squareoffHour = v; }
    public void setSquareoffMinute(int v) { this.squareoffMinute = v; }
}
