package com.algo.trade.strategy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.indicator.VolatilityRegimeDetector;
import com.algo.trade.risk.StrategyGovernor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adaptive RSI Configuration — dynamically adjusts RSI period and thresholds
 * based on volatility regime + governor state.
 *
 * Adjustments:
 *   Range-bound (LOW_VOL)  → 14-period, 70/30 bands (classic mean reversion)
 *   Trending (NORMAL)      → 14-period, 70/30 bands (standard)
 *   High volatility        → 9-period, 75/25 bands (faster signals, stricter extremes)
 *   Extreme volatility     → 5-period, 80/20 bands (only act on true extremes)
 *
 * Governor overlay:
 *   TOP rank    → -5 to overbought, +5 to oversold (looser — proven strategy)
 *   WEAK rank   → +5 to overbought, -5 to oversold (stricter)
 *   BLOCKED     → ultra-strict (85/15)
 */
@Component
public class AdaptiveRSIConfig {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveRSIConfig.class);

    private final VolatilityRegimeDetector regimeDetector;
    private final StrategyGovernor strategyGovernor;

    public record RSIConfig(
            int period,
            double overbought,
            double oversold,
            String regime,
            String governor
    ) {}

    public AdaptiveRSIConfig(VolatilityRegimeDetector regimeDetector, StrategyGovernor strategyGovernor) {
        this.regimeDetector = regimeDetector;
        this.strategyGovernor = strategyGovernor;
    }

    /**
     * Get adaptive RSI config for a strategy based on current regime + governor state.
     */
    public RSIConfig getConfig(IndexType indexType, String strategyName) {
        return getConfig(indexType, strategyName, java.util.List.of());
    }

    /**
     * Get adaptive RSI config with candle data for regime detection.
     */
    public RSIConfig getConfig(IndexType indexType, String strategyName, java.util.List<com.algo.trade.domain.Candle> candles) {
        String regime = regimeDetector.detect(candles).name();
        int period;
        double overbought, oversold;

        switch (regime) {
            case "LOW" -> { period = 14; overbought = 70; oversold = 30; }
            case "HIGH" -> { period = 9; overbought = 75; oversold = 25; }
            default -> { period = 14; overbought = 70; oversold = 30; }
        }

        // Governor overlay
        StrategyGovernor.GovernanceAction gov = strategyGovernor.getGovernance(strategyName);
        String govState = gov.rank().name();

        switch (gov.rank()) {
            case TOP -> { overbought -= 5; oversold += 5; }
            case WEAK -> { overbought += 5; oversold -= 5; }
            case BLOCKED -> { overbought = 85; oversold = 15; }
            default -> {} // GOOD = no adjustment
        }

        overbought = Math.min(85, Math.max(60, overbought));
        oversold = Math.max(15, Math.min(40, oversold));

        return new RSIConfig(period, overbought, oversold, regime, govState);
    }

    /**
     * Get RSI config specifically for expiry-day strategies.
     * Uses shorter periods since expiry moves are fast.
     */
    public RSIConfig getExpiryConfig(IndexType indexType, String strategyName) {
        RSIConfig base = getConfig(indexType, strategyName);
        int expiryPeriod = Math.min(base.period(), 9);
        double expiryOverbought = base.overbought() - 2;
        double expiryOversold = base.oversold() + 2;
        return new RSIConfig(expiryPeriod, expiryOverbought, expiryOversold,
                base.regime() + "_EXPIRY", base.governor());
    }
}
