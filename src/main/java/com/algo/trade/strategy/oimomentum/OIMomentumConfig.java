package com.algo.trade.strategy.oimomentum;

/**
 * Configuration for OIMomentumStrategy — all thresholds configurable.
 * Loaded from application.yml under 'oi-momentum' prefix.
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "oi-momentum")
@org.springframework.stereotype.Component
public class OIMomentumConfig {

    // ── Momentum Detection ──
    private double momentumThresholdPercent = 0.08;      // Large candle threshold
    private double spikeThresholdPercent = 0.40;         // Event spike: index move in 10 min
    private int rolling30MinWindowSeconds = 1800;        // 30-min rolling window

    // ── Entry Filters ──
    private double minOiChangePercent = 1.5;             // Minimum OI change to confirm
    private double pcrBullishThreshold = 1.2;            // PCR > 1.2 = bullish
    private double pcrBearishThreshold = 0.8;            // PCR < 0.8 = bearish

    // ── Trade Throttling ──
    private int maxTradesPerDay = 30;                    // Hard cap
    private int softTargetTradesPerDay = 15;             // Soft target
    private int maxReversalsPerDay = 3;                  // Max direction flips
    private int cooldownAfterSlSeconds = 120;            // 2 min cooldown after SL
    private int minimumHoldTimeSeconds = 45;             // Don't exit before 45s
    private int consecutiveLossPause = 3;                // Pause after 3 consecutive losses
    private int middayTradeReductionPercent = 50;        // Reduce entries 12:00-13:30

    // ── Exit Parameters ──
    private double stopLossPercent = 15;                 // Delta-adjusted SL
    private double targetPercent = 25;                   // Delta-adjusted target
    private double trailingActivationPercent = 12;       // Trailing starts at +12%
    private double trailingGapPercent = 8;               // Trail gap from peak
    private int squareoffHour = 15;
    private int squareoffMinute = 10;

    // ── Session Windows ──
    private String openingSessionStart = "09:16";
    private String openingSessionEnd = "10:00";
    private String middayStart = "12:00";
    private String middayEnd = "13:30";
    private String closingSessionStart = "15:00";

    // ── Enabled/Paper ──
    private boolean enabled = true;
    private boolean paperTrading = true;

    // Getters and setters
    public double getMomentumThresholdPercent() { return momentumThresholdPercent; }
    public void setMomentumThresholdPercent(double v) { this.momentumThresholdPercent = v; }
    public double getSpikeThresholdPercent() { return spikeThresholdPercent; }
    public void setSpikeThresholdPercent(double v) { this.spikeThresholdPercent = v; }
    public int getRolling30MinWindowSeconds() { return rolling30MinWindowSeconds; }
    public void setRolling30MinWindowSeconds(int v) { this.rolling30MinWindowSeconds = v; }
    public double getMinOiChangePercent() { return minOiChangePercent; }
    public void setMinOiChangePercent(double v) { this.minOiChangePercent = v; }
    public double getPcrBullishThreshold() { return pcrBullishThreshold; }
    public void setPcrBullishThreshold(double v) { this.pcrBullishThreshold = v; }
    public double getPcrBearishThreshold() { return pcrBearishThreshold; }
    public void setPcrBearishThreshold(double v) { this.pcrBearishThreshold = v; }
    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int v) { this.maxTradesPerDay = v; }
    public int getSoftTargetTradesPerDay() { return softTargetTradesPerDay; }
    public void setSoftTargetTradesPerDay(int v) { this.softTargetTradesPerDay = v; }
    public int getMaxReversalsPerDay() { return maxReversalsPerDay; }
    public void setMaxReversalsPerDay(int v) { this.maxReversalsPerDay = v; }
    public int getCooldownAfterSlSeconds() { return cooldownAfterSlSeconds; }
    public void setCooldownAfterSlSeconds(int v) { this.cooldownAfterSlSeconds = v; }
    public int getMinimumHoldTimeSeconds() { return minimumHoldTimeSeconds; }
    public void setMinimumHoldTimeSeconds(int v) { this.minimumHoldTimeSeconds = v; }
    public int getConsecutiveLossPause() { return consecutiveLossPause; }
    public void setConsecutiveLossPause(int v) { this.consecutiveLossPause = v; }
    public int getMiddayTradeReductionPercent() { return middayTradeReductionPercent; }
    public void setMiddayTradeReductionPercent(int v) { this.middayTradeReductionPercent = v; }
    public double getStopLossPercent() { return stopLossPercent; }
    public void setStopLossPercent(double v) { this.stopLossPercent = v; }
    public double getTargetPercent() { return targetPercent; }
    public void setTargetPercent(double v) { this.targetPercent = v; }
    public double getTrailingActivationPercent() { return trailingActivationPercent; }
    public void setTrailingActivationPercent(double v) { this.trailingActivationPercent = v; }
    public double getTrailingGapPercent() { return trailingGapPercent; }
    public void setTrailingGapPercent(double v) { this.trailingGapPercent = v; }
    public int getSquareoffHour() { return squareoffHour; }
    public void setSquareoffHour(int v) { this.squareoffHour = v; }
    public int getSquareoffMinute() { return squareoffMinute; }
    public void setSquareoffMinute(int v) { this.squareoffMinute = v; }
    public String getOpeningSessionStart() { return openingSessionStart; }
    public void setOpeningSessionStart(String v) { this.openingSessionStart = v; }
    public String getOpeningSessionEnd() { return openingSessionEnd; }
    public void setOpeningSessionEnd(String v) { this.openingSessionEnd = v; }
    public String getMiddayStart() { return middayStart; }
    public void setMiddayStart(String v) { this.middayStart = v; }
    public String getMiddayEnd() { return middayEnd; }
    public void setMiddayEnd(String v) { this.middayEnd = v; }
    public String getClosingSessionStart() { return closingSessionStart; }
    public void setClosingSessionStart(String v) { this.closingSessionStart = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isPaperTrading() { return paperTrading; }
    public void setPaperTrading(boolean v) { this.paperTrading = v; }
}
