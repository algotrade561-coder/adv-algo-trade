package com.algo.trade.tuning.change;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * §10c — an audit record of a tuning config change, so the loop can <em>measure</em> whether it helped.
 * The change itself is applied through the existing (guarded) settings UI; this row tags <em>what</em>
 * changed, <em>when</em>, by <em>whom</em>, and which report finding motivated it. The
 * {@code ChangeImpactAnalyzerPlugin} reads these to show before/after a change date.
 *
 * <p>Deliberately decoupled from live config: recording a change here does NOT write trading config (that
 * stays a human action in the settings screen) — it only logs the decision so improvements are attributable
 * and reversible-by-record. This is the safe half of §10c; programmatic apply-from-text is intentionally not
 * built (a wrong write trades real money).
 */
@Entity
@Table(name = "config_change_log", indexes = {
        @Index(name = "idx_ccl_changed_at", columnList = "changed_at")
})
public class ConfigChangeLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    @Column(name = "changed_by", length = 64)
    private String changedBy;

    /** Logical area, e.g. "gate", "risk-profile", "exit", "regime". */
    @Column(name = "area", length = 64)
    private String area;

    /** Config field/key changed, e.g. "chargesGateMinNetProfit". */
    @Column(name = "field", length = 128)
    private String field;

    @Column(name = "old_value", length = 256)
    private String oldValue;

    @Column(name = "new_value", length = 256)
    private String newValue;

    /** Free-text rationale, ideally citing the finding (e.g. "charges gate would-profit 41% over 184 rejects"). */
    @Column(name = "note", length = 1024)
    private String note;

    /** The report job whose actions.json motivated the change (optional). */
    @Column(name = "source_job_id", length = 64)
    private String sourceJobId;

    @Column(name = "reverted", nullable = false)
    private boolean reverted = false;

    public ConfigChangeLog() {}

    public Long getId() { return id; }
    public Instant getChangedAt() { return changedAt; }
    public void setChangedAt(Instant changedAt) { this.changedAt = changedAt; }
    public String getChangedBy() { return changedBy; }
    public void setChangedBy(String changedBy) { this.changedBy = changedBy; }
    public String getArea() { return area; }
    public void setArea(String area) { this.area = area; }
    public String getField() { return field; }
    public void setField(String field) { this.field = field; }
    public String getOldValue() { return oldValue; }
    public void setOldValue(String oldValue) { this.oldValue = oldValue; }
    public String getNewValue() { return newValue; }
    public void setNewValue(String newValue) { this.newValue = newValue; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getSourceJobId() { return sourceJobId; }
    public void setSourceJobId(String sourceJobId) { this.sourceJobId = sourceJobId; }
    public boolean isReverted() { return reverted; }
    public void setReverted(boolean reverted) { this.reverted = reverted; }
}
