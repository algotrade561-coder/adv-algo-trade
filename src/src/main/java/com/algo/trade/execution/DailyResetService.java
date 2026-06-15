package com.algo.trade.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Daily Reset Service — calls resetDaily() on all DailyResettable beans at midnight.
 * Provides a clean extension point for components that need state cleared daily.
 */
@Service
public class DailyResetService {

    private static final Logger log = LoggerFactory.getLogger(DailyResetService.class);

    private final List<DailyResettable> resettables;

    public DailyResetService(List<DailyResettable> resettables) {
        this.resettables = resettables;
        log.info("[DailyReset] Registered {} resettable components", resettables.size());
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void resetAll() {
        log.info("[DailyReset] Resetting {} components for new trading day", resettables.size());
        for (DailyResettable r : resettables) {
            try {
                r.resetDaily();
            } catch (Exception e) {
                log.error("[DailyReset] Error resetting {}: {}", r.getClass().getSimpleName(), e.getMessage());
            }
        }
    }
}
