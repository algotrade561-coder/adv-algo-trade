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
 * Phase 3 adapter for {@link StrategyType#REVERSAL_BUY}. Routed through
 * {@link TuningCaptureBridge} after legacy entry-signals recording.
 */
@Component
public class ReversalBuyCaptureAdapter implements TuningPipelineCapture {

    private static final Pattern RSI_IN_REASON = Pattern.compile("rsi=([+-]?\\d+(?:\\.\\d+)?)");

    @Override
    public StrategyType strategy() {
        return StrategyType.REVERSAL_BUY;
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
                new BucketDimension("rsi", "rsiValue",
                        List.of(0.0, 30.0, 50.0, 70.0, 100.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("direction", "direction", null, BandStyle.CATEGORICAL)
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
                StrategyType.REVERSAL_BUY,
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
                StrategyType.REVERSAL_BUY,
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
                StrategyType.REVERSAL_BUY,
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

        putIfPresent(attrs, "rsiValue", rsiValue(ctx, reasons));
        putIfPresent(attrs, "direction", direction(ctx, decision));
        attrs.put("firstFailedFilter", firstFailedFilter(ctx, decision));
        if (decision != null) {
            attrs.put("confidenceScore", decision.confidenceScore());
        }
        attrs.put("ivRank", ctx.ivRank());
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

    static Double rsiValue(SignalRecordContext ctx, String reasons) {
        if (ctx.rsiValue() != null) {
            return ctx.rsiValue();
        }
        // ReversalBuyStrategy stores RSI in StrategyDiagnostics.ema9 → scalpEma9 via builder.
        if (ctx.scalpEma9() != null) {
            return ctx.scalpEma9();
        }
        Matcher matcher = RSI_IN_REASON.matcher(reasons);
        if (matcher.find()) {
            return Double.parseDouble(matcher.group(1));
        }
        Matcher rsiPrefix = Pattern.compile("RSI=([+-]?\\d+(?:\\.\\d+)?)").matcher(reasons);
        if (rsiPrefix.find()) {
            return Double.parseDouble(rsiPrefix.group(1));
        }
        return null;
    }

    static String direction(SignalRecordContext ctx, StrategyDecision decision) {
        if (decision != null && TuningCaptureBridge.isFiredSignal(decision)) {
            return decision.optionType().map(OptionType::name).orElse(null);
        }
        if (ctx.scalpCrossType() != null) {
            return mapReversalDirection(ctx.scalpCrossType());
        }
        return null;
    }

    static String mapReversalDirection(String raw) {
        if ("BULLISH_REVERSAL".equals(raw)) {
            return OptionType.CE.name();
        }
        if ("BEARISH_REVERSAL".equals(raw)) {
            return OptionType.PE.name();
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
