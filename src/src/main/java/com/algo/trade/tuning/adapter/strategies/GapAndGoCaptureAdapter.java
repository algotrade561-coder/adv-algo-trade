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

/** Phase 4 adapter for {@link StrategyType#GAP_AND_GO}. */
@Component
public class GapAndGoCaptureAdapter implements TuningPipelineCapture {

    private static final Pattern GAP_PCT = Pattern.compile("gap=([+-]?\\d+(?:\\.\\d+)?)%");
    private static final Pattern BODY_PCT = Pattern.compile("body=([+-]?\\d+(?:\\.\\d+)?)%");
    private static final Pattern VOLUME_RATIO = Pattern.compile("volume=([+-]?\\d+(?:\\.\\d+)?)x");

    @Override
    public StrategyType strategy() {
        return StrategyType.GAP_AND_GO;
    }

    @Override
    public CadenceHint cadence() {
        return CadenceHint.LOW;
    }

    @Override
    public List<BucketDimension> bucketDimensions() {
        return List.of(
                new BucketDimension("gapPct", "gapPct",
                        List.of(0.0, 0.15, 0.30, 0.50, 1.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("bodyPct", "bodyPct",
                        List.of(0.4, 0.6, 0.8, 1.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("volumeRatio", "volumeRatio",
                        List.of(1.0, 1.3, 1.5, 2.0, 3.0), BandStyle.NUMERIC_RANGE)
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
        return new SignalEvent(
                decision.timestamp() != null ? decision.timestamp() : Instant.now(),
                Instant.now(),
                StrategyType.GAP_AND_GO,
                IndexType.from(ctx.underlying()),
                correlationKey,
                instrumentKey(decision, ctx),
                strike(decision),
                decision.optionType().orElse(OptionType.CE),
                decision.optionPrice().orElse(BigDecimal.ZERO),
                evaluationAttributes(ctx, decision, null));
    }

    @Override
    public EvaluationEvent buildEvaluationEvent(
            EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row) {
        return buildImmediateEvaluationEvent(row.firstPayload(), row.firstAt());
    }

    @Override
    public EvaluationEvent buildImmediateEvaluationEvent(SignalRecordContext ctx, Instant eventTime) {
        StrategyDecision decision = ctx.decision();
        String blocker = normalizeBlocker(firstFailedFilter(ctx, decision));
        EvaluationOutcome outcome = mapOutcome(ctx, decision);
        return new EvaluationEvent(
                eventTime,
                Instant.now(),
                StrategyType.GAP_AND_GO,
                IndexType.from(ctx.underlying()),
                episodeId(ctx, blocker, eventTime),
                outcome,
                outcome == EvaluationOutcome.BLOCKED ? blocker : null,
                1,
                evaluationAttributes(ctx, decision, null));
    }

    static Map<String, Object> evaluationAttributes(
            SignalRecordContext ctx, StrategyDecision decision,
            EpisodeAggregator.EpisodeRow<IndexType, String, SignalRecordContext> row) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        String reasons = reasons(decision);
        putIfPresent(attrs, "gapPct", gapPct(reasons));
        putIfPresent(attrs, "bodyPct", bodyPct(ctx, reasons));
        putIfPresent(attrs, "volumeRatio", volumeRatio(reasons));
        if (ctx.scalpCrossType() != null) {
            attrs.put("direction", ctx.scalpCrossType());
        }
        attrs.put("firstFailedFilter", firstFailedFilter(ctx, decision));
        attrs.put("ivRank", ctx.ivRank());
        if (decision != null) {
            attrs.put("confidenceScore", decision.confidenceScore());
        }
        attrs.put("executed", ctx.executed());
        return attrs;
    }

    static Double gapPct(String reasons) {
        Matcher m = GAP_PCT.matcher(reasons);
        return m.find() ? Math.abs(Double.parseDouble(m.group(1))) : null;
    }

    static Double bodyPct(SignalRecordContext ctx, String reasons) {
        if (ctx.bbBandwidth() != null) {
            return ctx.bbBandwidth();
        }
        Matcher m = BODY_PCT.matcher(reasons);
        return m.find() ? Math.abs(Double.parseDouble(m.group(1))) : null;
    }

    static Double volumeRatio(String reasons) {
        Matcher volume = VOLUME_RATIO.matcher(reasons);
        if (volume.find()) {
            return Double.parseDouble(volume.group(1));
        }
        Matcher ratio = Pattern.compile("ratio=([+-]?\\d+(?:\\.\\d+)?)x").matcher(reasons);
        return ratio.find() ? Double.parseDouble(ratio.group(1)) : null;
    }

    static EvaluationOutcome mapOutcome(SignalRecordContext ctx, StrategyDecision decision) {
        if (TuningCaptureBridge.isFiredSignal(decision)) {
            return EvaluationOutcome.FIRED;
        }
        String filter = firstFailedFilter(ctx, decision);
        if ("timeWindow".equals(normalizeBlockerStatic(filter))) {
            return EvaluationOutcome.SKIPPED;
        }
        return EvaluationOutcome.BLOCKED;
    }

    private static String normalizeBlockerStatic(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        int paren = raw.indexOf('(');
        if (paren > 0) {
            return raw.substring(0, paren);
        }
        return raw;
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

    private static String reasons(StrategyDecision decision) {
        return decision != null && decision.reasons() != null
                ? String.join("; ", decision.reasons()) : "";
    }

    private static String episodeId(SignalRecordContext ctx, String blocker, Instant eventTime) {
        return "EVAL-" + Integer.toUnsignedString(
                (ctx.underlying().name() + ":" + blocker + ":" + eventTime.toEpochMilli()).hashCode(), 16);
    }

    private static int strike(StrategyDecision decision) {
        return decision.selectedStrike().map(BigDecimal::intValue).orElse(0);
    }

    private static String instrumentKey(StrategyDecision decision, SignalRecordContext ctx) {
        return decision.selectedInstrumentKey().orElse(
                ctx.selectedInstrumentKey() != null ? ctx.selectedInstrumentKey() : "");
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
