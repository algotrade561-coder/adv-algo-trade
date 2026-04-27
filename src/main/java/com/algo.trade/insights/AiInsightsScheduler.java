package com.algo.trade.insights;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;

@Component
public class AiInsightsScheduler {

    private static final Logger log = LoggerFactory.getLogger(AiInsightsScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AiAnalysisService aiAnalysisService;

    public AiInsightsScheduler(AiAnalysisService aiAnalysisService) {
        this.aiAnalysisService = aiAnalysisService;
    }

    /** Runs at 9, 10, 11, 12, 13, 14, 15 IST on weekdays. */
    @Scheduled(cron = "0 0 9,12,15 * * MON-FRI", zone = "Asia/Kolkata")
    public void runHourlyAnalysis() {
        LocalTime now = LocalTime.now(IST);
        String runType = now.getHour() == 9 ? "MARKET_OPEN" : "HOURLY";
        log.info("AI insights scheduler fired: runType={}, time={}", runType, now);
        try {
            aiAnalysisService.runAnalysis(runType);
        } catch (Exception e) {
            log.error("AI insights scheduler failed: {}", e.getMessage(), e);
        }
    }
}
