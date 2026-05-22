package com.algo.trade.reporting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * End-of-day analysis for {@code oi-shift-trap-*.csv} files.
 */
final class OiShiftTrapTuningAnalyzer {

    private OiShiftTrapTuningAnalyzer() {
    }

    static TrapReport analyze(
            List<SignalTuningCsvLoader.OiShiftTrapEvalRow> evaluations,
            List<SignalTuningCsvLoader.OiShiftTrapNearMissRow> nearMisses,
            List<SignalTuningCsvLoader.OiShiftTrapSignalRow> trapSignals,
            List<SignalTuningCsvLoader.SignalRow> genericSignals) {

        if (evaluations.isEmpty() && nearMisses.isEmpty() && trapSignals.isEmpty()) {
            return TrapReport.empty();
        }

        Map<String, Long> blockers = evaluations.stream()
                .filter(e -> e.primaryBlocker() != null && !e.primaryBlocker().isBlank())
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.OiShiftTrapEvalRow::primaryBlocker,
                        Collectors.counting()));

        Map<String, Long> outcomes = evaluations.stream()
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.OiShiftTrapEvalRow::outcome, Collectors.counting()));

        Map<String, Long> nearMissByUnderlying = nearMisses.stream()
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.OiShiftTrapNearMissRow::underlying,
                        Collectors.counting()));

        long trapBuysInGeneric = genericSignals.stream()
                .filter(s -> "OI_SHIFT_TRAP".equals(s.strategyType()))
                .filter(SignalTuningCsvLoader.SignalRow::isBuy)
                .count();

        long noAffordable = genericSignals.stream()
                .filter(s -> "OI_SHIFT_TRAP".equals(s.strategyType()))
                .filter(SignalTuningCsvLoader.SignalRow::isNoTrade)
                .filter(s -> s.firstFailedFilter() != null && s.firstFailedFilter().contains("noAffordable"))
                .count();

        long lowVolume = blockers.getOrDefault("underlying_volume", 0L);

        List<SignalTuningAnalyzer.Recommendation> recs = buildRecommendations(
                evaluations.size(), trapSignals.size(), nearMisses.size(), trapBuysInGeneric,
                noAffordable, lowVolume, blockers, outcomes, nearMissByUnderlying);

        return new TrapReport(evaluations.size(), nearMisses.size(), trapSignals.size(),
                blockers, outcomes, nearMisses, trapSignals, recs);
    }

    private static List<SignalTuningAnalyzer.Recommendation> buildRecommendations(
            long evalSamples,
            long trapSignalRows,
            long nearMissRows,
            long genericBuys,
            long noAffordable,
            long lowVolume,
            Map<String, Long> blockers,
            Map<String, Long> outcomes,
            Map<String, Long> nearMissByUnderlying) {

        List<SignalTuningAnalyzer.Recommendation> list = new ArrayList<>();

        if (trapSignalRows == 0 && genericBuys == 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_shift_trap",
                    "Zero OI Shift Trap BUY signals in period",
                    "Review oi-shift-trap-evaluations.csv primaryBlocker column; top gates: MIN_OI, IMBALANCE, SCORE, trend"));
        }

        if (noAffordable > 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_shift_trap_execution",
                    noAffordable + " NO_TRADE row(s) with noAffordableOption after trap logic",
                    "Trap may fire but premium/risk caps block entry — lower minCombinedPremium or widen max premium"));
        }

        if (lowVolume > evalSamples * 0.3 && evalSamples > 5) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap",
                    lowVolume + " eval sample(s) blocked on underlying_volume",
                    "For BANKNIFTY consider OI_PROXY volume mode in underlying config"));
        }

        String topBlocker = blockers.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + "(" + e.getValue() + ")")
                .orElse("—");
        if (!blockers.isEmpty()) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap_funnel",
                    evalSamples + " eval samples (30s throttle); top blocker: " + topBlocker,
                    "See oi-shift-trap-evaluations.csv — bestCe/bestPe score and failedGate"));
        }

        if (nearMissRows > 0) {
            String topNm = nearMissByUnderlying.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(e -> e.getKey() + "(" + e.getValue() + ")")
                    .orElse("—");
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap_near_miss",
                    nearMissRows + " near-miss strike(s) (score 40–49)",
                    "Closest to trading on " + topNm + " — consider lowering score threshold from 50"));
        }

        long flatTrend = outcomes.getOrDefault("TREND_FLAT", 0L);
        if (flatTrend > evalSamples * 0.25 && evalSamples > 5) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap",
                    flatTrend + " samples with TREND_FLAT outcome",
                    "3-candle trend filter may be too strict in chop — review detectShortTermTrend"));
        }

        if (list.isEmpty()) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap",
                    trapSignalRows + " dedicated trap signal row(s), " + genericBuys + " generic BUY(s)",
                    "Continue EOD review of near-miss and evaluation CSVs"));
        }
        return list;
    }

    record TrapReport(
            long evaluationSamples,
            long nearMissRows,
            long trapSignalRows,
            Map<String, Long> blockersByGate,
            Map<String, Long> outcomes,
            List<SignalTuningCsvLoader.OiShiftTrapNearMissRow> nearMisses,
            List<SignalTuningCsvLoader.OiShiftTrapSignalRow> signals,
            List<SignalTuningAnalyzer.Recommendation> recommendations
    ) {
        static TrapReport empty() {
            return new TrapReport(0, 0, 0, Map.of(), Map.of(), List.of(), List.of(), List.of());
        }
    }
}
