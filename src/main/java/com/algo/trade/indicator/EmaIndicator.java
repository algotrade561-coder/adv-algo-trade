package com.algo.trade.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Exponential moving average calculator.
 */
@Component
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
        // Seed with SMA of the first `period` values — single-value seed causes persistent bias
        int seedEnd = Math.min(period, values.size());
        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < seedEnd; i++) {
            sum = sum.add(values.get(i));
        }
        BigDecimal ema = sum.divide(BigDecimal.valueOf(seedEnd), MATH_CONTEXT);
        for (int i = seedEnd; i < values.size(); i++) {
            ema = values.get(i).subtract(ema).multiply(multiplier, MATH_CONTEXT).add(ema);
        }
        return ema;
    }
}
