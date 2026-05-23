package com.algo.trade.config;

import com.algo.trade.indicator.BreakoutDetector;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.indicator.OiChangeTracker;
import com.algo.trade.indicator.VolatilityFilter;
import com.algo.trade.indicator.VolumeSpikeDetector;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.strategy.OptionChainAnalyzer;
import com.algo.trade.strategy.RuleBasedOptionsStrategy;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class StrategyConfiguration {

    @Bean
    VwapIndicator vwapIndicator() {
        return new VwapIndicator();
    }

    @Bean
    EmaIndicator emaIndicator() {
        return new EmaIndicator();
    }

    @Bean
    VolumeSpikeDetector volumeSpikeDetector() {
        return new VolumeSpikeDetector();
    }

    @Bean
    BreakoutDetector breakoutDetector() {
        return new BreakoutDetector();
    }

    @Bean
    VolatilityFilter volatilityFilter() {
        return new VolatilityFilter();
    }

    @Bean
    OiChangeTracker oiChangeTracker() {
        return new OiChangeTracker();
    }

    @Bean
    OptionChainAnalyzer optionChainAnalyzer() {
        return new OptionChainAnalyzer();
    }

    @Bean
    RuleBasedOptionsStrategy ruleBasedOptionsStrategy(
            TradingProperties properties,
            GlobalConfigService globalConfigService,
            VwapIndicator vwapIndicator,
            EmaIndicator emaIndicator,
            VolumeSpikeDetector volumeSpikeDetector,
            BreakoutDetector breakoutDetector,
            VolatilityFilter volatilityFilter,
            OiChangeTracker oiChangeTracker,
            OptionChainAnalyzer optionChainAnalyzer,
            StrategySignalCsvRecorder signalCsvRecorder,
            com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService,
            com.algo.trade.strategy.oimomentum.OperatorFrameworkService operatorFrameworkService
    ) {
        return new RuleBasedOptionsStrategy(properties, globalConfigService, vwapIndicator, emaIndicator, volumeSpikeDetector, breakoutDetector,
                volatilityFilter, oiChangeTracker, optionChainAnalyzer, signalCsvRecorder, underlyingConfigService,
                operatorFrameworkService);
    }
}
