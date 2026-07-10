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

    // ── Dynamic (percentile-relative) VIX gate (Finding 1). Default OFF → fixed thresholds above. ──
    // Rationale: long premium is long vega — gate on LOW IV percentile (buy cheap optionality, let
    // the signal supply direction); short premium gates on HIGH IV percentile. 52-week window.
    @Value("${market-guard.vix-dynamic.enabled:false}")
    private boolean vixDynamicEnabled;

    /** Long premium allowed only when the live VIX sits BELOW this 52-wk percentile (cheap IV). */
    @Value("${market-guard.vix-dynamic.long-max-ivp:50.0}")
    private double longMaxIvp;

    /** Short premium needs the live VIX AT OR ABOVE this 52-wk percentile (rich IV to sell). */
    @Value("${market-guard.vix-dynamic.short-min-ivp:50.0}")
    private double shortMinIvp;

    /** ...and BELOW this percentile — above it, IV is crisis-rich and too dangerous to sell. */
    @Value("${market-guard.vix-dynamic.short-max-ivp:90.0}")
    private double shortMaxIvp;

    /** Hard absolute backstop: never sell premium when live VIX exceeds this, regardless of percentile. */
    @Value("${market-guard.vix-dynamic.absolute-vix-ceiling:35.0}")
    private double absoluteVixCeiling;

    /** Optional — clean India-VIX-only history for percentile gating (decoupled from the ATM-IV
     *  IVRankTracker). Absent → fixed-threshold fallback. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.indicator.IndiaVixHistory indiaVixHistory;

    // ── Data-freshness entry gate (Finding 3). Default OFF. Blocks NEW entries when the live
    // tick feed is stale, so strategies don't act on minutes-old OI/price. ──
    @Value("${market-guard.freshness.enabled:false}")
    private boolean freshnessGateEnabled;

    /** Max acceptable age (seconds) of the last WebSocket tick before new entries are blocked. */
    @Value("${market-guard.freshness.max-tick-age-sec:45}")
    private long maxTickAgeSec;

    /**
     * P1.1: Max acceptable age (seconds) of the last successful REST quote batch. When the WS tick
     * is stale but REST is still fresh (age &lt; this), entries are allowed in DEGRADED mode — a WS
     * zombie alone must not halt trading all day while REST keeps data current. Only when BOTH the
     * WS tick AND the REST batch are stale do we block. Never relaxes the both-stale case.
     */
    @Value("${market-guard.freshness.rest-max-age-sec:90}")
    private long restMaxAgeSec;

    /** Optional — exposes the live WS tick age. Absent → freshness gate is a no-op.
     *  {@code @Lazy} breaks the bean cycle MarketGuard → OiRestFallbackService → KiteWebSocketClient
     *  → MarketGuard (the WS client pushes VIX ticks back into MarketGuard). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.marketdata.OiRestFallbackService oiRestFallbackService;

    /**
     * Optional — when present, the three VIX thresholds are read from the runtime
     * {@code GlobalConfig} (editable on the Settings page) instead of the static {@code @Value}
     * defaults above, so they can be tuned without a restart. The {@code @Value} fields remain
     * the fallback when the service or a value is absent.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.config.GlobalConfigService globalConfigService;

    /** Event dates loaded from YAML config (market-guard.event-dates). */
    @Value("${market-guard.event-dates:}")
    private String configuredEventDatesRaw;

    /** Event window in minutes before/after event date to block trading. */
    @Value("${market-guard.event-window-minutes:0}")
    private int eventWindowMinutes;

    /** When true, the long-premium event block is scoped to the event window instead of the whole
     *  session (2026-06-27). Set false to restore the legacy all-day block. */
    @Value("${market-guard.event-window-scoped:true}")
    private boolean eventWindowScoped;

    /** Default minutes before the event time to start blocking when no explicit window is configured. */
    private static final int DEFAULT_PRE_EVENT_MINUTES = 15;

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
    // Last positive VIX tick + when it arrived — used to ride out transient feed gaps instead of
    // hard-blocking every long-premium entry (the 86k vix_unavailable blocks, 2026-06-27).
    private final AtomicReference<Double> lastGoodVix = new AtomicReference<>(0.0);
    private volatile long lastGoodVixAtMs = 0L;
    /** Max age of the last-good intraday VIX before we prefer the EOD-history value instead. */
    private static final long VIX_FALLBACK_MAX_AGE_MS = 6 * 60 * 60 * 1000L; // 6h (covers a session + restart)
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
        if (vix > 0) {
            lastGoodVix.set(vix);
            lastGoodVixAtMs = System.currentTimeMillis();
        }
    }

    /**
     * The VIX to gate on: the live tick when present, else a fallback so a transient feed gap doesn't
     * hard-block all entries. Fallback chain: last positive intraday tick (if &lt; 6h old) → most
     * recent EOD India-VIX from history → 0. Returns 0 only on a true cold start with no history at all.
     */
    private double effectiveVix() {
        double live = currentVix.get();
        if (live > 0) return live;
        double lastGood = lastGoodVix.get();
        if (lastGood > 0 && (System.currentTimeMillis() - lastGoodVixAtMs) <= VIX_FALLBACK_MAX_AGE_MS) {
            return lastGood;
        }
        if (indiaVixHistory != null) {
            double eod = indiaVixHistory.lastValue();
            if (eod > 0) return eod;
        }
        return 0;
    }

    /** True when the gate is running on a fallback estimate rather than a live VIX tick. */
    public boolean isVixDegraded() { return currentVix.get() <= 0 && effectiveVix() > 0; }

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

    // ── Effective VIX thresholds: runtime config (Settings page) overrides the @Value defaults ──
    public double effectiveVixMinForLongPremium() {
        if (globalConfigService != null) {
            var v = globalConfigService.getVixMinForLongPremium();
            if (v != null) return v.doubleValue();
        }
        return vixMinForLongPremium;
    }
    public double effectiveVixMinForShortPremium() {
        if (globalConfigService != null) {
            var v = globalConfigService.getVixMinForShortPremium();
            if (v != null) return v.doubleValue();
        }
        return vixMinForShortPremium;
    }
    public double effectiveVixMaxForShortPremium() {
        if (globalConfigService != null) {
            var v = globalConfigService.getVixMaxForShortPremium();
            if (v != null) return v.doubleValue();
        }
        return vixMaxForShortPremium;
    }

    /**
     * Data-freshness block (Finding 3). When enabled, blocks new entries while the live tick feed
     * is stale (or not yet live), so strategies never enter on minutes-old OI/price. Null = fresh.
     */
    private String dataFreshnessBlock() {
        if (!freshnessGateEnabled || oiRestFallbackService == null) return null;
        long wsAge;
        long restAge;
        try {
            wsAge = oiRestFallbackService.getWsTickAgeSec();
            restAge = oiRestFallbackService.getRestAgeSec();
        } catch (Exception e) { return null; }

        // WS feed healthy → fresh, allow.
        if (wsAge >= 0 && wsAge <= maxTickAgeSec) {
            return null;
        }

        // P1.1: WS is stale (or never live). Allow entries in DEGRADED mode if REST is keeping data
        // fresh — a WS zombie alone must not block all day. Block only when BOTH feeds are stale.
        boolean restFresh = restAge >= 0 && restAge <= restMaxAgeSec;
        if (restFresh) {
            log.warn("[MarketGuard] WS tick stale ({}s > {}s) but REST fresh ({}s ≤ {}s) — allowing DEGRADED entries",
                    wsAge, maxTickAgeSec, restAge, restMaxAgeSec);
            return null;
        }

        if (wsAge < 0 && restAge < 0) {
            return "Market data feed not yet live (no WS or REST tick) — blocking entries until fresh data";
        }
        return String.format("Tick feed stale — WS %ds (>%ds) AND REST %ds (>%ds) — blocking entries until fresh data",
                wsAge, maxTickAgeSec, restAge, restMaxAgeSec);
    }

    /**
     * Live VIX percentile (0–100) against the trailing 52 weeks of India-VIX history, or -1 when
     * the tracker is absent / history is insufficient (caller then uses the fixed thresholds).
     * India VIX is market-wide, so NIFTY's series is used as the reference.
     */
    private double currentVixPercentile() {
        if (indiaVixHistory == null) return -1;
        try {
            return indiaVixHistory.percentile(effectiveVix());
        } catch (Exception e) {
            return -1;
        }
    }

    /** Public 52-week India-VIX percentile of the live VIX (0–100), or -1 if unavailable.
     *  Shared by VIXRegimeFilter and the dashboard so they all rank against the same clean series. */
    public double vixPercentile() {
        return currentVixPercentile();
    }

    /** Record the live India VIX into the clean series at end of day (15:31 IST), so the gate's
     *  percentile keeps a pure India-VIX history and never drifts toward ATM IV. */
    @org.springframework.scheduling.annotation.Scheduled(cron = "0 31 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void recordEodIndiaVix() {
        double vix = currentVix.get();
        if (vix <= 0) {
            // Feed silent at close — record today's last good intraday tick (if from this session)
            // rather than leaving a hole in the series (the cause of the stale-since-06-19 gap).
            double lastGood = lastGoodVix.get();
            if (lastGood > 0 && (System.currentTimeMillis() - lastGoodVixAtMs) <= VIX_FALLBACK_MAX_AGE_MS) {
                vix = lastGood;
            }
        }
        if (indiaVixHistory != null && vix > 0) {
            indiaVixHistory.record(vix);
        }
    }

    /**
     * VIX-based block reason for LONG premium. Dynamic mode: block when IV is expensive
     * (percentile above {@code longMaxIvp}); fixed/fallback: block when VIX is below the floor.
     * Returns null when the VIX condition is acceptable.
     */
    private String longVixBlock(double vix) {
        if (vixDynamicEnabled) {
            double ivp = currentVixPercentile();
            if (ivp >= 0) {
                // Dynamic gate active: block only when IV is EXPENSIVE (high percentile).
                // Low VIX = cheap options = favorable for long premium. Do NOT fall through
                // to the absolute floor — that's only a backstop for missing history.
                return ivp > longMaxIvp
                        ? String.format("VIX at %.0fth pct of 52wk range (> %.0f) — IV expensive, long premium overpaying", ivp, longMaxIvp)
                        : null;
            }
            // ivp < 0 → insufficient history → fall through to the absolute backstop
            log.debug("[MarketGuard] VIX percentile unavailable (history < 20 sessions), using fixed floor");
        }
        double minLong = effectiveVixMinForLongPremium();
        return vix < minLong
                ? String.format("VIX %.1f too low (min %.1f) — options cheap, IV may not expand", vix, minLong)
                : null;
    }

    /**
     * VIX-based block reason for SHORT premium. A hard absolute ceiling always applies; then
     * dynamic mode gates on the percentile band [{@code shortMinIvp}, {@code shortMaxIvp}], with
     * the fixed min/max band as the fallback. Returns null when acceptable.
     */
    private String shortVixBlock(double vix) {
        if (vix > absoluteVixCeiling) {
            return String.format("VIX %.1f above hard ceiling %.1f — no premium selling", vix, absoluteVixCeiling);
        }
        if (vixDynamicEnabled) {
            double ivp = currentVixPercentile();
            if (ivp >= 0) {
                if (ivp < shortMinIvp)
                    return String.format("VIX at %.0fth pct of 52wk range (< %.0f) — premium too cheap to sell", ivp, shortMinIvp);
                if (ivp > shortMaxIvp)
                    return String.format("VIX at %.0fth pct of 52wk range (> %.0f) — crisis-rich, too dangerous to sell", ivp, shortMaxIvp);
                return null;
            }
        }
        double maxShort = effectiveVixMaxForShortPremium();
        double minShort = effectiveVixMinForShortPremium();
        if (vix > maxShort) return String.format("VIX %.1f > max %.1f — too volatile to sell premium", vix, maxShort);
        if (vix < minShort) return String.format("VIX %.1f < min %.1f — premium too cheap to sell", vix, minShort);
        return null;
    }

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
        String staleBlock = dataFreshnessBlock();
        if (staleBlock != null) {
            logRateLimited("data-stale", "[MarketGuard] {}", staleBlock);
            return staleBlock;
        }
        double vix = effectiveVix();
        if (vix <= 0) {
            logRateLimited("vix-unavailable",
                "[MarketGuard] VIX unavailable (no live tick, no fallback) — blocking entry as safety default");
            return "VIX feed unavailable — blocking entries until live VIX data arrives";
        }
        if (currentVix.get() <= 0) {
            logRateLimited("vix-degraded",
                "[MarketGuard] VIX feed silent — gating on fallback estimate {}", vix);
        }
        String vixBlock = longVixBlock(vix);
        if (vixBlock != null) {
            return vixBlock;
        }
        if (isCircuitBreakerTriggered()) {
            double move = Math.abs((todayCurrent - todayOpen) / todayOpen) * 100;
            return String.format("Circuit breaker triggered — index moved %.1f%%", move);
        }
        if (!allowEventDay) {
            if (isEventBlockActiveNow()) {
                return "Event day — high impact event today, avoid new entries";
            }
            if (isPreEventDay()) {
                return "Pre-event day — high impact event tomorrow, caution";
            }
        }
        return null;
    }

    /**
     * Map {@link #longPremiumBlockReason()} text to a stable reject-reason token for CSV counters.
     *
     * @param prefix e.g. {@code market_guard} or {@code market_guard_entry}
     */
    public static String normalizeLongPremiumRejectToken(String prefix, String blockReason) {
        if (blockReason == null || blockReason.isBlank()) {
            return prefix + ":unknown";
        }
        String lower = blockReason.toLowerCase();
        if (lower.contains("stale") || lower.contains("not yet live")) {
            return prefix + ":data_stale";
        }
        if (lower.contains("unavailable")) {
            return prefix + ":vix_unavailable";
        }
        if (lower.contains("too low")) {
            return prefix + ":vix_too_low";
        }
        if (lower.contains("iv expensive") || lower.contains("overpaying")) {
            return prefix + ":vix_iv_expensive";   // dynamic gate: IV percentile too high to buy
        }
        if (lower.contains("circuit breaker")) {
            return prefix + ":circuit_breaker";
        }
        if (lower.contains("pre-event")) {
            return prefix + ":pre_event_day";
        }
        if (lower.contains("event day")) {
            return prefix + ":event_day";
        }
        return prefix + ":unknown";
    }

    /** Safe to enter SHORT PREMIUM strategies (selling options). */
    public boolean isSafeForShortPremium() {
        return shortPremiumBlockReason() == null;
    }

    /**
     * Returns a human-readable reason why short premium is blocked, or null if safe.
     */
    public String shortPremiumBlockReason() {
        String staleBlock = dataFreshnessBlock();
        if (staleBlock != null) {
            logRateLimited("data-stale", "[MarketGuard] {}", staleBlock);
            return staleBlock;
        }
        double vix = currentVix.get();
        String vixBlock = shortVixBlock(vix);
        if (vixBlock != null) {
            logRateLimited("vix-short", "[MarketGuard] short premium blocked: {}", vixBlock);
            return vixBlock;
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

    public boolean isEventDay() { return effectiveEventDates().contains(LocalDate.now(IST)); }
    public boolean isPreEventDay() { return effectiveEventDates().contains(LocalDate.now(IST).plusDays(1)); }

    /**
     * Whether the long-premium event block is active <i>right now</i> (2026-06-27). The legacy
     * {@code isEventDay()} blocked the entire session on an event date; this scopes the block to the
     * actual event window {@code [eventTime − preMinutes, eventTime + postMinutes]} so an RBI
     * announcement at 10:00 only suppresses entries ~09:45–10:30 rather than all day. Falls back to
     * an all-day block when scoping is disabled or the date has no window metadata (safe default).
     */
    public boolean isEventBlockActiveNow() {
        LocalDate today = LocalDate.now(IST);
        if (!effectiveEventDates().contains(today)) return false;
        if (!eventWindowScoped) return true;                 // legacy all-day block
        EventWindow w = eventWindows.get(today);
        if (w == null) return true;                          // no metadata → block all day (safe)
        LocalTime now = LocalTime.now(IST);
        int preMin = eventWindowMinutes > 0 ? eventWindowMinutes : DEFAULT_PRE_EVENT_MINUTES;
        LocalTime start = w.time().minusMinutes(preMin);
        LocalTime end = w.time().plusMinutes(w.postEventMinutes());
        return !now.isBefore(start) && !now.isAfter(end);
    }

    /**
     * Returns the next event date within {@code maxDaysAhead} calendar days
     * (inclusive of today), or empty if none. Used by strategies that key on
     * "are we within an event window" (e.g. EventDrivenBuyStrategy). Single
     * source of truth — strategies should NOT maintain their own hardcoded
     * event-date lists; they should read from this method so application.yml's
     * {@code risk.event-dates} stays authoritative across the system.
     */
    public Optional<LocalDate> nextEventWithin(int maxDaysAhead) {
        if (maxDaysAhead < 0) return Optional.empty();
        LocalDate today = LocalDate.now(IST);
        List<LocalDate> dates = effectiveEventDates();
        for (int i = 0; i <= maxDaysAhead; i++) {
            LocalDate probe = today.plusDays(i);
            if (dates.contains(probe)) return Optional.of(probe);
        }
        return Optional.empty();
    }

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
