package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Average True Range (ATR) indicator — measures market volatility.
 * Used for dynamic stop-loss sizing, trailing stops, and regime detection.
 */
@Component
public class AtrIndicator {

    /**
     * Compute ATR over the last {@code periods} candles.
     * @return ATR value, or 0 if insufficient candles
     */
    public double calculateATR(List<Candle> candles, int periods) {
        if (candles == null || candles.size() < periods + 1 || periods <= 0) return 0;

        double sum = 0;
        int start = candles.size() - periods;
        for (int i = start; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double high = c.high().doubleValue();
            double low = c.low().doubleValue();
            double prevClose = prev.close().doubleValue();
            double tr = Math.max(high - low,
                        Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
        }
        return sum / periods;
    }

    /**
     * ATR as a percentage of the latest close price.
     * @return ATR%, or 1.0 if close ≤ 0
     */
    public double calculateATRPercent(List<Candle> candles, int periods) {
        double atr = calculateATR(candles, periods);
        if (candles == null || candles.isEmpty()) return 1.0;
        double close = candles.getLast().close().doubleValue();
        if (close <= 0) return 1.0;
        return (atr / close) * 100;
    }

    /**
     * Dynamic SL% based on ATR with time-based multiplier near expiry.
     * Formula: (2 × ATR / entryPremium) × 100 × timeMultiplier, clamped [15, 60].
     * @return SL percentage, or 30 for invalid inputs
     */
    public double calculateDynamicSL(double entryPremium, double atr, int daysToExpiry) {
        if (entryPremium <= 0 || atr <= 0) return 30;

        double atrBasedSL = (2 * atr / entryPremium) * 100;

        double timeMultiplier = switch (daysToExpiry) {
            case 0 -> 0.5;
            case 1 -> 0.7;
            case 2 -> 0.85;
            default -> 1.0;
        };

        double dynamicSL = atrBasedSL * timeMultiplier;
        return Math.max(15, Math.min(60, dynamicSL));
    }

    /**
     * Trailing SL level based on ATR with profit-based tightening.
     * Trail distance = clamp((1.5 × ATR / entryPremium) × 100, 8, 25) × tighteningFactor.
     * @return trailing SL level, or -999 if peakProfitPercent < 10 (inactive)
     */
    public double calculateTrailingSL(double profitPercent, double peakProfitPercent,
                                       double atr, double entryPremium) {
        if (peakProfitPercent < 10) return -999;
        if (entryPremium <= 0 || atr <= 0) return -999;

        double baseTrail = (1.5 * atr / entryPremium) * 100;
        baseTrail = Math.max(8, Math.min(25, baseTrail));

        double tighteningFactor;
        if (peakProfitPercent > 50) tighteningFactor = 0.6;
        else if (peakProfitPercent > 30) tighteningFactor = 0.75;
        else if (peakProfitPercent > 20) tighteningFactor = 0.85;
        else tighteningFactor = 1.0;

        double adjustedTrail = baseTrail * tighteningFactor;
        return peakProfitPercent - adjustedTrail;
    }
}
