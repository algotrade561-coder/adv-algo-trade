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
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Phase 4 adapter for {@link StrategyType#EVENT_DRIVEN_BUY}. */
@Component
public class EventDrivenBuyCaptureAdapter implements TuningPipelineCapture {

    private static final Pattern EVENT_DATE = Pattern.compile("event=(\\d{4}-\\d{2}-\\d{2})");

    @Override
    public StrategyType strategy() {
        return StrategyType.EVENT_DRIVEN_BUY;
    }

    @Override
    public CadenceHint cadence() {
        return CadenceHint.LOW;
    }

    @Override
    public List<BucketDimension> bucketDimensions() {
        return List.of(
                new BucketDimension("daysToEvent", "daysToEvent",
                        List.of(0.0, 1.0, 2.0, 3.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("ivRankAtEntry", "ivRank",
                        List.of(0.0, 20.0, 40.0, 60.0, 80.0, 100.0), BandStyle.NUMERIC_RANGE)
        );
    }

    @Override
    public String normalizeBlocker(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        if (raw.contains("No signal conditions met")) {
            return "no_event_within_window";
        }
        if (raw.contains("IV rank") && raw.contains("too high")) {
            return "iv_too_high";
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
                StrategyType.EVENT_DRIVEN_BUY,
                IndexType.from(ctx.underlying()),
                correlationKey,
                instrumentKey(decision, ctx),
                strike(decision),
                decision.optionType().orElse(OptionType.CE),
                decision.optionPrice().orElse(BigDecimal.ZERO),
                evaluationAttributes(ctx, decision));
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
                StrategyType.EVENT_DRIVEN_BUY,
                IndexType.from(ctx.underlying()),
                episodeId(ctx, blocker, eventTime),
                outcome,
                outcome == EvaluationOutcome.BLOCKED ? blocker : null,
                1,
                evaluationAttributes(ctx, decision));
    }

    static Map<String, Object> evaluationAttributes(SignalRecordContext ctx, StrategyDecision decision) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        String reasons = decision != null && decision.reasons() != null
                ? String.join("; ", decision.reasons()) : "";
        putIfPresent(attrs, "daysToEvent", daysToEvent(reasons));
        attrs.put("ivRank", ctx.ivRank());
        attrs.put("firstFailedFilter", firstFailedFilter(ctx, decision));
        if (decision != null) {
            attrs.put("confidenceScore", decision.confidenceScore());
        }
        attrs.put("executed", ctx.executed());
        return attrs;
    }

    static Long daysToEvent(String reasons) {
        Matcher m = EVENT_DATE.matcher(reasons);
        if (!m.find()) {
            return null;
        }
        LocalDate eventDate = LocalDate.parse(m.group(1));
        return ChronoUnit.DAYS.between(LocalDate.now(), eventDate);
    }

    static EvaluationOutcome mapOutcome(SignalRecordContext ctx, StrategyDecision decision) {
        if (TuningCaptureBridge.isFiredSignal(decision)) {
            return EvaluationOutcome.FIRED;
        }
        if (decision != null && decision.reasons() != null) {
            String joined = String.join("; ", decision.reasons());
            if (joined.contains("No signal conditions met")) {
                return EvaluationOutcome.SKIPPED;
            }
            if (joined.contains("IV rank") && joined.contains("too high")) {
                return EvaluationOutcome.BLOCKED;
            }
        }
        return EvaluationOutcome.BLOCKED;
    }

    static String firstFailedFilter(SignalRecordContext ctx, StrategyDecision decision) {
        if (ctx.firstFailedFilter() != null && !ctx.firstFailedFilter().isBlank()) {
            return ctx.firstFailedFilter();
        }
        if (decision == null || decision.reasons() == null || decision.reasons().isEmpty()) {
            return "No signal conditions met for Event Driven Buy";
        }
        return SignalTuningFilterUtils.inferFailedFilter(String.join("; ", decision.reasons()));
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
