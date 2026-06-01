package com.algo.trade.strategy.oishifttrap;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

/**
 * Single-row JPA entity holding the OI Shift Trap limit-ladder runtime
 * settings. Replaces the old arm-and-confirm runtime config.
 *
 * <p>The ladder places three resting limit BUY orders below the arm premium
 * when a legacy trap candidate appears. Tier discounts are operator-tunable
 * via the Settings UI; everything else is a thin safety wrapper.</p>
 *
 * <p>Mode enum:</p>
 * <ul>
 *   <li>{@code OFF}    — ladder is disabled, legacy strategy fires unchanged.</li>
 *   <li>{@code SHADOW} — ladder simulates fills against the live LTP stream
 *       and records diagnostics, but does not place any live orders. Legacy
 *       strategy ALSO does not fire — the ladder is the chosen entry layer.</li>
 *   <li>{@code LIVE}   — ladder emits real {@code StrategyDecision}s for each
 *       virtually-filled tier; legacy entry is replaced.</li>
 * </ul>
 *
 * <p>Default is {@code OFF} — out-of-the-box behaviour is identical to the
 * legacy strategy.</p>
 */
@Entity
@Table(name = "oi_shift_trap_ladder_config")
public class OiShiftTrapLadderConfig {

    @Id
    private Long id = 1L;

    /** Ladder mode: OFF / SHADOW / LIVE. Stored as upper-case string. */
    @Column(name = "ladder_mode", nullable = false, length = 16)
    @ColumnDefault("'OFF'")
    private String ladderMode = "OFF";

    /** Tier 1 discount as a fraction (0.03 = 3% below arm). */
    @Column(name = "tier1_discount", nullable = false)
    @ColumnDefault("0.03")
    private double tier1Discount = 0.03;

    /** Tier 2 discount as a fraction. */
    @Column(name = "tier2_discount", nullable = false)
    @ColumnDefault("0.06")
    private double tier2Discount = 0.06;

    /** Tier 3 discount as a fraction. */
    @Column(name = "tier3_discount", nullable = false)
    @ColumnDefault("0.10")
    private double tier3Discount = 0.10;

    /** Auto-cancel any unfilled tier after this many minutes from arm. */
    @Column(name = "ladder_window_min", nullable = false)
    @ColumnDefault("30")
    private int ladderWindowMin = 30;

    /** Minimum operator score required at arm time to place the ladder. */
    @Column(name = "ladder_op_score_arm_floor", nullable = false)
    @ColumnDefault("50")
    private int ladderOpScoreArmFloor = 50;

    /** Cancel all unfilled tiers if op-score drops by &gt;= this since arm. */
    @Column(name = "ladder_op_score_cancel_delta", nullable = false)
    @ColumnDefault("20")
    private int ladderOpScoreCancelDelta = 20;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 200, nullable = false)
    private String updatedBy = "yaml-seed";

    @Column(name = "updated_reason", length = 500)
    private String updatedReason;

    // ── Getters / setters ────────────────────────────────────────────────

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getLadderMode() { return ladderMode; }
    public void setLadderMode(String v) { this.ladderMode = v; }
    public double getTier1Discount() { return tier1Discount; }
    public void setTier1Discount(double v) { this.tier1Discount = v; }
    public double getTier2Discount() { return tier2Discount; }
    public void setTier2Discount(double v) { this.tier2Discount = v; }
    public double getTier3Discount() { return tier3Discount; }
    public void setTier3Discount(double v) { this.tier3Discount = v; }
    public int getLadderWindowMin() { return ladderWindowMin; }
    public void setLadderWindowMin(int v) { this.ladderWindowMin = v; }
    public int getLadderOpScoreArmFloor() { return ladderOpScoreArmFloor; }
    public void setLadderOpScoreArmFloor(int v) { this.ladderOpScoreArmFloor = v; }
    public int getLadderOpScoreCancelDelta() { return ladderOpScoreCancelDelta; }
    public void setLadderOpScoreCancelDelta(int v) { this.ladderOpScoreCancelDelta = v; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }
    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
    public String getUpdatedReason() { return updatedReason; }
    public void setUpdatedReason(String v) { this.updatedReason = v; }

    // ── Convenience ──────────────────────────────────────────────────────

    public boolean isEnabled() {
        return ladderMode != null && !"OFF".equalsIgnoreCase(ladderMode);
    }

    public boolean isShadow() {
        return "SHADOW".equalsIgnoreCase(ladderMode);
    }

    public boolean isLive() {
        return "LIVE".equalsIgnoreCase(ladderMode);
    }
}
