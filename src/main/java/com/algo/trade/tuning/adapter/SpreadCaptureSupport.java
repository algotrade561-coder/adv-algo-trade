package com.algo.trade.tuning.adapter;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.SpreadEvaluationContext;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.reporting.SignalTuningFilterUtils;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.EvaluationOutcome;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared helpers for Phase 5 spread capture adapters and bridge wiring. */
public final class SpreadCaptureSupport {

    private static final Pattern NET_DEBIT = Pattern.compile("Net debit: ([\\d.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern NET_CREDIT = Pattern.compile("Net credit: ([\\d.]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern EMA_GAP = Pattern.compile("gap=([\\d.]+)%");
    private static final Pattern WING_DISTANCE = Pattern.compile("wingDistance=([\\d.]+)");
    private static final Pattern OTM_DISTANCE = Pattern.compile("otmDistance=([\\d.]+)");
    private static final Pattern THETA_DELTA = Pattern.compile("thetaDelta=([\\d.]+)");
    private static final Pattern EXPIRY_GAP = Pattern.compile("expiryGap=([\\d.]+)");
    private static final Pattern HEDGE_DISTANCE = Pattern.compile("hedgeDistance=([\\d.]+)");

    private static final Set<String> GATE_BLOCKER_PREFIXES = Set.of(
            "disabled",
            "alreadyHasOpenPosition",
            "crossStrategyGate",
            "correlationGate",
            "marketGuard",
            "portfolioGreeksCap",
            "MARGIN_REJECTED",
            "PARTIAL_UNWOUND"
    );

    private SpreadCaptureSupport() {
    }

    public static final Set<StrategyType> SPREAD_CAPTURE_STRATEGIES = Set.of(
            StrategyType.BULL_CALL_SPREAD,
            StrategyType.BEAR_PUT_SPREAD,
            StrategyType.LONG_STRADDLE,
            StrategyType.LONG_STRANGLE,
            StrategyType.SHORT_STRADDLE,
            StrategyType.SHORT_STRANGLE,
            StrategyType.IRON_CONDOR,
            StrategyType.BUTTERFLY,
            StrategyType.CALENDAR_SPREAD,
            StrategyType.DIAGONAL_SPREAD,
            StrategyType.JADE_LIZARD,
            StrategyType.SYNTHETIC_FUTURES
    );

    public static SignalRecordContext toRecordContext(StrategyType strategy,
                                                      SpreadEvaluationContext spreadCtx,
                                                      Optional<StrategyDecision> decisionOpt,
                                                      String rejectReason) {
        StrategyDecision decision = decisionOpt.orElseGet(() -> syntheticBlockedDecision(spreadCtx, rejectReason));
        String blocker = rejectReason != null && !rejectReason.isBlank()
                ? rejectReason
                : firstFailedFilter(null, decision);

        return SignalRecordContext.builder()
                .strategyType(strategy.name())
                .underlying(spreadCtx.underlying())
                .decision(decision)
                .trendCandles(spreadCtx.trendCandles())
                .ivRank(spreadCtx.ivRank())
                .ema9Ema21Gap(emaGap(spreadCtx, blocker))
                .scalpCrossType(direction(spreadCtx, blocker, strategy))
                .firstFailedFilter(blocker)
                .build();
    }

    public static String normalizeBlocker(String raw) {
        if (raw == null || raw.isBlank()) {
            return "unknown";
        }
        int paren = raw.indexOf('(');
        if (paren > 0) {
            return raw.substring(0, paren);
        }
        return raw;
    }

    public static EvaluationOutcome mapOutcome(SignalRecordContext ctx, StrategyDecision decision) {
        if (TuningCaptureBridge.isFiredSignal(decision)) {
            return EvaluationOutcome.FIRED;
        }
        String blocker = ctx != null ? ctx.firstFailedFilter() : firstFailedFilter(ctx, decision);
        if (blocker != null) {
            String token = normalizeBlocker(blocker);
            for (String gate : GATE_BLOCKER_PREFIXES) {
                if (token.startsWith(gate)) {
                    return EvaluationOutcome.BLOCKED;
                }
            }
        }
        return EvaluationOutcome.SKIPPED;
    }

    public static Map<String, Object> evaluationAttributes(SignalRecordContext ctx,
                                                           StrategyDecision decision,
                                                           StrategyType strategy) {
        Map<String, Object> attrs = new LinkedHashMap<>();
        String reasons = decision != null && decision.reasons() != null
                ? String.join("; ", decision.reasons()) : "";
        String blocker = firstFailedFilter(ctx, decision);

        attrs.put("ivRank", ctx.ivRank());
        putIfPresent(attrs, "netDebit", netDebit(reasons));
        putIfPresent(attrs, "netCredit", netCredit(reasons));
        putIfPresent(attrs, "combinedPremium", combinedPremium(reasons));
        putIfPresent(attrs, "combinedCredit", netCredit(reasons));
        putIfPresent(attrs, "emaGap", emaGap(ctx, blocker));
        putIfPresent(attrs, "emaDivergence", emaGap(ctx, blocker));
        putIfPresent(attrs, "wingDistance", parseDouble(WING_DISTANCE, reasons));
        putIfPresent(attrs, "otmDistance", parseDouble(OTM_DISTANCE, reasons));
        putIfPresent(attrs, "hedgeDistance", parseDouble(HEDGE_DISTANCE, reasons));
        putIfPresent(attrs, "thetaDelta", parseDouble(THETA_DELTA, reasons));
        putIfPresent(attrs, "expiryGap", parseDouble(EXPIRY_GAP, reasons));
        putIfPresent(attrs, "direction", direction(ctx, blocker, strategy));
        attrs.put("firstFailedFilter", blocker);
        if (decision != null) {
            attrs.put("confidenceScore", decision.confidenceScore());
        }
        attrs.put("executed", ctx.executed());
        if (ctx.executionStage() != null) {
            attrs.put("executionStage", ctx.executionStage());
        }
        return attrs;
    }

    public static String firstFailedFilter(SignalRecordContext ctx, StrategyDecision decision) {
        if (ctx != null && ctx.firstFailedFilter() != null && !ctx.firstFailedFilter().isBlank()) {
            return ctx.firstFailedFilter();
        }
        if (decision == null || decision.reasons() == null || decision.reasons().isEmpty()) {
            return "unknown";
        }
        return SignalTuningFilterUtils.inferFailedFilter(String.join("; ", decision.reasons()));
    }

    static Double netDebit(String reasons) {
        Matcher m = NET_DEBIT.matcher(reasons);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    static Double netCredit(String reasons) {
        Matcher m = NET_CREDIT.matcher(reasons);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    static Double combinedPremium(String reasons) {
        Double debit = netDebit(reasons);
        if (debit != null) {
            return debit;
        }
        return netCredit(reasons);
    }

    static Double emaGap(SpreadEvaluationContext spreadCtx, String blocker) {
        if (blocker != null) {
            Matcher gap = EMA_GAP.matcher(blocker);
            if (gap.find()) {
                return Double.parseDouble(gap.group(1));
            }
        }
        return emaGapFromCandles(spreadCtx.trendCandles(), spreadCtx.underlyingPrice());
    }

    static Double emaGap(SignalRecordContext ctx, String blocker) {
        if (ctx.ema9Ema21Gap() != null) {
            return ctx.ema9Ema21Gap();
        }
        if (blocker != null) {
            Matcher gap = EMA_GAP.matcher(blocker);
            if (gap.find()) {
                return Double.parseDouble(gap.group(1));
            }
        }
        return emaGapFromCandles(ctx.trendCandles(), ctx.decision() != null
                ? ctx.decision().underlyingPrice() : null);
    }

    static Double emaGapFromCandles(List<com.algo.trade.domain.Candle> candles, BigDecimal spot) {
        if (candles == null || candles.size() < 21 || spot == null || spot.signum() <= 0) {
            return null;
        }
        EmaIndicator ema = new EmaIndicator();
        List<BigDecimal> closes = candles.stream().map(com.algo.trade.domain.Candle::close).toList();
        BigDecimal ema9 = ema.calculate(closes, 9);
        BigDecimal ema21 = ema.calculate(closes, 21);
        if (ema21.signum() == 0) {
            return 0.0;
        }
        return ema9.subtract(ema21).abs().doubleValue() / ema21.doubleValue() * 100.0;
    }

    static String direction(SpreadEvaluationContext spreadCtx, String blocker, StrategyType strategy) {
        if (blocker != null) {
            if (blocker.contains("bearish") || blocker.contains("BEAR")) {
                return "BEAR";
            }
            if (blocker.contains("bullish") || blocker.contains("BULL")) {
                return "BULL";
            }
        }
        return switch (strategy) {
            case BEAR_PUT_SPREAD -> "BEAR";
            case BULL_CALL_SPREAD, SYNTHETIC_FUTURES -> "BULL";
            default -> spreadCtx.trendCandles() != null && spreadCtx.trendCandles().size() >= 21
                    ? trendDirection(spreadCtx) : null;
        };
    }

    static String direction(SignalRecordContext ctx, String blocker, StrategyType strategy) {
        if (ctx.scalpCrossType() != null) {
            return ctx.scalpCrossType();
        }
        return direction(new SpreadEvaluationContext(
                ctx.decision() != null ? ctx.decision().underlyingPrice() : BigDecimal.ZERO,
                ctx.ivRank(), null, null, ctx.underlying(), IndexType.from(ctx.underlying()),
                null, ctx.trendCandles()), blocker, strategy);
    }

    private static String trendDirection(SpreadEvaluationContext spreadCtx) {
        EmaIndicator ema = new EmaIndicator();
        List<BigDecimal> closes = spreadCtx.trendCandles().stream()
                .map(com.algo.trade.domain.Candle::close).toList();
        BigDecimal ema9 = ema.calculate(closes, 9);
        BigDecimal ema21 = ema.calculate(closes, 21);
        return ema9.compareTo(ema21) > 0 ? "BULL" : "BEAR";
    }

    private static StrategyDecision syntheticBlockedDecision(SpreadEvaluationContext spreadCtx, String rejectReason) {
        List<String> reasons = rejectReason != null && !rejectReason.isBlank()
                ? List.of("spreadEntryBlocked:" + rejectReason)
                : List.of("spreadEntryConditionNotMet");
        return new StrategyDecision(
                java.time.Instant.now(),
                spreadCtx.underlying(),
                SignalType.NO_TRADE,
                spreadCtx.underlyingPrice(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), false, Optional.empty(), false,
                BigDecimal.ZERO, reasons);
    }

    private static Double parseDouble(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    private static void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }
}
