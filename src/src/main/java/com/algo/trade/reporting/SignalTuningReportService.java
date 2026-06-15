package com.algo.trade.reporting;

import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.capture.TuningReportJobStatus;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Thin facade that adds Telegram notification on top of {@link TuningReportService}.
 * Kept as a separate class because {@code SignalTuningScheduler} (15:30 IST cron)
 * wants the notification side-effect, while controllers want bare submit / poll / read.
 */
@Service
public class SignalTuningReportService {

    private static final Logger log = LoggerFactory.getLogger(SignalTuningReportService.class);

    private final TelegramAlertService telegramAlertService;
    private final TuningReportService reportService;

    public SignalTuningReportService(TelegramAlertService telegramAlertService,
                                     TuningReportService reportService) {
        this.telegramAlertService = telegramAlertService;
        this.reportService = reportService;
    }

    public TuningRunResult generate() {
        LocalDate to = LocalDate.now();
        LocalDate from = to.minusDays(7);
        String jobId = reportService.submit(
                from, to, EnumSet.allOf(StrategyType.class), "scheduler", false, null);
        String summary = "📊 Tuning report submitted (in-process)\nJob: " + jobId
                + "\nPeriod: " + from + " → " + to
                + "\nPoll GET /reports/tuning/jobs/" + jobId;
        telegramAlertService.signalTuningReport(summary);
        log.info("Tuning report job submitted: {}", jobId);
        return new TuningRunResult(Instant.now(), Path.of("reports", "tuning", "html", jobId + ".html").toString(),
                0, 0, 0, summary);
    }

    public String readHtml(Path path) {
        return reportService.readHtml(path.getFileName().toString().replace(".html", ""));
    }

    public Optional<Path> latestHtmlReport() {
        return reportService.recentJobs(1).stream()
                .filter(j -> j.getStatus() == TuningReportJobStatus.COMPLETE && j.getOutputHtml() != null)
                .map(j -> Path.of(j.getOutputHtml()))
                .findFirst();
    }

    public record TuningRunResult(
            Instant generatedAt,
            String htmlReportPath,
            long totalEvaluations,
            long buySignals,
            int recommendationCount,
            String telegramSummary
    ) {
    }
}
