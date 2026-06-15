package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * OI Divergence Filter — detects when price and OI are diverging.
 *
 * Price UP + OI DOWN  → weak bullish move (longs covering, not new buying)
 * Price DOWN + OI DOWN → weak bearish move (shorts covering, not new selling)
 *
 * When divergence is detected, the strategy should avoid entry or reduce confidence.
 * When OI confirms the price move (both rising), the move has institutional backing.
 */
@Component
public class OIDivergenceFilter {

    /**
     * Returns true when OI CONFIRMS the price direction (no divergence).
     * CE entry: price rising + OI rising = confirmed bullish
     * PE entry: price falling + OI rising = confirmed bearish
     */
    public boolean isOIConfirmed(List<Candle> candles, boolean isBullish) {
        if (candles.size() < 3) return true; // not enough data, don't block

        Candle latest = candles.getLast();
        Candle prev   = candles.get(candles.size() - 2);

        boolean priceUp = latest.close().compareTo(prev.close()) > 0;
        boolean oiUp    = latest.openInterest() > prev.openInterest();

        if (isBullish) {
            // For CE: price up + OI down = divergence (weak move)
            if (priceUp && !oiUp) return false;
            return true;
        } else {
            // For PE: price down + OI down = divergence (weak move)
            if (!priceUp && !oiUp) return false;
            return true;
        }
    }

    /** OI change percentage between last two candles. */
    public double oiChangePercent(List<Candle> candles) {
        if (candles.size() < 2) return 0;
        long prev = candles.get(candles.size() - 2).openInterest();
        long cur  = candles.getLast().openInterest();
        if (prev == 0) return 0;
        return ((double)(cur - prev) / prev) * 100.0;
    }
}
