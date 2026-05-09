package com.algo.trade.monitoring;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Central registry for all scheduled tasks in the application.
 *
 * Provides:
 *   - Observability: last run time, run count, error count, health status per task
 *   - Control: enable/disable individual tasks at runtime
 *   - Trigger: force-run any task on demand via REST
 *   - Stale detection: flags tasks that haven't run in 3× their expected interval
 *
 * Each @Scheduled method registers itself and checks isEnabled() before executing.
 * The registry does NOT own the scheduling — Spring @Scheduled still drives execution.
 * This is a thin observability + control layer on top.
 */
@Service
public class SchedulerRegistry {

    private static final Logger log = LoggerFactory.getLogger(SchedulerRegistry.class);

    private final Map<String, TaskEntry> tasks = new ConcurrentHashMap<>();

    /**
     * Register a scheduled task. Called once at startup (typically in @PostConstruct or constructor).
     *
     * @param name           unique task name (e.g., "positionSync", "pcrCalculator")
     * @param description    human-readable description
     * @param intervalMs     expected interval in milliseconds (0 for cron-based tasks)
     * @param runnable       the method to execute when triggered manually
     */
    public void register(String name, String description, long intervalMs, Runnable runnable) {
        tasks.put(name, new TaskEntry(name, description, intervalMs, runnable));
        log.info("[SchedulerRegistry] Registered: {} (interval={}ms)", name, intervalMs);
    }

    /** Check if a task is enabled. Called at the top of each @Scheduled method. */
    public boolean isEnabled(String name) {
        TaskEntry entry = tasks.get(name);
        return entry == null || entry.enabled.get(); // unknown tasks default to enabled
    }

    /** Record a successful run. Called at the end of each @Scheduled method. */
    public void recordRun(String name) {
        TaskEntry entry = tasks.get(name);
        if (entry != null) {
            entry.lastRunTime = Instant.now();
            entry.runCount.incrementAndGet();
            entry.lastError = null;
        }
    }

    /** Record a failed run. Called in catch blocks. */
    public void recordError(String name, String error) {
        TaskEntry entry = tasks.get(name);
        if (entry != null) {
            entry.lastRunTime = Instant.now();
            entry.runCount.incrementAndGet();
            entry.errorCount.incrementAndGet();
            entry.lastError = error;
        }
    }

    /** Enable or disable a task at runtime. */
    public void setEnabled(String name, boolean enabled) {
        TaskEntry entry = tasks.get(name);
        if (entry != null) {
            entry.enabled.set(enabled);
            log.info("[SchedulerRegistry] {} → {}", name, enabled ? "ENABLED" : "DISABLED");
        }
    }

    /** Force-trigger a task immediately. Returns false if task not found or already running. */
    public boolean triggerNow(String name) {
        TaskEntry entry = tasks.get(name);
        if (entry == null) return false;
        if (entry.runnable == null) return false;
        if (!entry.triggerInProgress.compareAndSet(false, true)) {
            log.info("[SchedulerRegistry] Trigger skipped — {} is already running", name);
            return false;
        }
        try {
            log.info("[SchedulerRegistry] Manual trigger: {}", name);
            entry.runnable.run();
            entry.lastRunTime = Instant.now();
            entry.runCount.incrementAndGet();
            entry.lastError = null;
            return true;
        } catch (Exception e) {
            entry.errorCount.incrementAndGet();
            entry.lastError = e.getMessage();
            log.warn("[SchedulerRegistry] Manual trigger failed for {}: {}", name, e.getMessage());
            return false;
        } finally {
            entry.triggerInProgress.set(false);
        }
    }

    /** Get status of all registered tasks. */
    public List<TaskStatus> getAllStatus() {
        Instant now = Instant.now();
        return tasks.values().stream()
                .sorted(Comparator.comparing(e -> e.name))
                .map(e -> {
                    long ageSec = e.lastRunTime != null ? Duration.between(e.lastRunTime, now).toSeconds() : -1;
                    String health;
                    if (e.lastRunTime == null) {
                        health = "NEVER_RUN";
                    } else if (!e.enabled.get()) {
                        health = "DISABLED";
                    } else if (e.intervalMs > 0 && ageSec > (e.intervalMs / 1000) * 3) {
                        health = "STALE";
                    } else if (e.lastError != null) {
                        health = "ERROR";
                    } else {
                        health = "OK";
                    }
                    return new TaskStatus(
                            e.name, e.description, e.enabled.get(),
                            e.intervalMs, e.lastRunTime != null ? e.lastRunTime.toString() : null,
                            ageSec, e.runCount.get(), e.errorCount.get(),
                            e.lastError, health
                    );
                })
                .toList();
    }

    /** Get names of all registered tasks. */
    public Set<String> getTaskNames() {
        return Collections.unmodifiableSet(tasks.keySet());
    }

    // ── Internal ──────────────────────────────────────────────

    private static class TaskEntry {
        final String name;
        final String description;
        final long intervalMs;
        final Runnable runnable;
        final AtomicBoolean enabled = new AtomicBoolean(true);
        final AtomicBoolean triggerInProgress = new AtomicBoolean(false);
        final AtomicLong runCount = new AtomicLong();
        final AtomicLong errorCount = new AtomicLong();
        volatile Instant lastRunTime;
        volatile String lastError;

        TaskEntry(String name, String description, long intervalMs, Runnable runnable) {
            this.name = name;
            this.description = description;
            this.intervalMs = intervalMs;
            this.runnable = runnable;
        }
    }

    public record TaskStatus(
            String name,
            String description,
            boolean enabled,
            long intervalMs,
            String lastRunTime,
            long lastRunAgeSec,
            long runCount,
            long errorCount,
            String lastError,
            String health
    ) {}
}
