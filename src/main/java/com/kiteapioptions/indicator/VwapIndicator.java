package com.kiteapioptions.indicator;

import com.kiteapioptions.domain.Candle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Volume weighted average price calculator.
 */
public class VwapIndicator {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public BigDecimal calculate(List<Candle> candles) {
        if (candles == null || candles.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal priceVolume = BigDecimal.ZERO;
        long totalVolume = 0;
        for (Candle candle : candles) {
            if (candle.volume() <= 0) {
                continue;
            }
            BigDecimal typicalPrice = candle.high().add(candle.low()).add(candle.close())
                    .divide(BigDecimal.valueOf(3), MATH_CONTEXT);
            priceVolume = priceVolume.add(typicalPrice.multiply(BigDecimal.valueOf(candle.volume()), MATH_CONTEXT));
            totalVolume += candle.volume();
        }
        if (totalVolume == 0) {
            return candles.getLast().close();
        }
        return priceVolume.divide(BigDecimal.valueOf(totalVolume), MATH_CONTEXT);
    }
}
