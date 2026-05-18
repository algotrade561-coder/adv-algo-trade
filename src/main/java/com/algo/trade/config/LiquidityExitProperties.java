package com.algo.trade.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Microstructure / liquidity exit thresholds — prevent being trapped in illiquid positions.
 */
@ConfigurationProperties(prefix = "trading.exit.liquidity")
public record LiquidityExitProperties(
        boolean enabled,
        Duration staleQuoteWarnAfter,
        Duration staleQuoteForceExitAfter,
        double spreadWarnPercent,
        double spreadExitPercent,
        double spreadVsEntryMultiplier,
        double volumeCollapseRatio,
        double oiCollapseRatio,
        boolean exitWhenBidAskMissing,
        int missingBidAskTicksRequired,
        boolean volumeFloorEnabled,
        boolean marketHoursOnly
) {
    public LiquidityExitProperties {
        if (staleQuoteWarnAfter == null) {
            staleQuoteWarnAfter = Duration.ofSeconds(90);
        }
        if (staleQuoteForceExitAfter == null) {
            staleQuoteForceExitAfter = Duration.ofSeconds(180);
        }
        if (spreadWarnPercent <= 0) {
            spreadWarnPercent = 5.0;
        }
        if (spreadExitPercent <= 0) {
            spreadExitPercent = 8.0;
        }
        if (spreadVsEntryMultiplier <= 0) {
            spreadVsEntryMultiplier = 2.0;
        }
        if (volumeCollapseRatio <= 0 || volumeCollapseRatio > 1) {
            volumeCollapseRatio = 0.35;
        }
        if (oiCollapseRatio <= 0 || oiCollapseRatio > 1) {
            oiCollapseRatio = 0.35;
        }
        if (missingBidAskTicksRequired <= 0) {
            missingBidAskTicksRequired = 3;
        }
    }

    public static LiquidityExitProperties defaults() {
        return new LiquidityExitProperties(
                true,
                Duration.ofSeconds(90),
                Duration.ofSeconds(180),
                5.0,
                8.0,
                2.0,
                0.35,
                0.35,
                true,
                3,
                false,
                true
        );
    }
}
