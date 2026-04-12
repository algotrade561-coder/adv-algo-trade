package com.kiteapioptions.execution;

import com.kiteapioptions.config.TradingProperties;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Maintains trailing stop prices for long option positions.
 */
@Service
public class TrailingStopService {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final TradingProperties properties;

    public TrailingStopService(TradingProperties properties) {
        this.properties = properties;
    }

    public Optional<BigDecimal> nextStop(BigDecimal entryPrice, BigDecimal highestPrice, Optional<BigDecimal> currentStop) {
        BigDecimal activationPrice = entryPrice.multiply(BigDecimal.ONE.add(
                properties.exit().trailingStopActivationPercent().movePointLeft(2)), MATH_CONTEXT);
        if (highestPrice.compareTo(activationPrice) < 0) {
            return currentStop;
        }

        BigDecimal candidate = highestPrice.multiply(BigDecimal.ONE.subtract(
                properties.exit().trailingGapPercent().movePointLeft(2)), MATH_CONTEXT);
        if (currentStop.isEmpty() || candidate.compareTo(currentStop.get()) > 0) {
            return Optional.of(candidate);
        }
        return currentStop;
    }

    public boolean isStopHit(BigDecimal lastPrice, BigDecimal stopPrice) {
        return lastPrice.compareTo(stopPrice) <= 0;
    }
}
