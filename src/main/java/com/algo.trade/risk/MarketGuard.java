package com.algo.trade.risk;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    private final AtomicReference<Double> currentVix = new AtomicReference<>(0.0);
    private final AtomicReference<Double> currentPcr = new AtomicReference<>(0.0);
    private volatile double todayOpen = 0;
    private volatile double todayCurrent = 0;
    private final Map<String, Long> lastLogTime = new ConcurrentHashMap<>();

    // Known high-impact event dates — update as announced (RBI policy, Budget, GDP, etc.)
    private static final List<LocalDate> EVENT_DATES = List.of(
        LocalDate.of(2025, 6, 6),  LocalDate.of(2025, 8, 6),
        LocalDate.of(2025, 10, 8), LocalDate.of(2025, 12, 5),
        LocalDate.of(2026, 2, 1),  LocalDate.of(2026, 4, 9),
        LocalDate.of(2026, 6, 5),  LocalDate.of(2026, 8, 5),
        LocalDate.of(2026, 10, 7), LocalDate.of(2026, 12, 4)
    );

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
        if (isEventDay()) {
            return "Event day — high impact event today, avoid new entries";
        }
        if (isPreEventDay()) {
            return "Pre-event day — high impact event tomorrow, caution";
        }
        return null;
    }

    /** Safe to enter SHORT PREMIUM strategies (selling options). */
    public boolean isSafeForShortPremium() {
        double vix = currentVix.get();
        if (vix > vixMaxForShortPremium) {
            logRateLimited("vix-high",
                "[MarketGuard] VIX={} > max={}, blocking short premium", vix, vixMaxForShortPremium);
            return false;
        }
        if (vix < vixMinForShortPremium) {
            logRateLimited("vix-low",
                "[MarketGuard] VIX={} < min={}, premium too cheap to sell", vix, vixMinForShortPremium);
            return false;
        }
        if (isEventDay() || isPreEventDay()) {
            logRateLimited("event", "[MarketGuard] Event day — blocking short premium");
            return false;
        }
        if (isCircuitBreakerTriggered()) {
            logRateLimited("circuit", "[MarketGuard] Circuit breaker triggered");
            return false;
        }
        return true;
    }

    public boolean isEventDay() { return EVENT_DATES.contains(LocalDate.now()); }
    public boolean isPreEventDay() { return EVENT_DATES.contains(LocalDate.now().plusDays(1)); }

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
