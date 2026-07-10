package com.algo.trade.reporting;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.analyzer.TuningAnalyzerCoordinator;
import com.algo.trade.tuning.analyzer.TuningReport;
import com.algo.trade.tuning.capture.TuningReportJobEntity;
import com.algo.trade.tuning.capture.TuningReportJobRepository;
import com.algo.trade.tuning.capture.TuningReportJobStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Builds the tuning HTML report <strong>in-process</strong> on a Spring {@code @Async}
 * thread. Replaces the previous forked-JVM design — DuckDB runs in native (off-heap)
 * memory, so loading the analyzer in the trading JVM has near-zero JVM-heap impact,
 * and the report is scheduled at 15:30 IST when the trading scanner is already idle.
 *
 * <p><b>Why in-process now</b>: the original forked-JVM build was a defensive design
 * around the legacy {@code SignalTuningAnalyzer}, which loaded {@code entry-candles.csv}
 * (~750 MB) into the JVM heap and crashed with OOM. That analyzer was retired in
 * Phase 6; the current DuckDB-backed pipeline orchestrates SQL queries and writes
 * HTML — heap usage is negligible. Forking added 10-15 s JVM boot, a separate Spring
 * context with profile-gated component scan, JAR-path version drift, and required
 * {@code @Profile("!tuning-report")} annotations on every controller. Eliminating
 * the fork removes all of that complexity.</p>
 *
 * <p><b>Public API preserved</b> from the previous {@code ForkedJvmReportService} so
 * callers — {@code TuningReportController}, {@code TuningHealthController},
 * {@code ReportingService}, {@code SignalTuningScheduler} — keep working unchanged.</p>
 */
@Service
public class TuningReportService {

    private static final Logger log = LoggerFactory.getLogger(TuningReportService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    /**
     * Cool-down between submits. Short because the in-process analyzer is cheap —
     * just enough to prevent accidental double-clicks. Bypassable with {@code force=true}.
     */
    private static final Duration COOLDOWN = Duration.ofMinutes(1);

    private final TuningReportJobRepository jobRepo;
    private final SignalTuningProperties tuningProperties;
    private final TuningAnalyzerCoordinator coordinator;
    private final com.algo.trade.tuning.analyzer.TuningActionSynthesizer actionSynthesizer;
    private final com.algo.trade.tuning.store.ParquetRollerService parquetRoller;

    @Value("${tuning.report.output-dir:reports/tuning/html}")
    private String outputDir;

    public TuningReportService(TuningReportJobRepository jobRepo,
                               SignalTuningProperties tuningProperties,
                               TuningAnalyzerCoordinator coordinator,
                               com.algo.trade.tuning.analyzer.TuningActionSynthesizer actionSynthesizer,
                               com.algo.trade.tuning.store.ParquetRollerService parquetRoller) {
        this.jobRepo = jobRepo;
        this.tuningProperties = tuningProperties;
        this.coordinator = coordinator;
        this.actionSynthesizer = actionSynthesizer;
        this.parquetRoller = parquetRoller;
    }

    public String submit(LocalDate from, LocalDate to, Set<StrategyType> strategies,
                         String requestedBy, boolean forced, String forceReason) {
        if (!forced && tuningProperties.isBlockDuringMarketHours() && isMarketHours()) {
            throw new MarketHoursBlockedException(nextEligibleSlot());
        }
        if (!forced) {
            Optional<Duration> cooldown = cooldownRemaining();
            if (cooldown.isPresent()) {
                throw new ReportCooldownException(cooldown.get());
            }
        }

        String jobId = "JOB-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase();
        String strategyCsv = strategies.stream().map(Enum::name).collect(Collectors.joining(","));
        TuningReportJobEntity job = new TuningReportJobEntity(
                jobId, requestedBy, strategyCsv, from, to, forced, forceReason);
        job.setOutputHtml(Path.of(outputDir, jobId + ".html").toString().replace("\\", "/"));
        jobRepo.save(job);
        runAsync(job, strategies);
        return jobId;
    }

    /**
     * Automated EOD tuning report (#2): the report was UI/manual-only despite the class doc claiming a
     * schedule. Fire post-close at 15:40 IST (after ParquetRoller's 15:30 roll, while the box is still up
     * ~until 16:05) for all strategies, forced (bypass the market-hours block + cooldown). Spring @Scheduled
     * honors the {@code zone} (unlike the box's system cron which ignores CRON_TZ). (2026-07-02)
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 40 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledEodReport() {
        try {
            LocalDate today = LocalDate.now(IST);
            String jobId = submit(today, today, java.util.EnumSet.allOf(StrategyType.class),
                    "auto-eod", true, "scheduled EOD 15:40 IST");
            log.info("[TuningReport] auto-EOD report submitted: jobId={} date={}", jobId, today);
        } catch (Exception ex) {
            log.warn("[TuningReport] auto-EOD report failed to submit (non-fatal): {}", ex.getMessage());
        }
    }

    public Optional<TuningReportJobEntity> findJob(String jobId) {
        return jobRepo.findById(jobId);
    }

    public List<TuningReportJobEntity> recentJobs(int limit) {
        return jobRepo.findAllByOrderByRequestedAtDesc(PageRequest.of(0, Math.max(1, limit)));
    }

    public String readHtml(String jobId) {
        TuningReportJobEntity job = jobRepo.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown job: " + jobId));
        if (job.getStatus() != TuningReportJobStatus.COMPLETE) {
            throw new IllegalStateException("Job not complete: " + job.getStatus());
        }
        Path path = job.getOutputHtml() != null
                ? Path.of(job.getOutputHtml())
                : Path.of(outputDir, jobId + ".html");
        try {
            return Files.readString(path);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read report HTML: " + path, ex);
        }
    }

    /** Reads the ranked tuning actions JSON written alongside the HTML; {@code {"actions":[]}} if absent. */
    public String readActions(String jobId) {
        TuningReportJobEntity job = jobRepo.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown job: " + jobId));
        if (job.getStatus() != TuningReportJobStatus.COMPLETE) {
            throw new IllegalStateException("Job not complete: " + job.getStatus());
        }
        Path html = job.getOutputHtml() != null
                ? Path.of(job.getOutputHtml())
                : Path.of(outputDir, jobId + ".html");
        Path actions = html.resolveSibling(
                html.getFileName().toString().replaceFirst("\\.html$", "") + "-actions.json");
        try {
            return Files.exists(actions) ? Files.readString(actions) : "{\"actions\":[]}";
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to read actions: " + actions, ex);
        }
    }

    /**
     * Runs the analyzer on a Spring {@code @Async} thread. The trading JVM's scheduler
     * already runs on a separate executor; this @Async puts the long-running report
     * build on the application's default async pool so the REST submit returns
     * immediately with the queued job ID.
     */
    @Async
    public void runAsync(TuningReportJobEntity job, Set<StrategyType> strategies) {
        Instant started = Instant.now();
        job.setStatus(TuningReportJobStatus.RUNNING);
        job.setStartedAt(started);
        jobRepo.save(job);

        try {
            log.info("[TuningReport] job {} starting in-process: {} → {} for {} strategies",
                    job.getJobId(), job.getFromDate(), job.getToDate(), strategies.size());

            // Roll-on-demand (§3d): archive any still-un-rolled PAST days in the requested range to Parquet
            // before analysing, so a day stranded by a missed 15:30 roll is read via the archive. Today is
            // left as CSV (its session isn't complete and forward checkpoints land next morning); EventScan
            // reads CSV ∪ Parquet, so both are covered. Best-effort — never block the report on a roll.
            try {
                LocalDate todayIst = LocalDate.now(IST);
                List<LocalDate> pastDates = new java.util.ArrayList<>();
                for (LocalDate d = job.getFromDate();
                     !d.isAfter(job.getToDate()); d = d.plusDays(1)) {
                    if (d.isBefore(todayIst)) {
                        pastDates.add(d);
                    }
                }
                if (!pastDates.isEmpty()) {
                    parquetRoller.rollDatesOnDemand(pastDates);
                }
            } catch (Exception rex) {
                log.warn("[TuningReport] job {} roll-on-demand failed (non-fatal): {}",
                        job.getJobId(), rex.getMessage());
            }

            TuningReport report = coordinator.analyze(job.getFromDate(), job.getToDate(), strategies);

            Path outFile = Path.of(job.getOutputHtml() != null
                    ? job.getOutputHtml()
                    : Path.of(outputDir, job.getJobId() + ".html").toString());
            Files.createDirectories(outFile.getParent());
            Files.writeString(outFile, report.renderHtml());

            // P2 / §3d — emit the machine-readable actions.json next to the HTML (best-effort).
            try {
                Path actionsFile = outFile.resolveSibling(
                        outFile.getFileName().toString().replaceFirst("\\.html$", "") + "-actions.json");
                Files.writeString(actionsFile, actionSynthesizer.toJson(report));
                log.info("[TuningReport] job {} wrote actions: {}", job.getJobId(), actionsFile);
            } catch (Exception ax) {
                log.warn("[TuningReport] job {} actions.json failed (non-fatal): {}", job.getJobId(), ax.getMessage());
            }

            job.setStatus(TuningReportJobStatus.COMPLETE);
            job.setFinishedAt(Instant.now());
            job.setDurationSec((int) Duration.between(started, job.getFinishedAt()).getSeconds());
            jobRepo.save(job);
            log.info("[TuningReport] job {} complete: {} sections, {}s",
                    job.getJobId(), report.sectionCount(), job.getDurationSec());
        } catch (Exception ex) {
            log.warn("[TuningReport] job {} failed: {}", job.getJobId(), ex.getMessage(), ex);
            failJob(job, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    private void failJob(TuningReportJobEntity job, String message) {
        job.setStatus(TuningReportJobStatus.FAILED);
        job.setFinishedAt(Instant.now());
        job.setErrorMessage(message != null && message.length() > 1000
                ? message.substring(0, 1000) : message);
        jobRepo.save(job);
    }

    boolean isMarketHours() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        DayOfWeek dow = now.getDayOfWeek();
        if (dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY) {
            return false;
        }
        LocalTime t = now.toLocalTime();
        return !t.isBefore(LocalTime.of(9, 15)) && t.isBefore(LocalTime.of(15, 30));
    }

    ZonedDateTime nextEligibleSlot() {
        ZonedDateTime now = ZonedDateTime.now(IST);
        ZonedDateTime close = now.with(LocalTime.of(15, 30));
        if (now.isBefore(close) && isMarketHours()) {
            return close;
        }
        return now.plusMinutes(1);
    }

    /**
     * 15-min cool-down between report generations. Failed / killed jobs (no resources
     * consumed) don't trigger the lockout — only successfully-launched runs do.
     */
    Optional<Duration> cooldownRemaining() {
        List<TuningReportJobEntity> recent = jobRepo.findAllByOrderByRequestedAtDesc(PageRequest.of(0, 10));
        for (TuningReportJobEntity job : recent) {
            if (job.getRequestedAt() == null) {
                continue;
            }
            TuningReportJobStatus status = job.getStatus();
            if (status == TuningReportJobStatus.FAILED || status == TuningReportJobStatus.KILLED) {
                continue;
            }
            Duration since = Duration.between(job.getRequestedAt(), Instant.now());
            if (since.compareTo(COOLDOWN) < 0) {
                return Optional.of(COOLDOWN.minus(since));
            }
            break;
        }
        return Optional.empty();
    }
}
