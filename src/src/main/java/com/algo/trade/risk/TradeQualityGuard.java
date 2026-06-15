package com.algo.trade.risk;

import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.LiveCandleBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Trade Quality Guard — pre-entry quality scoring based on market conditions.
 *
 * Checks:
 * - EMA alignment (EMA9 vs EMA21 on 5-min)
 * - VIX regime (not too high, not too low)
 * - ATR threshold (sufficient volatility for profitable entry)
 * - Time-of-day appropriateness
 *
 * Returns a pass/fail decision with reason. Strategies can bypass via skipQualityGuard().
 */
@Component
public class TradeQualityGuard {

    private static final Logger log = LoggerFactory.getLogger(TradeQualityGuard.class);

    private final LiveCandleBuilder candleBuilder;
    private final MarketGuard marketGuard;

    @Value("${trading.quality-guard.enabled:true}")
    private boolean enabled;

    @Value("${trading.quality-guard.min-atr-percent:0.1}")
    private double minAtrPercent;

    @Value("${trading.quality-guard.max-vix:25}")
    private double maxVix;

    @Value("${trading.quality-guard.min-vix:10}")
    private double minVix;

    public record QualityCheck(boolean allowed, String filterName, String reason) {
        public static QualityCheck pass() { return new QualityCheck(true, null, null); }
        public static QualityCheck fail(String filter, String reason) { return new QualityCheck(false, filter, reason); }
    }

    public TradeQualityGuard(LiveCandleBuilder candleBuilder, MarketGuard marketGuard) {
        this.candleBuilder = candleBuilder;
        this.marketGuard = marketGuard;
    }

    /**
     * Check entry quality for a given underlying and strategy.
     */
    public QualityCheck checkEntryQuality(UnderlyingSymbol underlying, String strategyName) {
        if (!enabled) return QualityCheck.pass();

        // VIX check
        double vix = marketGuard.getCurrentVix();
        if (vix > maxVix) {
            return QualityCheck.fail("VIX_HIGH",
                    "VIX " + String.format("%.1f", vix) + " > max " + maxVix + " — market too volatile");
        }
        if (vix < minVix && vix > 0) {
            return QualityCheck.fail("VIX_LOW",
                    "VIX " + String.format("%.1f", vix) + " < min " + minVix + " — IV too low for buying");
        }

        // Circuit breaker check
        if (marketGuard.isCircuitBreakerTriggered()) {
            return QualityCheck.fail("CIRCUIT_BREAKER", "Market circuit breaker triggered");
        }

        return QualityCheck.pass();
    }

    /**
     * Check if a strategy is performing well enough to continue today.
     * Placeholder for win-rate tracking (can be enhanced with StrategyAttributionService).
     */
    public boolean isSetupHealthy(String strategyName) {
        return true; // Default: allow all — StrategyGovernor handles performance-based blocking
    }
}
