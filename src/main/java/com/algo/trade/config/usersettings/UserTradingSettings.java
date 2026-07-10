package com.algo.trade.config.usersettings;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Layer-2 per-user trading settings (sparse). Holds only what the user actually sets on the
 * redesigned Trading Settings page; everything null inherits from the risk profile, then the
 * global config (Layer-1), then YAML defaults (Layer-0) via {@link TradingConfigResolver}.
 *
 * <p>A fresh user is a single row (riskProfile=BALANCED, all overrides null) and therefore behaves
 * identically to today's global config.
 */
@Entity
@Table(name = "user_trading_settings")
public class UserTradingSettings {

    /** Owning user id (one row per user). */
    @Id
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "risk_profile", length = 20, nullable = false)
    @ColumnDefault("'BALANCED'")
    private String riskProfile = "BALANCED";

    /** null ⇒ inherit global capital (future: auto-pull from broker margin). */
    @Column(name = "total_capital", precision = 19, scale = 4)
    private BigDecimal totalCapital;

    /** null ⇒ profile/global daily-loss %. */
    @Column(name = "daily_loss_pct", precision = 19, scale = 4)
    private BigDecimal dailyLossPercent;

    /** null ⇒ global daily profit target. */
    @Column(name = "daily_profit_target", precision = 19, scale = 4)
    private BigDecimal dailyProfitTarget;

    /** STANDARD ⇒ inherit global session times; CUSTOM ⇒ use the four time fields below. */
    @Column(name = "session_preset", length = 20, nullable = false)
    @ColumnDefault("'STANDARD'")
    private String sessionPreset = "STANDARD";

    @Column(name = "entry_start_time", length = 5)
    private String entryStartTime;

    @Column(name = "entry_cutoff_time", length = 5)
    private String entryCutoffTime;

    @Column(name = "forced_exit_time", length = 5)
    private String forcedExitTime;

    @Column(name = "failsafe_squareoff_time", length = 5)
    private String failSafeSquareoffTime;

    /** null ⇒ inherit global manage-synced toggle. */
    @Column(name = "manage_synced_trades")
    private Boolean manageSyncedTrades;

    /** Auto-modes — default ON (live). Reset-to-manual flips these off and the resolver uses
     *  the stored manual values instead. */
    @Column(name = "auto_exits", nullable = false)
    @ColumnDefault("true")
    private boolean autoExits = true;

    @Column(name = "auto_vix_gate", nullable = false)
    @ColumnDefault("true")
    private boolean autoVixGate = true;

    @Column(name = "auto_iv_cap", nullable = false)
    @ColumnDefault("true")
    private boolean autoIvCap = true;

    /** Sparse JSON map of any raw GlobalConfig field a power user overrode (Advanced drawer). */
    @Lob
    @Column(name = "advanced_overrides")
    private String advancedOverrides;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 200)
    private String updatedBy;

    public UserTradingSettings() {}

    public UserTradingSettings(Long userId) {
        this.userId = userId;
    }

    // ── getters / setters ──
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }

    public String getRiskProfile() { return riskProfile; }
    public void setRiskProfile(String riskProfile) { this.riskProfile = riskProfile; }

    public BigDecimal getTotalCapital() { return totalCapital; }
    public void setTotalCapital(BigDecimal totalCapital) { this.totalCapital = totalCapital; }

    public BigDecimal getDailyLossPercent() { return dailyLossPercent; }
    public void setDailyLossPercent(BigDecimal dailyLossPercent) { this.dailyLossPercent = dailyLossPercent; }

    public BigDecimal getDailyProfitTarget() { return dailyProfitTarget; }
    public void setDailyProfitTarget(BigDecimal dailyProfitTarget) { this.dailyProfitTarget = dailyProfitTarget; }

    public String getSessionPreset() { return sessionPreset; }
    public void setSessionPreset(String sessionPreset) { this.sessionPreset = sessionPreset; }

    public String getEntryStartTime() { return entryStartTime; }
    public void setEntryStartTime(String entryStartTime) { this.entryStartTime = entryStartTime; }

    public String getEntryCutoffTime() { return entryCutoffTime; }
    public void setEntryCutoffTime(String entryCutoffTime) { this.entryCutoffTime = entryCutoffTime; }

    public String getForcedExitTime() { return forcedExitTime; }
    public void setForcedExitTime(String forcedExitTime) { this.forcedExitTime = forcedExitTime; }

    public String getFailSafeSquareoffTime() { return failSafeSquareoffTime; }
    public void setFailSafeSquareoffTime(String failSafeSquareoffTime) { this.failSafeSquareoffTime = failSafeSquareoffTime; }

    public Boolean getManageSyncedTrades() { return manageSyncedTrades; }
    public void setManageSyncedTrades(Boolean manageSyncedTrades) { this.manageSyncedTrades = manageSyncedTrades; }

    public boolean isAutoExits() { return autoExits; }
    public void setAutoExits(boolean autoExits) { this.autoExits = autoExits; }

    public boolean isAutoVixGate() { return autoVixGate; }
    public void setAutoVixGate(boolean autoVixGate) { this.autoVixGate = autoVixGate; }

    public boolean isAutoIvCap() { return autoIvCap; }
    public void setAutoIvCap(boolean autoIvCap) { this.autoIvCap = autoIvCap; }

    public String getAdvancedOverrides() { return advancedOverrides; }
    public void setAdvancedOverrides(String advancedOverrides) { this.advancedOverrides = advancedOverrides; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
