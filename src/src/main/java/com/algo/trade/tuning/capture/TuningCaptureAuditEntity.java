package com.algo.trade.tuning.capture;

import com.algo.trade.strategy.StrategyType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * Change log for {@link TuningCaptureConfigEntity}. One row per field changed.
 *
 * <p>Surfaces in the live dashboard's "Recent anomalies" feed when a capture
 * toggle was flipped during market hours — provides the audit trail for
 * "why did capture stop / start at 10:42 yesterday."</p>
 */
@Entity
@Table(name = "tuning_capture_audit", indexes = {
        @Index(name = "idx_capture_audit_strategy_time", columnList = "strategy,changedAt"),
        @Index(name = "idx_capture_audit_time", columnList = "changedAt")
})
public class TuningCaptureAuditEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt = Instant.now();

    @Column(name = "changed_by", length = 64)
    private String changedBy;

    @Enumerated(EnumType.STRING)
    @Column(name = "strategy", length = 64, nullable = false)
    private StrategyType strategy;

    @Column(name = "field_name", length = 64, nullable = false)
    private String fieldName;

    @Column(name = "old_value", length = 256)
    private String oldValue;

    @Column(name = "new_value", length = 256)
    private String newValue;

    @Column(name = "reason", length = 255)
    private String reason;

    public TuningCaptureAuditEntity() {}

    public TuningCaptureAuditEntity(StrategyType strategy, String fieldName,
                                    String oldValue, String newValue,
                                    String changedBy, String reason) {
        this.strategy = strategy;
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedBy = changedBy;
        this.reason = reason;
        this.changedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Instant getChangedAt() { return changedAt; }
    public String getChangedBy() { return changedBy; }
    public StrategyType getStrategy() { return strategy; }
    public String getFieldName() { return fieldName; }
    public String getOldValue() { return oldValue; }
    public String getNewValue() { return newValue; }
    public String getReason() { return reason; }
}
