package com.algo.trade.strategy.filter;

import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalTime;

/**
 * Session Manager Filter — time-of-day session windows for Indian market.
 *
 * Sessions:
 *   MORNING_MOMENTUM (9:15–9:45)  → GapAndGo, DirectionalBuy, MorningMomentum
 *   POST_OPEN_TREND  (9:45–11:00) → All strategies
 *   MIDDAY_CHOP      (11:00–13:30)→ Block unless strong trend
 *   PRE_EXPIRY       (13:30–14:30)→ Momentum strategies + scalps
 *   GAMMA_SCALPING   (14:30–15:15)→ Gamma/Reversal scalps (expiry only)
 *   CLOSED           (other)      → No entries
 */
@Component
public class SessionManagerFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionManagerFilter.class);

    private final ExpiryCalendar expiryCalendar;

    @Value("${session.midday-strong-trend-threshold:0.20}") private double middayTrendThreshold;

    public enum Session {
        MORNING_MOMENTUM, POST_OPEN_TREND, MIDDAY_CHOP, PRE_EXPIRY, GAMMA_SCALPING, CLOSED
    }

    public SessionManagerFilter(ExpiryCalendar expiryCalendar) {
        this.expiryCalendar = expiryCalendar;
    }

    public Session getCurrentSession() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 15))) return Session.CLOSED;
        if (now.isBefore(LocalTime.of(9, 45))) return Session.MORNING_MOMENTUM;
        if (now.isBefore(LocalTime.of(11, 0))) return Session.POST_OPEN_TREND;
        if (now.isBefore(LocalTime.of(13, 30))) return Session.MIDDAY_CHOP;
        if (now.isBefore(LocalTime.of(14, 30))) return Session.PRE_EXPIRY;
        if (now.isBefore(LocalTime.of(15, 15))) return Session.GAMMA_SCALPING;
        return Session.CLOSED;
    }

    public boolean isStrategyAllowedNow(String strategyName, boolean isExpiryDay) {
        Session session = getCurrentSession();
        String upper = strategyName.toUpperCase();

        return switch (session) {
            case MORNING_MOMENTUM ->
                    upper.contains("GAP") || upper.contains("DIRECTIONAL")
                    || upper.contains("VOL") || upper.contains("MOMENTUM")
                    || upper.contains("MORNING");
            case POST_OPEN_TREND -> true;
            case MIDDAY_CHOP -> false;
            case PRE_EXPIRY ->
                    upper.contains("DIRECTIONAL") || upper.contains("MOMENTUM")
                    || upper.contains("REVERSAL") || upper.contains("OI_SHIFT")
                    || upper.contains("VOL") || upper.contains("EVENT")
                    || (isExpiryDay && upper.contains("EXPIRY"));
            case GAMMA_SCALPING ->
                    isExpiryDay && (upper.contains("GAMMA") || upper.contains("EXPIRY")
                    || upper.contains("REVERSAL"));
            case CLOSED -> false;
        };
    }

    public boolean isMiddayChopOverride(double trendStrength) {
        return getCurrentSession() == Session.MIDDAY_CHOP && trendStrength >= middayTrendThreshold;
    }

    public String getBlockReason(String strategyName, boolean isExpiryDay) {
        Session session = getCurrentSession();
        if (isStrategyAllowedNow(strategyName, isExpiryDay)) return null;
        return "SESSION_BLOCKED: " + strategyName + " not allowed in " + session
                + (isExpiryDay ? " (expiry day)" : "");
    }
}
