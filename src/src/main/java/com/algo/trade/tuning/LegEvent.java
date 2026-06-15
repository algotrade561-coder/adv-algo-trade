package com.algo.trade.tuning;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/**
 * Per-leg fill/exit event for multi-leg spread trades. Sibling to {@link ExecutionEvent}
 * but carries leg identity ({@code legNumber}, side, option type, strike).
 */
public record LegEvent(
        Instant eventTime,
        Instant recordedAt,
        StrategyType strategy,
        IndexType index,
        String correlationKey,
        int legNumber,
        String legSide,
        OptionType legOptionType,
        int legStrike,
        String legInstrumentKey,
        String stage,
        int requestedQty,
        int filledQty,
        BigDecimal avgFillPrice,
        Map<String, Object> attributes
) implements TuningEvent {

    public LegEvent {
        attributes = attributes != null ? Map.copyOf(attributes) : Map.of();
    }

    @Override
    public TuningEventType type() {
        return TuningEventType.LEG;
    }

    @Override
    public Map<String, Object> attributes() {
        return attributes;
    }
}
