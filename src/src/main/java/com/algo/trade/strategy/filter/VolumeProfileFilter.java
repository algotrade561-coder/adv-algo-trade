package com.algo.trade.strategy.filter;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveCandleBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Volume Profile Filter (Visible Range) — identifies support/resistance from volume distribution.
 *
 * Builds a volume-at-price histogram from intraday candles to identify:
 *   - High Volume Nodes (HVN) → strong support/resistance
 *   - Low Volume Nodes (LVN) → breakout zones
 *   - Value Area High/Low (VAH/VAL) → 70% of volume traded between these levels
 *   - Point of Control (POC) → price level with highest volume
 */
@Component
public class VolumeProfileFilter {

    private static final Logger log = LoggerFactory.getLogger(VolumeProfileFilter.class);

    private final LiveCandleBuilder candleBuilder;

    @Value("${volume-profile.bin-count:50}") private int binCount;
    @Value("${volume-profile.value-area-percent:70}") private double valueAreaPercent;
    @Value("${volume-profile.hvn-threshold-multiplier:1.5}") private double hvnThresholdMultiplier;
    @Value("${volume-profile.lvn-threshold-multiplier:0.5}") private double lvnThresholdMultiplier;
    @Value("${volume-profile.proximity-percent:0.15}") private double proximityPercent;

    public record ValueArea(double poc, double vah, double val, double dayHigh, double dayLow) {}

    public record VolumeNode(double priceLevel, long volume, boolean isHVN, boolean isLVN) {}

    public VolumeProfileFilter(LiveCandleBuilder candleBuilder) {
        this.candleBuilder = candleBuilder;
    }

    public ValueArea getValueArea(IndexType indexType) {
        long token = resolveToken(indexType);
        List<Candle> candles = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles.size() < 10) return null;

        double dayHigh = candles.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(0);
        double dayLow = candles.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(0);
        if (dayHigh <= dayLow) return null;

        double binSize = (dayHigh - dayLow) / binCount;
        if (binSize <= 0) return null;

        long[] volumeBins = new long[binCount];
        double[] priceBins = new double[binCount];

        for (int i = 0; i < binCount; i++) {
            priceBins[i] = dayLow + (i + 0.5) * binSize;
        }

        for (Candle c : candles) {
            int lowBin = Math.max(0, (int) ((c.low().doubleValue() - dayLow) / binSize));
            int highBin = Math.min(binCount - 1, (int) ((c.high().doubleValue() - dayLow) / binSize));
            int binsSpanned = highBin - lowBin + 1;
            long volPerBin = binsSpanned > 0 ? c.volume() / binsSpanned : c.volume();
            for (int i = lowBin; i <= highBin; i++) {
                volumeBins[i] += volPerBin;
            }
        }

        int pocBin = 0;
        long maxVol = 0;
        for (int i = 0; i < binCount; i++) {
            if (volumeBins[i] > maxVol) { maxVol = volumeBins[i]; pocBin = i; }
        }
        double poc = priceBins[pocBin];

        long totalVolume = Arrays.stream(volumeBins).sum();
        long targetVolume = (long) (totalVolume * valueAreaPercent / 100.0);

        long accumulatedVolume = volumeBins[pocBin];
        int vaLowBin = pocBin, vaHighBin = pocBin;

        while (accumulatedVolume < targetVolume) {
            long addLow = (vaLowBin > 0) ? volumeBins[vaLowBin - 1] : 0;
            long addHigh = (vaHighBin < binCount - 1) ? volumeBins[vaHighBin + 1] : 0;
            if (addHigh >= addLow && vaHighBin < binCount - 1) { vaHighBin++; accumulatedVolume += addHigh; }
            else if (vaLowBin > 0) { vaLowBin--; accumulatedVolume += addLow; }
            else break;
        }

        double vah = priceBins[vaHighBin] + binSize / 2;
        double val = priceBins[vaLowBin] - binSize / 2;

        return new ValueArea(poc, vah, val, dayHigh, dayLow);
    }

    public boolean isBreakoutZone(IndexType indexType, double price) {
        long token = resolveToken(indexType);
        List<Candle> candles = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles.size() < 10) return true;

        double dayHigh = candles.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(0);
        double dayLow = candles.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(0);
        if (dayHigh <= dayLow || price < dayLow || price > dayHigh) return true;

        double binSize = (dayHigh - dayLow) / binCount;
        if (binSize <= 0) return true;

        long[] volumeBins = new long[binCount];
        for (Candle c : candles) {
            int lowBin = Math.max(0, (int) ((c.low().doubleValue() - dayLow) / binSize));
            int highBin = Math.min(binCount - 1, (int) ((c.high().doubleValue() - dayLow) / binSize));
            int binsSpanned = highBin - lowBin + 1;
            long volPerBin = binsSpanned > 0 ? c.volume() / binsSpanned : c.volume();
            for (int i = lowBin; i <= highBin; i++) volumeBins[i] += volPerBin;
        }

        long avgVolPerBin = Arrays.stream(volumeBins).sum() / binCount;
        int priceBin = Math.min(binCount - 1, Math.max(0, (int) ((price - dayLow) / binSize)));
        return volumeBins[priceBin] < avgVolPerBin * lvnThresholdMultiplier;
    }

    public boolean isSupportResistance(IndexType indexType, double price) {
        long token = resolveToken(indexType);
        List<Candle> candles = candleBuilder.getHistory(token, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
        if (candles.size() < 10) return false;

        double dayHigh = candles.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(0);
        double dayLow = candles.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(0);
        if (dayHigh <= dayLow || price < dayLow || price > dayHigh) return false;

        double binSize = (dayHigh - dayLow) / binCount;
        if (binSize <= 0) return false;

        long[] volumeBins = new long[binCount];
        for (Candle c : candles) {
            int lowBin = Math.max(0, (int) ((c.low().doubleValue() - dayLow) / binSize));
            int highBin = Math.min(binCount - 1, (int) ((c.high().doubleValue() - dayLow) / binSize));
            int binsSpanned = highBin - lowBin + 1;
            long volPerBin = binsSpanned > 0 ? c.volume() / binsSpanned : c.volume();
            for (int i = lowBin; i <= highBin; i++) volumeBins[i] += volPerBin;
        }

        long avgVolPerBin = Arrays.stream(volumeBins).sum() / binCount;
        int priceBin = Math.min(binCount - 1, Math.max(0, (int) ((price - dayLow) / binSize)));
        return volumeBins[priceBin] > avgVolPerBin * hvnThresholdMultiplier;
    }

    public boolean isNearPOC(IndexType indexType, double price) {
        ValueArea va = getValueArea(indexType);
        if (va == null) return false;
        double distance = Math.abs(price - va.poc) / va.poc * 100;
        return distance <= proximityPercent;
    }

    public boolean confirmBreakout(IndexType indexType, double price, String direction) {
        ValueArea va = getValueArea(indexType);
        if (va == null) return true;
        boolean inLVN = isBreakoutZone(indexType, price);
        boolean outsideValueArea = ("BULLISH".equals(direction) && price > va.vah)
                || ("BEARISH".equals(direction) && price < va.val);
        return inLVN || outsideValueArea;
    }

    public boolean confirmReversal(IndexType indexType, double price) {
        return isSupportResistance(indexType, price);
    }

    private long resolveToken(IndexType idx) {
        return idx == IndexType.BANKNIFTY ? 260105L : 256265L;
    }
}
