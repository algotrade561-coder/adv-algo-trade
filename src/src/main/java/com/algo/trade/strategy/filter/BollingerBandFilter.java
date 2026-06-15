package com.algo.trade.strategy.filter;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveCandleBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bollinger Band Filter — confirmation layer for all buying strategies.
 *
 * Provides three signal types:
 *   1. SQUEEZE_BREAKOUT — bandwidth contracted then price breaks out
 *   2. BAND_RIDE — price hugging upper/lower band with volume
 *   3. MEAN_REVERSION — price touched outer band and reversed toward midline
 */
@Component
public class BollingerBandFilter {

    private static final Logger log = LoggerFactory.getLogger(BollingerBandFilter.class);

    private final LiveCandleBuilder candleBuilder;

    @Value("${bollinger.period:20}") private int period;
    @Value("${bollinger.std-dev-multiplier:2.0}") private double stdDevMultiplier;
    @Value("${bollinger.squeeze-threshold:0.02}") private double squeezeThreshold;
    @Value("${bollinger.breakout-volume-multiplier:1.3}") private double breakoutVolumeMultiplier;

    public enum BBSignalType { SQUEEZE_BREAKOUT, BAND_RIDE, MEAN_REVERSION, NONE }

    public record BBSignal(BBSignalType type, String direction, double bandwidth,
                           double pricePosition, boolean squeezed, String reason) {}

    public record BollingerBands(double upper, double middle, double lower, double bandwidth) {}

    public BollingerBandFilter(LiveCandleBuilder candleBuilder) {
        this.candleBuilder = candleBuilder;
    }

    public BollingerBands calculate(List<Candle> candles) {
        if (candles.size() < period) return null;

        int start = candles.size() - period;
        double sum = 0;
        for (int i = start; i < candles.size(); i++) {
            sum += candles.get(i).close().doubleValue();
        }
        double sma = sum / period;

        double variance = 0;
        for (int i = start; i < candles.size(); i++) {
            double diff = candles.get(i).close().doubleValue() - sma;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / period);

        double upper = sma + stdDevMultiplier * stdDev;
        double lower = sma - stdDevMultiplier * stdDev;
        double bandwidth = sma > 0 ? (upper - lower) / sma * 100 : 0;

        return new BollingerBands(upper, sma, lower, bandwidth);
    }

    public BBSignal getSignal(IndexType indexType) {
        long token = resolveToken(indexType);
        List<Candle> candles5m = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles5m.size() < period + 2) {
            return new BBSignal(BBSignalType.NONE, null, 0, 0.5, false, "Insufficient data");
        }

        BollingerBands current = calculate(candles5m);
        if (current == null) return new BBSignal(BBSignalType.NONE, null, 0, 0.5, false, "Calc failed");

        Candle latest = candles5m.get(candles5m.size() - 1);
        Candle prev = candles5m.get(candles5m.size() - 2);
        double price = latest.close().doubleValue();

        double range = current.upper - current.lower;
        double pricePosition = range > 0 ? (price - current.lower) / range : 0.5;
        boolean squeezed = current.bandwidth < squeezeThreshold * 100;

        List<Candle> prevCandles = candles5m.subList(0, candles5m.size() - 1);
        BollingerBands prevBB = calculate(prevCandles);
        boolean wasSqueezed = prevBB != null && prevBB.bandwidth < squeezeThreshold * 100;

        double avgVol = candles5m.stream().mapToLong(Candle::volume).average().orElse(1);
        boolean volumeSurge = avgVol > 0 && latest.volume() > avgVol * breakoutVolumeMultiplier;

        // SQUEEZE BREAKOUT
        if (wasSqueezed && !squeezed && volumeSurge) {
            String direction = price > current.middle ? "BULLISH" : "BEARISH";
            return new BBSignal(BBSignalType.SQUEEZE_BREAKOUT, direction, current.bandwidth,
                    pricePosition, false,
                    "BB squeeze breakout " + direction + " bandwidth=" + String.format("%.2f", current.bandwidth) + "%");
        }

        // BAND RIDE
        double bodyPct = Math.abs(latest.close().doubleValue() - latest.open().doubleValue()) / latest.open().doubleValue() * 100;
        if (price >= current.upper && bodyPct > 0.05 && volumeSurge) {
            return new BBSignal(BBSignalType.BAND_RIDE, "BULLISH", current.bandwidth, pricePosition, squeezed,
                    "Riding upper band with volume");
        }
        if (price <= current.lower && bodyPct > 0.05 && volumeSurge) {
            return new BBSignal(BBSignalType.BAND_RIDE, "BEARISH", current.bandwidth, pricePosition, squeezed,
                    "Riding lower band with volume");
        }

        // MEAN REVERSION
        if (prevBB != null) {
            if (prev.close().doubleValue() <= prevBB.lower && latest.close().doubleValue() > current.lower
                    && latest.close().doubleValue() > latest.open().doubleValue()) {
                return new BBSignal(BBSignalType.MEAN_REVERSION, "BULLISH", current.bandwidth, pricePosition, squeezed,
                        "Mean reversion from lower band");
            }
            if (prev.close().doubleValue() >= prevBB.upper && latest.close().doubleValue() < current.upper
                    && latest.close().doubleValue() < latest.open().doubleValue()) {
                return new BBSignal(BBSignalType.MEAN_REVERSION, "BEARISH", current.bandwidth, pricePosition, squeezed,
                        "Mean reversion from upper band");
            }
        }

        return new BBSignal(BBSignalType.NONE, null, current.bandwidth, pricePosition, squeezed, "No BB signal");
    }

    public boolean confirmBreakout(IndexType indexType, String direction) {
        long token = resolveToken(indexType);
        List<Candle> candles5m = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles5m.size() < period + 2) return true;

        BollingerBands bb = calculate(candles5m);
        if (bb == null) return true;

        double price = candles5m.get(candles5m.size() - 1).close().doubleValue();
        List<Candle> prevCandles = candles5m.subList(0, candles5m.size() - 1);
        BollingerBands prevBB = calculate(prevCandles);

        if ("BULLISH".equals(direction)) {
            boolean priceBeyondUpper = price >= bb.upper * 0.998;
            boolean expanding = prevBB != null && bb.bandwidth > prevBB.bandwidth;
            boolean aboveMid = price > bb.middle;
            return priceBeyondUpper || (expanding && aboveMid);
        } else {
            boolean priceBeyondLower = price <= bb.lower * 1.002;
            boolean expanding = prevBB != null && bb.bandwidth > prevBB.bandwidth;
            boolean belowMid = price < bb.middle;
            return priceBeyondLower || (expanding && belowMid);
        }
    }

    public boolean confirmReversal(IndexType indexType, String direction) {
        long token = resolveToken(indexType);
        List<Candle> candles5m = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles5m.size() < period + 2) return true;

        BollingerBands bb = calculate(candles5m);
        if (bb == null) return true;

        Candle latest = candles5m.get(candles5m.size() - 1);
        Candle prev = candles5m.get(candles5m.size() - 2);

        if ("BULLISH".equals(direction)) {
            boolean touchedLower = prev.low().doubleValue() <= bb.lower * 1.002;
            boolean bouncing = latest.close().doubleValue() > latest.open().doubleValue()
                    && latest.close().doubleValue() > prev.close().doubleValue();
            return touchedLower && bouncing;
        } else {
            boolean touchedUpper = prev.high().doubleValue() >= bb.upper * 0.998;
            boolean dropping = latest.close().doubleValue() < latest.open().doubleValue()
                    && latest.close().doubleValue() < prev.close().doubleValue();
            return touchedUpper && dropping;
        }
    }

    public boolean isSqueezed(IndexType indexType) {
        long token = resolveToken(indexType);
        List<Candle> candles5m = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles5m.size() < period) return false;
        BollingerBands bb = calculate(candles5m);
        return bb != null && bb.bandwidth < squeezeThreshold * 100;
    }

    private long resolveToken(IndexType idx) {
        return idx == IndexType.BANKNIFTY ? 260105L : 256265L;
    }
}
