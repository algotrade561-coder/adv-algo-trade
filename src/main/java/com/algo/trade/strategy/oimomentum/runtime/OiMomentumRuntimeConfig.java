package com.algo.trade.strategy.oimomentum.runtime;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;

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

    // ── Legacy enhancements (data-validated 29 May 2026) ──────────────────────
    // See OI_MOMENTUM_EMPIRICAL_REPLAY_RESULTS.md for the supporting numbers.

    /**
     * P0-2: filter baseline CASE 1-5 by time-of-day mode. When true, AFTERNOON_POSITION
     * (13:30-14:45) and LAST_HOUR (14:45-15:10) entries are skipped, and MIDDAY_DISCIPLINE
     * (11:30-13:30) requires 4-of-4 confluence (CASE 1 only, or CASE 3 with op_score ≥ 60).
     * Replay shows raw win-rate 48% → 58% with this filter alone.
     *
     * <p>NOTE: {@link ColumnDefault} is required when this column is added to a table
     * that already has the seed row (id=1) — without the DEFAULT clause Hibernate's
     * {@code ALTER TABLE ... ADD COLUMN ... NOT NULL} fails because the existing row
     * has no value. The literal must match the Java initial value above.</p>
     */
    @Column(name = "legacy_tod_mode_enabled", nullable = false)
    @ColumnDefault("true")
    private boolean legacyTimeOfDayModeEnabled = true;

    /**
     * P0-1: enable CASE 0 (OI-led entry) — fires before any price breakout when the chain
     * is screaming. Strict thresholds (replay: 91% 30m win on 12 fires in 12 days).
     */
    @Column(name = "case0_enabled", nullable = false)
    @ColumnDefault("true")
    private boolean case0Enabled = true;

    /**
     * CASE 0 shadow mode — evaluate and log but do not bind. LIVE-mode default is
     * {@code false}: CASE 0 actually places entries.
     */
    @Column(name = "case0_shadow_mode", nullable = false)
    @ColumnDefault("false")
    private boolean case0ShadowMode = false;

    /** CASE 0 minimum operator score. Replay calibration: 80. Loose alternative: 70. */
    @Column(name = "case0_op_score_threshold", nullable = false)
    @ColumnDefault("80")
    private int case0OpScoreThreshold = 80;

    /** CASE 0 maximum 20-min spot range (%). Replay calibration: 0.10. Loose: 0.15. */
    @Column(name = "case0_coil_max_pct", nullable = false)
    @ColumnDefault("0.10")
    private double case0CoilMaxPct = 0.10;

    /** CASE 0 minimum |PCR slope 5m| in the operator direction. Replay calibration: 0.02. */
    @Column(name = "case0_pcr_slope_min_abs", nullable = false)
    @ColumnDefault("0.02")
    private double case0PcrSlopeMinAbs = 0.02;

    /**
     * P1-3 nuance: when CASE 4 (OI vs momentum conflict) fires, mark a watch-list bonus
     * for the NEXT aligned signal within 20 minutes. Replay shows OI-direction wins
     * marginally over momentum-direction in CASE 4 (53.6% vs 46.4% at 60m).
     */
    @Column(name = "case4_watchlist_bonus_enabled", nullable = false)
    @ColumnDefault("true")
    private boolean case4WatchlistBonusEnabled = true;

    // ── R3 — Adaptive CASE 0 for low-VIX (29 May 2026 — data-validated 82% 60m win) ──

    /** Enable the low-VIX second tier of CASE 0 (replay: 71.7% 30m, 82.1% 60m win, ~4/day). */
    @Column(name = "case0_low_vix_enabled", nullable = false)
    @ColumnDefault("true")
    private boolean case0LowVixEnabled = true;

    /** Activate low-VIX tier when current VIX < this. */
    @Column(name = "case0_low_vix_vix_threshold", nullable = false)
    @ColumnDefault("17.0")
    private double case0LowVixVixThreshold = 17.0;

    /** Op-score threshold for low-VIX tier (looser than strict tier's 80). */
    @Column(name = "case0_low_vix_op_score", nullable = false)
    @ColumnDefault("65")
    private int case0LowVixOpScoreThreshold = 65;

    /** Coil % for low-VIX tier (looser than strict tier's 0.10). */
    @Column(name = "case0_low_vix_coil_max_pct", nullable = false)
    @ColumnDefault("0.20")
    private double case0LowVixCoilMaxPct = 0.20;

    // ── R2 — Range-edge fade (29 May 2026 — data-validated 53.8% 30m / 56.5% 60m win) ──

    /** Enable range-edge fade detector (~13.6 fires/day at 54% win, addresses range-bound gap). */
    @Column(name = "range_edge_fade_enabled", nullable = false)
    @ColumnDefault("true")
    private boolean rangeEdgeFadeEnabled = true;

    /** Max 30M range to consider "ranging" (% of spot). */
    @Column(name = "range_edge_fade_range_max_pct", nullable = false)
    @ColumnDefault("0.30")
    private double rangeEdgeFadeRangeMaxPct = 0.30;

    /** Edge band — top/bottom this fraction of range triggers fade. */
    @Column(name = "range_edge_fade_edge_pct", nullable = false)
    @ColumnDefault("0.20")
    private double rangeEdgeFadeEdgePct = 0.20;

    /** Min 5-min OI build (contracts) on the trapping side to confirm fade. */
    @Column(name = "range_edge_fade_oi_build_min", nullable = false)
    @ColumnDefault("3000")
    private int rangeEdgeFadeOiBuildMin = 3000;

    // ── Theta-decay gate (applied to all new entries; also retroactively to CASE 0) ──

    /** Enable theta-decay rejection. Trades whose expected theta cost exceeds the
     *  configured % of expected gain are skipped. */
    @Column(name = "theta_decay_check_enabled", nullable = false)
    @ColumnDefault("true")
    private boolean thetaDecayCheckEnabled = true;

    /** Max % of expected gain that theta is allowed to eat. Default 30%. */
    @Column(name = "theta_decay_max_cost_pct", nullable = false)
    @ColumnDefault("30.0")
    private double thetaDecayMaxCostPct = 30.0;

    /** When this row was last modified (audit). */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Operator who made the last change (email if OAuth, or "yaml-seed"). */
    @Column(name = "updated_by", length = 200, nullable = false)
    private String updatedBy = "yaml-seed";

    /** Optional reason for the change — useful for audit. */
    @Column(name = "updated_reason", length = 500)
    private String updatedReason;

    // ── P4 tuning instrumentation ───────────────────────────────────────────

    /** Log every reject to CSV (disable after data-gathering week to save disk). */
    @Column(name = "record_every_reject", nullable = false)
    @ColumnDefault("true")
    private boolean recordEveryReject = true;

    @Column(name = "reject_sample_interval_seconds", nullable = false)
    @ColumnDefault("30")
    private int rejectSampleIntervalSeconds = 30;

    @Column(name = "matrix_reject_sample_interval_seconds", nullable = false)
    @ColumnDefault("5")
    private int matrixRejectSampleIntervalSeconds = 5;

    @Column(name = "summary_reject_top_n", nullable = false)
    @ColumnDefault("10")
    private int summaryRejectTopN = 10;

    @Column(name = "reject_episode_window_seconds", nullable = false)
    @ColumnDefault("60")
    private int rejectEpisodeWindowSeconds = 60;

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

    public boolean isLegacyTimeOfDayModeEnabled() { return legacyTimeOfDayModeEnabled; }
    public void setLegacyTimeOfDayModeEnabled(boolean v) { this.legacyTimeOfDayModeEnabled = v; }

    public boolean isCase0Enabled() { return case0Enabled; }
    public void setCase0Enabled(boolean v) { this.case0Enabled = v; }

    public boolean isCase0ShadowMode() { return case0ShadowMode; }
    public void setCase0ShadowMode(boolean v) { this.case0ShadowMode = v; }

    public int getCase0OpScoreThreshold() { return case0OpScoreThreshold; }
    public void setCase0OpScoreThreshold(int v) { this.case0OpScoreThreshold = v; }

    public double getCase0CoilMaxPct() { return case0CoilMaxPct; }
    public void setCase0CoilMaxPct(double v) { this.case0CoilMaxPct = v; }

    public double getCase0PcrSlopeMinAbs() { return case0PcrSlopeMinAbs; }
    public void setCase0PcrSlopeMinAbs(double v) { this.case0PcrSlopeMinAbs = v; }

    public boolean isCase4WatchlistBonusEnabled() { return case4WatchlistBonusEnabled; }
    public void setCase4WatchlistBonusEnabled(boolean v) { this.case4WatchlistBonusEnabled = v; }

    public boolean isCase0LowVixEnabled() { return case0LowVixEnabled; }
    public void setCase0LowVixEnabled(boolean v) { this.case0LowVixEnabled = v; }
    public double getCase0LowVixVixThreshold() { return case0LowVixVixThreshold; }
    public void setCase0LowVixVixThreshold(double v) { this.case0LowVixVixThreshold = v; }
    public int getCase0LowVixOpScoreThreshold() { return case0LowVixOpScoreThreshold; }
    public void setCase0LowVixOpScoreThreshold(int v) { this.case0LowVixOpScoreThreshold = v; }
    public double getCase0LowVixCoilMaxPct() { return case0LowVixCoilMaxPct; }
    public void setCase0LowVixCoilMaxPct(double v) { this.case0LowVixCoilMaxPct = v; }

    public boolean isRangeEdgeFadeEnabled() { return rangeEdgeFadeEnabled; }
    public void setRangeEdgeFadeEnabled(boolean v) { this.rangeEdgeFadeEnabled = v; }
    public double getRangeEdgeFadeRangeMaxPct() { return rangeEdgeFadeRangeMaxPct; }
    public void setRangeEdgeFadeRangeMaxPct(double v) { this.rangeEdgeFadeRangeMaxPct = v; }
    public double getRangeEdgeFadeEdgePct() { return rangeEdgeFadeEdgePct; }
    public void setRangeEdgeFadeEdgePct(double v) { this.rangeEdgeFadeEdgePct = v; }
    public int getRangeEdgeFadeOiBuildMin() { return rangeEdgeFadeOiBuildMin; }
    public void setRangeEdgeFadeOiBuildMin(int v) { this.rangeEdgeFadeOiBuildMin = v; }

    public boolean isThetaDecayCheckEnabled() { return thetaDecayCheckEnabled; }
    public void setThetaDecayCheckEnabled(boolean v) { this.thetaDecayCheckEnabled = v; }
    public double getThetaDecayMaxCostPct() { return thetaDecayMaxCostPct; }
    public void setThetaDecayMaxCostPct(double v) { this.thetaDecayMaxCostPct = v; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }

    public String getUpdatedReason() { return updatedReason; }
    public void setUpdatedReason(String v) { this.updatedReason = v; }

    public boolean isRecordEveryReject() { return recordEveryReject; }
    public void setRecordEveryReject(boolean v) { this.recordEveryReject = v; }
    public int getRejectSampleIntervalSeconds() { return rejectSampleIntervalSeconds; }
    public void setRejectSampleIntervalSeconds(int v) { this.rejectSampleIntervalSeconds = v; }
    public int getMatrixRejectSampleIntervalSeconds() { return matrixRejectSampleIntervalSeconds; }
    public void setMatrixRejectSampleIntervalSeconds(int v) { this.matrixRejectSampleIntervalSeconds = v; }
    public int getSummaryRejectTopN() { return summaryRejectTopN; }
    public void setSummaryRejectTopN(int v) { this.summaryRejectTopN = v; }
    public int getRejectEpisodeWindowSeconds() { return rejectEpisodeWindowSeconds; }
    public void setRejectEpisodeWindowSeconds(int v) { this.rejectEpisodeWindowSeconds = v; }
}
