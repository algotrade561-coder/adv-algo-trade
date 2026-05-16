package com.algo.trade.reporting;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Generates the signal-tuning HTML report and Telegram summary after the cash session.
 */
@Component
public class SignalTuningScheduler {

    private static final Logger log = LoggerFactory.getLogger(SignalTuningScheduler.class);

    private final SignalTuningReportService reportService;

    @Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    public SignalTuningScheduler(SignalTuningReportService reportService) {
        this.reportService = reportService;
    }

    /** Weekdays 15:30 IST — end-of-day tuning report from {@code reports/entry-signals} CSVs. */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void generatePostMarketReport() {
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("signalTuning")) {
            log.debug("Signal tuning scheduler skipped: disabled in registry");
            return;
        }
        if (schedulerRegistry != null) {
            schedulerRegistry.recordRun("signalTuning");
        }
        log.info("Signal tuning scheduler fired");
        try {
            SignalTuningReportService.TuningRunResult result = reportService.generate();
            log.info("Signal tuning scheduler completed: html={}, evals={}, buys={}",
                    result.htmlReportPath(),
                    result.totalEvaluations(),
                    result.buySignals());
        } catch (Exception ex) {
            log.error("Signal tuning scheduler failed: {}", ex.getMessage(), ex);
            if (errorEventService != null) {
                errorEventService.low("SignalTuning", "Signal tuning report failed: " + ex.getMessage());
            }
        }
    }
}
