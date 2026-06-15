package com.algo.trade.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Bot Activity Tracker — logs what each strategy is doing at any given moment.
 *
 * Provides real-time visibility into:
 * - What each strategy is currently scanning/evaluating
 * - Filter rejections (why a signal was blocked)
 * - Trades placed and exited
 * - Time since last activity per strategy
 *
 * Used by the Angular dashboard to show live strategy activity feed.
 */
@Component
public class BotActivityTracker {

    private static final Logger log = LoggerFactory.getLogger(BotActivityTracker.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_EVENTS = 100; // keep last 100 events

    public record ActivityEvent(
            Instant timestamp,
            String strategy,
            String type,        // SCAN, SIGNAL, FILTER_REJECT, TRADE_PLACED, TRADE_EXITED
            String details
    ) {}

    private final Deque<ActivityEvent> recentEvents = new ConcurrentLinkedDeque<>();
    private final Map<String, Instant> lastActivity = new ConcurrentHashMap<>();
    private final Map<String, String> currentState = new ConcurrentHashMap<>();

    /**
     * Record a scan event (strategy is evaluating conditions).
     */
    public void scan(String strategy, String details) {
        addEvent(strategy, "SCAN", details);
        currentState.put(strategy, "Scanning: " + details);
    }

    /**
     * Record a signal detection.
     */
    public void signalDetected(String strategy, String direction, String details) {
        addEvent(strategy, "SIGNAL", direction + " — " + details);
        currentState.put(strategy, "Signal: " + direction);
    }

    /**
     * Record a filter rejection (signal was blocked by a pre-entry filter).
     */
    public void filterRejected(String strategy, String filterName, String reason) {
        addEvent(strategy, "FILTER_REJECT", filterName + ": " + reason);
        currentState.put(strategy, "Blocked by " + filterName);
    }

    /**
     * Record a trade placement.
     */
    public void tradePlaced(String strategy, String symbol, String details, double premium) {
        addEvent(strategy, "TRADE_PLACED", symbol + " @₹" + String.format("%.0f", premium) + " — " + details);
        currentState.put(strategy, "Position open: " + symbol);
    }

    /**
     * Record a trade exit.
     */
    public void tradeExited(String strategy, String symbol, String reason, double pnl) {
        addEvent(strategy, "TRADE_EXITED",
                symbol + " — " + reason + " P&L=₹" + String.format("%.0f", pnl));
        currentState.put(strategy, "Idle (last exit: " + reason + ")");
    }

    private void addEvent(String strategy, String type, String details) {
        Instant now = Instant.now();
        recentEvents.addFirst(new ActivityEvent(now, strategy, type, details));
        lastActivity.put(strategy, now);
        // Trim to max size
        while (recentEvents.size() > MAX_EVENTS) {
            recentEvents.pollLast();
        }
    }

    // ── Dashboard API ─────────────────────────────────────────────────────────

    /**
     * Get recent activity events (newest first).
     */
    public List<ActivityEvent> getRecentEvents(int limit) {
        return recentEvents.stream().limit(limit).toList();
    }

    /**
     * Get current state of all strategies.
     */
    public Map<String, String> getCurrentStates() {
        return Map.copyOf(currentState);
    }

    /**
     * Get time since last activity for each strategy.
     */
    public Map<String, Long> getIdleSeconds() {
        Map<String, Long> idle = new LinkedHashMap<>();
        Instant now = Instant.now();
        lastActivity.forEach((strategy, lastTime) -> {
            idle.put(strategy, java.time.Duration.between(lastTime, now).getSeconds());
        });
        return idle;
    }
}
