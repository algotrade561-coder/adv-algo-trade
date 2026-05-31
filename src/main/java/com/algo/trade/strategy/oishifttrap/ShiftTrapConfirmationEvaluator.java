package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.marketdata.LiveInstrumentCache;
import java.math.BigDecimal;
import java.util.List;

/**
 * Shadow confirmation gates recorded at signal time — no production gating.
 */
final class ShiftTrapConfirmationEvaluator {

    record Confirmations(
            boolean momentumDecelerating,
            boolean spotStalled,
            boolean oiStillBuilding,
            boolean oppositeOiFlushing,
            boolean priceRetraced,
            boolean proximityTightening,
            boolean volumeSpike,
            boolean pcrAligned,
            int passedCount
    ) {
        static Confirmations empty() {
            return new Confirmations(false, false, false, false, false, false, false, false, 0);
        }
    }

    private ShiftTrapConfirmationEvaluator() {
    }

    static Confirmations evaluate(
            String trapSide,
            OptionChainLevel trapLevel,
            BigDecimal spot,
            List<Candle> underlyingCandles,
            ShiftTrapVelocityCalculator.Velocity velocity,
            LiveInstrumentCache cache,
            String underlying) {

        if (spot == null || spot.signum() <= 0) {
            return Confirmations.empty();
        }

        boolean momentumDecelerating = velocity.spotAcceleration() < 0;
        boolean spotStalled = spotStalled(underlyingCandles, spot);
        boolean oiStillBuilding = trapLevel != null && trappedOiChange(trapSide, trapLevel) > 0;
        boolean oppositeOiFlushing = trapLevel != null && oppositeOiChange(trapSide, trapLevel) < -1_000;
        boolean priceRetraced = priceRetraced(trapSide, underlyingCandles, spot);
        boolean proximityTightening = false; // needs prior snapshot — shadow false at signal unless decelerating
        boolean volumeSpike = volumeSpike(underlyingCandles);
        boolean pcrAligned = pcrAligned(trapSide, cache, underlying);

        int count = 0;
        if (momentumDecelerating) count++;
        if (spotStalled) count++;
        if (oiStillBuilding) count++;
        if (oppositeOiFlushing) count++;
        if (priceRetraced) count++;
        if (proximityTightening) count++;
        if (volumeSpike) count++;
        if (pcrAligned) count++;

        return new Confirmations(momentumDecelerating, spotStalled, oiStillBuilding, oppositeOiFlushing,
                priceRetraced, proximityTightening, volumeSpike, pcrAligned, count);
    }

    private static boolean spotStalled(List<Candle> candles, BigDecimal spot) {
        if (candles == null || candles.size() < 2 || spot.signum() <= 0) {
            return false;
        }
        Candle last = candles.getLast();
        Candle prev = candles.get(candles.size() - 2);
        double range = Math.max(last.high().doubleValue(), prev.high().doubleValue())
                - Math.min(last.low().doubleValue(), prev.low().doubleValue());
        return range / spot.doubleValue() * 100.0 < 0.05;
    }

    private static long trappedOiChange(String trapSide, OptionChainLevel level) {
        return "PE".equals(trapSide) ? level.putOpenInterestChange() : level.callOpenInterestChange();
    }

    private static long oppositeOiChange(String trapSide, OptionChainLevel level) {
        return "PE".equals(trapSide) ? level.callOpenInterestChange() : level.putOpenInterestChange();
    }

    private static boolean priceRetraced(String trapSide, List<Candle> candles, BigDecimal spot) {
        if (candles == null || candles.size() < 5) {
            return false;
        }
        int from = Math.max(0, candles.size() - 10);
        double spotVal = spot.doubleValue();
        if ("PE".equals(trapSide)) {
            double low = candles.subList(from, candles.size()).stream()
                    .mapToDouble(c -> c.low().doubleValue()).min().orElse(spotVal);
            return spotVal > low && (spotVal - low) / spotVal * 100.0 >= 0.10;
        }
        double high = candles.subList(from, candles.size()).stream()
                .mapToDouble(c -> c.high().doubleValue()).max().orElse(spotVal);
        return spotVal < high && (high - spotVal) / spotVal * 100.0 >= 0.10;
    }

    private static boolean volumeSpike(List<Candle> candles) {
        if (candles == null || candles.size() < 6) {
            return false;
        }
        long current = candles.getLast().volume();
        long avg = 0;
        for (int i = candles.size() - 6; i < candles.size() - 1; i++) {
            avg += candles.get(i).volume();
        }
        avg /= 5;
        return avg > 0 && current > avg * 1.5;
    }

    private static boolean pcrAligned(String trapSide, LiveInstrumentCache cache, String underlying) {
        try {
            IndexType ix = IndexType.valueOf(underlying);
            double pcr = cache.getRealtimePcr(ix);
            if (pcr <= 0) {
                return false;
            }
            return "PE".equals(trapSide) ? pcr > 1.0 : pcr < 1.0;
        } catch (Exception ex) {
            return false;
        }
    }

    static OptionChainLevel findTrapLevel(OptionChainSnapshot snapshot, BigDecimal strike) {
        if (snapshot == null || strike == null) {
            return null;
        }
        return snapshot.levels().stream()
                .filter(l -> l.strike().compareTo(strike) == 0)
                .findFirst()
                .orElse(null);
    }
}
