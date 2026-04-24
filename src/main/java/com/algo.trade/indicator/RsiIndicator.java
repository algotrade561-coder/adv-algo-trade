package com.algo.trade.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Relative Strength Index using Wilder smoothing.
 * In this project the strategy applies RSI to underlying-market closes for directional momentum,
 * not to option-premium closes.
 */
public class RsiIndicator {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public BigDecimal calculate(List<BigDecimal> closes, int period) {
        if (closes == null || closes.size() < period + 1) {
            return BigDecimal.valueOf(50);
        }

        BigDecimal avgGain = BigDecimal.ZERO;
        BigDecimal avgLoss = BigDecimal.ZERO;

        // Initial average over first period
        for (int i = 1; i <= period; i++) {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            if (change.signum() > 0) {
                avgGain = avgGain.add(change);
            } else {
                avgLoss = avgLoss.add(change.abs());
            }
        }
        avgGain = avgGain.divide(BigDecimal.valueOf(period), MATH_CONTEXT);
        avgLoss = avgLoss.divide(BigDecimal.valueOf(period), MATH_CONTEXT);

        // Wilder smoothing for remaining values
        for (int i = period + 1; i < closes.size(); i++) {
            BigDecimal change = closes.get(i).subtract(closes.get(i - 1));
            BigDecimal gain = change.signum() > 0 ? change : BigDecimal.ZERO;
            BigDecimal loss = change.signum() < 0 ? change.abs() : BigDecimal.ZERO;
            avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1), MATH_CONTEXT)
                    .add(gain).divide(BigDecimal.valueOf(period), MATH_CONTEXT);
            avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1), MATH_CONTEXT)
                    .add(loss).divide(BigDecimal.valueOf(period), MATH_CONTEXT);
        }

        if (avgLoss.signum() == 0) {
            return BigDecimal.valueOf(100);
        }
        BigDecimal rs = avgGain.divide(avgLoss, MATH_CONTEXT);
        return BigDecimal.valueOf(100).subtract(
                BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), MATH_CONTEXT));
    }
}
