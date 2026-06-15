package com.algo.trade.risk;

import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Global circuit breaker for consecutive losing trades.
 *
 * Tracks consecutive losses per strategy and globally.
 * When threshold is breached:
 *   - Per-strategy: pauses that strategy for a cooldown period (15 min)
 *   - Global: 5 consecutive losses across all strategies → soft halt + alert (30 min)
 *
 * Resets on a winning trade or after cooldown expires.
 */
@Component
public class ConsecutiveLossCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(ConsecutiveLossCircuitBreaker.class);

    private final TelegramAlertService alertService;

    private final Map<String, AtomicInteger> strategyLossCount = new ConcurrentHashMap<>();
    private final Map<String, Long> strategyCooldownUntil = new ConcurrentHashMap<>();

    private final AtomicInteger globalConsecutiveLosses = new AtomicInteger(0);
    private volatile boolean globalCircuitOpen = false;

    private static final int STRATEGY_LOSS_THRESHOLD = 3;
    private static final int GLOBAL_LOSS_THRESHOLD = 5;
    private static final long STRATEGY_COOLDOWN_MS = 15 * 60 * 1000L;
    private static final long GLOBAL_COOLDOWN_MS = 30 * 60 * 1000L;
    private volatile long globalCooldownUntil = 0;

    public ConsecutiveLossCircuitBreaker(TelegramAlertService alertService) {
        this.alertService = alertService;
    }

    /**
     * Record a trade result. Call after every trade closes.
     */
    public void recordTradeResult(String strategyName, boolean isWin, double pnl) {
        if (isWin) {
            strategyLossCount.computeIfAbsent(strategyName, k -> new AtomicInteger(0)).set(0);
            strategyCooldownUntil.remove(strategyName);

            int prevGlobal = globalConsecutiveLosses.getAndSet(0);
            if (prevGlobal >= GLOBAL_LOSS_THRESHOLD && globalCircuitOpen) {
                globalCircuitOpen = false;
                globalCooldownUntil = 0;
                log.info("[CircuitBreaker] Global circuit CLOSED — winning trade by {}", strategyName);
            }
        } else {
            int stratLosses = strategyLossCount
                    .computeIfAbsent(strategyName, k -> new AtomicInteger(0))
                    .incrementAndGet();
            int globalLosses = globalConsecutiveLosses.incrementAndGet();

            log.info("[CircuitBreaker] Loss recorded: {} (strategy={}, global={})",
                    strategyName, stratLosses, globalLosses);

            if (stratLosses >= STRATEGY_LOSS_THRESHOLD) {
                long cooldownEnd = System.currentTimeMillis() + STRATEGY_COOLDOWN_MS;
                strategyCooldownUntil.put(strategyName, cooldownEnd);
                log.warn("[CircuitBreaker] {} hit {} consecutive losses — paused for 15 min",
                        strategyName, stratLosses);
                alertService.systemAlert("⏸ " + strategyName + " paused: " + stratLosses + " consecutive losses");
            }

            if (globalLosses >= GLOBAL_LOSS_THRESHOLD && !globalCircuitOpen) {
                globalCircuitOpen = true;
                globalCooldownUntil = System.currentTimeMillis() + GLOBAL_COOLDOWN_MS;
                log.error("[CircuitBreaker] GLOBAL circuit OPEN — {} consecutive losses", globalLosses);
                alertService.systemAlert("🚨 Circuit breaker OPEN: " + globalLosses
                        + " consecutive losses. New entries paused 30 min.");
            }
        }
    }

    /**
     * Check if a strategy is allowed to enter.
     */
    public boolean isEntryAllowed(String strategyName) {
        if (globalCircuitOpen) {
            if (System.currentTimeMillis() > globalCooldownUntil) {
                globalCircuitOpen = false;
                globalConsecutiveLosses.set(0);
                log.info("[CircuitBreaker] Global cooldown expired — circuit auto-closed");
            } else {
                return false;
            }
        }

        Long cooldownEnd = strategyCooldownUntil.get(strategyName);
        if (cooldownEnd != null) {
            if (System.currentTimeMillis() > cooldownEnd) {
                strategyCooldownUntil.remove(strategyName);
                strategyLossCount.computeIfAbsent(strategyName, k -> new AtomicInteger(0)).set(0);
                return true;
            } else {
                return false;
            }
        }
        return true;
    }

    public String getBlockReason(String strategyName) {
        if (globalCircuitOpen) {
            long remainingMs = globalCooldownUntil - System.currentTimeMillis();
            return "Global circuit breaker: " + globalConsecutiveLosses.get()
                    + " consecutive losses, resumes in " + (remainingMs / 60000) + " min";
        }
        Long cooldownEnd = strategyCooldownUntil.get(strategyName);
        if (cooldownEnd != null && System.currentTimeMillis() <= cooldownEnd) {
            int losses = strategyLossCount.getOrDefault(strategyName, new AtomicInteger(0)).get();
            long remainingMs = cooldownEnd - System.currentTimeMillis();
            return strategyName + ": " + losses + " consecutive losses, resumes in " + (remainingMs / 60000) + " min";
        }
        return null;
    }

    public int getConsecutiveLosses(String strategyName) {
        return strategyLossCount.getOrDefault(strategyName, new AtomicInteger(0)).get();
    }

    public int getGlobalConsecutiveLosses() { return globalConsecutiveLosses.get(); }
    public boolean isGlobalCircuitOpen() { return globalCircuitOpen; }

    public void forceClose() {
        globalCircuitOpen = false;
        globalCooldownUntil = 0;
        globalConsecutiveLosses.set(0);
        strategyLossCount.clear();
        strategyCooldownUntil.clear();
        log.info("[CircuitBreaker] Force-closed by operator");
    }

    @Scheduled(cron = "0 0 0 * * *")
    public void dailyReset() {
        globalCircuitOpen = false;
        globalCooldownUntil = 0;
        globalConsecutiveLosses.set(0);
        strategyLossCount.clear();
        strategyCooldownUntil.clear();
        log.info("[CircuitBreaker] Daily reset complete");
    }
}
