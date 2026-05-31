package com.algo.trade.tuning.adapter.strategies;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.reporting.SignalTuningFilterUtils;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.adapter.BucketDimension;
import com.algo.trade.tuning.adapter.BucketDimension.BandStyle;
import com.algo.trade.tuning.adapter.CadenceHint;
import com.algo.trade.tuning.adapter.TuningCaptureBridge;
import com.algo.trade.tuning.adapter.TuningPipelineCapture;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Phase 3 adapter for {@link StrategyType#DIRECTIONAL_BUY}. Routed through
 * {@link com.algo.trade.tuning.adapter.TuningCaptureBridge} after legacy
 * {@code entry-signals.csv} recording.
 */
@Component
public class DirectionalBuyCaptureAdapter implements TuningPipelineCapture {

    @Override
    public StrategyType strategy() {
        return StrategyType.DIRECTIONAL_BUY;
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
    public List<BucketDimension> bucketDimensions() {
        return List.of(
                new BucketDimension("confidenceScore", "confidenceScore",
                        List.of(0.0, 50.0, 60.0, 70.0, 80.0, 90.0, 100.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("ivRank", "ivRank",
                        List.of(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("firstFailedFilter", "firstFailedFilter",
                        null, BandStyle.CATEGORICAL),
                new BucketDimension("imbalanceBand", "imbalance",
                        List.of(0.5, 0.8, 1.0, 1.2, 2.0), BandStyle.NUMERIC_RANGE)
        );
    }

    @Override
    public String normalizeBlocker(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        int paren = raw.indexOf('(');
        if (paren > 0) {
            return raw.substring(0, paren);
        }
        return raw;
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
                StrategyType.DIRECTIONAL_BUY,
                index,
                correlationKey,
                instrumentKey,
                strike,
                optionType,
                premium,
                evaluationAttributes(ctx, decision, null));
    }

    @Override
    public EvaluationEvent buildEvaluationEvent(
            EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row) {
        SignalRecordContext ctx = row.firstPayload();
        StrategyDecision decision = ctx.decision();
        IndexType index = IndexType.from(ctx.underlying());
        String episodeId = "EVAL-" + Integer.toUnsignedString(
                (row.streamKey().name() + ":" + row.dedupKey() + ":" + row.firstAt().toEpochMilli()).hashCode(),
                16);
        return new EvaluationEvent(
                row.firstAt(),
                Instant.now(),
                StrategyType.DIRECTIONAL_BUY,
                index,
                episodeId,
                mapOutcome(decision),
                mapOutcome(decision) == EvaluationOutcome.BLOCKED ? row.dedupKey() : null,
                row.tickCount(),
                evaluationAttributes(ctx, decision, row));
    }

    @Override
    public EvaluationEvent buildImmediateEvaluationEvent(SignalRecordContext ctx, Instant eventTime) {
        StrategyDecision decision = ctx.decision();
        IndexType index = IndexType.from(ctx.underlying());
        String blocker = normalizeBlocker(firstFailedFilter(ctx, decision));
        String episodeId = "EVAL-" + Integer.toUnsignedString(
                (index.name() + ":" + blocker + ":" + eventTime.toEpochMilli()).hashCode(), 16);
        EvaluationOutcome outcome = mapOutcome(decision);
        return new EvaluationEvent(
                eventTime,
                Instant.now(),
                StrategyType.DIRECTIONAL_BUY,
                index,
                episodeId,
                outcome,
                outcome == EvaluationOutcome.BLOCKED ? blocker : null,
                1,
                evaluationAttributes(ctx, decision, null));
    }

    static Map<String, Object> evaluationAttributes(
            SignalRecordContext ctx,
            StrategyDecision decision,
            EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        if (decision != null) {
            attrs.put("confidenceScore", decision.confidenceScore());
            attrs.put("signalType", decision.signalType().name());
            decision.imbalance().ifPresent(v -> attrs.put("imbalance", v));
            attrs.put("vwapPassed", decision.vwapConditionPassed());
            attrs.put("volumeSpike", decision.volumeSpike());
        }
        attrs.put("ivRank", ctx.ivRank());
        attrs.put("firstFailedFilter", firstFailedFilter(ctx, decision));
        attrs.put("breakoutPassed", ctx.breakoutPassed());
        attrs.put("oiPassed", ctx.oiPassed());
        attrs.put("ivPassed", ctx.ivPassed());
        attrs.put("liquidityPassed", ctx.liquidityPassed());
        attrs.put("timePassed", ctx.timePassed());
        putIfPresent(attrs, "rsiValue", ctx.rsiValue());
        putIfPresent(attrs, "atrValue", ctx.atrValue());
        putIfPresent(attrs, "ema9Ema21Gap", ctx.ema9Ema21Gap());
        putIfPresent(attrs, "vixLevel", ctx.vixLevel());
        putIfPresent(attrs, "daysToExpiry", ctx.daysToExpiry());
        putFunnelFlags(attrs, ctx, decision);
        if (row != null) {
            attrs.put("episodeFirstAt", row.firstAt().toString());
            attrs.put("episodeLastAt", row.lastAt().toString());
            attrs.put("rawBlocker", row.dedupKey());
        }
        if (ctx.executionStage() != null) {
            attrs.put("executionStage", ctx.executionStage());
        }
        attrs.put("executed", ctx.executed());
        return attrs;
    }

    /** Ordered funnel stage pass flags for {@link DirectionalBuyAnalyzerPlugin}. */
    static void putFunnelFlags(Map<String, Object> attrs,
                               SignalRecordContext ctx,
                               StrategyDecision decision) {
        attrs.put("funnel_vwap", decision != null && decision.vwapConditionPassed());
        attrs.put("funnel_breakout", ctx.breakoutPassed());
        attrs.put("funnel_volume", decision != null && decision.volumeSpike());
        attrs.put("funnel_oi", ctx.oiPassed());
        attrs.put("funnel_iv", ctx.ivPassed());
        attrs.put("funnel_rsi", inferRsiPassed(decision));
        attrs.put("funnel_score", decision != null
                && !String.join("; ", decision.reasons()).contains("Signal score failed"));
    }

    private static boolean inferRsiPassed(StrategyDecision decision) {
        if (decision == null || decision.reasons() == null) {
            return false;
        }
        String reasons = String.join("; ", decision.reasons());
        return !reasons.contains("RSI momentum gate failed");
    }

    static String firstFailedFilter(SignalRecordContext ctx, StrategyDecision decision) {
        if (ctx.firstFailedFilter() != null && !ctx.firstFailedFilter().isBlank()) {
            return ctx.firstFailedFilter();
        }
        if (decision == null || decision.reasons() == null) {
            return "unknown";
        }
        return SignalTuningFilterUtils.inferFailedFilter(String.join("; ", decision.reasons()));
    }

    private static EvaluationOutcome mapOutcome(StrategyDecision decision) {
        if (decision == null) {
            return EvaluationOutcome.BLOCKED;
        }
        if (TuningCaptureBridge.isFiredSignal(decision)) {
            return EvaluationOutcome.FIRED;
        }
        return EvaluationOutcome.BLOCKED;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
