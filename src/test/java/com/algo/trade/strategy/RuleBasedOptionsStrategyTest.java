package com.algo.trade.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.indicator.BreakoutDetector;
import com.algo.trade.indicator.OiChangeTracker;
import com.algo.trade.indicator.VolatilityFilter;
import com.algo.trade.indicator.VolumeSpikeDetector;
import com.algo.trade.indicator.VwapIndicator;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RuleBasedOptionsStrategyTest {

    @Test
    void emitsBuyCallWhenAllEnabledConditionsPass() {
        var strategy = strategy();
        var request = bullishRequest(LocalTime.of(10, 0), selectedQuote(140, 20_000, 130_000, 20));

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.BUY_CE);
        assertThat(decision.vwapConditionPassed()).isTrue();
        assertThat(decision.volumeSpike()).isTrue();
        assertThat(decision.reasons()).contains("OI behavior supports entry");
    }

    @Test
    void blocksEntryAfterCutoffTime() {
        var strategy = strategy();
        // Default entryCutoffTime is 15:10, so 15:15 is after cutoff
        var request = bullishRequest(LocalTime.of(15, 15), selectedQuote(140, 20_000, 130_000, 20));

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.NO_TRADE);
        assertThat(decision.reasons()).contains("Entry time window failed");
    }

    @Test
    void blocksEntryWhenIvIsTooHigh() {
        var strategy = strategy();
        var request = bullishRequest(LocalTime.of(10, 0), selectedQuote(140, 20_000, 130_000, 120));

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.NO_TRADE);
        assertThat(decision.reasons()).contains("IV filter failed");
    }

    @Test
    void blocksPutEntryWhenUnderlyingTrendIsNotBearish() {
        var strategy = strategy();
        var request = new StrategyEvaluationRequest(Instant.parse("2026-04-12T10:00:00Z"), LocalTime.of(10, 0),
                UnderlyingSymbol.NIFTY, bullishUnderlyingCandles(), bullishUnderlyingCandles(), optionCandles(), optionChain(),
                "NFO:NIFTY24APR24000PE", BigDecimal.valueOf(24_000), 75, OptionType.PE,
                selectedQuote(140, 20_000, 130_000, 20), Optional.of(selectedQuote(120, 10_000, 100_000, 20)), 0.0);

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.NO_TRADE);
        assertThat(decision.reasons()).contains("Side-specific entry filter failed");
    }

    @Test
    void blocksEntryWhenOiSupportFails() {
        var strategy = strategy();
        var request = new StrategyEvaluationRequest(Instant.parse("2026-04-12T10:00:00Z"), LocalTime.of(10, 0),
                UnderlyingSymbol.NIFTY, bullishUnderlyingCandles(), bullishUnderlyingCandles(), optionCandles(),
                weakPeOiChain(), "NFO:NIFTY24APR24000PE", BigDecimal.valueOf(24_000), 75, OptionType.PE,
                selectedQuote(140, 20_000, 90_000, 20), Optional.of(selectedQuote(145, 10_000, 100_000, 20)), 0.0);

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.NO_TRADE);
        assertThat(decision.reasons()).contains("OI behavior does not support entry");
    }

    @Test
    void allowsCallEntryWhenOiSupportFailsButCeOiGateIsOptional() {
        var strategy = strategy();
        var request = bullishRequest(
                LocalTime.of(10, 0),
                selectedQuote(140, 20_000, 90_000, 20),
                Optional.of(selectedQuote(145, 10_000, 100_000, 20)),
                weakOiChain());

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.BUY_CE);
        assertThat(decision.reasons()).contains("OI behavior does not support entry");
    }

    @Test
    void blocksCallEntryWhenOiDivergenceShowsHeavyCallWriting() {
        var strategy = strategy();
        var request = bullishRequest(
                LocalTime.of(10, 0),
                selectedQuote(140, 20_000, 130_000, 20),
                Optional.of(selectedQuote(120, 10_000, 100_000, 20)),
                bullishDivergenceChain());

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.NO_TRADE);
        assertThat(decision.reasons()).contains("OI divergence rejected entry", "OI behavior does not support entry");
    }

    @Test
    void blocksEntryWhenBreakoutIsNotConfirmedAcrossConsecutiveCloses() {
        var strategy = strictCeConfirmationStrategy();
        var request = new StrategyEvaluationRequest(Instant.parse("2026-04-12T10:00:00Z"), LocalTime.of(10, 0),
                UnderlyingSymbol.NIFTY, weakBreakoutUnderlyingCandles(), weakBreakoutUnderlyingCandles(), optionCandles(),
                supportiveBreakoutChain(), "NFO:NIFTY24APR24000CE", BigDecimal.valueOf(24_000), 75, OptionType.CE,
                selectedQuote(140, 20_000, 130_000, 20), Optional.of(selectedQuote(120, 10_000, 100_000, 20)), 0.0);

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.NO_TRADE);
        assertThat(decision.reasons()).contains("Breakout condition passed", "Breakout confirmation failed");
    }

    @Test
    void blocksEntryWhenResistanceIsTooCloseAboveSpot() {
        var strategy = strategy();
        var request = bullishRequest(
                LocalTime.of(10, 0),
                selectedQuote(140, 20_000, 130_000, 20),
                Optional.of(selectedQuote(120, 10_000, 100_000, 20)),
                tightResistanceOptionChain());

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.NO_TRADE);
        assertThat(decision.reasons()).contains("Resistance headroom failed");
    }

    private RuleBasedOptionsStrategy strategy() {
        return new RuleBasedOptionsStrategy(new TradingProperties(null, false, null, null, null, null, null,
                null, null, null, null, null, null), new VwapIndicator(),
                new com.algo.trade.indicator.EmaIndicator(),
                new VolumeSpikeDetector(), new BreakoutDetector(), new VolatilityFilter(),
                new OiChangeTracker(), new OptionChainAnalyzer(), null);
    }

    private RuleBasedOptionsStrategy strictCeConfirmationStrategy() {
        TradingProperties defaults = new TradingProperties(null, false, null, null, null, null, null,
                null, null, null, null, null, null);
        TradingProperties.Entry entry = defaults.entry();
        TradingProperties.Entry strictEntry = new TradingProperties.Entry(
                entry.timeframe(),
                entry.trendTimeframe(),
                entry.enabledOptionTypes(),
                entry.vwapFilterEnabled(),
                entry.trendFilterEnabled(),
                entry.volumeSpikeMultiplier(),
                entry.breakoutBufferPercent(),
                entry.breakoutLookback(),
                entry.volumeLookback(),
                entry.bullishImbalanceThreshold(),
                entry.bearishImbalanceThreshold(),
                entry.minLiquidityVolume(),
                entry.maxIvPercent(),
                entry.minSignalScorePercent(),
                entry.ceOiSupportRequired(),
                entry.peOiSupportRequired(),
                entry.ceOiDivergenceFilterEnabled(),
                entry.peOiDivergenceFilterEnabled(),
                entry.oiDivergenceMultiplier(),
                entry.oiDivergenceMinChange(),
                2,
                entry.peBreakoutConfirmationCandles(),
                entry.entryStartTime(),
                entry.entryCutoffTime(),
                entry.allowFirstMinutesEntry(),
                entry.noEntryFirstMinutes(),
                entry.rsiFilterEnabled(),
                entry.rsiPeriod(),
                entry.rsiCeBuyThreshold(),
                entry.rsiPeSellThreshold()
        );
        TradingProperties strictProperties = new TradingProperties(
                defaults.mode(),
                defaults.marketDataMode(),
                defaults.executionMode(),
                defaults.liveTradingEnabled(),
                defaults.timezone(),
                defaults.broker(),
                defaults.symbols(),
                defaults.strike(),
                strictEntry,
                defaults.exit(),
                defaults.risk(),
                defaults.paper(),
                defaults.safety(),
                defaults.telegram(),
                defaults.algo(),
                defaults.backtest()
        );
        return new RuleBasedOptionsStrategy(strictProperties, new VwapIndicator(),
                new com.algo.trade.indicator.EmaIndicator(),
                new VolumeSpikeDetector(), new BreakoutDetector(), new VolatilityFilter(),
                new OiChangeTracker(), new OptionChainAnalyzer(), null);
    }

    private StrategyEvaluationRequest bullishRequest(LocalTime marketTime, Quote selectedQuote) {
        return bullishRequest(marketTime, selectedQuote, Optional.of(selectedQuote(120, 10_000, 100_000, 20)), optionChain());
    }

    private StrategyEvaluationRequest bullishRequest(
            LocalTime marketTime,
            Quote selectedQuote,
            Optional<Quote> previousSelectedQuote,
            OptionChainSnapshot optionChain
    ) {
        return new StrategyEvaluationRequest(Instant.parse("2026-04-12T10:00:00Z"), marketTime,
                UnderlyingSymbol.NIFTY, bullishUnderlyingCandles(), bullishUnderlyingCandles(), optionCandles(), optionChain,
                "NFO:NIFTY24APR24000CE", BigDecimal.valueOf(24_000), 75, OptionType.CE, selectedQuote,
                previousSelectedQuote, 0.0);
    }

    private List<Candle> bullishUnderlyingCandles() {
        return List.of(
                candle("NSE:NIFTY 50", 23_950, 23_970, 23_930, 23_950, 1000),
                candle("NSE:NIFTY 50", 23_960, 23_980, 23_940, 23_960, 1100),
                candle("NSE:NIFTY 50", 23_970, 23_990, 23_950, 23_970, 1200),
                candle("NSE:NIFTY 50", 23_980, 24_000, 23_960, 23_980, 1300),
                candle("NSE:NIFTY 50", 24_120, 24_140, 24_110, 24_130, 3200),
                candle("NSE:NIFTY 50", 24_145, 24_180, 24_135, 24_170, 5000)
        );
    }

    private List<Candle> weakBreakoutUnderlyingCandles() {
        return List.of(
                candle("NSE:NIFTY 50", 23_950, 23_970, 23_930, 23_950, 1000),
                candle("NSE:NIFTY 50", 23_960, 23_980, 23_940, 23_960, 1100),
                candle("NSE:NIFTY 50", 23_970, 23_990, 23_950, 23_970, 1200),
                candle("NSE:NIFTY 50", 23_980, 24_000, 23_960, 23_980, 1300),
                candle("NSE:NIFTY 50", 24_000, 24_010, 23_990, 24_005, 1500),
                candle("NSE:NIFTY 50", 24_130, 24_160, 24_120, 24_150, 5000)
        );
    }

    private List<Candle> optionCandles() {
        return List.of(
                candle("NFO:NIFTY24APR24000CE", 100, 110, 95, 105, 1000),
                candle("NFO:NIFTY24APR24000CE", 106, 112, 100, 108, 1000),
                candle("NFO:NIFTY24APR24000CE", 108, 114, 102, 110, 1000),
                candle("NFO:NIFTY24APR24000CE", 110, 116, 104, 112, 1000),
                candle("NFO:NIFTY24APR24000CE", 112, 118, 106, 114, 1000),
                candle("NFO:NIFTY24APR24000CE", 130, 145, 128, 140, 3000)
        );
    }

    private OptionChainSnapshot optionChain() {
        return new OptionChainSnapshot(UnderlyingSymbol.NIFTY, Instant.now(), BigDecimal.valueOf(24_130), List.of(
                level(24_000, 1000, 8000, 100, 1200),
                level(24_050, 2000, 6000, 100, 800),
                level(24_100, 10_000, 3000, -500, 100)
        ));
    }

    private OptionChainSnapshot supportiveBreakoutChain() {
        return new OptionChainSnapshot(UnderlyingSymbol.NIFTY, Instant.now(), BigDecimal.valueOf(24_130), List.of(
                level(24_000, 1000, 8000, -200, 1200),
                level(24_050, 1500, 6500, -200, 900),
                level(24_100, 9000, 3500, -500, 100)
        ));
    }

    private OptionChainSnapshot tightResistanceOptionChain() {
        return new OptionChainSnapshot(UnderlyingSymbol.NIFTY, Instant.now(), BigDecimal.valueOf(24_130), List.of(
                level(24_180, 12_000, 3000, 300, 100),
                level(24_100, 4000, 7000, -200, 600),
                level(24_050, 2000, 6000, -200, 500)
        ));
    }

    private OptionChainSnapshot bullishDivergenceChain() {
        return new OptionChainSnapshot(UnderlyingSymbol.NIFTY, Instant.now(), BigDecimal.valueOf(24_130), List.of(
                level(24_000, 12_000, 3000, 80_000, 10_000),
                level(24_050, 14_000, 3200, 90_000, 12_000),
                level(24_100, 16_000, 3500, 95_000, 8_000)
        ));
    }

    private OptionChainSnapshot weakOiChain() {
        return new OptionChainSnapshot(UnderlyingSymbol.NIFTY, Instant.now(), BigDecimal.valueOf(24_130), List.of(
                level(24_000, 3000, 2500, 200, -100),
                level(24_050, 3500, 2800, 200, -100),
                level(24_100, 5000, 2900, 200, -100)
        ));
    }

    private OptionChainSnapshot weakPeOiChain() {
        return new OptionChainSnapshot(UnderlyingSymbol.NIFTY, Instant.now(), BigDecimal.valueOf(24_130), List.of(
                level(24_000, 3000, 5000, -100, 100),
                level(24_050, 3500, 5400, -100, 100),
                level(24_100, 5000, 6500, -100, 100)
        ));
    }

    private OptionChainLevel level(int strike, long callOi, long putOi, long callOiChange, long putOiChange) {
        return new OptionChainLevel(BigDecimal.valueOf(strike), callOi, putOi, callOiChange, putOiChange,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100));
    }

    private Quote selectedQuote(int price, long volume, long oi, int iv) {
        return new Quote("NFO:NIFTY24APR24000CE", Instant.now(), BigDecimal.valueOf(price), volume, oi,
                Optional.of(BigDecimal.valueOf(iv)), Optional.empty(), Optional.empty());
    }

    private Candle candle(String instrumentKey, int open, int high, int low, int close, long volume) {
        return new Candle(instrumentKey, Instant.now(), Timeframe.ONE_MINUTE, BigDecimal.valueOf(open),
                BigDecimal.valueOf(high), BigDecimal.valueOf(low), BigDecimal.valueOf(close), volume, 0);
    }
}
