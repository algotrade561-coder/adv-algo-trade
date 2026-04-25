package com.algo.trade.backtest;

import com.algo.trade.config.GlobalConfig;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;

import java.util.List;
import java.util.Map;

/**
 * Captures all configuration at the time of a verification run.
 * Includes global config (entry/exit/risk from DB), trading properties (infrastructure),
 * per-strategy configs, and any detected mismatches.
 */
public record ConfigSnapshot(
        GlobalConfig globalConfig,
        TradingProperties tradingProperties,
        Map<StrategyType, StrategyConfig> strategyConfigs,
        List<String> configMismatches
) {

    public ConfigSnapshot {
        strategyConfigs = strategyConfigs == null ? Map.of() : Map.copyOf(strategyConfigs);
        configMismatches = configMismatches == null ? List.of() : List.copyOf(configMismatches);
    }
}
