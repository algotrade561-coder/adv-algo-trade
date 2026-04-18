package com.kiteapioptions.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OptionType;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlgoTradingSchedulerTest {

    @Test
    void enablesOnlyConfiguredLiveOptionTypes() {
        var defaults = TradingProperties.Entry.defaults();
        var peOnlyEntry = new TradingProperties.Entry(
                defaults.timeframe(),
                defaults.trendTimeframe(),
                List.of(OptionType.PE),
                defaults.vwapFilterEnabled(),
                defaults.trendFilterEnabled(),
                defaults.volumeSpikeMultiplier(),
                defaults.breakoutBufferPercent(),
                defaults.breakoutLookback(),
                defaults.volumeLookback(),
                defaults.bullishImbalanceThreshold(),
                defaults.bearishImbalanceThreshold(),
                defaults.minLiquidityVolume(),
                defaults.maxIvPercent(),
                defaults.minSignalScorePercent(),
                defaults.entryStartTime(),
                defaults.entryCutoffTime(),
                defaults.allowFirstMinutesEntry(),
                defaults.noEntryFirstMinutes(),
                defaults.rsiFilterEnabled(),
                defaults.rsiPeriod(),
                defaults.rsiCeBuyThreshold(),
                defaults.rsiPeSellThreshold()
        );
        var properties = new TradingProperties(null, false, null, null, null, null, peOnlyEntry, null, null, null,
                null, null, null);

        assertThat(AlgoTradingScheduler.optionTypeEnabled(properties, OptionType.PE)).isTrue();
        assertThat(AlgoTradingScheduler.optionTypeEnabled(properties, OptionType.CE)).isFalse();
    }
}
