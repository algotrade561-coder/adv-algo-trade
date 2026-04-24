package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.List;

/**
 * Detects swing high and swing low breakouts with a configurable buffer.
 */
public class BreakoutDetector {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    public boolean breaksAboveSwingHigh(List<Candle> candles, int lookback, BigDecimal bufferPercent) {
        if (candles == null || candles.size() < 2) {
            return false;
        }
        Candle latest = candles.getLast();
        BigDecimal swingHigh = swingHigh(candles.subList(0, candles.size() - 1), lookback);
        BigDecimal required = applyPositiveBuffer(swingHigh, bufferPercent);
        return latest.close().compareTo(required) > 0;
    }

    public boolean breaksBelowSwingLow(List<Candle> candles, int lookback, BigDecimal bufferPercent) {
        if (candles == null || candles.size() < 2) {
            return false;
        }
        Candle latest = candles.getLast();
        BigDecimal swingLow = swingLow(candles.subList(0, candles.size() - 1), lookback);
        BigDecimal required = applyNegativeBuffer(swingLow, bufferPercent);
        return latest.close().compareTo(required) < 0;
    }

    public BigDecimal swingHigh(List<Candle> candles, int lookback) {
        validate(candles, lookback);
        return candles.subList(Math.max(0, candles.size() - lookback), candles.size()).stream()
                .map(Candle::high)
                .max(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    public BigDecimal swingLow(List<Candle> candles, int lookback) {
        validate(candles, lookback);
        return candles.subList(Math.max(0, candles.size() - lookback), candles.size()).stream()
                .map(Candle::low)
                .min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal applyPositiveBuffer(BigDecimal value, BigDecimal bufferPercent) {
        return value.multiply(BigDecimal.ONE.add(bufferPercent.divide(BigDecimal.valueOf(100), MATH_CONTEXT)),
                MATH_CONTEXT);
    }

    private BigDecimal applyNegativeBuffer(BigDecimal value, BigDecimal bufferPercent) {
        return value.multiply(BigDecimal.ONE.subtract(bufferPercent.divide(BigDecimal.valueOf(100), MATH_CONTEXT)),
                MATH_CONTEXT);
    }

    private void validate(List<Candle> candles, int lookback) {
        if (candles == null || candles.isEmpty()) {
            throw new IllegalArgumentException("candles must not be empty");
        }
        if (lookback <= 0) {
            throw new IllegalArgumentException("lookback must be positive");
        }
    }
}
