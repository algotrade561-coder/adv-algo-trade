package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OptionType;
import java.math.BigDecimal;
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
                defaults.ceOiSupportRequired(),
                defaults.peOiSupportRequired(),
                defaults.ceOiDivergenceFilterEnabled(),
                defaults.peOiDivergenceFilterEnabled(),
                defaults.oiDivergenceMultiplier(),
                defaults.oiDivergenceMinChange(),
                defaults.ceBreakoutConfirmationCandles(),
                defaults.peBreakoutConfirmationCandles(),
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

    @Test
    void computesPremiumCapUsingLiveInstrumentLotSize() {
        var properties = new TradingProperties(null, false, null, null, null, null, null, null, null, null,
                null, null, null);

        assertThat(AlgoTradingScheduler.maxTradablePremium(properties, BigDecimal.valueOf(12), 75)).isEqualByComparingTo("400");
        assertThat(AlgoTradingScheduler.maxTradablePremium(properties, BigDecimal.valueOf(12), 65)).isEqualByComparingTo("461.5384615384615");
        assertThat(AlgoTradingScheduler.maxTradablePremium(properties, BigDecimal.valueOf(12), 0)).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
