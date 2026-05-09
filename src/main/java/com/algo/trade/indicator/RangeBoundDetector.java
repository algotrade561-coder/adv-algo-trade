package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Range-Bound Detector — identifies when the market is in a choppy/sideways phase.
 *
 * Uses two signals:
 *   1. ATR compression: current ATR < 60% of 20-period average ATR → market is contracting
 *   2. Directional efficiency: net price change / sum of absolute candle moves < 0.3 → choppy
 *
 * When both signals agree, the market is range-bound and directional strategies should skip.
 */
@Component
public class RangeBoundDetector {

    private static final Logger log = LoggerFactory.getLogger(RangeBoundDetector.class);
    private static final int ATR_PERIOD = 14;
    private static final int LOOKBACK = 20;
    private static final double ATR_COMPRESSION_THRESHOLD = 0.60;
    private static final double DIRECTIONAL_EFFICIENCY_THRESHOLD = 0.30;

    /**
     * @return true if the market appears range-bound/choppy
     */
    public boolean isRangeBound(List<Candle> candles) {
        if (candles.size() < LOOKBACK + ATR_PERIOD) return false;

        // 1. ATR compression
        double currentAtr = calculateAtr(candles, ATR_PERIOD);
        double historicalAtr = calculateAtr(candles.subList(0, candles.size() - ATR_PERIOD), ATR_PERIOD);
        boolean atrCompressed = historicalAtr > 0 && (currentAtr / historicalAtr) < ATR_COMPRESSION_THRESHOLD;

        // 2. Directional efficiency (net move / total path)
        List<Candle> recent = candles.subList(candles.size() - LOOKBACK, candles.size());
        double netMove = Math.abs(recent.getLast().close().subtract(recent.getFirst().open()).doubleValue());
        double totalPath = 0;
        for (int i = 1; i < recent.size(); i++) {
            totalPath += Math.abs(recent.get(i).close().subtract(recent.get(i - 1).close()).doubleValue());
        }
        double efficiency = totalPath > 0 ? netMove / totalPath : 1.0;
        boolean choppy = efficiency < DIRECTIONAL_EFFICIENCY_THRESHOLD;

        boolean rangeBound = atrCompressed && choppy;
        if (rangeBound) {
            log.debug("[RangeBound] Detected: ATR ratio={} efficiency={}",
                    String.format("%.2f", historicalAtr > 0 ? currentAtr / historicalAtr : 0),
                    String.format("%.2f", efficiency));
        }
        return rangeBound;
    }

    private double calculateAtr(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 0;
        double sum = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double tr = Math.max(c.high().subtract(c.low()).doubleValue(),
                    Math.max(Math.abs(c.high().subtract(prev.close()).doubleValue()),
                            Math.abs(c.low().subtract(prev.close()).doubleValue())));
            sum += tr;
        }
        return sum / period;
    }
}
