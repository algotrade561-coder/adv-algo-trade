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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Phase 3 adapter for {@link StrategyType#VOLATILITY_BREAKOUT}. Routed through
 * {@link TuningCaptureBridge} after legacy entry-signals recording.
 */
@Component
public class VolatilityBreakoutCaptureAdapter implements TuningPipelineCapture {

    private static final Pattern BANDWIDTH = Pattern.compile("(?:bw|bandwidth)=([+-]?\\d+(?:\\.\\d+)?)%?");
    private static final Pattern POC_DISTANCE = Pattern.compile("dist=([+-]?\\d+(?:\\.\\d+)?)%");

    @Override
    public StrategyType strategy() {
        return StrategyType.VOLATILITY_BREAKOUT;
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
                new BucketDimension("squeezeRatio", "squeezeRatio",
                        List.of(1.0, 1.5, 2.0, 3.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("ivRank", "ivRank",
                        List.of(0.0, 30.0, 50.0, 70.0, 100.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("pocDistancePct", "pocDistancePct",
                        List.of(0.0, 0.3, 0.5, 1.0), BandStyle.NUMERIC_RANGE)
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
                StrategyType.VOLATILITY_BREAKOUT,
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
                StrategyType.VOLATILITY_BREAKOUT,
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
                StrategyType.VOLATILITY_BREAKOUT,
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
        String reasons = decision != null && decision.reasons() != null
                ? String.join("; ", decision.reasons()) : "";

        putIfPresent(attrs, "squeezeRatio", squeezeRatio(ctx, reasons));
        attrs.put("ivRank", ctx.ivRank());
        putIfPresent(attrs, "pocDistancePct", pocDistancePct(reasons));
        if (ctx.bbSqueeze() != null) {
            attrs.put("bbSqueeze", ctx.bbSqueeze());
        }
        putIfPresent(attrs, "bbUpperBand", ctx.bbUpperBand());
        putIfPresent(attrs, "bbLowerBand", ctx.bbLowerBand());
        attrs.put("firstFailedFilter", firstFailedFilter(ctx, decision));
        if (decision != null) {
            attrs.put("confidenceScore", decision.confidenceScore());
        }
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

    static Double squeezeRatio(SignalRecordContext ctx, String reasons) {
        if (ctx.bbBandwidth() != null) {
            return ctx.bbBandwidth();
        }
        Matcher matcher = BANDWIDTH.matcher(reasons);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return null;
    }

    static Double pocDistancePct(String reasons) {
        Matcher matcher = POC_DISTANCE.matcher(reasons);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        return null;
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
