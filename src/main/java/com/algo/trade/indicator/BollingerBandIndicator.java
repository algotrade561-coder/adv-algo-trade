package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import java.util.List;

/**
 * Bollinger Band bandwidth calculation.
 * Bandwidth = (4 × stdDev) / SMA × 100 (as percentage of price).
 * Low bandwidth indicates a squeeze (tight range, breakout imminent).
 */
public final class BollingerBandIndicator {

    private BollingerBandIndicator() {}

    /**
     * Compute BB bandwidth from the last {@code period} candle closes.
     *
     * @param candles list of candles (must have at least {@code period} entries)
     * @param period  lookback period (typically 20)
     * @return bandwidth as percentage of price, or -1 if insufficient data
     */
    public static double bandwidth(List<? extends Candle> candles, int period) {
        if (candles == null || candles.size() < period) {
            return -1;
        }
        double[] closes = candles.stream()
                .skip(candles.size() - period)
                .mapToDouble(c -> c.close().doubleValue())
                .toArray();
        if (closes.length < period) {
            return -1;
        }
        double sma = 0;
        for (double c : closes) sma += c;
        sma /= period;
        if (sma <= 0) return -1;

        double variance = 0;
        for (double c : closes) variance += Math.pow(c - sma, 2);
        double stdDev = Math.sqrt(variance / period);

        return (stdDev * 4) / sma * 100;
    }

    /**
     * Returns true if bandwidth indicates a squeeze (below threshold).
     */
    public static boolean isSqueeze(List<? extends Candle> candles, int period, double thresholdPct) {
        double bw = bandwidth(candles, period);
        return bw >= 0 && bw <= thresholdPct;
    }
}
