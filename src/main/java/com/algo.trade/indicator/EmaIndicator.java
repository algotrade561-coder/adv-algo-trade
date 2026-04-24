package com.algo.trade.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Exponential moving average calculator.
 */
public class EmaIndicator {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public BigDecimal calculate(List<BigDecimal> values, int period) {
        if (values == null || values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        if (period <= 0) {
            throw new IllegalArgumentException("period must be positive");
        }

        BigDecimal multiplier = BigDecimal.valueOf(2).divide(BigDecimal.valueOf(period + 1L), MATH_CONTEXT);
        BigDecimal ema = values.getFirst();
        for (int i = 1; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(multiplier, MATH_CONTEXT).add(ema);
        }
        return ema;
    }
}
