package com.algo.trade.tuning;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One row per order-lifecycle transition for a signal: placement, fill, partial fill,
 * rejection, or cancellation. Multiple {@code ExecutionEvent}s typically share a single
 * {@link #correlationKey()} (the originating signal's {@code decisionKey}).
 *
 * <p>{@link #stage()} carries the broker-side stage (e.g. {@code "ORDER_OPEN"},
 * {@code "FILLED"}, {@code "ORDER_GUARD_REJECTED"}, {@code "BROKER_ERROR"}). The
 * stage vocabulary mirrors {@code entry-execution-outcomes.csv} for continuity with
 * the legacy pipeline during dual-write.</p>
 *
 * <p>{@link #slippagePct()} is derived: {@code (avgFillPrice − signalPremium) /
 * signalPremium × 100}. May be {@code null} for non-fill stages.</p>
 */
public record ExecutionEvent(
        Instant eventTime,
        Instant recordedAt,
        StrategyType strategy,
        IndexType index,
        String correlationKey,
        String orderId,
        String stage,
        int requestedQty,
        int filledQty,
        BigDecimal avgFillPrice,
        Double slippagePct,
        String brokerRejectionReason,
        Map<String, Object> attributes
) implements TuningEvent {

    public ExecutionEvent {
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(stage, "stage");
        Objects.requireNonNull(attributes, "attributes (use Map.of() for empty)");
        if (requestedQty < 0) {
            throw new IllegalArgumentException("requestedQty must be >= 0, got " + requestedQty);
        }
        if (filledQty < 0 || filledQty > requestedQty) {
            throw new IllegalArgumentException("filledQty must be in [0, requestedQty]; got "
                    + filledQty + " of " + requestedQty);
        }
    }

    @Override
    public TuningEventType type() {
        return TuningEventType.EXECUTION;
    }
}
