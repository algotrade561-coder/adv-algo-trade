package com.algo.trade.persistence;

import com.algo.trade.execution.exit.ExitEvaluationSnapshot;
import com.algo.trade.execution.exit.ExitMode;
import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted exit evaluation snapshot for post-mortem audit.
 * One row per evaluation cycle per position (latest overwrites previous).
 */
@Entity
@Table(name = "exit_evaluations", indexes = {
        @Index(name = "idx_ee_position_id", columnList = "positionId")
})
public class ExitEvaluationEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String positionId;

    private String positionType;
    private String strategyType;
    private String underlying;
    private Instant evaluatedAt;
    private double profitPercent;
    private double peakProfitPercent;
    private double effectiveStopLossPercent;
    private double effectiveTargetPercent;
    private double effectiveTrailActivationPercent;
    private double effectiveTrailGapPercent;
    private boolean atrUsed;
    private String exitMode;
    private String decision;

    @Column(length = 500)
    private String detail;

    protected ExitEvaluationEntity() {}

    public static ExitEvaluationEntity from(ExitEvaluationSnapshot snapshot) {
        ExitEvaluationEntity e = new ExitEvaluationEntity();
        e.positionId = snapshot.positionId();
        e.positionType = snapshot.positionType();
        e.strategyType = snapshot.strategyType();
        e.underlying = snapshot.underlying();
        e.evaluatedAt = snapshot.evaluatedAt();
        e.profitPercent = snapshot.profitPercent();
        e.peakProfitPercent = snapshot.peakProfitPercent();
        e.effectiveStopLossPercent = snapshot.effectiveStopLossPercent();
        e.effectiveTargetPercent = snapshot.effectiveTargetPercent();
        e.effectiveTrailActivationPercent = snapshot.effectiveTrailActivationPercent();
        e.effectiveTrailGapPercent = snapshot.effectiveTrailGapPercent();
        e.atrUsed = snapshot.atrUsed();
        e.exitMode = snapshot.exitMode() != null ? snapshot.exitMode().name() : null;
        e.decision = snapshot.decision();
        e.detail = snapshot.detail();
        return e;
    }

    public Long getId() { return id; }
    public String getPositionId() { return positionId; }
    public String getDecision() { return decision; }
    public Instant getEvaluatedAt() { return evaluatedAt; }
}
