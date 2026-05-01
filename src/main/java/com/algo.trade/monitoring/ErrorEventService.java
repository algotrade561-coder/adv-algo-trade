package com.algo.trade.monitoring;

import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.ErrorEventEntity;
import com.algo.trade.persistence.ErrorEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Unified error handling service — the single funnel for all application errors.
 *
 * Every component calls this service when something goes wrong. It:
 *   1. Persists to ErrorEventEntity (DB)
 *   2. Logs to the DIAGNOSTICS logger
 *   3. Sends Telegram for CRITICAL/HIGH severity (rate-limited per component)
 *   4. Increments SystemDiagnosticsService counters
 *   5. Exposes recent errors for the UI
 *
 * Severity levels:
 *   CRITICAL — Telegram immediately (broker auth, position sync, order rejection, exit failure)
 *   HIGH     — Telegram with rate limit (WebSocket disconnect, data staleness, DB errors)
 *   MEDIUM   — Log + persist only (REST fallback, candle gap, config issues)
 *   LOW      — Log only (cache miss, debug-level operational events)
 */
@Service
public class ErrorEventService {

    private static final Logger log = LoggerFactory.getLogger(ErrorEventService.class);
    private static final Logger diagLog = LoggerFactory.getLogger("DIAGNOSTICS");
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Rate limit: max 1 Telegram alert per component per 5 minutes for HIGH severity. */
    private static final long RATE_LIMIT_MILLIS = 5 * 60 * 1000;

    private final ErrorEventRepository errorEventRepository;
    private final TelegramAlertService telegramAlertService;

    /** component → last alert timestamp for rate limiting. */
    private final Map<String, Long> lastAlertTime = new ConcurrentHashMap<>();

    public ErrorEventService(ErrorEventRepository errorEventRepository,
                              TelegramAlertService telegramAlertService) {
        this.errorEventRepository = errorEventRepository;
        this.telegramAlertService = telegramAlertService;
    }

    // ── Convenience methods for each severity ─────────────────────────────

    /** CRITICAL — always persists + always sends Telegram immediately. */
    public void critical(String component, String message) {
        record(Severity.CRITICAL, component, message, null);
    }

    public void critical(String component, String message, Throwable cause) {
        record(Severity.CRITICAL, component, message, cause);
    }

    /** HIGH — persists + sends Telegram with rate limiting per component. */
    public void high(String component, String message) {
        record(Severity.HIGH, component, message, null);
    }

    public void high(String component, String message, Throwable cause) {
        record(Severity.HIGH, component, message, cause);
    }

    /** MEDIUM — persists + logs to DIAGNOSTICS. No Telegram. */
    public void medium(String component, String message) {
        record(Severity.MEDIUM, component, message, null);
    }

    public void medium(String component, String message, Throwable cause) {
        record(Severity.MEDIUM, component, message, cause);
    }

    /** LOW — logs to DIAGNOSTICS only. No DB persistence. */
    public void low(String component, String message) {
        diagLog.info("LOW [{}] {}", component, message);
    }

    // ── Core recording logic ──────────────────────────────────────────────

    private void record(Severity severity, String component, String message, Throwable cause) {
        Instant now = Instant.now();

        // 1. Log to DIAGNOSTICS logger
        String logMsg = severity + " [" + component + "] " + message;
        if (cause != null) {
            logMsg += " | Cause: " + rootCauseMessage(cause);
        }
        switch (severity) {
            case CRITICAL -> diagLog.error(logMsg);
            case HIGH -> diagLog.warn(logMsg);
            default -> diagLog.info(logMsg);
        }

        // 2. Persist to DB (skip for LOW)
        if (severity != Severity.LOW) {
            try {
                String truncatedMsg = message;
                if (cause != null) {
                    truncatedMsg += " | " + rootCauseMessage(cause);
                }
                if (truncatedMsg.length() > 1000) {
                    truncatedMsg = truncatedMsg.substring(0, 1000);
                }
                errorEventRepository.save(new ErrorEventEntity(now, component, severity.name(), truncatedMsg));
            } catch (Exception dbEx) {
                log.warn("Failed to persist error event: {}", dbEx.getMessage());
            }
        }

        // 3. Telegram alert based on severity
        switch (severity) {
            case CRITICAL -> sendTelegramImmediate(component, message, cause);
            case HIGH -> sendTelegramRateLimited(component, message, cause);
            default -> { /* no Telegram for MEDIUM/LOW */ }
        }
    }

    private void sendTelegramImmediate(String component, String message, Throwable cause) {
        String alertText = "🔴 CRITICAL [" + component + "]\n" + message;
        if (cause != null) {
            alertText += "\nCause: " + rootCauseMessage(cause);
        }
        try {
            telegramAlertService.systemAlert(alertText);
        } catch (Exception ex) {
            log.warn("Failed to send critical Telegram alert: {}", ex.getMessage());
        }
    }

    private void sendTelegramRateLimited(String component, String message, Throwable cause) {
        long now = System.currentTimeMillis();
        Long lastSent = lastAlertTime.get(component);
        if (lastSent != null && (now - lastSent) < RATE_LIMIT_MILLIS) {
            diagLog.debug("Telegram rate-limited for component={} (sent {}s ago)", component, (now - lastSent) / 1000);
            return;
        }
        lastAlertTime.put(component, now);

        String alertText = "🟡 HIGH [" + component + "]\n" + message;
        if (cause != null) {
            alertText += "\nCause: " + rootCauseMessage(cause);
        }
        try {
            telegramAlertService.systemAlert(alertText);
        } catch (Exception ex) {
            log.warn("Failed to send high-severity Telegram alert: {}", ex.getMessage());
        }
    }

    // ── Query methods for UI ──────────────────────────────────────────────

    /** Recent errors today, ordered by most recent first. */
    public List<ErrorEventEntity> recentErrors(int limit) {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        List<ErrorEventEntity> all = errorEventRepository.findByTimestampAfterOrderByTimestampDesc(todayStart);
        return all.size() > limit ? all.subList(0, limit) : all;
    }

    /** Count of errors by severity today. */
    public Map<String, Long> errorCountsBySeverity() {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        return Map.of(
                "CRITICAL", errorEventRepository.countBySeverityAndTimestampAfter("CRITICAL", todayStart),
                "HIGH", errorEventRepository.countBySeverityAndTimestampAfter("HIGH", todayStart),
                "MEDIUM", errorEventRepository.countBySeverityAndTimestampAfter("MEDIUM", todayStart)
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private String rootCauseMessage(Throwable t) {
        Throwable current = t;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String msg = current.getMessage();
        return (msg != null && !msg.isBlank()) ? msg : current.getClass().getSimpleName();
    }

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }
}
