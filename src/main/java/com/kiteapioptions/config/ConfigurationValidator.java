package com.kiteapioptions.config;

import com.kiteapioptions.domain.ExecutionMode;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ConfigurationValidator {

    private static final Logger log = LoggerFactory.getLogger(ConfigurationValidator.class);

    private final TradingProperties properties;

    public ConfigurationValidator(TradingProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void validate() {
        if (properties.liveTradingEnabled() && properties.mode() != com.kiteapioptions.domain.TradingMode.LIVE) {
            throw new IllegalStateException("trading.live-trading-enabled=true requires trading.mode=LIVE");
        }
        if (properties.executionMode() == ExecutionMode.ZERODHA && !properties.liveTradingEnabled()) {
            throw new IllegalStateException("execution-mode=ZERODHA requires trading.live-trading-enabled=true");
        }
        if (!properties.entry().entryCutoffTime().isAfter(properties.entry().entryStartTime())) {
            throw new IllegalStateException("entry-cutoff-time must be after entry-start-time");
        }
        if (!properties.exit().forcedExitTime().isAfter(properties.entry().entryCutoffTime())) {
            throw new IllegalStateException("forced-exit-time must be after entry-cutoff-time");
        }

        BigDecimal theoreticalMaxRisk = properties.risk().maxRiskPerTradePercent()
                .multiply(BigDecimal.valueOf(properties.risk().maxTradesPerDay()));
        if (properties.risk().maxDailyLossPercent().compareTo(theoreticalMaxRisk) < 0) {
            log.warn("Configured maxDailyLossPercent is below theoretical max risk across all daily trades: maxDailyLossPercent={}, maxRiskPerTradePercent={}, maxTradesPerDay={}",
                    properties.risk().maxDailyLossPercent(),
                    properties.risk().maxRiskPerTradePercent(),
                    properties.risk().maxTradesPerDay());
        }
        if (properties.liveTradingEnabled() && properties.executionMode() != ExecutionMode.ZERODHA) {
            log.warn("live-trading-enabled=true but execution-mode={} so real orders will not be placed",
                    properties.executionMode());
        }
    }
}
