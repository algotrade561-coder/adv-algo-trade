package com.kiteapioptions.domain;

import com.kiteapioptions.util.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record OptionChainSnapshot(
        UnderlyingSymbol underlying,
        Instant timestamp,
        BigDecimal underlyingPrice,
        List<OptionChainLevel> levels
) {
    public OptionChainSnapshot {
        Validation.notNull(underlying, "underlying");
        Validation.notNull(timestamp, "timestamp");
        Validation.nonNegative(underlyingPrice, "underlyingPrice");
        levels = List.copyOf(levels == null ? List.of() : levels);
    }
}
