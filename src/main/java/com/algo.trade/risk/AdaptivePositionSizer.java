package com.algo.trade.risk;

import com.algo.trade.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * Adaptive position sizer — scales lot size based on:
 * 1. VIX regime (high VIX = smaller position)
 * 2. Daily P&L trajectory (losing day = reduce size)
 * 3. Hard cap from config (max-trades-per-day as proxy for max lots)
 *
 * Ported from AlgoTradingOptions, adapted to use TradingProperties.
 */
@Component
public class AdaptivePositionSizer {

    private static final Logger log = LoggerFactory.getLogger(AdaptivePositionSizer.class);
    private static final MathContext MC = MathContext.DECIMAL64;

    private final TradingProperties properties;
    private final MarketGuard marketGuard;
    private final RiskManager riskManager;

    public AdaptivePositionSizer(TradingProperties properties,
                                  MarketGuard marketGuard,
                                  RiskManager riskManager) {
        this.properties = properties;
        this.marketGuard = marketGuard;
        this.riskManager = riskManager;
    }

    /**
     * Calculate the appropriate lot size for a trade.
     * May reduce below baseLots but never increases above it.
     */
    public int calculateLots(int baseLots) {
        int lots = baseLots;

        // 1. VIX scaling — reduce size when VIX is high
        double vix = marketGuard.getCurrentVix();
        if (vix > 22) {
            lots = Math.max(1, lots / 2);
            log.info("[AdaptiveSizer] VIX={} > 22, reducing lots to {}", vix, lots);
        } else if (vix > 18) {
            lots = Math.max(1, (int)(lots * 0.75));
            log.info("[AdaptiveSizer] VIX={} > 18, reducing lots to {}", vix, lots);
        }

        // 2. Daily P&L scaling — reduce if losing day
        BigDecimal dailyPnl = riskManager.getDailyPnl();
        BigDecimal maxLoss = properties.risk().totalCapital()
                .multiply(properties.risk().maxDailyLossPercent(), MC)
                .divide(BigDecimal.valueOf(100), MC);
        BigDecimal halfLoss = maxLoss.multiply(BigDecimal.valueOf(0.5), MC);

        if (dailyPnl.compareTo(halfLoss.negate()) <= 0) {
            lots = Math.max(1, lots / 2);
            log.info("[AdaptiveSizer] Daily P&L={} (50% of limit), reducing lots to {}", dailyPnl, lots);
        }

        if (lots != baseLots) {
            log.info("[AdaptiveSizer] base={} → adjusted={} (VIX={} P&L={})",
                    baseLots, lots, vix, dailyPnl.setScale(0, java.math.RoundingMode.HALF_UP));
        }
        return lots;
    }
}
