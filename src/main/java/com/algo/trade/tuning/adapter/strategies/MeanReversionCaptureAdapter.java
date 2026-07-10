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
import com.algo.trade.tuning.adapter.TuningPipelineCapture;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Minimal Phase-3 tuning adapter for {@link StrategyType#MEAN_REVERSION}. The strategy trades live but
 * had no {@link TuningPipelineCapture}, so {@code TuningCaptureBridge} DROPPED its captures
 * ("no_pipeline_adapter_registered" WARN). This records the signal/evaluation events so the data-loop is
 * closed. It uses the shared {@link SpreadCaptureSupport} outcome/blocker helpers and a generic attribute
 * map (the decision reasons) — intentionally lean, with no strategy-specific reason parsing (which can be
 * added later if MEAN_REVERSION becomes a primary live strategy).
 */
@Component
public class MeanReversionCaptureAdapter implements TuningPipelineCapture {

    @Override
    public StrategyType strategy() {
        return StrategyType.MEAN_REVERSION;
    }

    @Override
    public CadenceHint cadence() {
        return CadenceHint.MEDIUM;
    }

    @Override
    public int defaultEpisodeWindowSec() {
        return 60;
    }

    @Override
    public SignalEvent buildSignalEvent(SignalRecordContext ctx, String correlationKey) {
        StrategyDecision decision = ctx.decision();
        IndexType index = IndexType.from(ctx.underlying());
        int strike = decision.selectedStrike().map(BigDecimal::intValue).orElse(0);
        OptionType optionType = decision.optionType().orElse(OptionType.CE);
        BigDecimal premium = decision.optionPrice().orElse(BigDecimal.ZERO);
        String instrumentKey = decision.selectedInstrumentKey().orElse(
                ctx.selectedInstrumentKey() != null ? ctx.selectedInstrumentKey() : "");
        return new SignalEvent(
                decision.timestamp() != null ? decision.timestamp() : Instant.now(),
                Instant.now(),
                StrategyType.MEAN_REVERSION,
                index,
                correlationKey,
                instrumentKey,
                strike,
                optionType,
                premium,
                attributes(decision));
    }

    @Override
    public EvaluationEvent buildEvaluationEvent(
            EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row) {
        SignalRecordContext ctx = row.firstPayload();
        StrategyDecision decision = ctx.decision();
        IndexType index = IndexType.from(ctx.underlying());
        EvaluationOutcome outcome = SpreadCaptureSupport.mapOutcome(ctx, decision);
        String episodeId = "EVAL-" + Integer.toUnsignedString(
                (row.streamKey().name() + ":" + row.dedupKey() + ":" + row.firstAt().toEpochMilli()).hashCode(), 16);
        return new EvaluationEvent(
                row.firstAt(),
                Instant.now(),
                StrategyType.MEAN_REVERSION,
                index,
                episodeId,
                outcome,
                outcome == EvaluationOutcome.BLOCKED ? row.dedupKey() : null,
                row.tickCount(),
                attributes(decision));
    }

    @Override
    public EvaluationEvent buildImmediateEvaluationEvent(SignalRecordContext ctx, Instant eventTime) {
        StrategyDecision decision = ctx.decision();
        IndexType index = IndexType.from(ctx.underlying());
        EvaluationOutcome outcome = SpreadCaptureSupport.mapOutcome(ctx, decision);
        String blocker = normalizeBlocker(SpreadCaptureSupport.firstFailedFilter(ctx, decision));
        String episodeId = "EVAL-" + Integer.toUnsignedString(
                (index.name() + ":" + blocker + ":" + eventTime.toEpochMilli()).hashCode(), 16);
        return new EvaluationEvent(
                eventTime,
                Instant.now(),
                StrategyType.MEAN_REVERSION,
                index,
                episodeId,
                outcome,
                outcome == EvaluationOutcome.BLOCKED ? blocker : null,
                1,
                attributes(decision));
    }

    private static Map<String, Object> attributes(StrategyDecision decision) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (decision != null && decision.reasons() != null && !decision.reasons().isEmpty()) {
            String reasons = String.join("; ", decision.reasons());
            attrs.put("reasons", reasons);
            // #87: stamp a firstFailedFilter so the scorecard top-blocker + forward-checkpoint reject arm can
            // NAME mean_reversion's gate instead of degrading to '(skipped)'. Derived from the decision
            // reasons via the shared inferrer (this adapter has no ctx here).
            attrs.put("firstFailedFilter",
                    com.algo.trade.reporting.SignalTuningFilterUtils.inferFailedFilter(reasons));
        }
        return attrs;
    }
}
