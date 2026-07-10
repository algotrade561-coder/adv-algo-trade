package com.algo.trade.config.usersettings;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Resolved effective trading configuration for a user. Provides typed accessors that satisfy
 * both {@link com.algo.trade.config.GlobalConfigService} (BigDecimal/int/boolean style) and
 * {@link com.algo.trade.controller.TradingSettingsController} (DTO projection).
 *
 * <p>Each field resolves through the chain: USER override → PROFILE default → GLOBAL baseline.
 * The {@code provenance} map tracks which layer each field came from.
 */
public class EffectiveTradingConfig {

    public enum Source { USER, PROFILE, GLOBAL }

    // ── Identity ──
    private Long userId;
    private RiskProfile riskProfile = RiskProfile.BALANCED;

    // ── Capital / Risk ──
    private BigDecimal totalCapital;
    private BigDecimal maxRiskPerTradePercent;
    private BigDecimal maxDailyLossPercent;
    private BigDecimal dailyProfitTarget;

    // ── Concurrency ──
    private int maxTradesPerDay;
    private int maxConsecutiveLosses;
    private int maxOpenTrades;
    private int maxLotsPerTrade;
    private int maxOpenPositionsPerStrategy;
    private int cooldownMinutes;
    private int directionFlipCooldownMinutes;
    private int maxEntriesPerScan;
    private int maxEntriesPerScanPerUnderlying;

    // ── Entry gates ──
    private BigDecimal minSignalScorePercent;
    private int minEnvironmentScore;

    // ── Session ──
    private String sessionPreset = "STANDARD";
    private String entryStartTime;
    private String entryCutoffTime;
    private String forcedExitTime;
    private String failSafeSquareoffTime;

    // ── Exits ──
    private BigDecimal stopLossPercent;
    private BigDecimal targetPercent;
    private BigDecimal trailingStopActivationPercent;
    private BigDecimal trailingGapPercent;
    private int maxHoldMinutes;
    private boolean partialProfitBookingEnabled;
    private boolean vwapExitEnabled;
    private BigDecimal ivCollapseMaxProfitPercent;

    // ── Toggles ──
    private boolean manageSyncedTrades;
    private boolean autoExits = true;
    private boolean autoVixGate = true;
    private boolean autoIvCap = true;

    // ── Provenance ──
    private Map<String, Source> provenance = new LinkedHashMap<>();

    public EffectiveTradingConfig() {}

    // ── Getters (satisfying GlobalConfigService & TradingSettingsController) ──

    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public RiskProfile getRiskProfile() { return riskProfile; }
    public void setRiskProfile(RiskProfile riskProfile) { this.riskProfile = riskProfile; }

    public BigDecimal getTotalCapital() { return totalCapital; }
    public void setTotalCapital(BigDecimal v) { this.totalCapital = v; }

    public BigDecimal getMaxRiskPerTradePercent() { return maxRiskPerTradePercent; }
    public void setMaxRiskPerTradePercent(BigDecimal v) { this.maxRiskPerTradePercent = v; }

    public BigDecimal getMaxDailyLossPercent() { return maxDailyLossPercent; }
    public void setMaxDailyLossPercent(BigDecimal v) { this.maxDailyLossPercent = v; }

    public BigDecimal getDailyProfitTarget() { return dailyProfitTarget; }
    public void setDailyProfitTarget(BigDecimal v) { this.dailyProfitTarget = v; }

    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int v) { this.maxTradesPerDay = v; }

    public int getMaxConsecutiveLosses() { return maxConsecutiveLosses; }
    public void setMaxConsecutiveLosses(int v) { this.maxConsecutiveLosses = v; }

    public int getMaxOpenTrades() { return maxOpenTrades; }
    public void setMaxOpenTrades(int v) { this.maxOpenTrades = v; }

    public int getMaxLotsPerTrade() { return maxLotsPerTrade; }
    public void setMaxLotsPerTrade(int v) { this.maxLotsPerTrade = v; }

    public int getMaxOpenPositionsPerStrategy() { return maxOpenPositionsPerStrategy; }
    public void setMaxOpenPositionsPerStrategy(int v) { this.maxOpenPositionsPerStrategy = v; }

    public int getCooldownMinutes() { return cooldownMinutes; }
    public void setCooldownMinutes(int v) { this.cooldownMinutes = v; }

    public int getDirectionFlipCooldownMinutes() { return directionFlipCooldownMinutes; }
    public void setDirectionFlipCooldownMinutes(int v) { this.directionFlipCooldownMinutes = v; }

    public int getMaxEntriesPerScan() { return maxEntriesPerScan; }
    public void setMaxEntriesPerScan(int v) { this.maxEntriesPerScan = v; }

    public int getMaxEntriesPerScanPerUnderlying() { return maxEntriesPerScanPerUnderlying; }
    public void setMaxEntriesPerScanPerUnderlying(int v) { this.maxEntriesPerScanPerUnderlying = v; }

    public BigDecimal getMinSignalScorePercent() { return minSignalScorePercent; }
    public void setMinSignalScorePercent(BigDecimal v) { this.minSignalScorePercent = v; }

    public int getMinEnvironmentScore() { return minEnvironmentScore; }
    public void setMinEnvironmentScore(int v) { this.minEnvironmentScore = v; }

    public String getSessionPreset() { return sessionPreset; }
    public void setSessionPreset(String v) { this.sessionPreset = v; }

    public String getEntryStartTime() { return entryStartTime; }
    public void setEntryStartTime(String v) { this.entryStartTime = v; }

    public String getEntryCutoffTime() { return entryCutoffTime; }
    public void setEntryCutoffTime(String v) { this.entryCutoffTime = v; }

    public String getForcedExitTime() { return forcedExitTime; }
    public void setForcedExitTime(String v) { this.forcedExitTime = v; }

    public String getFailSafeSquareoffTime() { return failSafeSquareoffTime; }
    public void setFailSafeSquareoffTime(String v) { this.failSafeSquareoffTime = v; }

    public BigDecimal getStopLossPercent() { return stopLossPercent; }
    public void setStopLossPercent(BigDecimal v) { this.stopLossPercent = v; }

    public BigDecimal getTargetPercent() { return targetPercent; }
    public void setTargetPercent(BigDecimal v) { this.targetPercent = v; }

    public BigDecimal getTrailingStopActivationPercent() { return trailingStopActivationPercent; }
    public void setTrailingStopActivationPercent(BigDecimal v) { this.trailingStopActivationPercent = v; }

    public BigDecimal getTrailingGapPercent() { return trailingGapPercent; }
    public void setTrailingGapPercent(BigDecimal v) { this.trailingGapPercent = v; }

    public int getMaxHoldMinutes() { return maxHoldMinutes; }
    public void setMaxHoldMinutes(int v) { this.maxHoldMinutes = v; }

    public boolean isPartialProfitBookingEnabled() { return partialProfitBookingEnabled; }
    public void setPartialProfitBookingEnabled(boolean v) { this.partialProfitBookingEnabled = v; }

    public boolean isVwapExitEnabled() { return vwapExitEnabled; }
    public void setVwapExitEnabled(boolean v) { this.vwapExitEnabled = v; }

    public BigDecimal getIvCollapseMaxProfitPercent() { return ivCollapseMaxProfitPercent; }
    public void setIvCollapseMaxProfitPercent(BigDecimal v) { this.ivCollapseMaxProfitPercent = v; }

    public boolean isManageSyncedTrades() { return manageSyncedTrades; }
    public void setManageSyncedTrades(boolean v) { this.manageSyncedTrades = v; }

    public boolean isAutoExits() { return autoExits; }
    public void setAutoExits(boolean v) { this.autoExits = v; }

    public boolean isAutoVixGate() { return autoVixGate; }
    public void setAutoVixGate(boolean v) { this.autoVixGate = v; }

    public boolean isAutoIvCap() { return autoIvCap; }
    public void setAutoIvCap(boolean v) { this.autoIvCap = v; }

    public Map<String, Source> getProvenance() { return provenance; }
    public void setProvenance(Map<String, Source> v) { this.provenance = v; }

    /** Convenience: record provenance for a field. */
    public void putProvenance(String field, Source source) {
        if (provenance == null) provenance = new LinkedHashMap<>();
        provenance.put(field, source);
    }
}
