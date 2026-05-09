package com.algo.trade.indicator;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Simple implied-volatility threshold filter for entry decisions.
 */
public class VolatilityFilter {

    public boolean isAcceptable(Optional<BigDecimal> impliedVolatility, BigDecimal maxIvPercent) {
        return impliedVolatility.isEmpty() || impliedVolatility.get().compareTo(maxIvPercent) <= 0;
    }
}
