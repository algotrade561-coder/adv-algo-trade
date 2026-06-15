package com.algo.trade.tuning;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One row per closed trade. Path-aware: MAE / MFE / time-to-MAE / time-to-MFE are
 * populated from {@code MaeMfeTracker} (Phase 1, Commit 7) over the life of the trade,
 * so the analyzer can answer "how deep did this trade go before bouncing" without
 * scanning per-tick candle data.
 *
 * <p>{@link #correlationKey()} is the {@code decisionKey} of the originating signal.</p>
 *
 * <p>{@link #reversal()} is {@code true} when this exit was triggered by a strategy's
 * reversal logic (close existing + open opposite) rather than a normal SL / target /
 * trail / squareoff.</p>
 */
public record ExitEvent(
        Instant eventTime,
        Instant recordedAt,
        StrategyType strategy,
        IndexType index,
        String correlationKey,
        String tradeId,
        String exitReason,
        BigDecimal entryPrice,
        BigDecimal exitPrice,
        double realizedPnlPct,
        long holdSec,
        double maePct,
        double mfePct,
        long timeToMaeSec,
        long timeToMfeSec,
        boolean reversal,
        Map<String, Object> attributes
) implements TuningEvent {

    public ExitEvent {
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(tradeId, "tradeId");
        Objects.requireNonNull(exitReason, "exitReason");
        Objects.requireNonNull(entryPrice, "entryPrice");
        Objects.requireNonNull(exitPrice, "exitPrice");
        Objects.requireNonNull(attributes, "attributes (use Map.of() for empty)");
        if (holdSec < 0) {
            throw new IllegalArgumentException("holdSec must be >= 0, got " + holdSec);
        }
        if (maePct > 0) {
            throw new IllegalArgumentException("maePct must be <= 0 (adverse), got " + maePct);
        }
        if (mfePct < 0) {
            throw new IllegalArgumentException("mfePct must be >= 0 (favorable), got " + mfePct);
        }
    }

    @Override
    public TuningEventType type() {
        return TuningEventType.EXIT;
    }
}
