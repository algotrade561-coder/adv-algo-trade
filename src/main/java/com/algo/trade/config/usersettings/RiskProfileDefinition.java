package com.algo.trade.config.usersettings;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Stores the risk parameters for each named risk profile (CONSERVATIVE, BALANCED, AGGRESSIVE).
 * Admins can tune these; users reference them by name via their UserTradingSettings.
 *
 * <p>The {@code name} field is the natural primary key (matches {@link RiskProfile} enum names).
 */
@Entity
@Table(name = "risk_profile_definition")
public class RiskProfileDefinition {

    @Id
    @Column(name = "name", length = 30)
    private String name;

    // ── 13-field Bundle (risk + selectivity + concurrency) ──
    private Double maxRiskPerTradePercent;
    private Double maxDailyLossPercent;
    private Integer maxTradesPerDay;
    private Integer maxConsecutiveLosses;
    private Integer maxOpenTrades;
    private Integer maxLotsPerTrade;
    private Integer maxOpenPositionsPerStrategy;
    private Double minSignalScorePercent;
    private Integer minEnvironmentScore;
    private Integer cooldownMinutes;
    private Integer directionFlipCooldownMinutes;
    private Integer maxEntriesPerScan;
    private Integer maxEntriesPerScanPerUnderlying;

    // ── Exit-style fields (profile-static, not in bundle) ──
    private Double stopLossPercent;
    private Double targetPercent;
    private Double trailingStopActivationPercent;
    private Double trailingGapPercent;
    private Integer maxHoldMinutes;
    private Boolean partialProfitBookingEnabled;
    private Boolean vwapExitEnabled;
    private Double ivCollapseMaxProfitPercent;

    // ── Batch 2 — additional risk fields (legacy compat) ──
    private Integer cooldownSeconds;
    private Integer flipCooldownMinutes;
    private Integer entriesPerScan;
    private Integer entriesPerScanPerIndex;

    // ── Audit ──
    @Column(name = "updated_at")
    private Instant updatedAt;

    @Column(name = "updated_by", length = 200)
    private String updatedBy;

    public RiskProfileDefinition() {}

    public RiskProfileDefinition(String name) {
        this.name = name;
    }

    /** Construct from enum defaults. */
    public RiskProfileDefinition(String name, RiskProfile.Bundle bundle) {
        this.name = name;
        if (bundle != null) {
            applyBundle(bundle);
        }
    }

    // ── Bundle conversion ────────────────────────────────────────────

    /** Export the 13 profile-driven risk fields as an immutable bundle. */
    public RiskProfile.Bundle toBundle() {
        return new RiskProfile.Bundle(
                maxRiskPerTradePercent != null ? maxRiskPerTradePercent : 5.0,
                maxDailyLossPercent != null ? maxDailyLossPercent : 5.0,
                maxTradesPerDay != null ? maxTradesPerDay : 4,
                maxConsecutiveLosses != null ? maxConsecutiveLosses : 2,
                maxOpenTrades != null ? maxOpenTrades : 1,
                maxLotsPerTrade != null ? maxLotsPerTrade : 5,
                maxOpenPositionsPerStrategy != null ? maxOpenPositionsPerStrategy : 1,
                minSignalScorePercent != null ? minSignalScorePercent : 55.0,
                minEnvironmentScore != null ? minEnvironmentScore : 50,
                cooldownMinutes != null ? cooldownMinutes : 0,
                directionFlipCooldownMinutes != null ? directionFlipCooldownMinutes : 60,
                maxEntriesPerScan != null ? maxEntriesPerScan : 1,
                maxEntriesPerScanPerUnderlying != null ? maxEntriesPerScanPerUnderlying : 1
        );
    }

    /** Overwrite the 13 bundle fields from the given bundle. */
    public void applyBundle(RiskProfile.Bundle bundle) {
        this.maxRiskPerTradePercent = bundle.maxRiskPerTradePercent();
        this.maxDailyLossPercent = bundle.maxDailyLossPercent();
        this.maxTradesPerDay = bundle.maxTradesPerDay();
        this.maxConsecutiveLosses = bundle.maxConsecutiveLosses();
        this.maxOpenTrades = bundle.maxOpenTrades();
        this.maxLotsPerTrade = bundle.maxLotsPerTrade();
        this.maxOpenPositionsPerStrategy = bundle.maxOpenPositionsPerStrategy();
        this.minSignalScorePercent = bundle.minSignalScorePercent();
        this.minEnvironmentScore = bundle.minEnvironmentScore();
        this.cooldownMinutes = bundle.cooldownMinutes();
        this.directionFlipCooldownMinutes = bundle.directionFlipCooldownMinutes();
        this.maxEntriesPerScan = bundle.maxEntriesPerScan();
        this.maxEntriesPerScanPerUnderlying = bundle.maxEntriesPerScanPerUnderlying();
    }

    // ── Getters & Setters ─────────────────────────────────────────

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public Double getMaxRiskPerTradePercent() { return maxRiskPerTradePercent; }
    public void setMaxRiskPerTradePercent(Double v) { this.maxRiskPerTradePercent = v; }

    public Double getMaxDailyLossPercent() { return maxDailyLossPercent; }
    public void setMaxDailyLossPercent(Double v) { this.maxDailyLossPercent = v; }

    public Integer getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(Integer v) { this.maxTradesPerDay = v; }

    public Integer getMaxConsecutiveLosses() { return maxConsecutiveLosses; }
    public void setMaxConsecutiveLosses(Integer v) { this.maxConsecutiveLosses = v; }

    public Integer getMaxOpenTrades() { return maxOpenTrades; }
    public void setMaxOpenTrades(Integer v) { this.maxOpenTrades = v; }

    public Integer getMaxLotsPerTrade() { return maxLotsPerTrade; }
    public void setMaxLotsPerTrade(Integer v) { this.maxLotsPerTrade = v; }

    public Integer getMaxOpenPositionsPerStrategy() { return maxOpenPositionsPerStrategy; }
    public void setMaxOpenPositionsPerStrategy(Integer v) { this.maxOpenPositionsPerStrategy = v; }

    public Double getMinSignalScorePercent() { return minSignalScorePercent; }
    public void setMinSignalScorePercent(Double v) { this.minSignalScorePercent = v; }

    public Integer getMinEnvironmentScore() { return minEnvironmentScore; }
    public void setMinEnvironmentScore(Integer v) { this.minEnvironmentScore = v; }

    public Integer getCooldownMinutes() { return cooldownMinutes; }
    public void setCooldownMinutes(Integer v) { this.cooldownMinutes = v; }

    public Integer getDirectionFlipCooldownMinutes() { return directionFlipCooldownMinutes; }
    public void setDirectionFlipCooldownMinutes(Integer v) { this.directionFlipCooldownMinutes = v; }

    public Integer getMaxEntriesPerScan() { return maxEntriesPerScan; }
    public void setMaxEntriesPerScan(Integer v) { this.maxEntriesPerScan = v; }

    public Integer getMaxEntriesPerScanPerUnderlying() { return maxEntriesPerScanPerUnderlying; }
    public void setMaxEntriesPerScanPerUnderlying(Integer v) { this.maxEntriesPerScanPerUnderlying = v; }

    // ── Exit-style fields ──
    public Double getStopLossPercent() { return stopLossPercent; }
    public void setStopLossPercent(Double v) { this.stopLossPercent = v; }

    public Double getTargetPercent() { return targetPercent; }
    public void setTargetPercent(Double v) { this.targetPercent = v; }

    public Double getTrailingStopActivationPercent() { return trailingStopActivationPercent; }
    public void setTrailingStopActivationPercent(Double v) { this.trailingStopActivationPercent = v; }

    public Double getTrailingGapPercent() { return trailingGapPercent; }
    public void setTrailingGapPercent(Double v) { this.trailingGapPercent = v; }

    public Integer getMaxHoldMinutes() { return maxHoldMinutes; }
    public void setMaxHoldMinutes(Integer v) { this.maxHoldMinutes = v; }

    public boolean isPartialProfitBookingEnabled() { return partialProfitBookingEnabled != null && partialProfitBookingEnabled; }
    public Boolean getPartialProfitBookingEnabled() { return partialProfitBookingEnabled; }
    public void setPartialProfitBookingEnabled(Boolean v) { this.partialProfitBookingEnabled = v; }

    public boolean isVwapExitEnabled() { return vwapExitEnabled != null && vwapExitEnabled; }
    public Boolean getVwapExitEnabled() { return vwapExitEnabled; }
    public void setVwapExitEnabled(Boolean v) { this.vwapExitEnabled = v; }

    public Double getIvCollapseMaxProfitPercent() { return ivCollapseMaxProfitPercent; }
    public void setIvCollapseMaxProfitPercent(Double v) { this.ivCollapseMaxProfitPercent = v; }

    // ── Batch 2 legacy compat ──
    public Integer getCooldownSeconds() { return cooldownSeconds; }
    public void setCooldownSeconds(Integer v) { this.cooldownSeconds = v; }

    public Integer getFlipCooldownMinutes() { return flipCooldownMinutes; }
    public void setFlipCooldownMinutes(Integer v) { this.flipCooldownMinutes = v; }

    public Integer getEntriesPerScan() { return entriesPerScan; }
    public void setEntriesPerScan(Integer v) { this.entriesPerScan = v; }

    public Integer getEntriesPerScanPerIndex() { return entriesPerScanPerIndex; }
    public void setEntriesPerScanPerIndex(Integer v) { this.entriesPerScanPerIndex = v; }

    // ── Audit ──
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
}
