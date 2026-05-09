package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Computes realized (historical) volatility from close-to-close daily returns.
 * RV = annualized standard deviation of log returns = stddev(ln(C_t/C_{t-1})) * sqrt(252) * 100
 */
@Component
public class RealizedVolatilityCalculator {

    private static final double TRADING_DAYS = 252.0;

    /**
     * Compute 5-day realized volatility (as a percentage) from intraday candles.
     * Uses close prices only. Returns 0 if fewer than 2 closes available.
     */
    public double calculate5Day(List<Candle> candles) {
        return calculate(candles, 5);
    }

    /**
     * Compute N-day realized volatility (as a percentage) from candles.
     * Uses the last (n+1) close prices to produce n log returns.
     * Returns 0 if insufficient data.
     */
    public double calculate(List<Candle> candles, int days) {
        if (candles == null || candles.size() < 2) return 0.0;

        int lookback = Math.min(days + 1, candles.size());
        List<Candle> window = candles.subList(candles.size() - lookback, candles.size());

        double[] logReturns = new double[window.size() - 1];
        for (int i = 1; i < window.size(); i++) {
            double prev = window.get(i - 1).close().doubleValue();
            double curr = window.get(i).close().doubleValue();
            if (prev <= 0 || curr <= 0) return 0.0;
            logReturns[i - 1] = Math.log(curr / prev);
        }

        if (logReturns.length == 0) return 0.0;

        double mean = 0.0;
        for (double r : logReturns) mean += r;
        mean /= logReturns.length;

        double variance = 0.0;
        for (double r : logReturns) variance += (r - mean) * (r - mean);
        variance /= logReturns.length;

        // Annualize based on timeframe — assume daily candles for RV; multiply by 100 for percentage
        return Math.sqrt(variance * TRADING_DAYS) * 100.0;
    }
}
