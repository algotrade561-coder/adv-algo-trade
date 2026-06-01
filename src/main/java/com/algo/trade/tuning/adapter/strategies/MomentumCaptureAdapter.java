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
 * Phase 3 adapter for {@link StrategyType#MOMENTUM}. Routed through
 * {@link TuningCaptureBridge} after legacy entry-signals recording.
 */
@Component
public class MomentumCaptureAdapter implements TuningPipelineCapture {

    private static final Pattern ROC_IN_MOMENTUM = Pattern.compile("ROC=([+-]?\\d+(?:\\.\\d+)?)%");
    private static final Pattern EMA21_IN_MOMENTUM = Pattern.compile("EMA21=([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern VOLUME_RATIO = Pattern.compile("Volume=([+-]?\\d+(?:\\.\\d+)?)x");
    private static final Pattern ROC_IN_PARENS = Pattern.compile("\\(([+-]?\\d+(?:\\.\\d+)?)%\\)");
    private static final Pattern ROC_IN_DECCEL = Pattern.compile("roc=([+-]?\\d+(?:\\.\\d+)?)");
    private static final Pattern TREND_MISALIGNED = Pattern.compile(
            "trendMisaligned\\(price=([+-]?\\d+(?:\\.\\d+)?),ema21=([+-]?\\d+(?:\\.\\d+)?)\\)");
    // 2026-06-01 — parsed from MomentumStrategy's enriched reasons strings.
    private static final Pattern PREV_ROC = Pattern.compile("prevROC=([+-]?\\d+(?:\\.\\d+)?)%");
    private static final Pattern ACCELERATING = Pattern.compile("accelerating=(true|false)");
    private static final Pattern ATR_PCT = Pattern.compile("ATR=([+-]?\\d+(?:\\.\\d+)?)%");
    private static final Pattern SCORE_BREAKDOWN = Pattern.compile(
            "ScoreBreakdown: base=(\\d+) roc=(\\d+) accel=(\\d+) vol=(\\d+) atr=(\\d+) total=(\\d+)");

    @Override
    public StrategyType strategy() {
        return StrategyType.MOMENTUM;
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
                new BucketDimension("rocPct", "rocPct",
                        List.of(0.0, 0.25, 0.50, 0.75, 1.0, 1.5), BandStyle.NUMERIC_RANGE),
                new BucketDimension("ema21Gap", "ema21Gap",
                        List.of(-1.0, -0.5, 0.0, 0.5, 1.0), BandStyle.NUMERIC_RANGE),
                new BucketDimension("volRatio", "volumeRatio",
                        List.of(0.8, 1.0, 1.2, 1.5, 2.0), BandStyle.NUMERIC_RANGE)
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
                StrategyType.MOMENTUM,
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
                StrategyType.MOMENTUM,
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
                StrategyType.MOMENTUM,
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

        putIfPresent(attrs, "rocPct", rocPct(ctx, decision, reasons));
        putIfPresent(attrs, "ema21Gap", ema21Gap(ctx, decision, reasons));
        putIfPresent(attrs, "volumeRatio", volumeRatio(reasons));
        // 2026-06-01 — enriched diagnostics for end-of-day tuning.
        putIfPresent(attrs, "prevRocPct", parseDouble(PREV_ROC, reasons));
        putIfPresent(attrs, "accelerating", parseBool(ACCELERATING, reasons));
        putIfPresent(attrs, "atrPct", parseDouble(ATR_PCT, reasons));
        putIfPresent(attrs, "scoreBreakdown", parseScoreBreakdown(reasons));
        if (ctx.scalpCrossType() != null) {
            attrs.put("direction", ctx.scalpCrossType());
        }
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

    static Double rocPct(SignalRecordContext ctx, StrategyDecision decision, String reasons) {
        if (ctx.bbBandwidth() != null) {
            return ctx.bbBandwidth();
        }
        Matcher momentumRoc = ROC_IN_MOMENTUM.matcher(reasons);
        if (momentumRoc.find()) {
            return Math.abs(Double.parseDouble(momentumRoc.group(1)));
        }
        Matcher decelRoc = ROC_IN_DECCEL.matcher(reasons);
        if (decelRoc.find()) {
            return Math.abs(Double.parseDouble(decelRoc.group(1)));
        }
        Matcher parenRoc = ROC_IN_PARENS.matcher(reasons);
        if (parenRoc.find()) {
            return Math.abs(Double.parseDouble(parenRoc.group(1)));
        }
        return null;
    }

    static Double ema21Gap(SignalRecordContext ctx, StrategyDecision decision, String reasons) {
        Matcher misaligned = TREND_MISALIGNED.matcher(reasons);
        if (misaligned.find()) {
            return gapPercent(Double.parseDouble(misaligned.group(1)), Double.parseDouble(misaligned.group(2)));
        }
        Matcher ema21 = EMA21_IN_MOMENTUM.matcher(reasons);
        if (ema21.find() && decision != null && decision.underlyingPrice() != null) {
            return gapPercent(decision.underlyingPrice().doubleValue(), Double.parseDouble(ema21.group(1)));
        }
        return null;
    }

    static Double volumeRatio(String reasons) {
        Matcher volume = VOLUME_RATIO.matcher(reasons);
        if (volume.find()) {
            return Double.parseDouble(volume.group(1));
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

    private static double gapPercent(double price, double ema21) {
        if (ema21 == 0.0d) {
            return 0.0d;
        }
        return (price - ema21) / ema21 * 100.0d;
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

    /** Generic parser for "TAG=number(%)" reason tokens. Returns null on miss. */
    static Double parseDouble(Pattern p, String reasons) {
        if (reasons == null) return null;
        Matcher m = p.matcher(reasons);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    /** Generic parser for "TAG=true/false" reason tokens. Returns null on miss. */
    static Boolean parseBool(Pattern p, String reasons) {
        if (reasons == null) return null;
        Matcher m = p.matcher(reasons);
        return m.find() ? Boolean.valueOf(m.group(1)) : null;
    }

    /**
     * Parses "ScoreBreakdown: base=55 roc=10 accel=10 vol=5 atr=5 total=85" into a
     * structured map so the tuning report can answer "which bonuses fire most
     * often on winning signals." Returns null when the breakdown isn't present.
     */
    static Map<String, Integer> parseScoreBreakdown(String reasons) {
        if (reasons == null) return null;
        Matcher m = SCORE_BREAKDOWN.matcher(reasons);
        if (!m.find()) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("base", Integer.parseInt(m.group(1)));
        out.put("roc", Integer.parseInt(m.group(2)));
        out.put("accel", Integer.parseInt(m.group(3)));
        out.put("vol", Integer.parseInt(m.group(4)));
        out.put("atr", Integer.parseInt(m.group(5)));
        out.put("total", Integer.parseInt(m.group(6)));
        return out;
    }
}
