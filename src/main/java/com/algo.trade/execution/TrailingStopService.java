package com.algo.trade.execution;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.strategy.StrategyConfigService;
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

    private final StrategyConfigService strategyConfigService;
    private final GlobalConfigService globalConfigService;
    private final BigDecimal fixedActivationPercent;
    private final BigDecimal fixedGapPercent;

    /** Spring-managed: reads trailing params from GlobalConfigService (DB-backed). */
    @org.springframework.beans.factory.annotation.Autowired
    public TrailingStopService(StrategyConfigService strategyConfigService, GlobalConfigService globalConfigService) {
        this.strategyConfigService = strategyConfigService;
        this.globalConfigService = globalConfigService;
        this.fixedActivationPercent = null;
        this.fixedGapPercent = null;
    }

    /** Backtest/test use: fixed trailing params from TradingProperties. */
    public TrailingStopService(TradingProperties properties) {
        this.strategyConfigService = null;
        this.globalConfigService = null;
        this.fixedActivationPercent = properties.exit().trailingStopActivationPercent();
        this.fixedGapPercent = properties.exit().trailingGapPercent();
    }

    /**
     * Convenience overload that reads trailing params from globalConfigService (backward compatible).
     */
    public Optional<BigDecimal> nextStop(BigDecimal entryPrice, BigDecimal highestPrice, Optional<BigDecimal> currentStop) {
        BigDecimal activation = fixedActivationPercent != null
                ? fixedActivationPercent
                : globalConfigService != null
                        ? globalConfigService.getTrailingStopActivationPercent()
                        : strategyConfigService.getDirectionalBuyConfig().getTrailingStopActivationPercent();
        BigDecimal gap = fixedGapPercent != null
                ? fixedGapPercent
                : globalConfigService != null
                        ? globalConfigService.getTrailingGapPercent()
                        : strategyConfigService.getDirectionalBuyConfig().getTrailingGapPercent();
        return nextStop(entryPrice, highestPrice, currentStop, activation, gap);
    }

    /**
     * Computes the next trailing stop using caller-supplied activation and gap percentages.
     * Used by LivePositionExitMonitor to pass per-strategy StrategyConfig values.
     */
    public Optional<BigDecimal> nextStop(BigDecimal entryPrice, BigDecimal highestPrice,
                                          Optional<BigDecimal> currentStop,
                                          BigDecimal activationPercent, BigDecimal gapPercent) {
        BigDecimal activationPrice = entryPrice.multiply(BigDecimal.ONE.add(
                activationPercent.movePointLeft(2)), MATH_CONTEXT);
        if (highestPrice.compareTo(activationPrice) < 0) {
            log.debug("Trailing stop unchanged before activation: entryPrice={}, highestPrice={}, activationPrice={}, currentStop={}",
                    entryPrice, highestPrice, activationPrice, currentStop.orElse(null));
            return currentStop;
        }

        BigDecimal candidate = highestPrice.multiply(BigDecimal.ONE.subtract(
                gapPercent.movePointLeft(2)), MATH_CONTEXT);
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
