package com.algo.trade.strategy;

import com.algo.trade.commodity.CrudeContext;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Unified context for recording a signal evaluation to entry-signals.csv.
 * Carries all 38+ ML features plus strategy diagnostics for every strategy type.
 * Used by the single unified recording method in StrategySignalCsvRecorder.
 */
public record SignalRecordContext(
        // Identity
        String strategyType,
        UnderlyingSymbol underlying,
        StrategyDecision decision,

        // Option data
        Quote selectedOptionQuote,
        Quote previousOptionQuote,
        String selectedInstrumentKey,
        BigDecimal selectedStrike,

        // Candles
        List<Candle> underlyingCandles,
        List<Candle> trendCandles,
        List<Candle> optionCandles,

        // Option chain analysis
        OptionChainAnalysis chainAnalysis,
        BigDecimal vwap,

        // Filter results
        boolean breakoutPassed,
        boolean oiPassed,
        boolean ivPassed,
        boolean liquidityPassed,
        boolean timePassed,

        // ML enrichment
        Double rsiValue,
        Double atrValue,
        Double ema9Ema21Gap,
        Double bidAskSpread,
        Double vixLevel,
        Long daysToExpiry,
        Double delta,
        Double gamma,
        Double theta,
        Double vega,
        Double realizedVol5d,
        Double ivSkew,

        // Strategy-specific diagnostics
        Double scalpEma9,
        Double scalpEma21,
        String scalpCrossType,
        Integer scalpConfirmCount,
        Double bbUpperBand,
        Double bbLowerBand,
        Double bbBandwidth,
        Boolean bbSqueeze,
        double ivRank,

        // Execution context
        String firstFailedFilter,
        boolean executed,
        String executionStage,

        // Crude oil snapshot (observational — not used in entry filters)
        CrudeContext crudeContext
) {
    /**
     * Builder for convenience — most fields are optional.
     */
    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private String strategyType;
        private UnderlyingSymbol underlying;
        private StrategyDecision decision;
        private Quote selectedOptionQuote;
        private Quote previousOptionQuote;
        private String selectedInstrumentKey;
        private BigDecimal selectedStrike;
        private List<Candle> underlyingCandles = List.of();
        private List<Candle> trendCandles = List.of();
        private List<Candle> optionCandles = List.of();
        private OptionChainAnalysis chainAnalysis;
        private BigDecimal vwap;
        private boolean breakoutPassed;
        private boolean oiPassed;
        private boolean ivPassed;
        private boolean liquidityPassed;
        private boolean timePassed;
        private Double rsiValue, atrValue, ema9Ema21Gap, bidAskSpread, vixLevel;
        private Long daysToExpiry;
        private Double delta, gamma, theta, vega, realizedVol5d, ivSkew;
        private Double scalpEma9, scalpEma21;
        private String scalpCrossType;
        private Integer scalpConfirmCount;
        private Double bbUpperBand, bbLowerBand, bbBandwidth;
        private Boolean bbSqueeze;
        private double ivRank;
        private String firstFailedFilter;
        private boolean executed;
        private String executionStage;
        private CrudeContext crudeContext;

        public Builder strategyType(String v) { this.strategyType = v; return this; }
        public Builder underlying(UnderlyingSymbol v) { this.underlying = v; return this; }
        public Builder decision(StrategyDecision v) { this.decision = v; return this; }
        public Builder selectedOptionQuote(Quote v) { this.selectedOptionQuote = v; return this; }
        public Builder previousOptionQuote(Quote v) { this.previousOptionQuote = v; return this; }
        public Builder selectedInstrumentKey(String v) { this.selectedInstrumentKey = v; return this; }
        public Builder selectedStrike(BigDecimal v) { this.selectedStrike = v; return this; }
        public Builder underlyingCandles(List<Candle> v) { this.underlyingCandles = v != null ? v : List.of(); return this; }
        public Builder trendCandles(List<Candle> v) { this.trendCandles = v != null ? v : List.of(); return this; }
        public Builder optionCandles(List<Candle> v) { this.optionCandles = v != null ? v : List.of(); return this; }
        public Builder chainAnalysis(OptionChainAnalysis v) { this.chainAnalysis = v; return this; }
        public Builder vwap(BigDecimal v) { this.vwap = v; return this; }
        public Builder breakoutPassed(boolean v) { this.breakoutPassed = v; return this; }
        public Builder oiPassed(boolean v) { this.oiPassed = v; return this; }
        public Builder ivPassed(boolean v) { this.ivPassed = v; return this; }
        public Builder liquidityPassed(boolean v) { this.liquidityPassed = v; return this; }
        public Builder timePassed(boolean v) { this.timePassed = v; return this; }
        public Builder rsiValue(Double v) { this.rsiValue = v; return this; }
        public Builder atrValue(Double v) { this.atrValue = v; return this; }
        public Builder ema9Ema21Gap(Double v) { this.ema9Ema21Gap = v; return this; }
        public Builder bidAskSpread(Double v) { this.bidAskSpread = v; return this; }
        public Builder vixLevel(Double v) { this.vixLevel = v; return this; }
        public Builder daysToExpiry(Long v) { this.daysToExpiry = v; return this; }
        public Builder delta(Double v) { this.delta = v; return this; }
        public Builder gamma(Double v) { this.gamma = v; return this; }
        public Builder theta(Double v) { this.theta = v; return this; }
        public Builder vega(Double v) { this.vega = v; return this; }
        public Builder realizedVol5d(Double v) { this.realizedVol5d = v; return this; }
        public Builder ivSkew(Double v) { this.ivSkew = v; return this; }
        public Builder scalpEma9(Double v) { this.scalpEma9 = v; return this; }
        public Builder scalpEma21(Double v) { this.scalpEma21 = v; return this; }
        public Builder scalpCrossType(String v) { this.scalpCrossType = v; return this; }
        public Builder scalpConfirmCount(Integer v) { this.scalpConfirmCount = v; return this; }
        public Builder bbUpperBand(Double v) { this.bbUpperBand = v; return this; }
        public Builder bbLowerBand(Double v) { this.bbLowerBand = v; return this; }
        public Builder bbBandwidth(Double v) { this.bbBandwidth = v; return this; }
        public Builder bbSqueeze(Boolean v) { this.bbSqueeze = v; return this; }
        public Builder ivRank(double v) { this.ivRank = v; return this; }
        public Builder firstFailedFilter(String v) { this.firstFailedFilter = v; return this; }
        public Builder executed(boolean v) { this.executed = v; return this; }
        public Builder executionStage(String v) { this.executionStage = v; return this; }
        public Builder crudeContext(CrudeContext v) { this.crudeContext = v; return this; }
        public Builder diagnostics(StrategyDiagnostics d) {
            if (d == null) return this;
            this.scalpEma9 = d.ema9(); this.scalpEma21 = d.ema21();
            this.scalpCrossType = d.emaCrossType(); this.scalpConfirmCount = d.emaCrossConfirmCount();
            this.bbUpperBand = d.bbUpper(); this.bbLowerBand = d.bbLower();
            this.bbBandwidth = d.bbBandwidth(); this.bbSqueeze = d.bbSqueeze();
            this.firstFailedFilter = d.firstFailedFilter();
            return this;
        }

        public SignalRecordContext build() {
            return new SignalRecordContext(
                    strategyType, underlying, decision,
                    selectedOptionQuote, previousOptionQuote, selectedInstrumentKey, selectedStrike,
                    underlyingCandles, trendCandles, optionCandles,
                    chainAnalysis, vwap,
                    breakoutPassed, oiPassed, ivPassed, liquidityPassed, timePassed,
                    rsiValue, atrValue, ema9Ema21Gap, bidAskSpread, vixLevel, daysToExpiry,
                    delta, gamma, theta, vega, realizedVol5d, ivSkew,
                    scalpEma9, scalpEma21, scalpCrossType, scalpConfirmCount,
                    bbUpperBand, bbLowerBand, bbBandwidth, bbSqueeze, ivRank,
                    firstFailedFilter, executed, executionStage, crudeContext);
        }
    }
}
