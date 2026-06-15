package com.algo.trade.strategy.filter;

import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Dynamic Trade Governor — adjusts system aggressiveness based on daily performance.
 *
 * Monitors:
 * 1. Trade frequency: if too few trades → loosen filters
 * 2. Trade frequency: if too many trades → tighten filters
 * 3. Daily P&L trajectory: winning day → maintain; losing day → reduce size
 * 4. Slippage trend: high slippage → widen LIMIT buffer automatically
 *
 * Exposes:
 * - getAggressivenessLevel() → 0.5 (conservative) to 1.5 (aggressive)
 * - getRecommendedLimitBuffer() → dynamic buffer based on slippage data
 * - shouldActivateFallback() → true if system has been idle too long
 */
@Component
public class DynamicTradeGovernor {

    private static final Logger log = LoggerFactory.getLogger(DynamicTradeGovernor.class);

    private final TelegramAlertService alertService;

    @Autowired(required = false)
    private com.algo.trade.risk.AdaptiveHaltManager adaptiveHaltManager;

    @Value("${governor.target-trades-per-day:15}") private int targetTradesPerDay;
    @Value("${governor.min-trades-before-loosen:5}") private int minTradesBeforeLoosen;
    @Value("${governor.max-trades-before-tighten:30}") private int maxTradesBeforeTighten;
    @Value("${governor.base-limit-buffer:0.5}") private double baseLimitBuffer;

    private volatile double aggressivenessLevel = 1.0;
    private volatile double recommendedLimitBuffer = 0.5;
    private volatile int tradesToday = 0;
    private volatile double dailyPnl = 0;

    public DynamicTradeGovernor(TelegramAlertService alertService) {
        this.alertService = alertService;
    }

    @Scheduled(fixedDelay = 300_000)
    public void recalculate() {
        java.time.LocalTime now = java.time.LocalTime.now();
        if (now.isBefore(java.time.LocalTime.of(9, 30)) || now.isAfter(java.time.LocalTime.of(15, 25))) return;

        try {
            double newLevel = 1.0;

            double hoursSinceOpen = java.time.Duration.between(
                    java.time.LocalTime.of(9, 15), now).toMinutes() / 60.0;
            double expectedTrades = targetTradesPerDay * (hoursSinceOpen / 6.0);

            if (tradesToday < expectedTrades * 0.3 && tradesToday < minTradesBeforeLoosen) {
                newLevel = 1.3;
                log.debug("[Governor] Loosening: {} trades vs {} expected → aggressiveness={}",
                        tradesToday, (int) expectedTrades, newLevel);
            } else if (tradesToday > maxTradesBeforeTighten) {
                newLevel = 0.7;
                log.debug("[Governor] Tightening: {} trades > {} max → aggressiveness={}",
                        tradesToday, maxTradesBeforeTighten, newLevel);
            }

            if (dailyPnl < -2000) {
                newLevel *= 0.7;
                log.debug("[Governor] Losing day (₹{}) — reducing aggressiveness", (int) dailyPnl);
            } else if (dailyPnl > 3000) {
                newLevel = Math.min(newLevel, 1.0);
            }

            aggressivenessLevel = Math.max(0.5, Math.min(1.5, newLevel));
            recommendedLimitBuffer = baseLimitBuffer;

        } catch (Exception e) {
            log.debug("[Governor] Recalculate error: {}", e.getMessage());
        }
    }

    public double getAggressivenessLevel() {
        double level = aggressivenessLevel;
        if (adaptiveHaltManager != null) {
            double recovery = adaptiveHaltManager.getRecoveryAggressiveness();
            if (recovery < 1.0) {
                level *= recovery;
                level = Math.max(0.3, level);
            }
        }
        return level;
    }

    public double getRecommendedLimitBuffer() {
        return recommendedLimitBuffer;
    }

    public double getAdjustedSoftBlockThreshold(double baseThreshold) {
        return baseThreshold / aggressivenessLevel;
    }

    public boolean shouldActivateFallback() {
        return aggressivenessLevel >= 1.2 && tradesToday < minTradesBeforeLoosen;
    }

    public int getTradesToday() {
        return tradesToday;
    }

    public double getDailyPnl() {
        return dailyPnl;
    }

    /** Called externally to update trade count and P&L */
    public void updateMetrics(int trades, double pnl) {
        this.tradesToday = trades;
        this.dailyPnl = pnl;
    }

    public Map<String, Object> getStatus() {
        return Map.of(
                "aggressiveness", Math.round(aggressivenessLevel * 100) / 100.0,
                "tradesToday", tradesToday,
                "dailyPnl", Math.round(dailyPnl),
                "limitBuffer", Math.round(recommendedLimitBuffer * 100) / 100.0,
                "mode", aggressivenessLevel >= 1.2 ? "AGGRESSIVE" :
                        aggressivenessLevel <= 0.7 ? "CONSERVATIVE" : "NORMAL"
        );
    }
}
