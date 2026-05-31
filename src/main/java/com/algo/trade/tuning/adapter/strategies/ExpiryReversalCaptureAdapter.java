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

/** Phase 4 adapter for {@link StrategyType#EXPIRY_REVERSAL}. */
@Component
public class ExpiryReversalCaptureAdapter implements TuningPipelineCapture {

    private static final Pattern SPIKE_PCT = Pattern.compile("spike=([+-]?\\d+(?:\\.\\d+)?)%");
    private static final Pattern DTE = Pattern.compile("daysToExpiry=(\\d+)");

    @Override
    public StrategyType strategy() {
        return StrategyType.EXPIRY_REVERSAL;
    }

    @Override
    public CadenceHint cadence() {
        return CadenceHint.LOW;
    }

    @Override
    public List<BucketDimension> bucketDimensions() {
        return List.of(
                new BucketDimension("spikePct", "spikePct",
                        List.of(0.5, 0.75, 1.0, 1.5), BandStyle.NUMERIC_RANGE),
                new BucketDimension("dte", "daysToExpiry", null, BandStyle.CATEGORICAL),
                new BucketDimension("highOiStrikeDistance", "highOiStrikeDistance",
                        List.of(0.0, 50.0, 100.0, 200.0), BandStyle.NUMERIC_RANGE)
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
                StrategyType.EXPIRY_REVERSAL,
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
                StrategyType.EXPIRY_REVERSAL,
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
        String reasons = decision != null && decision.reasons() != null
                ? String.join("; ", decision.reasons()) : "";
        putIfPresent(attrs, "spikePct", spikePct(ctx, reasons));
        putIfPresent(attrs, "daysToExpiry", daysToExpiry(ctx, reasons));
        putIfPresent(attrs, "highOiStrikeDistance", highOiStrikeDistance(ctx, decision));
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

    static Double spikePct(SignalRecordContext ctx, String reasons) {
        if (ctx.bbBandwidth() != null) {
            return ctx.bbBandwidth();
        }
        Matcher m = SPIKE_PCT.matcher(reasons);
        return m.find() ? Math.abs(Double.parseDouble(m.group(1))) : null;
    }

    static Long daysToExpiry(SignalRecordContext ctx, String reasons) {
        if (ctx.daysToExpiry() != null && ctx.daysToExpiry() >= 0) {
            return ctx.daysToExpiry();
        }
        Matcher m = DTE.matcher(reasons);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    static Double highOiStrikeDistance(SignalRecordContext ctx, StrategyDecision decision) {
        if (ctx.selectedStrike() != null && decision != null && decision.underlyingPrice() != null) {
            return Math.abs(decision.underlyingPrice().subtract(ctx.selectedStrike()).doubleValue());
        }
        if (decision != null && decision.selectedStrike().isPresent() && decision.underlyingPrice() != null) {
            return Math.abs(decision.underlyingPrice().subtract(decision.selectedStrike().get()).doubleValue());
        }
        return null;
    }

    static EvaluationOutcome mapOutcome(SignalRecordContext ctx, StrategyDecision decision) {
        if (TuningCaptureBridge.isFiredSignal(decision)) {
            return EvaluationOutcome.FIRED;
        }
        String blocker = normalizeBlockerStatic(firstFailedFilter(ctx, decision));
        if (blocker.startsWith("notNearExpiry") || blocker.startsWith("afterCutoff")) {
            return EvaluationOutcome.SKIPPED;
        }
        return EvaluationOutcome.BLOCKED;
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
