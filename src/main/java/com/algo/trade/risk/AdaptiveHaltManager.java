package com.algo.trade.risk;

import com.algo.trade.execution.TradingStateService;
import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Adaptive Halt Manager — intelligent pause/resume with progressive recovery.
 *
 * Triggers adaptive halt on:
 *   - N consecutive losses within a time window (rapid loss streak)
 *   - Daily P&L approaching loss cap
 *
 * Pauses for escalating duration:
 *   1st halt: 10 min, 2nd: 20 min, 3rd: 45 min, 4th+: rest of day
 *
 * Resumes with reduced aggressiveness:
 *   After 1st: 70%, 2nd: 50%, 3rd: 30%
 */
@Component
public class AdaptiveHaltManager implements com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveHaltManager.class);

    private final TradingStateService tradingStateService;
    private final TelegramAlertService alertService;

    @Value("${trading.adaptive-halt.enabled:true}")
    private boolean enabled;

    @Value("${trading.adaptive-halt.rapid-loss-count:3}")
    private int rapidLossCount;

    @Value("${trading.adaptive-halt.rapid-loss-window-minutes:15}")
    private int rapidLossWindowMinutes;

    @Value("${trading.adaptive-halt.pnl-warning-percent:60}")
    private double pnlWarningPercent;

    @Value("${trading.adaptive-halt.base-pause-minutes:10}")
    private int basePauseMinutes;

    // State
    private volatile boolean adaptiveHalted = false;
    private volatile long haltStartTime = 0;
    private volatile long haltEndTime = 0;
    private volatile int haltsToday = 0;
    private volatile double recoveryAggressiveness = 1.0;
    private volatile String haltReason = "";

    private final Deque<Long> recentLossTimes = new ConcurrentLinkedDeque<>();

    public AdaptiveHaltManager(TradingStateService tradingStateService, TelegramAlertService alertService) {
        this.tradingStateService = tradingStateService;
        this.alertService = alertService;
    }

    /**
     * Record a trade loss — checks if adaptive halt should trigger.
     */
    public void recordLoss(double lossAmount) {
        if (!enabled) return;

        long now = System.currentTimeMillis();
        recentLossTimes.addFirst(now);

        // Clean old entries outside window
        long windowMs = rapidLossWindowMinutes * 60_000L;
        while (!recentLossTimes.isEmpty() && (now - recentLossTimes.peekLast()) > windowMs) {
            recentLossTimes.pollLast();
        }

        // Check rapid loss trigger
        if (recentLossTimes.size() >= rapidLossCount) {
            triggerAdaptiveHalt("RAPID_LOSS: " + recentLossTimes.size()
                    + " losses in " + rapidLossWindowMinutes + " min");
        }
    }

    /**
     * Check if P&L-based halt should trigger.
     */
    public void checkPnlHalt(double dailyPnl, double dailyMaxLoss) {
        if (!enabled || adaptiveHalted) return;
        double warningLevel = dailyMaxLoss * (pnlWarningPercent / 100.0);
        if (dailyPnl <= -warningLevel) {
            triggerAdaptiveHalt("PNL_WARNING: approaching daily loss limit");
        }
    }

    /**
     * Trigger an adaptive halt with escalating duration.
     */
    public void triggerAdaptiveHalt(String reason) {
        if (adaptiveHalted) return;

        haltsToday++;
        haltReason = reason;
        haltStartTime = System.currentTimeMillis();

        int pauseMinutes = switch (haltsToday) {
            case 1 -> basePauseMinutes;
            case 2 -> basePauseMinutes * 2;
            case 3 -> basePauseMinutes * 4;
            default -> basePauseMinutes * 8;
        };

        haltEndTime = haltStartTime + (pauseMinutes * 60_000L);
        adaptiveHalted = true;

        recoveryAggressiveness = switch (haltsToday) {
            case 1 -> 0.7;
            case 2 -> 0.5;
            case 3 -> 0.3;
            default -> 0.3;
        };

        tradingStateService.softHalt("Adaptive halt #" + haltsToday + ": " + reason);

        log.warn("[AdaptiveHalt] TRIGGERED (halt #{}) — pausing {}min, resume at {}%. Reason: {}",
                haltsToday, pauseMinutes, (int) (recoveryAggressiveness * 100), reason);

        alertService.systemAlert(String.format(
                "⏸️ ADAPTIVE HALT #%d: %s\nPausing %d minutes\nResume at %d%% aggressiveness",
                haltsToday, reason, pauseMinutes, (int) (recoveryAggressiveness * 100)));
    }

    /**
     * Check every 10 seconds if halt should be lifted.
     */
    @Scheduled(fixedDelay = 10_000)
    public void checkResume() {
        if (!adaptiveHalted) return;
        long now = System.currentTimeMillis();
        if (now < haltEndTime) return;

        // Recovery conditions
        LocalTime timeNow = LocalTime.now();
        if (timeNow.isAfter(LocalTime.of(15, 0))) return; // too late to resume

        adaptiveHalted = false;
        tradingStateService.resumeFromHalt();

        long pausedMinutes = (now - haltStartTime) / 60_000;
        log.info("[AdaptiveHalt] RESUMED after {}min pause. Aggressiveness: {}%",
                pausedMinutes, (int) (recoveryAggressiveness * 100));

        alertService.systemAlert(String.format("▶️ ADAPTIVE HALT LIFTED (was %dmin). Resuming at %d%%",
                pausedMinutes, (int) (recoveryAggressiveness * 100)));

        recentLossTimes.clear();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isAdaptiveHalted() { return adaptiveHalted; }

    public double getRecoveryAggressiveness() {
        if (!enabled) return 1.0;
        if (adaptiveHalted) return 0.0;
        return haltsToday > 0 ? recoveryAggressiveness : 1.0;
    }

    public long getRemainingPauseSeconds() {
        if (!adaptiveHalted) return 0;
        return Math.max(0, (haltEndTime - System.currentTimeMillis()) / 1000);
    }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", enabled);
        status.put("halted", adaptiveHalted);
        status.put("haltsToday", haltsToday);
        status.put("reason", haltReason);
        status.put("remainingSeconds", getRemainingPauseSeconds());
        status.put("recoveryAggressiveness", Math.round(recoveryAggressiveness * 100));
        status.put("recentLosses", recentLossTimes.size());
        return status;
    }

    public void manualResume() {
        if (adaptiveHalted) {
            adaptiveHalted = false;
            tradingStateService.resumeFromHalt();
            log.info("[AdaptiveHalt] Manually resumed by user");
            alertService.systemAlert("▶️ Adaptive halt manually lifted by user");
        }
    }

    public void resetDaily() {
        adaptiveHalted = false;
        haltsToday = 0;
        recoveryAggressiveness = 1.0;
        haltReason = "";
        recentLossTimes.clear();
    }
}
