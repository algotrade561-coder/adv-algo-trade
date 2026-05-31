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

/** Phase 4 adapter for {@link StrategyType#ITM_CONVICTION}. */
@Component
public class ItmConvictionCaptureAdapter implements TuningPipelineCapture {

    private static final Pattern GAP = Pattern.compile("gap=([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern VOLUME = Pattern.compile("vol=(\\d+)");
    private static final Pattern ATM_STRIKE = Pattern.compile("ATM(\\d+)");
    private static final Pattern ITM_STRIKE = Pattern.compile("ITM(\\d+)");

    @Override
    public StrategyType strategy() {
        return StrategyType.ITM_CONVICTION;
    }

    @Override
    public CadenceHint cadence() {
        return CadenceHint.HIGH;
    }

    @Override
    public int defaultEpisodeWindowSec() {
        return 60;
    }

    @Override
    public List<BucketDimension> bucketDimensions() {
        return List.of(
                new BucketDimension("strengthGap", "strengthGap",
                        List.of(0.0, 2.0, 4.0, 6.0, 10.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("itmDepth", "itmDepth",
                        List.of(0.0, 1.0, 2.0, 3.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("volumeBand", "volume",
                        List.of(0.0, 5000.0, 10000.0, 50000.0, 100000.0), BandStyle.NUMERIC_RANGE)
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
                StrategyType.ITM_CONVICTION,
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
        SignalRecordContext ctx = row.firstPayload();
        StrategyDecision decision = ctx.decision();
        String episodeId = "EVAL-" + Integer.toUnsignedString(
                (row.streamKey().name() + ":" + row.dedupKey() + ":" + row.firstAt().toEpochMilli()).hashCode(),
                16);
        return new EvaluationEvent(
                row.firstAt(),
                Instant.now(),
                StrategyType.ITM_CONVICTION,
                IndexType.from(ctx.underlying()),
                episodeId,
                mapOutcome(decision),
                mapOutcome(decision) == EvaluationOutcome.BLOCKED ? row.dedupKey() : null,
                row.tickCount(),
                evaluationAttributes(ctx, decision, row));
    }

    @Override
    public EvaluationEvent buildImmediateEvaluationEvent(SignalRecordContext ctx, Instant eventTime) {
        StrategyDecision decision = ctx.decision();
        String blocker = normalizeBlocker(firstFailedFilter(ctx, decision));
        EvaluationOutcome outcome = mapOutcome(decision);
        return new EvaluationEvent(
                eventTime,
                Instant.now(),
                StrategyType.ITM_CONVICTION,
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
        putIfPresent(attrs, "strengthGap", strengthGap(reasons));
        putIfPresent(attrs, "itmDepth", itmDepth(ctx.underlying(), reasons));
        putIfPresent(attrs, "volume", volume(reasons));
        attrs.put("firstFailedFilter", firstFailedFilter(ctx, decision));
        attrs.put("ivRank", ctx.ivRank());
        if (decision != null) {
            attrs.put("confidenceScore", decision.confidenceScore());
        }
        if (row != null) {
            attrs.put("episodeFirstAt", row.firstAt().toString());
            attrs.put("episodeLastAt", row.lastAt().toString());
            attrs.put("rawBlocker", row.dedupKey());
        }
        attrs.put("executed", ctx.executed());
        return attrs;
    }

    static Double strengthGap(String reasons) {
        Matcher m = GAP.matcher(reasons);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    static Long volume(String reasons) {
        Matcher m = VOLUME.matcher(reasons);
        return m.find() ? Long.parseLong(m.group(1)) : null;
    }

    static Integer itmDepth(com.algo.trade.domain.UnderlyingSymbol underlying, String reasons) {
        Matcher atm = ATM_STRIKE.matcher(reasons);
        Matcher itm = ITM_STRIKE.matcher(reasons);
        if (!atm.find() || !itm.find()) {
            return null;
        }
        int atmStrike = Integer.parseInt(atm.group(1));
        int itmStrike = Integer.parseInt(itm.group(1));
        int interval = IndexType.from(underlying).strikeInterval();
        if (interval <= 0) {
            return null;
        }
        return Math.abs(atmStrike - itmStrike) / interval;
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
