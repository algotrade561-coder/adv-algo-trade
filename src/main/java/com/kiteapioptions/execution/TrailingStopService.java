package com.kiteapioptions.execution;

import com.kiteapioptions.config.TradingProperties;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Maintains trailing stop prices for long option positions.
 */
@Service
public class TrailingStopService {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final Logger log = LoggerFactory.getLogger(TrailingStopService.class);

    private final TradingProperties properties;

    public TrailingStopService(TradingProperties properties) {
        this.properties = properties;
    }

    public Optional<BigDecimal> nextStop(BigDecimal entryPrice, BigDecimal highestPrice, Optional<BigDecimal> currentStop) {
        BigDecimal activationPrice = entryPrice.multiply(BigDecimal.ONE.add(
                properties.exit().trailingStopActivationPercent().movePointLeft(2)), MATH_CONTEXT);
        if (highestPrice.compareTo(activationPrice) < 0) {
            log.debug("Trailing stop unchanged before activation: entryPrice={}, highestPrice={}, activationPrice={}, currentStop={}",
                    entryPrice, highestPrice, activationPrice, currentStop.orElse(null));
            return currentStop;
        }

        BigDecimal candidate = highestPrice.multiply(BigDecimal.ONE.subtract(
                properties.exit().trailingGapPercent().movePointLeft(2)), MATH_CONTEXT);
        if (currentStop.isEmpty() || candidate.compareTo(currentStop.get()) > 0) {
            log.info("Trailing stop updated: entryPrice={}, highestPrice={}, previousStop={}, nextStop={}",
                    entryPrice, highestPrice, currentStop.orElse(null), candidate);
            return Optional.of(candidate);
        }
        log.debug("Trailing stop unchanged: entryPrice={}, highestPrice={}, currentStop={}, candidate={}",
                entryPrice, highestPrice, currentStop.get(), candidate);
        return currentStop;
    }

    public boolean isStopHit(BigDecimal lastPrice, BigDecimal stopPrice) {
        boolean hit = lastPrice.compareTo(stopPrice) <= 0;
        if (hit) {
            log.info("Trailing stop hit: lastPrice={}, stopPrice={}", lastPrice, stopPrice);
        }
        return hit;
    }
}
