package com.algo.trade.tuning.capture;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Metadata for a single tuning-report generation job. The trading JVM owns this row;
 * the forked analyzer JVM reads it via command-line args (it does not connect to H2).
 *
 * <p>Lifecycle: created with {@link TuningReportJobStatus#QUEUED} when the user clicks
 * Generate. Updated to {@link TuningReportJobStatus#RUNNING} when the parent process
 * spawns the forked JVM. Updated to {@link TuningReportJobStatus#COMPLETE} or
 * {@link TuningReportJobStatus#FAILED} when the child exits. The 15-minute total
 * wall-clock cap is enforced by the parent, which can flip the status to
 * {@link TuningReportJobStatus#KILLED}.</p>
 */
@Entity
@Table(name = "tuning_report_jobs", indexes = {
        @Index(name = "idx_report_jobs_requested", columnList = "requestedAt"),
        @Index(name = "idx_report_jobs_status", columnList = "status")
})
public class TuningReportJobEntity {

    @Id
    @Column(name = "job_id", length = 64, nullable = false)
    private String jobId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "requested_by", length = 64)
    private String requestedBy;

    /** Comma-separated {@link com.algo.trade.strategy.StrategyType} names. */
    @Column(name = "strategies", length = 512, nullable = false)
    private String strategies;

    @Column(name = "from_date", nullable = false)
    private LocalDate fromDate;

    @Column(name = "to_date", nullable = false)
    private LocalDate toDate;

    @Column(name = "forced", nullable = false)
    private boolean forced = false;

    @Column(name = "force_reason", length = 255)
    private String forceReason;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private TuningReportJobStatus status = TuningReportJobStatus.QUEUED;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "duration_sec")
    private Integer durationSec;

    @Column(name = "rows_scanned")
    private Long rowsScanned;

    @Column(name = "output_html", length = 512)
    private String outputHtml;

    @Column(name = "error_message", length = 1024)
    private String errorMessage;

    public TuningReportJobEntity() {}

    public TuningReportJobEntity(String jobId, String requestedBy, String strategies,
                                  LocalDate fromDate, LocalDate toDate,
                                  boolean forced, String forceReason) {
        this.jobId = jobId;
        this.requestedBy = requestedBy;
        this.strategies = strategies;
        this.fromDate = fromDate;
        this.toDate = toDate;
        this.forced = forced;
        this.forceReason = forceReason;
        this.requestedAt = Instant.now();
    }

    public String getJobId() { return jobId; }
    public void setJobId(String jobId) { this.jobId = jobId; }

    public Instant getRequestedAt() { return requestedAt; }
    public void setRequestedAt(Instant v) { this.requestedAt = v; }

    public String getRequestedBy() { return requestedBy; }
    public void setRequestedBy(String v) { this.requestedBy = v; }

    public String getStrategies() { return strategies; }
    public void setStrategies(String v) { this.strategies = v; }

    public LocalDate getFromDate() { return fromDate; }
    public void setFromDate(LocalDate v) { this.fromDate = v; }

    public LocalDate getToDate() { return toDate; }
    public void setToDate(LocalDate v) { this.toDate = v; }

    public boolean isForced() { return forced; }
    public void setForced(boolean v) { this.forced = v; }

    public String getForceReason() { return forceReason; }
    public void setForceReason(String v) { this.forceReason = v; }

    public TuningReportJobStatus getStatus() { return status; }
    public void setStatus(TuningReportJobStatus v) { this.status = v; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }

    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant v) { this.finishedAt = v; }

    public Integer getDurationSec() { return durationSec; }
    public void setDurationSec(Integer v) { this.durationSec = v; }

    public Long getRowsScanned() { return rowsScanned; }
    public void setRowsScanned(Long v) { this.rowsScanned = v; }

    public String getOutputHtml() { return outputHtml; }
    public void setOutputHtml(String v) { this.outputHtml = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }
}
