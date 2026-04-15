package com.kiteapioptions.strategy;

import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.OptionChainSnapshot;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Quote;
import com.kiteapioptions.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Immutable input bundle for rule-based entry evaluation.
 */
public record StrategyEvaluationRequest(
        Instant timestamp,
        LocalTime marketTime,
        UnderlyingSymbol underlying,
        List<Candle> underlyingCandles,
        List<Candle> trendUnderlyingCandles,
        List<Candle> selectedOptionCandles,
        OptionChainSnapshot optionChainSnapshot,
        String selectedInstrumentKey,
        BigDecimal selectedStrike,
        OptionType optionType,
        Quote selectedOptionQuote,
        Optional<Quote> previousSelectedOptionQuote
) {
    public StrategyEvaluationRequest {
        underlyingCandles = List.copyOf(underlyingCandles == null ? List.of() : underlyingCandles);
        trendUnderlyingCandles = List.copyOf(trendUnderlyingCandles == null ? underlyingCandles : trendUnderlyingCandles);
        selectedOptionCandles = List.copyOf(selectedOptionCandles == null ? List.of() : selectedOptionCandles);
        previousSelectedOptionQuote = previousSelectedOptionQuote == null ? Optional.empty() : previousSelectedOptionQuote;
    }
}
