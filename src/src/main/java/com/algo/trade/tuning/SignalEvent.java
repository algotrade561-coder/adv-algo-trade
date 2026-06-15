package com.algo.trade.tuning;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One row per BUY/SELL signal that fired. Universal fields are top-level; strategy-specific
 * quality fields (score, entry case, imbalance, etc.) live in {@link #attributes()}.
 *
 * <p>{@link #correlationKey()} is the {@code decisionKey} used by every downstream event
 * (execution, exit, forward checkpoints, shadow gates) to refer back to this signal.</p>
 */
public record SignalEvent(
        Instant eventTime,
        Instant recordedAt,
        StrategyType strategy,
        IndexType index,
        String correlationKey,
        String instrumentKey,
        int strike,
        OptionType optionType,
        BigDecimal entryPremium,
        Map<String, Object> attributes
) implements TuningEvent {

    public SignalEvent {
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(instrumentKey, "instrumentKey");
        Objects.requireNonNull(optionType, "optionType");
        Objects.requireNonNull(entryPremium, "entryPremium");
        Objects.requireNonNull(attributes, "attributes (use Map.of() for empty)");
    }

    @Override
    public TuningEventType type() {
        return TuningEventType.SIGNAL;
    }
}
