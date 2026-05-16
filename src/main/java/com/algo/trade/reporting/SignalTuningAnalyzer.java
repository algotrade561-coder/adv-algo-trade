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

        Map<String, ExecutionRowView> entryByKey = indexEntries(data.entryOutcomes());
        List<BuyOutcome> buyOutcomes = analyzeBuys(signals, data, entryByKey, data.chainLevelsByDecisionKey());
        List<StrategySummary> strategies = summarizeStrategies(signals);
        Map<String, Long> executionStages = data.entryOutcomes().stream()
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.ExecutionRow::stage, Collectors.counting()));

        List<Recommendation> recommendations = buildRecommendations(signals, strategies, buyOutcomes, executionStages);

        long totalEvals = signals.size();
        long buyCount = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy).count();
        long noTrade = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isNoTrade).count();
        long falseBreakouts = buyOutcomes.stream().filter(BuyOutcome::falseBreakout).count();
        long buysNoBreakout = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy)
                .filter(s -> !s.breakoutPassed()).count();
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
                buysNoBreakout,
                falseBreakouts,
                buyOutcomes.size(),
                chainOiMismatch,
                chainNoOiDelta,
                strategies,
                buyOutcomes,
                executionStages,
                recommendations
        );
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
            boolean logicFalseBreakout = !signal.breakoutPassed()
                    || signal.reasons().contains("Breakout condition failed");
            boolean priceFalseBreakout = path.labeled() && path.mfePct().compareTo(FALSE_BREAKOUT_MFE_PCT) < 0
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

    private static List<Recommendation> buildRecommendations(
            List<SignalTuningCsvLoader.SignalRow> signals,
            List<StrategySummary> strategies,
            List<BuyOutcome> buyOutcomes,
            Map<String, Long> executionStages
    ) {
        List<Recommendation> list = new ArrayList<>();

        long buysNoBreakout = signals.stream().filter(SignalTuningCsvLoader.SignalRow::isBuy)
                .filter(s -> !s.breakoutPassed()).count();
        if (buysNoBreakout > 0) {
            list.add(new Recommendation(Severity.CRITICAL, "entry_logic",
                    buysNoBreakout + " BUY signal(s) fired with breakoutPassed=false",
                    "Require breakoutConfirmed=true before emitting BUY_CE/BUY_PE in RuleBasedOptionsStrategy"));
        }

        long premiumGuard = executionStages.getOrDefault("ORDER_GUARD_REJECTED", 0L);
        if (premiumGuard >= 3) {
            list.add(new Recommendation(Severity.WARN, "execution",
                    premiumGuard + " ORDER_GUARD_REJECTED (often premium cap)",
                    "Reject expensive strikes in ScanContext before BUY; avoid repeated guard spam"));
        }

        long brokerErrors = executionStages.getOrDefault("BROKER_ERROR", 0L);
        if (brokerErrors > 0) {
            list.add(new Recommendation(Severity.CRITICAL, "broker",
                    brokerErrors + " BROKER_ERROR on entry",
                    "Whitelist server IP on Kite developer console; verify live-trading-enabled"));
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
            } else if (strategy.buyRatePercent() < 0.05 && strategy.evaluations() > 500) {
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
            long buysWithoutBreakoutFlag,
            long falseBreakoutLabeled,
            long buyOutcomesAnalyzed,
            long chainOiMismatchCount,
            long chainMissingOiDeltaCount,
            List<StrategySummary> strategies,
            List<BuyOutcome> buyOutcomes,
            Map<String, Long> executionStages,
            List<Recommendation> recommendations
    ) {
        static Report empty() {
            return new Report(Instant.now(), Instant.now(), 0, 0, 0, 0, 0, 0, 0, 0,
                    List.of(), List.of(), Map.of(), List.of(
                    new Recommendation(Severity.INFO, "general", "No entry-signals.csv data found",
                            "Run the scanner during market hours; files live under reports/entry-signals/")));
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
}
