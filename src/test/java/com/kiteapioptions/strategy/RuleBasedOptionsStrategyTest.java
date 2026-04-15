package com.kiteapioptions.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.OptionChainLevel;
import com.kiteapioptions.domain.OptionChainSnapshot;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Quote;
import com.kiteapioptions.domain.SignalType;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.UnderlyingSymbol;
import com.kiteapioptions.indicator.BreakoutDetector;
import com.kiteapioptions.indicator.OiChangeTracker;
import com.kiteapioptions.indicator.VolatilityFilter;
import com.kiteapioptions.indicator.VolumeSpikeDetector;
import com.kiteapioptions.indicator.VwapIndicator;
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
        var request = bullishRequest(LocalTime.of(15, 0), selectedQuote(140, 20_000, 130_000, 20));

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
                "NFO:NIFTY24APR24000PE", BigDecimal.valueOf(24_000), OptionType.PE, selectedQuote(140, 20_000,
                130_000, 20), Optional.of(selectedQuote(120, 10_000, 100_000, 20)));

        var decision = strategy.evaluateEntry(request);

        assertThat(decision.signalType()).isEqualTo(SignalType.NO_TRADE);
        assertThat(decision.reasons()).contains("Side-specific entry filter failed");
    }

    private RuleBasedOptionsStrategy strategy() {
        return new RuleBasedOptionsStrategy(new TradingProperties(null, false, null, null, null, null, null,
                null, null, null, null, null, null), new VwapIndicator(),
                new com.kiteapioptions.indicator.EmaIndicator(),
                new VolumeSpikeDetector(), new BreakoutDetector(), new VolatilityFilter(),
                new OiChangeTracker(), new OptionChainAnalyzer(), null);
    }

    private StrategyEvaluationRequest bullishRequest(LocalTime marketTime, Quote selectedQuote) {
        return new StrategyEvaluationRequest(Instant.parse("2026-04-12T10:00:00Z"), marketTime,
                UnderlyingSymbol.NIFTY, bullishUnderlyingCandles(), bullishUnderlyingCandles(), optionCandles(), optionChain(),
                "NFO:NIFTY24APR24000CE", BigDecimal.valueOf(24_000), OptionType.CE, selectedQuote,
                Optional.of(selectedQuote(120, 10_000, 100_000, 20)));
    }

    private List<Candle> bullishUnderlyingCandles() {
        return List.of(
                candle("NSE:NIFTY 50", 23_950, 23_970, 23_930, 23_950, 1000),
                candle("NSE:NIFTY 50", 23_960, 23_980, 23_940, 23_960, 1100),
                candle("NSE:NIFTY 50", 23_970, 23_990, 23_950, 23_970, 1200),
                candle("NSE:NIFTY 50", 23_980, 24_000, 23_960, 23_980, 1300),
                candle("NSE:NIFTY 50", 23_990, 24_010, 23_970, 23_990, 1400),
                candle("NSE:NIFTY 50", 24_130, 24_150, 24_100, 24_130, 5000)
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
