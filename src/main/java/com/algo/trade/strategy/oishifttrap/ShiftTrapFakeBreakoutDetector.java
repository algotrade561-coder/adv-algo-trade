package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Feature 7 — Fake-breakout / bull-bear trap blocker.
 *
 * <p>Classic operator trap: a sharp volume spike that immediately reverses inside the same
 * candle. Writers fade the move, retail gets stopped out. We block new OI Shift Trap entries
 * when the most recent candle shows both:
 * <ul>
 *   <li>Volume &gt; {@code volumeMultiplier} × average of the 5 prior bars, AND</li>
 *   <li>Retracement &gt; {@code reversalPercent} of the candle's open-to-extreme range.</li>
 * </ul>
 *
 * <p>Stateless — safe to share across threads. Owned by the OI Shift Trap pipeline; gated on
 * {@code OiShiftTrapConfig.enhancementsEnabled} by the caller. Returns {@code false} on
 * insufficient data so the legacy path is never harder.
 */
@Component
public class ShiftTrapFakeBreakoutDetector {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapFakeBreakoutDetector.class);
    private static final int LOOKBACK_BARS = 5;

    /**
     * Decide whether the last candle in {@code candles} is a fake-breakout (and entries should
     * be blocked). Returns {@code false} when there aren't enough bars to evaluate, when the
     * 5-bar average volume is zero, or when either gate fails.
     *
     * @param candles          chronologically ordered candles (most recent last)
     * @param volumeMultiplier minimum (last.volume / 5-bar avg) ratio to consider it a spike (e.g. 2.0)
     * @param reversalPercent  minimum fraction retraced from open-to-extreme range (e.g. 0.30 = 30%)
     */
    public boolean isFakeBreakout(List<Candle> candles, double volumeMultiplier, double reversalPercent) {
        if (candles == null || candles.size() < LOOKBACK_BARS + 1) {
            return false;
        }
        Candle last = candles.get(candles.size() - 1);
        long volSum = 0;
        for (int i = candles.size() - 1 - LOOKBACK_BARS; i < candles.size() - 1; i++) {
            volSum += candles.get(i).volume();
        }
        double avgVolume = volSum / (double) LOOKBACK_BARS;
        if (avgVolume <= 0) {
            return false;
        }
        double volRatio = last.volume() / avgVolume;
        if (volRatio < volumeMultiplier) {
            return false;
        }

        double open = last.open().doubleValue();
        double high = last.high().doubleValue();
        double low = last.low().doubleValue();
        double close = last.close().doubleValue();
        double upRange = high - open;
        double downRange = open - low;

        boolean reversed;
        if (upRange >= downRange) {
            // Bullish-leaning candle that pulled back from the high.
            if (upRange <= 0) {
                return false;
            }
            double retrace = (high - close) / upRange;
            reversed = retrace >= reversalPercent;
        } else {
            // Bearish-leaning candle that rebounded from the low.
            if (downRange <= 0) {
                return false;
            }
            double retrace = (close - low) / downRange;
            reversed = retrace >= reversalPercent;
        }

        if (reversed) {
            log.debug("[ShiftTrap] Fake-breakout detected: vol={} avg={} ratio={} retrace>={}",
                    last.volume(), String.format("%.0f", avgVolume),
                    String.format("%.2f", volRatio), reversalPercent);
        }
        return reversed;
    }
}
