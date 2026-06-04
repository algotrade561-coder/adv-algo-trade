package com.algo.trade.tuning.capture;

import com.algo.trade.strategy.StrategyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.ColumnDefault;

/**
 * Per-strategy capture toggle row. One row per registered strategy adapter.
 *
 * <p>As of 4 Jun 2026, all capture defaults are <strong>ON</strong>. Fresh DB
 * deployments and newly-registered strategy adapters auto-enable capture so a
 * brand new run accumulates tuning data from day one. The operator can flip
 * any strategy back OFF via the {@code /settings/tuning-capture} UI page or
 * the {@code PUT /tuning/capture/{strategy}} endpoint.</p>
 *
 * <p>Per-event-type flags ({@code captureEvaluations}, {@code captureSignals}, etc.)
 * also default to {@code true} so the master switch alone enables full capture
 * unless the user opts to drop a specific event type (e.g. skip high-volume
 * evaluations while keeping signal/exit data).</p>
 */
@Entity
@Table(name = "tuning_capture_config")
public class TuningCaptureConfigEntity {

    /** Primary key. Uses the {@link StrategyType} enum name so we never have orphan rows. */
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", length = 64, nullable = false)
    private StrategyType strategy;

    @Column(name = "capture_enabled", nullable = false)
    @ColumnDefault("true")
    private boolean captureEnabled = true;

    @Column(name = "capture_evaluations", nullable = false)
    @ColumnDefault("true")
    private boolean captureEvaluations = true;

    @Column(name = "capture_signals", nullable = false)
    @ColumnDefault("true")
    private boolean captureSignals = true;

    @Column(name = "capture_executions", nullable = false)
    @ColumnDefault("true")
    private boolean captureExecutions = true;

    @Column(name = "capture_exits", nullable = false)
    @ColumnDefault("true")
    private boolean captureExits = true;

    @Column(name = "capture_forward", nullable = false)
    @ColumnDefault("true")
    private boolean captureForward = true;

    @Column(name = "capture_shadow", nullable = false)
    @ColumnDefault("true")
    private boolean captureShadow = true;

    @Column(name = "episode_window_sec", nullable = false)
    @ColumnDefault("60")
    private int episodeWindowSec = 60;

    @Column(name = "notes", length = 255)
    private String notes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by", length = 64)
    private String updatedBy;

    // ── Getters / setters ─────────────────────────────────────────────────

    public StrategyType getStrategy() { return strategy; }
    public void setStrategy(StrategyType strategy) { this.strategy = strategy; }

    public boolean isCaptureEnabled() { return captureEnabled; }
    public void setCaptureEnabled(boolean v) { this.captureEnabled = v; }

    public boolean isCaptureEvaluations() { return captureEvaluations; }
    public void setCaptureEvaluations(boolean v) { this.captureEvaluations = v; }

    public boolean isCaptureSignals() { return captureSignals; }
    public void setCaptureSignals(boolean v) { this.captureSignals = v; }

    public boolean isCaptureExecutions() { return captureExecutions; }
    public void setCaptureExecutions(boolean v) { this.captureExecutions = v; }

    public boolean isCaptureExits() { return captureExits; }
    public void setCaptureExits(boolean v) { this.captureExits = v; }

    public boolean isCaptureForward() { return captureForward; }
    public void setCaptureForward(boolean v) { this.captureForward = v; }

    public boolean isCaptureShadow() { return captureShadow; }
    public void setCaptureShadow(boolean v) { this.captureShadow = v; }

    public int getEpisodeWindowSec() { return episodeWindowSec; }
    public void setEpisodeWindowSec(int v) { this.episodeWindowSec = Math.max(1, v); }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant v) { this.updatedAt = v; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String v) { this.updatedBy = v; }
}
