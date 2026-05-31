package com.algo.trade.tuning;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.StrategyType;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One row per signal, backfilled by {@code ForwardCheckpointService} (Phase 1, Commit 6)
 * approximately 31 minutes after the signal fired. Carries the spot path at fixed
 * checkpoints and derived MFE / MAE over the 30-minute observation window.
 *
 * <p>This event type replaces the 750 MB {@code entry-candles.csv} dependency: instead
 * of storing every candle per signal and recomputing MFE / MAE at report time, we
 * pre-compute the relevant numbers once at backfill and emit ~10 numeric columns per
 * signal.</p>
 *
 * <p>All {@code fwdSpot_*} fields are {@code Double} (nullable) because the backfill
 * may be partial: a row written 16 minutes after signal time has {@link #fwdSpot15m()}
 * populated but {@link #fwdSpot30m()} still {@code null}. The recorder treats the row
 * as in-flight until all checkpoints are filled, then promotes it to "complete."</p>
 *
 * <p>Strategy-specific forward attributes (e.g. trapped-strike OI delta for OI Shift
 * Trap) live in {@link #attributes()}.</p>
 */
public record ForwardCheckpointEvent(
        Instant eventTime,
        Instant recordedAt,
        StrategyType strategy,
        IndexType index,
        String correlationKey,
        Double fwdSpot30s,
        Double fwdSpot1m,
        Double fwdSpot5m,
        Double fwdSpot15m,
        Double fwdSpot30m,
        Double fwdMfe30mPct,
        Double fwdMae30mPct,
        Map<String, Object> attributes
) implements TuningEvent {

    public ForwardCheckpointEvent {
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(recordedAt, "recordedAt");
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(index, "index");
        Objects.requireNonNull(correlationKey, "correlationKey");
        Objects.requireNonNull(attributes, "attributes (use Map.of() for empty)");
    }

    @Override
    public TuningEventType type() {
        return TuningEventType.FORWARD_CHECKPOINT;
    }

    /** True when every {@code fwdSpot_*} field is populated and the row is final. */
    public boolean isComplete() {
        return fwdSpot30s != null
                && fwdSpot1m != null
                && fwdSpot5m != null
                && fwdSpot15m != null
                && fwdSpot30m != null;
    }
}
