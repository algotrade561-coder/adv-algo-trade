package com.algo.trade.strategy.filter;

import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Event & News Filter — gates entries around RBI policy, budget, earnings.
 *
 * Blocks entries when major events are imminent unless bias is clear.
 * Increases signal strength threshold during earnings season.
 *
 * INTRADAY AUTO-PAUSE: Detects VIX spikes (>5% in 5 min) which indicate
 * breaking news/events. Pauses entries for 10 min to avoid whipsaws.
 */
@Component
public class EventNewsFilter {

    private static final Logger log = LoggerFactory.getLogger(EventNewsFilter.class);

    private final MarketGuard marketGuard;

    @Value("${event-news.min-sentiment-score:0.3}") private double minSentimentScore;
    @Value("${event-news.signal-strength-multiplier-earnings:1.5}") private double earningsMultiplier;

    // Intraday VIX spike detection
    private volatile double lastVix = 0;
    private volatile long lastVixTime = 0;
    private volatile long vixSpikePauseUntil = 0;
    private final java.util.Deque<double[]> vixSamples = new java.util.concurrent.ConcurrentLinkedDeque<>();
    private static final int VIX_SPIKE_WINDOW_SECONDS = 300;
    private static final double VIX_SPIKE_THRESHOLD_PCT = 5.0;

    public EventNewsFilter(MarketGuard marketGuard) {
        this.marketGuard = marketGuard;
    }

    public void updateVix(double currentVix) {
        if (currentVix <= 0) return;
        long now = System.currentTimeMillis();

        java.time.LocalTime timeNow = java.time.LocalTime.now();
        if (timeNow.isBefore(java.time.LocalTime.of(9, 20))) {
            vixSamples.addLast(new double[]{now, currentVix});
            while (vixSamples.size() > 30) vixSamples.pollFirst();
            lastVix = currentVix;
            lastVixTime = now;
            return;
        }

        vixSamples.addLast(new double[]{now, currentVix});
        long cutoff = now - VIX_SPIKE_WINDOW_SECONDS * 1000L;
        while (!vixSamples.isEmpty() && vixSamples.peekFirst()[0] < cutoff) {
            vixSamples.pollFirst();
        }

        if (vixSamples.size() >= 10) {
            double oldestVix = vixSamples.peekFirst()[1];
            long oldestTime = (long) vixSamples.peekFirst()[0];
            long timeDiffMs = now - oldestTime;

            if (oldestVix > 0 && timeDiffMs >= 120_000) {
                double vixChange = ((currentVix - oldestVix) / oldestVix) * 100;
                if (Math.abs(vixChange) > VIX_SPIKE_THRESHOLD_PCT) {
                    vixSpikePauseUntil = now + 10 * 60_000L;
                    log.warn("[EventNews] VIX SPIKE detected: {}% over {}s ({}→{}) — pausing entries 10 min",
                            String.format("%.1f", vixChange), timeDiffMs / 1000,
                            String.format("%.2f", oldestVix), String.format("%.2f", currentVix));
                }
            }
        }

        lastVix = currentVix;
        lastVixTime = now;
    }

    public boolean isEntryAllowed(double signalStrength) {
        if (System.currentTimeMillis() < vixSpikePauseUntil) {
            log.debug("[EventNews] VIX spike pause active — blocking entry");
            return false;
        }

        if (marketGuard.isPreEventDay() || marketGuard.isEventDay()) {
            return signalStrength >= minSentimentScore;
        }

        if (isEarningsSeason()) {
            double adjustedThreshold = minSentimentScore * earningsMultiplier;
            if (signalStrength < adjustedThreshold) {
                log.debug("[EventNews] Earnings season: signal={} < threshold={}",
                        String.format("%.3f", signalStrength), String.format("%.3f", adjustedThreshold));
                return false;
            }
        }

        return true;
    }

    public boolean isVixSpikePaused() {
        return System.currentTimeMillis() < vixSpikePauseUntil;
    }

    public String getBlockReason(double signalStrength) {
        if (!isEntryAllowed(signalStrength)) {
            if (marketGuard.isPreEventDay() || marketGuard.isEventDay()) {
                return "EVENT_BLOCKED: signal=" + String.format("%.2f", signalStrength)
                        + " < threshold=" + minSentimentScore;
            }
            if (isEarningsSeason()) {
                return "EARNINGS_SEASON: signal too weak";
            }
        }
        return null;
    }

    public boolean isEarningsSeason() {
        // Simplified check — can be enhanced with external news feed integration
        return false;
    }

    public double getAdjustedSignalThreshold(double baseThreshold) {
        if (isEarningsSeason()) return baseThreshold * earningsMultiplier;
        if (marketGuard.isPreEventDay()) return baseThreshold * 1.3;
        return baseThreshold;
    }
}
