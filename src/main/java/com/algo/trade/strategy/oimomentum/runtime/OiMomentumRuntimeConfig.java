package com.algo.trade.strategy.oimomentum.runtime;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Single-row JPA entity holding operator-tunable OI Momentum settings that can be
 * changed at runtime without a restart.
 *
 * <p>Follows the same shape as {@link com.algo.trade.config.GlobalConfig}: id=1, lazily
 * seeded from YAML on first boot, updated via REST + UI.</p>
 *
 * <p>Only a deliberately narrow set of fields is exposed for runtime control:</p>
 * <ul>
 *   <li>Master mode (V3 disabled / shadow / live).</li>
 *   <li>Strategy-level enable + paper trading toggle.</li>
 *   <li>The five "hard block" safety flags + their numeric thresholds.</li>
 *   <li>Profit ratchet trigger.</li>
 *   <li>Daily trade cap.</li>
 * </ul>
 *
 * <p>Strategy-tuning parameters (thresholds, time windows, bias scores) stay in YAML —
 * they need backtest validation before they're twiddled mid-day.</p>
 */
@Entity
@Table(name = "oi_momentum_runtime_config")
public class OiMomentumRuntimeConfig {

    @Id
    private Long id = 1L;

    /** Master strategy switch. When false, OI_MOMENTUM is off entirely. */
    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    /** Paper trade vs live. */
    @Column(name = "paper_trading", nullable = false)
    private boolean paperTrading = false;

    /** V3 master switch. */
    @Column(name = "v3_enabled", nullable = false)
    private boolean v3Enabled = false;

    /** V3 shadow mode — log-only, do not bind. */
    @Column(name = "v3_shadow_mode", nullable = false)
    private boolean v3ShadowMode = false;

    /** Anti-pyramid same-strike re-entry block. */
    @Column(name = "anti_pyramid_enabled", nullable = false)
    private boolean antiPyramidEnabled = false;

    @Column(name = "anti_pyramid_cooldown_minutes", nullable = false)
    private int antiPyramidCooldownMinutes = 5;

    /** Expiry-day OTM late cutoff. */
    @Column(name = "expiry_otm_cutoff_enabled", nullable = false)
    private boolean expiryOtmCutoffEnabled = false;

    @Column(name = "expiry_otm_cutoff_time", nullable = false, length = 5)
    private String expiryOtmCutoffTime = "14:45";

    /** Daily loss absolute floor (rupees). 0 disables. */
    @Column(name = "daily_loss_limit_rupees", nullable = false)
    private double dailyLossLimitRupees = 0;

    /** Daily loss adaptive (× avg loser). 0 disables. */
    @Column(name = "daily_loss_multiplier_of_avg_loser", nullable = false)
    private double dailyLossMultiplierOfAvgLoser = 0;

    /** Hard halt after N consecutive losses. 0 disables. */
    @Column(name = "consecutive_loss_halt_count", nullable = false)
    private int consecutiveLossHaltCount = 0;

    /** Profit ratchet peak % trigger (sets break-even floor). 0 disables. */
    @Column(name = "break_even_trigger_percent", nullable = false)
    private double breakEvenTriggerPercent = 0;

    /** Hard cap on trades per day per index. */
    @Column(name = "max_trades_per_day", nullable = false)
    private int maxTradesPerDay = 20;

    /** When this row was last modified (audit). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Operator who made the last change (email if OAuth, or "yaml-seed"). */
    @Column(name = "updated_by", length = 200, nullable = false)
    private String updatedBy = "yaml-seed";

    /** Optional reason for the change — useful for audit. */
    @Column(name = "updated_reason", length = 500)
    private String updatedReason;

    // ── Getters / setters ─────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }

    public boolean isPaperTrading() { return paperTrading; }
    public void setPaperTrading(boolean v) { this.paperTrading = v; }

    public boolean isV3Enabled() { return v3Enabled; }
    public void setV3Enabled(boolean v) { this.v3Enabled = v; }

    public boolean isV3ShadowMode() { return v3ShadowMode; }
    public void setV3ShadowMode(boolean v) { this.v3ShadowMode = v; }

    public boolean isAntiPyramidEnabled() { return antiPyramidEnabled; }
    public void setAntiPyramidEnabled(boolean v) { this.antiPyramidEnabled = v; }

    public int getAntiPyramidCooldownMinutes() { return antiPyramidCooldownMinutes; }
    public void setAntiPyramidCooldownMinutes(int v) { this.antiPyramidCooldownMinutes = v; }

    public boolean isExpiryOtmCutoffEnabled() { return expiryOtmCutoffEnabled; }
    public void setExpiryOtmCutoffEnabled(boolean v) { this.expiryOtmCutoffEnabled = v; }

    public String getExpiryOtmCutoffTime() { return expiryOtmCutoffTime; }
    public void setExpiryOtmCutoffTime(String v) { this.expiryOtmCutoffTime = v; }

    public double getDailyLossLimitRupees() { return dailyLossLimitRupees; }
    public void setDailyLossLimitRupees(double v) { this.dailyLossLimitRupees = v; }

    public double getDailyLossMultiplierOfAvgLoser() { return dailyLossMultiplierOfAvgLoser; }
    public void setDailyLossMultiplierOfAvgLoser(double v) { this.dailyLossMultiplierOfAvgLoser = v; }

    public int getConsecutiveLossHaltCount() { return consecutiveLossHaltCount; }
    public void setConsecutiveLossHaltCount(int v) { this.consecutiveLossHaltCount = v; }

    public double getBreakEvenTriggerPercent() { return breakEvenTriggerPercent; }
    public void setBreakEvenTriggerPercent(double v) { this.breakEvenTriggerPercent = v; }

    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int v) { this.maxTradesPerDay = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }

    public String getUpdatedReason() { return updatedReason; }
    public void setUpdatedReason(String v) { this.updatedReason = v; }
}
