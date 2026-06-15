package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.Candle;
import java.math.BigDecimal;
import java.util.List;

/**
 * Spot velocity and acceleration from underlying candle history at signal time.
 */
public final class ShiftTrapVelocityCalculator {

    public record Velocity(double spotVelocity1m, double spotVelocity3m, double spotAcceleration) {
            public static Velocity zero() {
            return new Velocity(0, 0, 0);
        }
    }

    private ShiftTrapVelocityCalculator() {
    }

    public static Velocity compute(List<Candle> candles, BigDecimal spot) {
        if (candles == null || candles.size() < 2 || spot == null || spot.signum() <= 0) {
            return Velocity.zero();
        }
        double spotVal = spot.doubleValue();
        double v1m = pctMove(candles, spotVal, 1);
        double v3m = pctMove(candles, spotVal, 3);
        double acceleration = v1m - (v3m / 3.0);
        return new Velocity(v1m, v3m, acceleration);
    }

    private static double pctMove(List<Candle> candles, double spotNow, int lookbackMinutes) {
        if (candles.size() <= lookbackMinutes) {
            return 0;
        }
        double prior = candles.get(candles.size() - 1 - lookbackMinutes).close().doubleValue();
        if (prior <= 0) {
            return 0;
        }
        return (spotNow - prior) / prior * 100.0;
    }
}
