package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.adapter.CadenceHint;
import com.algo.trade.tuning.adapter.SpreadCaptureSupport;
import com.algo.trade.tuning.adapter.TuningCaptureBridge;
import com.algo.trade.tuning.adapter.TuningPipelineCapture;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Phase 5 base adapter for multi-leg spread strategies. */
public abstract class AbstractSpreadCaptureAdapter implements TuningPipelineCapture {

    @Override
    public CadenceHint cadence() {
        return CadenceHint.LOW;
    }

    @Override
    public int defaultEpisodeWindowSec() {
        return 60;
    }

    @Override
    public String normalizeBlocker(String raw) {
        return SpreadCaptureSupport.normalizeBlocker(raw);
    }

    @Override
    public SignalEvent buildSignalEvent(SignalRecordContext ctx, String correlationKey) {
        StrategyDecision decision = ctx.decision();
        BigDecimal premium = decision.optionPrice().orElse(BigDecimal.ZERO);
        String instrumentKey = decision.selectedInstrumentKey().orElse(correlationKey);
        return new SignalEvent(
                decision.timestamp() != null ? decision.timestamp() : Instant.now(),
                Instant.now(),
                strategy(),
                IndexType.from(ctx.underlying()),
                correlationKey,
                instrumentKey,
                0,
                OptionType.CE,
                premium,
                SpreadCaptureSupport.evaluationAttributes(ctx, decision, strategy()));
    }

    @Override
    public EvaluationEvent buildEvaluationEvent(
            EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row) {
        return buildImmediateEvaluationEvent(row.firstPayload(), row.firstAt());
    }

    @Override
    public EvaluationEvent buildImmediateEvaluationEvent(SignalRecordContext ctx, Instant eventTime) {
        StrategyDecision decision = ctx.decision();
        String blocker = normalizeBlocker(SpreadCaptureSupport.firstFailedFilter(ctx, decision));
        EvaluationOutcome outcome = SpreadCaptureSupport.mapOutcome(ctx, decision);
        String episodeId = "EVAL-" + Integer.toUnsignedString(
                (strategy().name() + ":" + blocker + ":" + eventTime.toEpochMilli()).hashCode(), 16);
        return new EvaluationEvent(
                eventTime,
                Instant.now(),
                strategy(),
                IndexType.from(ctx.underlying()),
                episodeId,
                outcome,
                outcome == EvaluationOutcome.BLOCKED ? blocker : null,
                1,
                SpreadCaptureSupport.evaluationAttributes(ctx, decision, strategy()));
    }

    protected static List<com.algo.trade.tuning.adapter.BucketDimension> numericRange(
            String name, String attrKey, List<Double> bands) {
        return List.of(new com.algo.trade.tuning.adapter.BucketDimension(
                name, attrKey, bands, com.algo.trade.tuning.adapter.BucketDimension.BandStyle.NUMERIC_RANGE));
    }

    protected static EvaluationOutcome firedOrSkipped(StrategyDecision decision) {
        return TuningCaptureBridge.isFiredSignal(decision)
                ? EvaluationOutcome.FIRED : EvaluationOutcome.SKIPPED;
    }
}
