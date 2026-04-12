package com.kiteapioptions.indicator;

import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Quote;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks open interest deltas between successive quote observations.
 */
public class OiChangeTracker {

    private final Map<String, Long> previousOpenInterest = new ConcurrentHashMap<>();

    public OiChange update(Quote quote) {
        Long previous = previousOpenInterest.put(quote.instrumentKey(), quote.openInterest());
        long change = previous == null ? 0 : quote.openInterest() - previous;
        return new OiChange(quote.instrumentKey(), previous == null ? quote.openInterest() : previous,
                quote.openInterest(), change);
    }

    public boolean priceAndOiRising(Quote previousQuote, Quote currentQuote) {
        return currentQuote.lastPrice().compareTo(previousQuote.lastPrice()) > 0
                && currentQuote.openInterest() > previousQuote.openInterest();
    }

    public boolean priceFallingAndOiRising(Quote previousQuote, Quote currentQuote) {
        return currentQuote.lastPrice().compareTo(previousQuote.lastPrice()) < 0
                && currentQuote.openInterest() > previousQuote.openInterest();
    }

    public record OiChange(String instrumentKey, long previousOpenInterest, long currentOpenInterest, long change) {
    }

    public record OptionOiSignal(OptionType optionType, boolean priceMoveWithOiBuildUp) {
    }
}
