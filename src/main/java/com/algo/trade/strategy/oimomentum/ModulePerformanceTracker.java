package com.algo.trade.strategy.oimomentum;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks intraday win/loss per entry signal module and pauses modules with poor performance.
 *
 * <p>Modules tracked by their momentum source tag: OPERATOR_OI_LED, OI_SURGE, PREMIUM_VELOCITY,
 * 30M_HIGH_BREAK, 30M_LOW_BREAK, LARGE_MOVE, OPERATOR_SQUEEZE, CASE0_OI_LED, SUSTAINED_DRIFT.</p>
 *
 * <p>Pause logic: if a module has fired ≥5 trades today with win rate < 40%, it's paused
 * for the rest of the session. Resumption happens automatically on the next trading day.</p>
 *
 * <p>This is DEFENSIVE only — it doesn't block new modules from firing, only pauses ones
 * that have demonstrated poor intraday performance. Prevents noise accumulation from a
 * single broken signal source while keeping others active.</p>
 */
@Component
public class ModulePerformanceTracker {

    private static final Logger log = LoggerFactory.getLogger(ModulePerformanceTracker.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MIN_TRADES_FOR_PAUSE = 5;
    private static final double PAUSE_WIN_RATE_THRESHOLD = 0.40;

    private final Map<String, AtomicInteger> tradesPerModule = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> winsPerModule = new ConcurrentHashMap<>();
    private final Map<String, Boolean> pausedModules = new ConcurrentHashMap<>();
    private volatile LocalDate currentDay = null;

    /**
     * Record a trade outcome for a module.
     *
     * @param module the momentum source tag (e.g., "OPERATOR_OI_LED", "30M_HIGH_BREAK")
     * @param profitable true if the trade closed with profit > 0
     */
    public void recordTrade(String module, boolean profitable) {
        resetIfNewDay();
        if (module == null || module.isBlank()) return;

        String key = normalizeModule(module);
        tradesPerModule.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (profitable) {
            winsPerModule.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        }

        // Check if this module should now be paused
        int trades = tradesPerModule.get(key).get();
        int wins = winsPerModule.getOrDefault(key, new AtomicInteger()).get();
        if (trades >= MIN_TRADES_FOR_PAUSE) {
            double winRate = (double) wins / trades;
            if (winRate < PAUSE_WIN_RATE_THRESHOLD && !pausedModules.getOrDefault(key, false)) {
                pausedModules.put(key, true);
                log.warn("[ModulePerf] PAUSING module '{}': {}/{} wins ({}%) < {}% threshold — "
                        + "will resume tomorrow", key, wins, trades,
                        String.format("%.0f", winRate * 100), PAUSE_WIN_RATE_THRESHOLD * 100);
            }
        }
    }

    /**
     * Check if a module is currently paused due to poor intraday performance.
     *
     * @param module the momentum source tag
     * @return true if paused (should skip entries from this module)
     */
    public boolean isPaused(String module) {
        resetIfNewDay();
        if (module == null || module.isBlank()) return false;
        return pausedModules.getOrDefault(normalizeModule(module), false);
    }

    /**
     * Get the current win rate for a module (0-100). Returns -1 if no trades yet.
     */
    public double getWinRate(String module) {
        String key = normalizeModule(module);
        int trades = tradesPerModule.getOrDefault(key, new AtomicInteger()).get();
        if (trades == 0) return -1;
        int wins = winsPerModule.getOrDefault(key, new AtomicInteger()).get();
        return (double) wins / trades * 100;
    }

    /**
     * Get summary for monitoring/logging.
     */
    public Map<String, Object> getSummary() {
        Map<String, Object> summary = new ConcurrentHashMap<>();
        for (String key : tradesPerModule.keySet()) {
            int trades = tradesPerModule.get(key).get();
            int wins = winsPerModule.getOrDefault(key, new AtomicInteger()).get();
            boolean paused = pausedModules.getOrDefault(key, false);
            summary.put(key, Map.of(
                    "trades", trades, "wins", wins,
                    "winRate", trades > 0 ? String.format("%.0f%%", (double) wins / trades * 100) : "N/A",
                    "paused", paused
            ));
        }
        return summary;
    }

    /**
     * Reset all counters if the day has changed. Called automatically on every access.
     */
    private void resetIfNewDay() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(currentDay)) {
            if (currentDay != null && !pausedModules.isEmpty()) {
                log.info("[ModulePerf] New trading day — resuming all paused modules: {}",
                        pausedModules.keySet());
            }
            tradesPerModule.clear();
            winsPerModule.clear();
            pausedModules.clear();
            currentDay = today;
        }
    }

    /**
     * Normalize module name — extract the base signal type from compound reasons.
     * e.g., "OI_SURGE:CE_OI_SURGE" → "OI_SURGE"
     * e.g., "OPERATOR_SQUEEZE[EXPIRY_HALF_SIZE]" → "OPERATOR_SQUEEZE"
     */
    private String normalizeModule(String module) {
        if (module == null) return "UNKNOWN";
        int colon = module.indexOf(':');
        if (colon > 0) module = module.substring(0, colon);
        int bracket = module.indexOf('[');
        if (bracket > 0) module = module.substring(0, bracket);
        return module.trim().toUpperCase();
    }
}
