package com.algo.trade.domain;

import com.algo.trade.util.Validation;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Explainable strategy decision record for logs, audit, and trade journals.
 */
public record StrategyDecision(
        Instant timestamp,
        UnderlyingSymbol underlying,
        SignalType signalType,
        BigDecimal underlyingPrice,
        Optional<BigDecimal> optionPrice,
        Optional<Long> optionOpenInterest,
        Optional<Integer> lotSize,
        Optional<BigDecimal> lotPrice,
        Optional<String> selectedInstrumentKey,
        Optional<BigDecimal> selectedStrike,
        Optional<OptionType> optionType,
        boolean vwapConditionPassed,
        Optional<BigDecimal> imbalance,
        boolean volumeSpike,
        BigDecimal confidenceScore,
        List<String> reasons
) {
    public StrategyDecision {
        Validation.notNull(timestamp, "timestamp");
        Validation.notNull(underlying, "underlying");
        Validation.notNull(signalType, "signalType");
        Validation.nonNegative(underlyingPrice, "underlyingPrice");
        optionPrice = optionPrice == null ? Optional.empty() : optionPrice;
        optionPrice.ifPresent(value -> Validation.nonNegative(value, "optionPrice"));
        optionOpenInterest = optionOpenInterest == null ? Optional.empty() : optionOpenInterest;
        optionOpenInterest.ifPresent(value -> {
            if (value < 0) {
                throw new IllegalArgumentException("optionOpenInterest must be non-negative");
            }
        });
        lotSize = lotSize == null ? Optional.empty() : lotSize;
        lotSize.ifPresent(value -> {
            if (value < 0) {
                throw new IllegalArgumentException("lotSize must be non-negative");
            }
        });
        lotPrice = lotPrice == null ? Optional.empty() : lotPrice;
        lotPrice.ifPresent(value -> Validation.nonNegative(value, "lotPrice"));
        selectedInstrumentKey = selectedInstrumentKey == null ? Optional.empty() : selectedInstrumentKey;
        selectedStrike = selectedStrike == null ? Optional.empty() : selectedStrike;
        optionType = optionType == null ? Optional.empty() : optionType;
        imbalance = imbalance == null ? Optional.empty() : imbalance;
        confidenceScore = confidenceScore == null ? BigDecimal.ZERO : confidenceScore;
        Validation.nonNegative(confidenceScore, "confidenceScore");
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }

    public StrategyDecision(
            Instant timestamp,
            UnderlyingSymbol underlying,
            SignalType signalType,
            BigDecimal underlyingPrice,
            Optional<BigDecimal> optionPrice,
            Optional<Long> optionOpenInterest,
            Optional<Integer> lotSize,
            Optional<BigDecimal> lotPrice,
            Optional<String> selectedInstrumentKey,
            Optional<BigDecimal> selectedStrike,
            Optional<OptionType> optionType,
            boolean vwapConditionPassed,
            Optional<BigDecimal> imbalance,
            boolean volumeSpike,
            List<String> reasons
    ) {
        this(timestamp, underlying, signalType, underlyingPrice, optionPrice, optionOpenInterest, lotSize, lotPrice,
                selectedInstrumentKey, selectedStrike, optionType, vwapConditionPassed, imbalance, volumeSpike,
                BigDecimal.ZERO, reasons);
    }
}
