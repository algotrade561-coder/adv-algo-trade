package com.kiteapioptions.config;

import com.kiteapioptions.indicator.BreakoutDetector;
import com.kiteapioptions.indicator.EmaIndicator;
import com.kiteapioptions.indicator.OiChangeTracker;
import com.kiteapioptions.indicator.VolatilityFilter;
import com.kiteapioptions.indicator.VolumeSpikeDetector;
import com.kiteapioptions.indicator.VwapIndicator;
import com.kiteapioptions.strategy.OptionChainAnalyzer;
import com.kiteapioptions.strategy.RuleBasedOptionsStrategy;
import com.kiteapioptions.strategy.StrategySignalCsvRecorder;
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
            VwapIndicator vwapIndicator,
            EmaIndicator emaIndicator,
            VolumeSpikeDetector volumeSpikeDetector,
            BreakoutDetector breakoutDetector,
            VolatilityFilter volatilityFilter,
            OiChangeTracker oiChangeTracker,
            OptionChainAnalyzer optionChainAnalyzer,
            StrategySignalCsvRecorder signalCsvRecorder
    ) {
        return new RuleBasedOptionsStrategy(properties, vwapIndicator, emaIndicator, volumeSpikeDetector, breakoutDetector,
                volatilityFilter, oiChangeTracker, optionChainAnalyzer, signalCsvRecorder);
    }
}
