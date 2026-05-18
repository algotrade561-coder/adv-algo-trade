package com.algo.trade.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Market Guard — pre-trade safety checks for all strategies.
 *
 * Checks:
 * 1. VIX filter — block short premium strategies when VIX is too high/low
 * 2. Event calendar — block trading 1 day before known high-impact events
 * 3. Circuit breaker — block if index has moved too much today
 *
 * VIX is updated from the WebSocket tick feed (India VIX token: 264969).
 */
@Component
public class MarketGuard {

    private static final Logger log = LoggerFactory.getLogger(MarketGuard.class);
    private static final long LOG_INTERVAL_MS = 60_000;

    @Value("${market-guard.vix-max-for-short-premium:21.0}")
    private double vixMaxForShortPremium;

    @Value("${market-guard.vix-min-for-short-premium:12.0}")
    private double vixMinForShortPremium;

    @Value("${market-guard.vix-min-for-long-premium:14.0}")
    private double vixMinForLongPremium;

    @Value("${market-guard.circuit-breaker-percent:2.5}")
    private double circuitBreakerPercent;

    /** Event dates loaded from YAML config (market-guard.event-dates). */
    @Value("${market-guard.event-dates:}")
    private String configuredEventDatesRaw;

    /** Event window in minutes before/after event date to block trading. */
    @Value("${market-guard.event-window-minutes:0}")
    private int eventWindowMinutes;

    private volatile List<LocalDate> configuredEventDates = List.of();

    /** Per-date event window metadata (event time + post-event minutes), parsed from extended CSV entries. */
    private volatile Map<LocalDate, EventWindow> eventWindows = new HashMap<>();

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Default event time when an entry is date-only (no @HH:mm/window suffix). */
    private static final LocalTime DEFAULT_EVENT_TIME = LocalTime.of(10, 0);

    /** Default post-event window in minutes when not specified in the CSV entry. */
    private static final int DEFAULT_POST_EVENT_MINUTES = 30;

    /** Window metadata for a single event. */
    public record EventWindow(LocalDate date, LocalTime time, int postEventMinutes) {
        public Instant endTime() {
            return LocalDateTime.of(date, time)
                    .plusMinutes(postEventMinutes)
                    .atZone(IST).toInstant();
        }
    }

    private final AtomicReference<Double> currentVix = new AtomicReference<>(0.0);
    private final AtomicReference<Double> currentPcr = new AtomicReference<>(0.0);
    private volatile double todayOpen = 0;
    private volatile double todayCurrent = 0;
    private final Map<String, Long> lastLogTime = new ConcurrentHashMap<>();

    // Fallback hardcoded event dates (used when YAML config is empty)
    private static final List<LocalDate> DEFAULT_EVENT_DATES = List.of(
        LocalDate.of(2025, 6, 6),  LocalDate.of(2025, 8, 6),
        LocalDate.of(2025, 10, 8), LocalDate.of(2025, 12, 5),
        LocalDate.of(2026, 2, 1),  LocalDate.of(2026, 4, 9),
        LocalDate.of(2026, 6, 5),  LocalDate.of(2026, 8, 5),
        LocalDate.of(2026, 10, 7), LocalDate.of(2026, 12, 4)
    );

    /**
     * Parse comma-separated event entries from YAML config.
     * Each entry is either {@code "yyyy-MM-dd"} (date-only, defaults to 10:00 IST + 30min window)
     * or {@code "yyyy-MM-dd@HH:mm/W"} where {@code W} is the post-event window in minutes
     * (e.g., {@code "2026-06-05@10:00/30"} = RBI policy at 10am IST, 30-min post-event window).
     */
    public static List<LocalDate> parseEventDates(String csv) {
        return parseEventEntries(csv).keySet().stream()
                .sorted()
                .toList();
    }

    /** Parses the extended CSV into a date → window map. Tolerates malformed entries (skipped + logged). */
    public static Map<LocalDate, EventWindow> parseEventEntries(String csv) {
        if (csv == null || csv.isBlank()) {
            return Map.of();
        }
        Map<LocalDate, EventWindow> out = new HashMap<>();
        for (String raw : csv.split(",")) {
            String token = raw.trim();
            if (token.isEmpty()) continue;
            try {
                LocalDate date;
                LocalTime time = DEFAULT_EVENT_TIME;
                int windowMin = DEFAULT_POST_EVENT_MINUTES;
                int atIdx = token.indexOf('@');
                if (atIdx < 0) {
                    date = LocalDate.parse(token);
                } else {
                    date = LocalDate.parse(token.substring(0, atIdx));
                    String suffix = token.substring(atIdx + 1);
                    int slashIdx = suffix.indexOf('/');
                    if (slashIdx < 0) {
                        time = LocalTime.parse(suffix);
                    } else {
                        time = LocalTime.parse(suffix.substring(0, slashIdx));
                        windowMin = Integer.parseInt(suffix.substring(slashIdx + 1));
                    }
                }
                out.put(date, new EventWindow(date, time, windowMin));
            } catch (Exception ex) {
                LoggerFactory.getLogger(MarketGuard.class).warn(
                        "[MarketGuard] Skipping malformed event entry '{}': {}", token, ex.getMessage());
            }
        }
        return out;
    }

    /**
     * Returns the post-event-window end time for an event entered today (if any),
     * intended for long-vol strategies to set {@code expectedEventEndTime} on entry.
     */
    public Optional<Instant> nextEventEndTimeToday() {
        EventWindow window = eventWindows.get(LocalDate.now(IST));
        if (window == null) return Optional.empty();
        Instant end = window.endTime();
        // Only return if the post-event window is still in the future
        return end.isAfter(Instant.now()) ? Optional.of(end) : Optional.empty();
    }

    private List<LocalDate> effectiveEventDates() {
        return (configuredEventDates != null && !configuredEventDates.isEmpty())
                ? configuredEventDates : DEFAULT_EVENT_DATES;
    }

    @jakarta.annotation.PostConstruct
    void initEventDates() {
        configuredEventDates = parseEventDates(configuredEventDatesRaw);
        eventWindows = parseEventEntries(configuredEventDatesRaw);
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called from KiteWebSocketClient when India VIX tick arrives. */
    public void updateVix(double vix) {
        currentVix.set(vix);
        log.debug("[MarketGuard] VIX updated: {}", vix);
    }

    /** Called from AlgoTradingScheduler after each option chain scan. */
    public void updatePcr(double pcr) {
        currentPcr.set(pcr);
        log.debug("[MarketGuard] PCR updated: {}", pcr);
    }

    /** Called from AlgoTradingScheduler to track circuit breaker. */
    public void updateIndexPrice(double open, double current) {
        this.todayOpen = open;
        this.todayCurrent = current;
    }

    public double getCurrentVix() { return currentVix.get(); }
    public double getCurrentPcr() { return currentPcr.get(); }

    /** Safe to enter LONG PREMIUM strategies (buying options). */
    public boolean isSafeForLongPremium() {
        return longPremiumBlockReason() == null;
    }

    /**
     * Returns the exact reason long premium entries are blocked,
     * or null if safe. This is what the UI displays — no guessing from thresholds.
     */
    public String longPremiumBlockReason() {
        return longPremiumBlockReason(false);
    }

    /**
     * Returns block reason for long premium, with option to bypass event-day check.
     * Long-vol strategies (LONG_STRADDLE, LONG_STRANGLE) pass allowEventDay=true
     * because event days are their highest-conviction entry signal.
     */
    public String longPremiumBlockReason(boolean allowEventDay) {
        double vix = currentVix.get();
        if (vix <= 0) {
            logRateLimited("vix-unavailable",
                "[MarketGuard] VIX unavailable ({}), blocking entry as safety default", vix);
            return "VIX feed unavailable — blocking entries until live VIX data arrives";
        }
        if (vix < vixMinForLongPremium) {
            return String.format("VIX %.1f too low (min %.1f) — options cheap, IV may not expand", vix, vixMinForLongPremium);
        }
        if (isCircuitBreakerTriggered()) {
            double move = Math.abs((todayCurrent - todayOpen) / todayOpen) * 100;
            return String.format("Circuit breaker triggered — index moved %.1f%%", move);
        }
        if (!allowEventDay) {
            if (isEventDay()) {
                return "Event day — high impact event today, avoid new entries";
            }
            if (isPreEventDay()) {
                return "Pre-event day — high impact event tomorrow, caution";
            }
        }
        return null;
    }

    /** Safe to enter SHORT PREMIUM strategies (selling options). */
    public boolean isSafeForShortPremium() {
        return shortPremiumBlockReason() == null;
    }

    /**
     * Returns a human-readable reason why short premium is blocked, or null if safe.
     */
    public String shortPremiumBlockReason() {
        double vix = currentVix.get();
        if (vix > vixMaxForShortPremium) {
            logRateLimited("vix-high",
                "[MarketGuard] VIX={} > max={}, blocking short premium", vix, vixMaxForShortPremium);
            return String.format("VIX %.1f > max %.1f — too volatile to sell premium", vix, vixMaxForShortPremium);
        }
        if (vix < vixMinForShortPremium) {
            logRateLimited("vix-low",
                "[MarketGuard] VIX={} < min={}, premium too cheap to sell", vix, vixMinForShortPremium);
            return String.format("VIX %.1f < min %.1f — premium too cheap to sell", vix, vixMinForShortPremium);
        }
        if (isEventDay() || isPreEventDay()) {
            logRateLimited("event", "[MarketGuard] Event day — blocking short premium");
            return "Event day or pre-event day — short premium blocked";
        }
        if (isCircuitBreakerTriggered()) {
            logRateLimited("circuit", "[MarketGuard] Circuit breaker triggered");
            return "Circuit breaker triggered — market moved too much today";
        }
        return null;
    }

    public boolean isEventDay() { return effectiveEventDates().contains(LocalDate.now()); }
    public boolean isPreEventDay() { return effectiveEventDates().contains(LocalDate.now().plusDays(1)); }

    public boolean isCircuitBreakerTriggered() {
        if (todayOpen <= 0) return false;
        double move = Math.abs((todayCurrent - todayOpen) / todayOpen) * 100;
        return move >= circuitBreakerPercent;
    }

    private void logRateLimited(String key, String message, Object... args) {
        long now = System.currentTimeMillis();
        Long last = lastLogTime.get(key);
        if (last == null || (now - last) >= LOG_INTERVAL_MS) {
            lastLogTime.put(key, now);
            log.warn(message, args);
        }
    }
}
