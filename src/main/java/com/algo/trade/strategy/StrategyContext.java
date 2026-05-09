package com.algo.trade.strategy;

import com.algo.trade.domain.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

/**
 * Unified market data snapshot passed to StrategyEvaluator.evaluate().
 * Replaces ad-hoc per-method parameter lists in AlgoTradingScheduler.
 */
public record StrategyContext(
        Instant timestamp,
        LocalTime marketTime,
        UnderlyingSymbol underlying,
        Map<Timeframe, List<Candle>> candlesByTimeframe,
        OptionChainSnapshot optionChainSnapshot,
        Map<OptionType, Instrument> selectedOptions,
        Map<String, List<Candle>> optionCandlesByKey,
        Map<String, Quote> allQuotes,
        Map<String, Quote> previousQuotes,
        Quote spotQuote,
        double ivRank,
        String ivRankSource,
        double vixLevel,
        long daysToExpiry
) {
    public List<Candle> candles(Timeframe tf) {
        return candlesByTimeframe.getOrDefault(tf, List.of());
    }

    public BigDecimal spotPrice() {
        return spotQuote != null ? spotQuote.lastPrice() : BigDecimal.ZERO;
    }
}
