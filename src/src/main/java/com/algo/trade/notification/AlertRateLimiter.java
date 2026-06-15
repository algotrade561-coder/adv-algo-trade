package com.algo.trade.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Borrowed from friend's repo (Phase 1.2, 2 Jun 2026 evening).
 *
 * <p>Rate-limits Telegram alerts to prevent flooding the bot API on volatile
 * sessions. Today (2 Jun) the 12:30 reversal would have generated 50+ alert
 * lines in rapid succession — most useless duplicates. This limiter
 * de-duplicates and caps per-minute volume.</p>
 *
 * <h3>Usage</h3>
 * Wrap existing {@link TelegramAlertService} calls:
 * <pre>
 *   if (alertRateLimiter.shouldSendAlert(message)) {
 *       telegramAlertService.systemAlert(message);
 *   }
 * </pre>
 *
 * Or, more conveniently, callers send through the limiter:
 * <pre>
 *   alertRateLimiter.send(telegramAlertService, message);
 * </pre>
 *
 * <h3>Rules</h3>
 * <ol>
 *   <li>Hard cap N alerts per rolling 60-second window (default 10)</li>
 *   <li>Deduplicate identical messages within a 5-minute window — second
 *       send for the same string is suppressed and the duplicate counter
 *       on the original is incremented (visible in {@link #stats()})</li>
 *   <li>Per-key buckets — pass a {@code key} (e.g. "HEARTBEAT_STALE_NIFTY")
 *       so different alert classes don't compete for the same minute-cap</li>
 * </ol>
 */
@Component
public class AlertRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(AlertRateLimiter.class);

    @Value("${trading.alert-rate-limiter.max-per-minute:10}")
    private int maxPerMinute = 10;

    @Value("${trading.alert-rate-limiter.dedupe-window-min:5}")
    private int dedupeWindowMin = 5;

    /** Per-key sliding window of timestamps in the last 60s. */
    private final Map<String, java.util.Deque<Instant>> windowByKey = new ConcurrentHashMap<>();

    /** Per-message dedup info — last sent time + duplicate count. */
    private final Map<String, MsgStats> dedupe = new ConcurrentHashMap<>();

    /**
     * Returns true iff the alert should be sent. Caller is responsible for
     * actually sending. Use {@link #shouldSendAlert(String, String)} for
     * per-key bucketing.
     */
    public boolean shouldSendAlert(String message) {
        return shouldSendAlert("default", message);
    }

    /**
     * @param key     bucket name — alerts of different keys don't share the
     *                rate budget. e.g. "HEARTBEAT", "OIST_SIGNAL", "EXIT".
     * @param message the alert content. Used for dedup.
     */
    public synchronized boolean shouldSendAlert(String key, String message) {
        Instant now = Instant.now();

        // Dedupe: identical message in last `dedupeWindowMin` minutes?
        MsgStats prev = dedupe.get(message);
        if (prev != null && Duration.between(prev.lastSent, now).toMinutes() < dedupeWindowMin) {
            prev.duplicateCount++;
            log.debug("[AlertRateLimiter] DEDUPE key={} dup_count={} msg='{}'",
                    key, prev.duplicateCount, abbreviate(message));
            return false;
        }

        // Per-key rolling 60s window
        java.util.Deque<Instant> window = windowByKey.computeIfAbsent(key,
                k -> new java.util.ArrayDeque<>());
        Instant cutoff = now.minusSeconds(60);
        while (!window.isEmpty() && window.peekFirst().isBefore(cutoff)) {
            window.pollFirst();
        }
        if (window.size() >= maxPerMinute) {
            log.debug("[AlertRateLimiter] RATE_LIMIT key={} ({}/min) msg='{}'",
                    key, maxPerMinute, abbreviate(message));
            return false;
        }
        window.addLast(now);
        dedupe.put(message,
                new MsgStats(now, prev == null ? 1 : prev.sendCount + 1, 0));
        return true;
    }

    /** Convenience wrapper — only calls {@code systemAlert} if not rate-limited. */
    public void send(TelegramAlertService telegram, String key, String message) {
        if (telegram == null) return;
        if (shouldSendAlert(key, message)) {
            telegram.systemAlert(message);
        }
    }

    /** Pure stats for the operator dashboard / health page. */
    public Map<String, MsgStats> stats() {
        return java.util.Collections.unmodifiableMap(dedupe);
    }

    /** Reset (test / startup-of-day). */
    public synchronized void reset() {
        windowByKey.clear();
        dedupe.clear();
    }

    private static String abbreviate(String s) {
        if (s == null) return "null";
        return s.length() <= 60 ? s : s.substring(0, 57) + "...";
    }

    /** Public so the dashboard endpoint can render it. */
    public static class MsgStats {
        public Instant lastSent;
        public int sendCount;
        public int duplicateCount;
        public MsgStats(Instant lastSent, int sendCount, int duplicateCount) {
            this.lastSent = lastSent;
            this.sendCount = sendCount;
            this.duplicateCount = duplicateCount;
        }
    }
}
