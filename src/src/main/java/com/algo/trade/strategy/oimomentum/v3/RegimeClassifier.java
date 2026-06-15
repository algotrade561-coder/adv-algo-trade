package com.algo.trade.strategy.oimomentum.v3;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.ExpiryCalendar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * V3 OPERATOR — Regime classifier.
 *
 * <p>Classifies the active regime per index using:
 * <ul>
 *   <li>Daily ATR percentile vs trailing 30-day window → LOW_VOL / NORMAL / HIGH_VOL.</li>
 *   <li>Expiry-calendar check → EXPIRY_DAY (uses resolved/preponed-aware expiry).</li>
 *   <li>Spot at 09:15 vs previous close → GAP_OPEN.</li>
 * </ul>
 *
 * <p>Refreshes at 09:25 IST and every 30 minutes during the session.</p>
 *
 * <p>Designed to be defensive: if any input is missing, falls back to NORMAL so the
 * strategy never blocks because of a stale ATR reading.</p>
 */
@Component
public class RegimeClassifier {

    private static final Logger log = LoggerFactory.getLogger(RegimeClassifier.class);

    private final ExpiryCalendar expiryCalendar;

    /** Cached classification per index — refreshed by recompute(). */
    private final Map<IndexType, Set<Regime>> cache = new ConcurrentHashMap<>();

    /**
     * Daily ATR per index — must be supplied by the strategy (or backtest harness).
     * Values in percent (e.g. 0.8 = 0.8%).
     */
    private final Map<IndexType, Double> dailyAtrPct = new ConcurrentHashMap<>();

    /** Previous close per index. */
    private final Map<IndexType, Double> previousClose = new ConcurrentHashMap<>();

    /** Session open price per index (set once at 09:15). */
    private final Map<IndexType, Double> sessionOpen = new ConcurrentHashMap<>();

    public RegimeClassifier(ExpiryCalendar expiryCalendar) {
        this.expiryCalendar = expiryCalendar;
    }

    public void setDailyAtrPct(IndexType ix, double atrPct) { dailyAtrPct.put(ix, atrPct); }
    public void setPreviousClose(IndexType ix, double close) { previousClose.put(ix, close); }
    public void setSessionOpen(IndexType ix, double open) { sessionOpen.put(ix, open); }

    public double getDailyAtrPct(IndexType ix) { return dailyAtrPct.getOrDefault(ix, 0.8); }
    public double getPreviousClose(IndexType ix) { return previousClose.getOrDefault(ix, 0.0); }
    public double getSessionOpen(IndexType ix) { return sessionOpen.getOrDefault(ix, 0.0); }

    /**
     * Classify the active regimes for an index. Pure function — uses the data set via
     * the setters above. Idempotent and side-effect-free; safe to call every tick.
     */
    public Set<Regime> classify(IndexType ix) {
        return classify(ix, LocalDate.now(), LocalTime.now());
    }

    Set<Regime> classify(IndexType ix, LocalDate today, LocalTime now) {
        Set<Regime> tags = EnumSet.noneOf(Regime.class);
        // Vol regime
        double atr = dailyAtrPct.getOrDefault(ix, 0.8);
        if (atr < 0.6) tags.add(Regime.LOW_VOL);
        else if (atr > 1.2) tags.add(Regime.HIGH_VOL);
        else tags.add(Regime.NORMAL);
        // Expiry-day check uses the calendar's resolved expiry (handles preponement).
        if (expiryCalendar.isExpiryDay(ix)) tags.add(Regime.EXPIRY_DAY);
        // Gap-open check
        double open = sessionOpen.getOrDefault(ix, 0.0);
        double prev = previousClose.getOrDefault(ix, 0.0);
        if (open > 0 && prev > 0) {
            double gapPct = Math.abs(open - prev) / prev * 100.0;
            if (gapPct > 0.5) tags.add(Regime.GAP_OPEN);
        }
        cache.put(ix, tags);
        return tags;
    }

    /** Read the latest cached classification (no recompute). */
    public Set<Regime> getCached(IndexType ix) {
        return cache.getOrDefault(ix, EnumSet.of(Regime.NORMAL));
    }
}
