package com.kiteapioptions.domain;

import com.kiteapioptions.util.Validation;
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
        Optional<String> selectedInstrumentKey,
        Optional<BigDecimal> selectedStrike,
        Optional<OptionType> optionType,
        boolean vwapConditionPassed,
        Optional<BigDecimal> imbalance,
        boolean volumeSpike,
        List<String> reasons
) {
    public StrategyDecision {
        Validation.notNull(timestamp, "timestamp");
        Validation.notNull(underlying, "underlying");
        Validation.notNull(signalType, "signalType");
        Validation.nonNegative(underlyingPrice, "underlyingPrice");
        selectedInstrumentKey = selectedInstrumentKey == null ? Optional.empty() : selectedInstrumentKey;
        selectedStrike = selectedStrike == null ? Optional.empty() : selectedStrike;
        optionType = optionType == null ? Optional.empty() : optionType;
        imbalance = imbalance == null ? Optional.empty() : imbalance;
        reasons = List.copyOf(reasons == null ? List.of() : reasons);
    }
}
