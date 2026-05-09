package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Tradable exchange instrument. Options carry expiry, strike, and option type.
 */
public record Instrument(
        long instrumentToken,
        String exchange,
        String tradingSymbol,
        String name,
        Optional<UnderlyingSymbol> underlying,
        Optional<LocalDate> expiry,
        Optional<BigDecimal> strike,
        Optional<OptionType> optionType,
        int lotSize,
        BigDecimal tickSize,
        boolean tradable
) {
    public Instrument {
        Validation.notBlank(exchange, "exchange");
        Validation.notBlank(tradingSymbol, "tradingSymbol");
        Validation.notBlank(name, "name");
        underlying = underlying == null ? Optional.empty() : underlying;
        expiry = expiry == null ? Optional.empty() : expiry;
        strike = strike == null ? Optional.empty() : strike;
        optionType = optionType == null ? Optional.empty() : optionType;
        if (lotSize < 0) {
            throw new IllegalArgumentException("lotSize must be non-negative");
        }
        Validation.nonNegative(tickSize, "tickSize");
    }

    public String instrumentKey() {
        return exchange + ":" + tradingSymbol;
    }
}
