package com.algo.trade.config;

import com.algo.trade.execution.exit.ExitMode;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Multi-leg spread execution and exit safety settings.
 */
@ConfigurationProperties(prefix = "trading.spread")
public record SpreadTradingProperties(
        boolean marginPreflightEnabled,
        boolean fillConfirmationEnabled,
        boolean parallelBuyLegsEnabled,
        boolean reconciliationEnabled,
        long reconciliationIntervalMs,
        double marginBufferPercent,
        Duration legFillTimeout,
        Duration parallelLegTimeout,
        int spreadLegMaxRetries,
        Duration startupPendingMaxAge,
        /** ATR | CONFIG | HYBRID for spread SL/target */
        String exitModeSetting,
        boolean spreadAtrExitsEnabled,
        boolean exitBackupEnabled,
        boolean partialProfitEnabled,
        boolean straddleAdjustmentEnabled,
        int straddleAdjustmentTriggerPoints,
        Duration staleQuoteThreshold,
        /** Broker-side SL-M for credit spread SELL legs (catastrophe floor). */
        boolean brokerSideSlEnabled,
        /** Multiplier on entry credit for SL-M trigger price (e.g., 2.0 = 2× credit received). */
        double brokerSideSlMultiplier
) {
    public SpreadTradingProperties {
        if (marginBufferPercent <= 0) {
            marginBufferPercent = 12.0;
        }
        if (legFillTimeout == null) {
            legFillTimeout = Duration.ofSeconds(30);
        }
        if (parallelLegTimeout == null) {
            parallelLegTimeout = Duration.ofSeconds(10);
        }
        if (spreadLegMaxRetries < 0) {
            spreadLegMaxRetries = 2;
        }
        if (startupPendingMaxAge == null) {
            startupPendingMaxAge = Duration.ofMinutes(30);
        }
        if (reconciliationIntervalMs <= 0) {
            reconciliationIntervalMs = 120_000L;
        }
        if (exitModeSetting == null || exitModeSetting.isBlank()) {
            exitModeSetting = "HYBRID";
        }
        if (staleQuoteThreshold == null) {
            staleQuoteThreshold = Duration.ofMinutes(5);
        }
        if (straddleAdjustmentTriggerPoints <= 0) {
            straddleAdjustmentTriggerPoints = 100;
        }
        if (brokerSideSlMultiplier <= 0) {
            brokerSideSlMultiplier = 2.0;
        }
    }

    public ExitMode exitMode() {
        return ExitMode.fromString(exitModeSetting);
    }

    public static SpreadTradingProperties defaults() {
        return new SpreadTradingProperties(
                true,
                true,
                true,
                true,
                120_000L,
                12.0,
                Duration.ofSeconds(30),
                Duration.ofSeconds(10),
                2,
                Duration.ofMinutes(30),
                "HYBRID",
                true,
                true,
                true,
                true,
                100,
                Duration.ofMinutes(5),
                false,
                2.0
        );
    }
}
