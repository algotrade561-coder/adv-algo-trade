package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;

public record OptionChainLevel(
        BigDecimal strike,
        long callOpenInterest,
        long putOpenInterest,
        long callOpenInterestChange,
        long putOpenInterestChange,
        BigDecimal callLastPrice,
        BigDecimal putLastPrice
) {
    public OptionChainLevel {
        Validation.nonNegative(strike, "strike");
        Validation.nonNegative(callLastPrice, "callLastPrice");
        Validation.nonNegative(putLastPrice, "putLastPrice");
        if (callOpenInterest < 0 || putOpenInterest < 0) {
            throw new IllegalArgumentException("open interest values must be non-negative");
        }
    }
}
