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
     * Range-bound evaluation result. {@code atrRatio} and {@code efficiency} are exposed
     * even when {@code rangeBound} is false so the AlgoFlow regime gate can be tuned
     * against days that should have traded but were blocked.
     *
     * <p>{@code atrRatio} = currentAtr / historicalAtr (lower = compressing).
     * {@code efficiency} = |net move| / sum of absolute step moves (lower = choppier).
     */
    public record Result(boolean rangeBound, double atrRatio, double efficiency) {
        public static final Result INSUFFICIENT_DATA = new Result(false, Double.NaN, Double.NaN);
    }

    /**
     * @return true if the market appears range-bound/choppy
     */
    public boolean isRangeBound(List<Candle> candles) {
        return evaluate(candles).rangeBound();
    }

    /**
     * Detailed evaluation exposing the underlying ATR-compression and
     * directional-efficiency metrics. Callers that need to surface or log these
     * values (e.g. AlgoFlow's regime gate) should prefer this over
     * {@link #isRangeBound(List)} so the metrics are available for both
     * range-bound and trending outcomes.
     */
    public Result evaluate(List<Candle> candles) {
        if (candles.size() < LOOKBACK + ATR_PERIOD) return Result.INSUFFICIENT_DATA;

        // 1. ATR compression
        double currentAtr = calculateAtr(candles, ATR_PERIOD);
        double historicalAtr = calculateAtr(candles.subList(0, candles.size() - ATR_PERIOD), ATR_PERIOD);
        double atrRatio = historicalAtr > 0 ? currentAtr / historicalAtr : Double.NaN;
        boolean atrCompressed = historicalAtr > 0 && atrRatio < ATR_COMPRESSION_THRESHOLD;

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
                    String.format("%.2f", atrRatio),
                    String.format("%.2f", efficiency));
        }
        return new Result(rangeBound, atrRatio, efficiency);
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
