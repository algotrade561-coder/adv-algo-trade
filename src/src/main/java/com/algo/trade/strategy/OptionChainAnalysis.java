package com.algo.trade.strategy;

import com.algo.trade.domain.UnderlyingSymbol;
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
        long nearbyCallOiChange,
        long nearbyPutOiChange,
        long resistanceCallOiChange,
        long supportPutOiChange
) {
}
