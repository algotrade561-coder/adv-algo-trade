package com.algo.trade.strategy.oishifttrap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.Timeframe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShiftTrapConfirmationEvaluatorTest {

    @Test
    void spotStalledWhenRecentRangeIsTiny() {
        List<Candle> candles = new ArrayList<>();
        Instant t = Instant.parse("2026-05-29T04:00:00Z");
        candles.add(candle(t, 25000, 25000.5, 24999.5, 25000));
        candles.add(candle(t.plusSeconds(60), 25000.1, 25000.2, 24999.9, 25000.1));
        assertTrue(ShiftTrapConfirmationEvaluator.evaluate(
                "CE", null, BigDecimal.valueOf(25000), candles,
                new ShiftTrapVelocityCalculator.Velocity(-0.01, -0.02, 0),
                null, "NIFTY").spotStalled());
    }

    @Test
    void momentumDeceleratingWhenAccelerationNegative() {
        List<Candle> candles = List.of();
        assertTrue(ShiftTrapConfirmationEvaluator.evaluate(
                "PE", null, BigDecimal.valueOf(25000), candles,
                new ShiftTrapVelocityCalculator.Velocity(-0.5, -0.3, -0.4),
                null, "NIFTY").momentumDecelerating());
    }

    @Test
    void momentumNotDeceleratingWhenAccelerationPositive() {
        assertFalse(ShiftTrapConfirmationEvaluator.evaluate(
                "PE", null, BigDecimal.valueOf(25000), List.of(),
                new ShiftTrapVelocityCalculator.Velocity(0.5, 0.2, 0.4),
                null, "NIFTY").momentumDecelerating());
    }

    private static Candle candle(Instant ts, double o, double h, double l, double c) {
        return new Candle("NIFTY", ts, Timeframe.ONE_MINUTE, BigDecimal.valueOf(o), BigDecimal.valueOf(h),
                BigDecimal.valueOf(l), BigDecimal.valueOf(c), 1000, 0);
    }
}
