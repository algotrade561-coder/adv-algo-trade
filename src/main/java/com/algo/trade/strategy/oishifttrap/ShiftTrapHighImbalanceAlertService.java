package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Phase 5 — high-imbalance Telegram watch alerts.
 *
 * <p>Emits a Telegram alert when any candidate strike's OI imbalance crosses
 * {@code imbalanceAlertRatio}, with two layers of throttling:
 * <ul>
 *   <li><b>Per-key cooldown</b> — {@code (underlying, side, strike)} cooldown of
 *       {@code imbalanceAlertCooldownMinutes} prevents the same strike from re-alerting.</li>
 *   <li><b>Global rate limit</b> — {@value #MAX_ALERTS_PER_MINUTE} alerts per rolling minute,
 *       to cap the worst-case spam when many strikes spike together (e.g. event-driven
 *       chains where 5+ strikes all show extreme imbalance simultaneously).</li>
 * </ul>
 *
 * <p>Strictly observability — does NOT influence trade decisions.
 */
@Component
public class ShiftTrapHighImbalanceAlertService {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapHighImbalanceAlertService.class);
    private static final int MAX_ALERTS_PER_MINUTE = 6;
    private static final Duration RATE_WINDOW = Duration.ofMinutes(1);

    private final OiShiftTrapConfig config;
    private final TelegramAlertService telegramAlertService;

    /** key = "UNDERLYING:SIDE:STRIKE" -> last alert time */
    private final ConcurrentHashMap<String, Instant> lastAlertAt = new ConcurrentHashMap<>();
    /** Rolling 60-second sliding window of alert timestamps. */
    private final ConcurrentLinkedDeque<Instant> recentAlertTimes = new ConcurrentLinkedDeque<>();

    public ShiftTrapHighImbalanceAlertService(
            @Autowired(required = false) OiShiftTrapConfig config,
            @Autowired(required = false) TelegramAlertService telegramAlertService) {
        this.config = config;
        this.telegramAlertService = telegramAlertService;
    }

    public void maybeAlert(UnderlyingSymbol underlying, String trapSide, BigDecimal strike,
                            double imbalance, long trappedOi, BigDecimal spot) {
        if (config == null || !config.isEnhancementsEnabled() || telegramAlertService == null) {
            return;
        }
        if (underlying == null || trapSide == null || strike == null || spot == null
                || spot.signum() <= 0) {
            return;
        }
        double threshold = config.getImbalanceAlertRatio();
        if (threshold <= 0 || imbalance < threshold) {
            return;
        }
        int cooldownMin = Math.max(1, config.getImbalanceAlertCooldownMinutes());
        String key = underlying.name() + ":" + trapSide + ":" + strike.toPlainString();
        Instant now = Instant.now();
        Instant prev = lastAlertAt.get(key);
        if (prev != null && Duration.between(prev, now).toMinutes() < cooldownMin) {
            return;
        }
        // Global rate-limit check — drop alerts that would exceed MAX_ALERTS_PER_MINUTE.
        if (!rateLimitAllow(now)) {
            log.debug("[ShiftTrap-HighImb] rate-limited (>{} alerts in last minute)", MAX_ALERTS_PER_MINUTE);
            return;
        }
        lastAlertAt.put(key, now);
        double proximityPct = Math.abs(strike.doubleValue() - spot.doubleValue()) / spot.doubleValue() * 100.0;
        try {
            telegramAlertService.systemAlert(String.format(
                    "🔍 High OI imbalance (no trade): %s %s strike=%s OI=%,d imbalance=%.1fx | spot=%s prox=%.2f%%",
                    underlying.name(), trapSide, strike.toPlainString(),
                    trappedOi, imbalance, spot.toPlainString(), proximityPct));
            log.info("[ShiftTrap-HighImb] Alerted {} {} strike={} imbalance={}x", underlying, trapSide,
                    strike, String.format("%.1f", imbalance));
        } catch (Exception ex) {
            log.debug("[ShiftTrap-HighImb] Telegram send failed: {}", ex.toString());
        }
    }

    /**
     * Rolling-window rate limit. Drains expired entries, then if we're under the cap,
     * registers a new entry and returns true. Otherwise returns false (alert blocked).
     */
    private boolean rateLimitAllow(Instant now) {
        Instant cutoff = now.minus(RATE_WINDOW);
        while (true) {
            Instant head = recentAlertTimes.peekFirst();
            if (head == null || !head.isBefore(cutoff)) break;
            recentAlertTimes.pollFirst();
        }
        if (recentAlertTimes.size() >= MAX_ALERTS_PER_MINUTE) {
            return false;
        }
        recentAlertTimes.addLast(now);
        return true;
    }

    public int trackedKeys() {
        return lastAlertAt.size();
    }

    public void clear() {
        lastAlertAt.clear();
        recentAlertTimes.clear();
    }
}
