package com.algo.trade.reporting;

import com.algo.trade.domain.Candle;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Analyzes loaded signal CSV data and produces tuning recommendations.
 */
final class SignalTuningAnalyzer {

    private static final MathContext MC = MathContext.DECIMAL64;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DISPLAY_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(IST);

    private static final BigDecimal FALSE_BREAKOUT_MFE_PCT = new BigDecimal("5");
    private static final BigDecimal FALSE_BREAKOUT_MAE_PCT = new BigDecimal("8");
    private static final int FORWARD_MINUTES = 45;

    private SignalTuningAnalyzer() {
    }

    static Report analyze(SignalTuningCsvLoader.Loaded data) {
        List<SignalTuningCsvLoader.SignalRow> signals = data.signals();
        if (signals.isEmpty()) {
            return Report.empty();
        }

        Instant periodFrom = signals.stream().map(SignalTuningCsvLoader.SignalRow::timestamp)
                .filter(t -> !t.equals(Instant.EPOCH)).min(Instant::compareTo).orElse(Instant.now());
        Instant periodTo = signals.stream().map(SignalTuningCsvLoader.SignalRow::timestamp).max(Instant::compareTo)
                .orElse(Instant.now());

        Map<String, ExecutionRowView> entryViewByKey = indexEntries(data.entryOutcomes());
        Map<String, String> execStageByKey = new LinkedHashMap<>();
        Map<String, String> execReasonByKey = new LinkedHashMap<>();
        Map<String, SignalTuningCsvLoader.ExecutionRow> entryExecByKey = new LinkedHashMap<>();
        for (var e : data.entryOutcomes()) {
            execStageByKey.put(e.decisionKey(), e.stage());
            execReasonByKey.put(e.decisionKey(), e.reasons() != null ? e.reasons() : "");
            entryExecByKey.put(e.decisionKey(), e);
        }
        OiMomentumTuningAnalyzer.OiReport oiReport = OiMomentumTuningAnalyzer.analyze(
                data.oiMomentumSignals(),
                data.oiMomentumRejects(),
                data.oiMomentumExits(),
                entryExecByKey,
                execStageByKey,
                execReasonByKey,
                data.chainLevelsByDecisionKey(),
                data.v3Decisions(),
                data.legacyDetections(),
                data.spikeEpisodes());
        List<SignalTuningCsvLoader.SignalRow> trapGeneric = signals.stream()
                .filter(s -> "OI_SHIFT_TRAP".equals(s.strategyType()))
                .toList();
        OiShiftTrapTuningAnalyzer.TrapReport trapReport = OiShiftTrapTuningAnalyzer.analyze(
                data.oiShiftTrapEvaluations(),
                data.oiShiftTrapNearMisses(),
                data.oiShiftTrapSignals(),
                trapGeneric);
        List<BuyOutcome> buyOutcomes = analyzeBuys(signals, data, entryViewByKey, data.chainLevelsByDecisionKey());
        List<StrategySummary> strategies = summarizeStrategies(signals);
        Map<String, Long> executionStages = data.entryOutcomes().stream()
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.ExecutionRow::stage, Collectors.counting()));

        List<Recommendation> recommendations = buildRecommendations(signals, strategies, buyOutcomes, executionStages, data);
        recommendations = mergeRecommendations(recommendations, oiReport.recommendations());
        recommendations = mergeRecommendations(recommendations, trapReport.recommendations());

        long totalEvals = signals.size();
        long buyCount = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy).count();
        long noTrade = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isNoTrade).count();
        long falseBreakouts = buyOutcomes.stream().filter(BuyOutcome::falseBreakout).count();
        long buysNoBreakoutConfirmed = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy)
                .filter(SignalTuningCsvLoader.SignalRow::isDirectionalBuy)
                .filter(s -> !s.breakoutConfirmed()).count();
        long swingFalseConfirmTrue = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy)
                .filter(SignalTuningCsvLoader.SignalRow::isDirectionalBuy)
                .filter(s -> !s.breakoutPassed() && s.breakoutConfirmed()).count();
        long oiSpikeBurstDuplicates = countOiSpikeBurstDuplicates(signals);
        long chainOiMismatch = buyOutcomes.stream()
                .filter(b -> b.chain() != null && b.chain().oiMismatch()).count();
        long chainNoOiDelta = buyOutcomes.stream()
                .filter(b -> b.chain() != null && b.chain().present() && !b.chain().oiDeltaPresent()).count();

        return new Report(
                periodFrom,
                periodTo,
                totalEvals,
                buyCount,
                noTrade,
                buysNoBreakoutConfirmed,
                swingFalseConfirmTrue,
                oiSpikeBurstDuplicates,
                falseBreakouts,
                buyOutcomes.size(),
                chainOiMismatch,
                chainNoOiDelta,
                strategies,
                buyOutcomes,
                executionStages,
                recommendations,
                oiReport,
                trapReport
        );
    }

    private static List<Recommendation> mergeRecommendations(List<Recommendation> base, List<Recommendation> extra) {
        List<Recommendation> merged = new ArrayList<>(base);
        merged.addAll(extra);
        return merged;
    }

    private static Map<String, ExecutionRowView> indexEntries(List<SignalTuningCsvLoader.ExecutionRow> rows) {
        Map<String, ExecutionRowView> map = new LinkedHashMap<>();
        for (var row : rows) {
            map.put(row.decisionKey(), new ExecutionRowView(row.stage(), row.accepted(), row.brokerRejectionReason(),
                    row.reasons()));
        }
        return map;
    }

    private static List<BuyOutcome> analyzeBuys(List<SignalTuningCsvLoader.SignalRow> signals,
                                                SignalTuningCsvLoader.Loaded data,
                                                Map<String, ExecutionRowView> entryByKey,
                                                Map<String, List<SignalTuningCsvLoader.ChainLevelRow>> chainByKey) {
        List<BuyOutcome> outcomes = new ArrayList<>();
        for (SignalTuningCsvLoader.SignalRow signal : signals) {
            if (!signal.isBuy()) {
                continue;
            }
            ForwardPath path = forwardPath(signal, data.optionCandlesByInstrument());
            boolean logicFalseBreakout = directionalBreakoutQualityFailed(signal);
            boolean priceFalseBreakout = signal.isDirectionalBuy()
                    && path.labeled()
                    && path.mfePct().compareTo(FALSE_BREAKOUT_MFE_PCT) < 0
                    && path.maePct().compareTo(FALSE_BREAKOUT_MAE_PCT) > 0;
            boolean falseBreakout = logicFalseBreakout || priceFalseBreakout;

            ExecutionRowView exec = entryByKey.get(signal.decisionKey());
            String execStage = exec == null ? "—" : exec.stage();
            SignalTuningChainSummarizer.ChainSummary chain = SignalTuningChainSummarizer.summarize(
                    signal, chainByKey.get(signal.decisionKey()));

            outcomes.add(new BuyOutcome(
                    signal.decisionKey(),
                    DISPLAY_TS.format(signal.timestamp()),
                    signal.strategyType(),
                    signal.signalType(),
                    signal.underlying(),
                    signal.instrumentKey(),
                    signal.optionPrice(),
                    signal.confidenceScore(),
                    signal.breakoutPassed(),
                    signal.breakoutConfirmed(),
                    signal.oiEntryCase(),
                    signal.volumeSpike(),
                    signal.oiPassed(),
                    execStage,
                    path.mfePct(),
                    path.maePct(),
                    falseBreakout,
                    logicFalseBreakout,
                    priceFalseBreakout,
                    chain
            ));
        }
        outcomes.sort(Comparator.comparing(BuyOutcome::timestamp));
        return outcomes;
    }

    private static ForwardPath forwardPath(SignalTuningCsvLoader.SignalRow signal,
                                           Map<String, List<Candle>> candlesByInstrument) {
        if (signal.optionPrice() == null || signal.optionPrice().signum() <= 0) {
            return ForwardPath.unlabeled();
        }
        List<Candle> candles = candlesByInstrument.get(signal.instrumentKey());
        if (candles == null || candles.isEmpty()) {
            return ForwardPath.unlabeled();
        }
        Instant end = signal.timestamp().plus(Duration.ofMinutes(FORWARD_MINUTES));
        BigDecimal entry = signal.optionPrice();
        BigDecimal maxHigh = entry;
        BigDecimal minLow = entry;
        boolean seen = false;
        for (Candle candle : candles) {
            if (candle.timestamp().isBefore(signal.timestamp()) || candle.timestamp().isAfter(end)) {
                continue;
            }
            seen = true;
            maxHigh = maxHigh.max(candle.high());
            minLow = minLow.min(candle.low());
        }
        if (!seen) {
            return ForwardPath.unlabeled();
        }
        BigDecimal mfe = maxHigh.subtract(entry).multiply(BigDecimal.valueOf(100), MC).divide(entry, MC);
        BigDecimal mae = entry.subtract(minLow).multiply(BigDecimal.valueOf(100), MC).divide(entry, MC);
        return new ForwardPath(true, mfe.setScale(1, RoundingMode.HALF_UP), mae.setScale(1, RoundingMode.HALF_UP));
    }

    private static List<StrategySummary> summarizeStrategies(List<SignalTuningCsvLoader.SignalRow> signals) {
        Map<String, List<SignalTuningCsvLoader.SignalRow>> byStrategy = signals.stream()
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.SignalRow::strategyType, LinkedHashMap::new,
                        Collectors.toList()));

        List<StrategySummary> summaries = new ArrayList<>();
        for (var entry : byStrategy.entrySet()) {
            String strategy = entry.getKey();
            List<SignalTuningCsvLoader.SignalRow> rows = entry.getValue();
            long evals = rows.size();
            long buys = rows.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy).count();
            long noTrades = rows.stream().filter(SignalTuningCsvLoader.SignalRow::isNoTrade).count();

            Map<String, Long> blockers = rows.stream()
                    .filter(SignalTuningCsvLoader.SignalRow::isNoTrade)
                    .collect(Collectors.groupingBy(
                            s -> s.firstFailedFilter() == null || s.firstFailedFilter().isBlank()
                                    ? SignalTuningCsvLoader.inferFailedFilter(s.reasons())
                                    : s.firstFailedFilter(),
                            Collectors.counting()));

            List<FilterCount> topBlockers = blockers.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .limit(5)
                    .map(e -> new FilterCount(e.getKey(), e.getValue(),
                            noTrades == 0 ? 0 : (e.getValue() * 100.0 / noTrades)))
                    .toList();

            double avgNearMissScore = rows.stream()
                    .filter(SignalTuningCsvLoader.SignalRow::isNoTrade)
                    .filter(s -> s.confidenceScore().compareTo(s.minSignalScore()) < 0)
                    .filter(s -> s.confidenceScore().compareTo(s.minSignalScore().subtract(new BigDecimal("15"))) >= 0)
                    .mapToDouble(s -> s.confidenceScore().doubleValue())
                    .average()
                    .orElse(0);

            summaries.add(new StrategySummary(strategy, evals, buys, noTrades,
                    evals == 0 ? 0 : (buys * 100.0 / evals), topBlockers,
                    Math.round(avgNearMissScore * 10) / 10.0));
        }
        summaries.sort(Comparator.comparingLong(StrategySummary::evaluations).reversed());
        return summaries;
    }

    /** Directional false-breakout logic aligns with entry gate: confirmation, not swing flag alone. */
    private static boolean directionalBreakoutQualityFailed(SignalTuningCsvLoader.SignalRow signal) {
        if (!signal.isDirectionalBuy()) {
            return false;
        }
        return !signal.breakoutConfirmed()
                || signal.reasons().contains("Breakout confirmation failed");
    }

    /**
     * Counts extra SPIKE BUY rows within 60s bursts, <strong>per underlying</strong>.
     * Cross-index spikes (e.g. NIFTY + SENSEX within 60s) are not duplicates.
     */
    private static long countOiSpikeBurstDuplicates(List<SignalTuningCsvLoader.SignalRow> signals) {
        Map<String, List<SignalTuningCsvLoader.SignalRow>> byUnderlying = signals.stream()
                .filter(SignalTuningCsvLoader.SignalRow::isBuy)
                .filter(SignalTuningCsvLoader.SignalRow::isOiMomentum)
                .filter(s -> s.reasons() != null && s.reasons().contains("SPIKE:"))
                .collect(Collectors.groupingBy(
                        s -> s.underlying() == null || s.underlying().isBlank() ? "_" : s.underlying()));
        long duplicates = 0;
        for (List<SignalTuningCsvLoader.SignalRow> oiBuys : byUnderlying.values()) {
            duplicates += countSpikeBurstDuplicatesForSeries(oiBuys);
        }
        return duplicates;
    }

    private static long countSpikeBurstDuplicatesForSeries(List<SignalTuningCsvLoader.SignalRow> oiBuys) {
        if (oiBuys.size() < 2) {
            return 0;
        }
        List<SignalTuningCsvLoader.SignalRow> sorted = oiBuys.stream()
                .sorted(Comparator.comparing(SignalTuningCsvLoader.SignalRow::timestamp))
                .toList();
        long duplicates = 0;
        Instant windowStart = sorted.get(0).timestamp();
        int burstCount = 1;
        for (int i = 1; i < sorted.size(); i++) {
            SignalTuningCsvLoader.SignalRow row = sorted.get(i);
            if (Duration.between(windowStart, row.timestamp()).getSeconds() <= 60) {
                burstCount++;
            } else {
                if (burstCount > 1) {
                    duplicates += burstCount - 1;
                }
                windowStart = row.timestamp();
                burstCount = 1;
            }
        }
        if (burstCount > 1) {
            duplicates += burstCount - 1;
        }
        return duplicates;
    }

    private static GuardRejectionSummary summarizeGuardRejections(List<SignalTuningCsvLoader.ExecutionRow> rows) {
        Map<String, Long> categories = new LinkedHashMap<>();
        for (SignalTuningCsvLoader.ExecutionRow row : rows) {
            if (!"ORDER_GUARD_REJECTED".equals(row.stage())) {
                continue;
            }
            String cat = categorizeGuardReason(row.reasons());
            categories.merge(cat, 1L, Long::sum);
        }
        return new GuardRejectionSummary(categories);
    }

    private static String categorizeGuardReason(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return "unknown";
        }
        String lower = reasons.toLowerCase();
        if (lower.contains("open trade already exists") || lower.contains("open buy order already exists")) {
            return "duplicateInstrument";
        }
        if (lower.contains("cooldown")) {
            return "cooldown";
        }
        if (lower.contains("direction flip")) {
            return "directionFlip";
        }
        if (lower.contains("premium") || lower.contains("expensive")) {
            return "premiumCap";
        }
        return "other";
    }

    private static List<Recommendation> buildRecommendations(
            List<SignalTuningCsvLoader.SignalRow> signals,
            List<StrategySummary> strategies,
            List<BuyOutcome> buyOutcomes,
            Map<String, Long> executionStages,
            SignalTuningCsvLoader.Loaded data
    ) {
        List<Recommendation> list = new ArrayList<>();

        long buysNoConfirm = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy)
                .filter(SignalTuningCsvLoader.SignalRow::isDirectionalBuy)
                .filter(s -> !s.breakoutConfirmed()).count();
        if (buysNoConfirm > 0) {
            list.add(new Recommendation(Severity.CRITICAL, "entry_logic",
                    buysNoConfirm + " DIRECTIONAL_BUY signal(s) without breakout confirmation",
                    "Entry requires breakoutConfirmed; fix RuleBasedOptionsStrategy or CSV reasons if BUY appears"));
        }

        long swingGap = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy)
                .filter(SignalTuningCsvLoader.SignalRow::isDirectionalBuy)
                .filter(s -> !s.breakoutPassed() && s.breakoutConfirmed()).count();
        if (swingGap > 0) {
            list.add(new Recommendation(Severity.INFO, "entry_logic",
                    swingGap + " DIRECTIONAL_BUY: swing breakoutPassed=false but confirmation passed",
                    "Swing vs confirm use different thresholds; current entry requires BOTH — if BUY fired, audit record timing or gate logic"));
        }

        long oiDupes = countOiSpikeBurstDuplicates(signals);
        if (oiDupes > 0) {
            list.add(new Recommendation(Severity.WARN, "oi_momentum",
                    oiDupes + " duplicate OI_MOMENTUM SPIKE BUY row(s) within 60s (same underlying)",
                    "Strengthen spike episode dedupe / skip CSV until order accepted; verify restarts do not reset spike clock"));
        }

        GuardRejectionSummary guardSummary = summarizeGuardRejections(data.entryOutcomes());
        long premiumGuard = executionStages.getOrDefault("ORDER_GUARD_REJECTED", 0L);
        if (premiumGuard >= 3) {
            list.add(new Recommendation(Severity.WARN, "execution",
                    premiumGuard + " ORDER_GUARD_REJECTED — " + guardSummary.describe(),
                    guardSummary.actionHint()));
        }

        long brokerErrors = executionStages.getOrDefault("BROKER_ERROR", 0L);
        if (brokerErrors > 0) {
            list.add(new Recommendation(Severity.CRITICAL, "broker",
                    brokerErrors + " BROKER_ERROR on entry (historical rows in outcomes CSV)",
                    "Whitelist server IP on Kite developer console; verify live-trading-enabled; re-run report after fix"));
        }

        long orderOpen = executionStages.getOrDefault("ORDER_OPEN", 0L);
        long orderFilled = executionStages.getOrDefault("ORDER_FILLED", 0L);
        if (orderOpen > 0 && orderFilled == 0) {
            list.add(new Recommendation(Severity.WARN, "execution",
                    orderOpen + " limit orders OPEN with 0 fills in outcomes CSV",
                    "Use marketable limits (SmartOrderRouter); shorten limitOrderCancelMinutes"));
        }

        long falseBreakouts = buyOutcomes.stream().filter(BuyOutcome::falseBreakout).count();
        if (!buyOutcomes.isEmpty() && falseBreakouts * 100 / buyOutcomes.size() >= 50) {
            list.add(new Recommendation(Severity.WARN, "quality",
                    falseBreakouts + "/" + buyOutcomes.size() + " BUYs look like false breakouts (MFE<5%, MAE>8%)",
                    "Tighten OI confirmation; require volumeSpike; raise minSignalScore"));
        }

        long chainMismatch = buyOutcomes.stream()
                .filter(b -> b.chain() != null && b.chain().oiMismatch()).count();
        if (!buyOutcomes.isEmpty() && chainMismatch > 0) {
            list.add(new Recommendation(Severity.WARN, "option_chain",
                    chainMismatch + "/" + buyOutcomes.size()
                            + " BUY(s): oiPassed but max OI build on opposite side (see chain table)",
                    "Tighten OI price-action filter; require CE/PE OI build to match signal direction"));
        }

        long chainStale = buyOutcomes.stream()
                .filter(b -> b.chain() != null && b.chain().present() && !b.chain().oiDeltaPresent()).count();
        if (!buyOutcomes.isEmpty() && chainStale * 100 / buyOutcomes.size() >= 50) {
            list.add(new Recommendation(Severity.WARN, "option_chain",
                    chainStale + "/" + buyOutcomes.size() + " BUY(s) have chain rows but OI change is all zero",
                    "Verify OI snapshot capture / chain snapshot scheduler; OI filter may be blind"));
        }

        long buysNoChain = buyOutcomes.stream()
                .filter(b -> b.chain() == null || !b.chain().present()).count();
        if (!buyOutcomes.isEmpty() && buysNoChain * 100 / buyOutcomes.size() >= 30) {
            list.add(new Recommendation(Severity.INFO, "option_chain",
                    buysNoChain + "/" + buyOutcomes.size() + " BUY(s) missing option-chain-levels rows",
                    "Ensure option-chain-levels.csv is written (same decisionKey as entry-signals)"));
        }

        List<StrategySummary> zeroBuy = strategies.stream()
                .filter(s -> s.evaluations() >= 50 && s.buySignals() == 0)
                .sorted(Comparator.comparing(StrategySummary::evaluations).reversed())
                .toList();
        if (zeroBuy.size() >= 5) {
            String topList = zeroBuy.stream()
                    .limit(14)
                    .map(s -> s.strategyType() + "(" + s.evaluations() + ")")
                    .collect(Collectors.joining(", "));
            list.add(new Recommendation(Severity.INFO, "activation_summary",
                    zeroBuy.size() + " strategies with 0 BUY (50+ evals). Heaviest: " + topList
                            + (zeroBuy.size() > 14 ? " …" : ""),
                    "See Strategy funnel table; inspect entry-signals.csv reasons only for types you care about"));
            int warnAdded = 0;
            for (StrategySummary strategy : zeroBuy) {
                if (strategy.evaluations() < 2_000 || warnAdded >= 3) {
                    continue;
                }
                String top = strategy.topBlockers().isEmpty() ? "unknown"
                        : strategy.topBlockers().get(0).filter();
                double pct = strategy.topBlockers().isEmpty() ? 0 : strategy.topBlockers().get(0).percent();
                list.add(new Recommendation(Severity.WARN, "activation",
                        strategy.strategyType() + ": 0 BUY in " + strategy.evaluations() + " evals (top block: "
                                + top + " " + String.format("%.0f%%", pct) + ")",
                        suggestForFilter(top, strategy)));
                warnAdded++;
            }
        } else {
            for (StrategySummary strategy : strategies) {
                if (strategy.evaluations() < 50) {
                    continue;
                }
                if (strategy.buySignals() == 0) {
                    String top = strategy.topBlockers().isEmpty() ? "unknown"
                            : strategy.topBlockers().get(0).filter();
                    double pct = strategy.topBlockers().isEmpty() ? 0 : strategy.topBlockers().get(0).percent();
                    list.add(new Recommendation(Severity.WARN, "activation",
                            strategy.strategyType() + ": 0 BUY in " + strategy.evaluations() + " evals (top block: "
                                    + top + " " + String.format("%.0f%%", pct) + ")",
                            suggestForFilter(top, strategy)));
                }
            }
        }
        for (StrategySummary strategy : strategies) {
            if (strategy.evaluations() < 50) {
                continue;
            }
            if (strategy.buySignals() > 0 && strategy.buyRatePercent() < 0.05 && strategy.evaluations() > 500) {
                list.add(new Recommendation(Severity.INFO, "activation",
                        strategy.strategyType() + ": very low BUY rate (" + String.format("%.3f%%", strategy.buyRatePercent()) + ")",
                        "Review top 2 blockers in report; loosen one gate per session"));
            }
        }

        Optional<StrategySummary> directional = strategies.stream()
                .filter(s -> "DIRECTIONAL_BUY".equals(s.strategyType())).findFirst();
        if (directional.isPresent()) {
            long scoreBlocks = directional.get().topBlockers().stream()
                    .filter(f -> "signalScore".equals(f.filter()))
                    .mapToLong(FilterCount::count)
                    .sum();
            long noTrades = directional.get().noTradeSignals();
            if (noTrades > 0 && scoreBlocks * 100 / noTrades > 25) {
                double near = directional.get().avgNearMissScore();
                list.add(new Recommendation(Severity.INFO, "parameters",
                        "DIRECTIONAL_BUY: signalScore blocks " + (scoreBlocks * 100 / noTrades)
                                + "% of NO_TRADE (near-miss avg " + near + ")",
                        near >= 63
                                ? "Consider minSignalScorePercent 65–68 if near-miss replay is profitable"
                                : "Keep minSignalScore; improve breakout/OI before lowering score"));
            }
        }

        if (list.isEmpty()) {
            list.add(new Recommendation(Severity.INFO, "general",
                    "No critical tuning flags from today's signal data",
                    "Continue paper trading; re-run after next session"));
        }
        return list;
    }

    private static String suggestForFilter(String filter, StrategySummary strategy) {
        return switch (filter) {
            case "signalScore" -> "Lower minSignalScorePercent slightly OR require breakout+volume instead of score alone";
            case "rsi" -> "Disable RSI gate for directional or widen RSI band in chop sessions";
            case "timeWindow" -> "Extend entryCutoffTime or entryStartTime in global config";
            case "trend", "breakout" -> "Reduce breakoutLookback/breakoutBuffer or trade only when both trend+breakout pass";
            case "volumeSpike" -> "Lower volumeSpikeMultiplier (e.g. 1.2 → 1.1)";
            case "oi" -> "Review oi-price-action.min-oi-change-percent; confirm chain snapshots align";
            case "momentumRoc" -> "Lower MOMENTUM MIN_ROC per underlying (NIFTY ~0.25%)";
            case "scalpSetup" -> "Reduce SCALPING MIN_CANDLES (22→14) and widen time window";
            case "ivRank" -> "Raise maxIvRankForBuying for VOLATILITY_BREAKOUT (~50)";
            case "sideFilter" -> "Check side-specific CE/PE filters and PCR bias";
            case "environmentScore" -> "Lower minEnvironmentScore (55→50) after validating with replay";
            default -> "Inspect sample reasons in entry-signals.csv for " + strategy.strategyType();
        };
    }

    record Report(
            Instant periodFrom,
            Instant periodTo,
            long totalEvaluations,
            long buySignals,
            long noTradeSignals,
            long buysWithoutBreakoutConfirmed,
            long swingBreakoutFalseConfirmTrue,
            long oiSpikeBurstDuplicates,
            long falseBreakoutLabeled,
            long buyOutcomesAnalyzed,
            long chainOiMismatchCount,
            long chainMissingOiDeltaCount,
            List<StrategySummary> strategies,
            List<BuyOutcome> buyOutcomes,
            Map<String, Long> executionStages,
            List<Recommendation> recommendations,
            OiMomentumTuningAnalyzer.OiReport oiMomentumReport,
            OiShiftTrapTuningAnalyzer.TrapReport oiShiftTrapReport
    ) {
        static Report empty() {
            return new Report(Instant.now(), Instant.now(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    List.of(), List.of(), Map.of(), List.of(
                    new Recommendation(Severity.INFO, "general", "No entry-signals.csv data found",
                            "Run the scanner during market hours; files live under reports/entry-signals/")),
                    OiMomentumTuningAnalyzer.OiReport.empty(),
                    OiShiftTrapTuningAnalyzer.TrapReport.empty());
        }
    }

    record StrategySummary(
            String strategyType,
            long evaluations,
            long buySignals,
            long noTradeSignals,
            double buyRatePercent,
            List<FilterCount> topBlockers,
            double avgNearMissScore
    ) {
    }

    record FilterCount(String filter, long count, double percent) {
    }

    record BuyOutcome(
            String decisionKey,
            String timestamp,
            String strategyType,
            String signalType,
            String underlying,
            String instrumentKey,
            BigDecimal entryPrice,
            BigDecimal score,
            boolean breakoutPassed,
            boolean breakoutConfirmed,
            String oiEntryCase,
            boolean volumeSpike,
            boolean oiPassed,
            String executionStage,
            BigDecimal mfePct,
            BigDecimal maePct,
            boolean falseBreakout,
            boolean logicFalseBreakout,
            boolean priceFalseBreakout,
            SignalTuningChainSummarizer.ChainSummary chain
    ) {
    }

    record Recommendation(Severity severity, String category, String finding, String action) {
    }

    enum Severity { CRITICAL, WARN, INFO }

    private record ExecutionRowView(String stage, boolean accepted, String brokerReason, String reasons) {
    }

    private record ForwardPath(boolean labeled, BigDecimal mfePct, BigDecimal maePct) {
        static ForwardPath unlabeled() {
            return new ForwardPath(false, BigDecimal.ZERO, BigDecimal.ZERO);
        }
    }

    private record GuardRejectionSummary(Map<String, Long> categories) {
        String describe() {
            if (categories.isEmpty()) {
                return "no reason text";
            }
            return categories.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .collect(Collectors.joining(", "));
        }

        String actionHint() {
            if (categories.getOrDefault("duplicateInstrument", 0L) + categories.getOrDefault("cooldown", 0L) > 0) {
                return "Suppress repeat BUY signals when guard would reject (open trade/order/cooldown)";
            }
            if (categories.getOrDefault("premiumCap", 0L) > 0) {
                return "Reject expensive strikes in ScanContext before emitting BUY";
            }
            return "Review order-guard rules and execution outcomes reasons";
        }
    }
}
