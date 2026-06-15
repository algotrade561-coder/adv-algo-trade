package com.algo.trade.tuning.adapter;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import java.time.Instant;

/**
 * Phase 3 extension of {@link TuningCaptureAdapter} for strategies routed through
 * {@link TuningCaptureBridge}. OI Momentum and OI Shift Trap keep inline dual-write
 * and do not implement this interface.
 */
public interface TuningPipelineCapture extends TuningCaptureAdapter {

    SignalEvent buildSignalEvent(SignalRecordContext ctx, String correlationKey);

    EvaluationEvent buildEvaluationEvent(
            EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row);

    EvaluationEvent buildImmediateEvaluationEvent(SignalRecordContext ctx, Instant eventTime);
}
