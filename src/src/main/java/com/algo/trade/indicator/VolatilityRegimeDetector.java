package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Volatility Regime Detector — classifies market into LOW / MEDIUM / HIGH
 * based on ATR (Average True Range) relative to price.
 *
 * LOW    → IV cheap, good time to BUY options
 * MEDIUM → normal conditions, standard strategy applies
 * HIGH   → IV expensive, avoid buying; consider tighter stops
 */
@Component
public class VolatilityRegimeDetector {

    public enum Regime { LOW, MEDIUM, HIGH }

    /** Detect regime from underlying candles using 14-period ATR. */
    public Regime detect(List<Candle> candles) {
        if (candles.size() < 15) return Regime.MEDIUM;
        double atrPct = atrPercent(candles, 14);
        if (atrPct < 0.5) return Regime.LOW;
        if (atrPct < 1.2) return Regime.MEDIUM;
        return Regime.HIGH;
    }

    /** Returns true when regime is suitable for buying options. */
    public boolean isSuitableForBuying(List<Candle> candles) {
        Regime r = detect(candles);
        return r == Regime.LOW || r == Regime.MEDIUM;
    }

    /** ATR as percentage of current price. */
    public double atrPercent(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 1.0;
        List<Candle> window = candles.subList(candles.size() - period - 1, candles.size());
        double atrSum = 0;
        for (int i = 1; i < window.size(); i++) {
            Candle cur  = window.get(i);
            Candle prev = window.get(i - 1);
            double hl = cur.high().subtract(cur.low()).doubleValue();
            double hc = Math.abs(cur.high().subtract(prev.close()).doubleValue());
            double lc = Math.abs(cur.low().subtract(prev.close()).doubleValue());
            atrSum += Math.max(hl, Math.max(hc, lc));
        }
        double atr = atrSum / period;
        double price = candles.getLast().close().doubleValue();
        return price > 0 ? (atr / price) * 100.0 : 1.0;
    }
}
