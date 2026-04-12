package com.kiteapioptions.strategy;

import com.kiteapioptions.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Derived option-chain market structure used by rule-based strategy evaluation.
 */
public record OptionChainAnalysis(
        UnderlyingSymbol underlying,
        Instant timestamp,
        Optional<BigDecimal> resistanceStrike,
        Optional<BigDecimal> supportStrike,
        BigDecimal nearbyPutCallOiImbalance,
        long nearbyCallOpenInterest,
        long nearbyPutOpenInterest,
        long resistanceCallOiChange,
        long supportPutOiChange
) {
}
