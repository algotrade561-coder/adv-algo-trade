package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.UnderlyingSymbol;
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
        int selectedLotSize,
        OptionType optionType,
        Quote selectedOptionQuote,
        Optional<Quote> previousSelectedOptionQuote
) {
    public StrategyEvaluationRequest {
        underlyingCandles = List.copyOf(underlyingCandles == null ? List.of() : underlyingCandles);
        trendUnderlyingCandles = List.copyOf(trendUnderlyingCandles == null ? underlyingCandles : trendUnderlyingCandles);
        selectedOptionCandles = List.copyOf(selectedOptionCandles == null ? List.of() : selectedOptionCandles);
        if (selectedLotSize < 0) {
            throw new IllegalArgumentException("selectedLotSize must be non-negative");
        }
        previousSelectedOptionQuote = previousSelectedOptionQuote == null ? Optional.empty() : previousSelectedOptionQuote;
    }
}
