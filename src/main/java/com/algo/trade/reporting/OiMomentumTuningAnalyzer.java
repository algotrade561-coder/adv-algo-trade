package com.algo.trade.reporting;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * End-of-day analysis for {@code oi-momentum-*.csv} files and V3 operator decisions.
 */
final class OiMomentumTuningAnalyzer {

    private OiMomentumTuningAnalyzer() {
    }

    static OiReport analyze(
            List<SignalTuningCsvLoader.OiMomentumSignalRow> signals,
            List<SignalTuningCsvLoader.OiMomentumRejectRow> rejects,
            List<SignalTuningCsvLoader.OiMomentumExitRow> exits,
            Map<String, String> executionStageByKey,
            Map<String, String> executionReasonByKey,
            Map<String, List<SignalTuningCsvLoader.ChainLevelRow>> chainByKey,
            List<SignalTuningCsvLoader.V3DecisionRow> v3Decisions) {

        if (signals.isEmpty() && rejects.isEmpty() && (v3Decisions == null || v3Decisions.isEmpty())) {
            return OiReport.empty();
        }

        List<OiTradeOutcome> trades = new ArrayList<>();
        for (SignalTuningCsvLoader.OiMomentumSignalRow signal : signals) {
            String execStage = executionStageByKey.getOrDefault(signal.decisionKey(), "—");
            String execReason = executionReasonByKey.getOrDefault(signal.decisionKey(), "");
            SignalTuningCsvLoader.OiMomentumExitRow exit = exits.stream()
                    .filter(e -> signal.decisionKey().equals(e.decisionKey()))
                    .findFirst()
                    .orElse(null);
            SignalTuningChainSummarizer.ChainSummary chain = SignalTuningChainSummarizer.summarizeOi(
                    signal, chainByKey.get(signal.decisionKey()));

            BigDecimal profitPct = exit != null ? exit.profitPct() : null;
            BigDecimal pnl = exit != null ? exit.realizedPnl() : null;

            trades.add(new OiTradeOutcome(
                    signal,
                    execStage,
                    execReason,
                    chain,
                    profitPct,
                    exit != null ? exit.exitReason() : "",
                    exit != null ? exit.holdSeconds() : 0,
                    pnl
            ));
        }
        trades.sort(Comparator.comparing(t -> t.signal().timestamp()));

        Map<String, CaseStats> byCase = new LinkedHashMap<>();
        for (OiTradeOutcome t : trades) {
            String caseName = signalCaseLabel(t.signal());
            byCase.computeIfAbsent(caseName, k -> new CaseStats()).add(t);
        }

        Map<String, SignalFillStats> signalFillByCase = buildSignalFillStats(trades);
        Map<String, Long> rejectReasonCounts = summarizeRejectReasons(rejects);
        Map<String, Long> rejectByTimeOfDay = rejects.stream()
                .filter(r -> r.timeOfDayMode() != null && !r.timeOfDayMode().isBlank())
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.OiMomentumRejectRow::timeOfDayMode,
                        Collectors.counting()));
        Map<String, Long> rejectByEntryPath = rejects.stream()
                .filter(r -> r.entryPath() != null && !r.entryPath().isBlank())
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.OiMomentumRejectRow::entryPath,
                        Collectors.counting()));

        long spikeDupes = countSpikeDuplicates(signals);
        V3Summary v3Summary = summarizeV3(v3Decisions != null ? v3Decisions : List.of());
        List<SignalTuningAnalyzer.Recommendation> recs = buildRecommendations(
                trades, rejects, byCase, signalFillByCase, rejectReasonCounts, spikeDupes, v3Summary);

        return new OiReport(
                signals.size(),
                rejects.size(),
                trades.size(),
                spikeDupes,
                byCase,
                trades,
                signalFillByCase,
                rejectReasonCounts,
                rejectByTimeOfDay,
                rejectByEntryPath,
                v3Summary,
                recs);
    }

    private static String signalCaseLabel(SignalTuningCsvLoader.OiMomentumSignalRow signal) {
        if (signal.matrixCase() != null && !signal.matrixCase().isBlank()) {
            return signal.matrixCase();
        }
        return signal.entryCase().isBlank() ? "UNKNOWN" : signal.entryCase();
    }

    private static Map<String, SignalFillStats> buildSignalFillStats(List<OiTradeOutcome> trades) {
        Map<String, SignalFillStats> out = new LinkedHashMap<>();
        for (OiTradeOutcome t : trades) {
            String label = signalCaseLabel(t.signal());
            out.computeIfAbsent(label, k -> new SignalFillStats()).add(t);
        }
        return out;
    }

    private static Map<String, Long> summarizeRejectReasons(
            List<SignalTuningCsvLoader.OiMomentumRejectRow> rejects) {
        return rejects.stream()
                .collect(Collectors.groupingBy(
                        r -> normalizeRejectReason(r.rejectReason()),
                        Collectors.counting()));
    }

    static String normalizeRejectReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return "unknown";
        }
        if (reason.startsWith("matrix_skip:")) {
            return "matrix_skip";
        }
        if (reason.startsWith("low_bias:")) {
            return "low_bias";
        }
        if (reason.startsWith("sl_cooldown:")) {
            return "sl_cooldown";
        }
        if (reason.startsWith("tod_")) {
            return reason.split("\\|")[0];
        }
        int pipe = reason.indexOf('|');
        if (pipe > 0) {
            return reason.substring(0, pipe);
        }
        int colon = reason.indexOf(':');
        if (colon > 0 && !reason.startsWith("CASE")) {
            return reason.substring(0, colon);
        }
        return reason;
    }

    private static V3Summary summarizeV3(List<SignalTuningCsvLoader.V3DecisionRow> rows) {
        if (rows.isEmpty()) {
            return V3Summary.empty();
        }
        Map<String, Long> verdictCounts = rows.stream()
                .collect(Collectors.groupingBy(
                        r -> r.verdict() != null && !r.verdict().isBlank() ? r.verdict() : "UNKNOWN",
                        Collectors.counting()));
        Map<String, Long> enterByTimeMode = rows.stream()
                .filter(r -> "ENTER".equals(r.verdict()))
                .collect(Collectors.groupingBy(
                        r -> r.timeMode() != null && !r.timeMode().isBlank() ? r.timeMode() : "UNKNOWN",
                        Collectors.counting()));
        long enterCount = rows.stream().filter(r -> "ENTER".equals(r.verdict())).count();
        Map<String, Long> gateFails = new LinkedHashMap<>();
        for (SignalTuningCsvLoader.V3DecisionRow r : rows) {
            if ("ENTER".equals(r.verdict())) {
                continue;
            }
            if (!r.g1Pass() && r.g1Reason() != null && !r.g1Reason().isBlank()) {
                gateFails.merge("G1:" + r.g1Reason(), 1L, Long::sum);
            }
            if (!r.g2Pass() && r.g2Reason() != null && !r.g2Reason().isBlank()) {
                gateFails.merge("G2:" + r.g2Reason(), 1L, Long::sum);
            }
            if (!r.g3Pass() && r.g3Reason() != null && !r.g3Reason().isBlank()) {
                gateFails.merge("G3:" + r.g3Reason(), 1L, Long::sum);
            }
            if (!r.g4Pass() && r.g4Reason() != null && !r.g4Reason().isBlank()) {
                gateFails.merge("G4:" + r.g4Reason(), 1L, Long::sum);
            }
        }
        return new V3Summary(rows.size(), enterCount, verdictCounts, enterByTimeMode, gateFails);
    }

    private static long countSpikeDuplicates(List<SignalTuningCsvLoader.OiMomentumSignalRow> signals) {
        List<SignalTuningCsvLoader.OiMomentumSignalRow> spikes = signals.stream()
                .filter(s -> s.entryCase() != null && s.entryCase().startsWith("SPIKE"))
                .sorted(Comparator.comparing(SignalTuningCsvLoader.OiMomentumSignalRow::timestamp))
                .toList();
        if (spikes.size() < 2) {
            return 0;
        }
        long dupes = 0;
        String lastEpisode = "";
        for (var s : spikes) {
            String ep = s.spikeEpisodeId() != null ? s.spikeEpisodeId() : "";
            if (!ep.isEmpty() && ep.equals(lastEpisode)) {
                dupes++;
            }
            lastEpisode = ep;
        }
        return dupes;
    }

    private static boolean isFilledStage(String stage) {
        if (stage == null) {
            return false;
        }
        return stage.contains("FILLED") || "EXECUTED".equals(stage);
    }

    private static List<SignalTuningAnalyzer.Recommendation> buildRecommendations(
            List<OiTradeOutcome> trades,
            List<SignalTuningCsvLoader.OiMomentumRejectRow> rejects,
            Map<String, CaseStats> byCase,
            Map<String, SignalFillStats> signalFillByCase,
            Map<String, Long> rejectReasonCounts,
            long spikeDupes,
            V3Summary v3Summary) {

        List<SignalTuningAnalyzer.Recommendation> list = new ArrayList<>();

        if (spikeDupes > 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_momentum",
                    spikeDupes + " duplicate SPIKE signal row(s) (same episode id)",
                    "Spike dedupe sets lastSpikeEntryTime before enter(); if dupes remain, check synchronized block"));
        }

        long openNoFill = trades.stream()
                .filter(t -> "ORDER_OPEN".equals(t.executionStage()))
                .count();
        if (openNoFill > 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_execution",
                    openNoFill + " OI entry(ies) stuck at ORDER_OPEN (0 fill in outcomes)",
                    "Check broker connectivity / order type; OI_MOMENTUM already routes via MARKET in ExecutionEngine"));
        }

        for (var e : signalFillByCase.entrySet()) {
            SignalFillStats stats = e.getValue();
            if (stats.signals() >= 2) {
                list.add(new SignalTuningAnalyzer.Recommendation(
                        SignalTuningAnalyzer.Severity.INFO, "oi_signal_fill",
                        e.getKey() + ": " + stats.signals() + " signals, "
                                + stats.filled() + " filled, " + stats.openNoFill() + " open, "
                                + stats.closed() + " closed, win " + stats.winRatePct() + "%%",
                        "Compare signal vs fill PnL before changing matrix / bias gates"));
            }
        }

        long lowPremium = trades.stream()
                .filter(t -> t.signal().premium() != null && t.signal().premium().doubleValue() < 25)
                .count();
        if (lowPremium > 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_quality",
                    lowPremium + " OI entry(ies) with premium < ₹25",
                    "Consider min premium gate for OI (illiquid / far OTM)"));
        }

        for (var e : byCase.entrySet()) {
            CaseStats stats = e.getValue();
            if (stats.count() >= 2 && stats.closed() > 0) {
                list.add(new SignalTuningAnalyzer.Recommendation(
                        SignalTuningAnalyzer.Severity.INFO, "oi_case_pnl",
                        e.getKey() + ": " + stats.count() + " signals, " + stats.closed()
                                + " closed, avg PnL ₹" + stats.avgPnl().setScale(0, RoundingMode.HALF_UP)
                                + ", win " + stats.winRatePct() + "%",
                        "Review case threshold in OIMomentumConfig after data week"));
            }
        }

        if (!rejects.isEmpty()) {
            String top = rejectReasonCounts.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(en -> en.getKey() + "(" + en.getValue() + ")")
                    .orElse("—");
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_funnel",
                    rejects.size() + " reject rows; top normalized reason: " + top,
                    "Use oi-momentum-rejects.csv (timeOfDayMode, matrixCase, entryPath, biasScore) "
                            + "with recordEveryReject during tuning week"));
        }

        if (v3Summary.decisionCount() > 0) {
            String topVerdict = v3Summary.verdictCounts().entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(en -> en.getKey() + "(" + en.getValue() + ")")
                    .orElse("—");
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "v3_operator",
                    v3Summary.decisionCount() + " V3 evaluations, "
                            + v3Summary.enterCount() + " ENTER; top verdict: " + topVerdict,
                    "See data/v3-decisions/*.csv for gate-level breakdown (G1–G4)"));
        }

        if (list.isEmpty() && !trades.isEmpty()) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_momentum",
                    trades.size() + " OI signal(s) captured with execution + exit linkage",
                    "Continue paper validation per entryCase / matrixCase"));
        }
        return list;
    }

    record OiReport(
            long signalCount,
            long rejectSampleCount,
            long tradeRows,
            long spikeDuplicates,
            Map<String, CaseStats> statsByCase,
            List<OiTradeOutcome> trades,
            Map<String, SignalFillStats> signalFillByCase,
            Map<String, Long> rejectReasonCounts,
            Map<String, Long> rejectByTimeOfDay,
            Map<String, Long> rejectByEntryPath,
            V3Summary v3Summary,
            List<SignalTuningAnalyzer.Recommendation> recommendations
    ) {
        static OiReport empty() {
            return new OiReport(0, 0, 0, 0, Map.of(), List.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                    V3Summary.empty(), List.of());
        }
    }

    record V3Summary(
            long decisionCount,
            long enterCount,
            Map<String, Long> verdictCounts,
            Map<String, Long> enterByTimeMode,
            Map<String, Long> gateFailReasons
    ) {
        static V3Summary empty() {
            return new V3Summary(0, 0, Map.of(), Map.of(), Map.of());
        }
    }

    static final class SignalFillStats {
        int signals;
        int filled;
        int openNoFill;
        int notFilled;
        int closed;
        int wins;
        BigDecimal totalPnl = BigDecimal.ZERO;

        void add(OiTradeOutcome t) {
            signals++;
            if ("ORDER_OPEN".equals(t.executionStage())) {
                openNoFill++;
            } else if (isFilledStage(t.executionStage())) {
                filled++;
            } else if (t.executionStage() != null && t.executionStage().contains("NOT")) {
                notFilled++;
            }
            if (t.realizedPnl() != null) {
                closed++;
                totalPnl = totalPnl.add(t.realizedPnl());
                if (t.realizedPnl().signum() > 0) {
                    wins++;
                }
            }
        }

        int signals() { return signals; }
        int filled() { return filled; }
        int openNoFill() { return openNoFill; }
        int notFilled() { return notFilled; }
        int closed() { return closed; }

        int winRatePct() {
            return closed == 0 ? 0 : (int) Math.round(wins * 100.0 / closed);
        }

        BigDecimal avgPnl() {
            return closed == 0 ? BigDecimal.ZERO
                    : totalPnl.divide(BigDecimal.valueOf(closed), 2, RoundingMode.HALF_UP);
        }
    }

    record OiTradeOutcome(
            SignalTuningCsvLoader.OiMomentumSignalRow signal,
            String executionStage,
            String brokerReason,
            SignalTuningChainSummarizer.ChainSummary chain,
            BigDecimal profitPct,
            String exitReason,
            long holdSeconds,
            BigDecimal realizedPnl
    ) {
    }

    static final class CaseStats {
        int count;
        int closed;
        int wins;
        BigDecimal totalPnl = BigDecimal.ZERO;

        void add(OiTradeOutcome t) {
            count++;
            if (t.realizedPnl() != null) {
                closed++;
                totalPnl = totalPnl.add(t.realizedPnl());
                if (t.realizedPnl().signum() > 0) {
                    wins++;
                }
            }
        }

        int count() {
            return count;
        }

        int closed() {
            return closed;
        }

        BigDecimal avgPnl() {
            return closed == 0 ? BigDecimal.ZERO
                    : totalPnl.divide(BigDecimal.valueOf(closed), 2, RoundingMode.HALF_UP);
        }

        int winRatePct() {
            return closed == 0 ? 0 : (int) Math.round(wins * 100.0 / closed);
        }
    }
}
