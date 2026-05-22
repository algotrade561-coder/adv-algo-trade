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
 * End-of-day analysis for {@code oi-momentum-*.csv} files.
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
            Map<String, List<SignalTuningCsvLoader.ChainLevelRow>> chainByKey) {

        if (signals.isEmpty() && rejects.isEmpty()) {
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
            String caseName = t.signal().entryCase().isBlank() ? "UNKNOWN" : t.signal().entryCase();
            byCase.computeIfAbsent(caseName, k -> new CaseStats()).add(t);
        }

        long spikeDupes = countSpikeDuplicates(signals);
        List<SignalTuningAnalyzer.Recommendation> recs = buildRecommendations(trades, rejects, byCase, spikeDupes);

        return new OiReport(signals.size(), rejects.size(), trades.size(), spikeDupes, byCase, trades, recs);
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

    private static List<SignalTuningAnalyzer.Recommendation> buildRecommendations(
            List<OiTradeOutcome> trades,
            List<SignalTuningCsvLoader.OiMomentumRejectRow> rejects,
            Map<String, CaseStats> byCase,
            long spikeDupes) {

        List<SignalTuningAnalyzer.Recommendation> list = new ArrayList<>();

        if (spikeDupes > 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_momentum",
                    spikeDupes + " duplicate SPIKE signal row(s) (same episode id)",
                    "Spike dedupe is per index; if duplicates remain, tighten episode id or skip CSV until order accepted"));
        }

        long openNoFill = trades.stream()
                .filter(t -> "ORDER_OPEN".equals(t.executionStage()))
                .count();
        if (openNoFill > 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_execution",
                    openNoFill + " OI entry(ies) stuck at ORDER_OPEN (0 fill in outcomes)",
                    "Prefer MARKET or marketable limits for OI_MOMENTUM"));
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
                        "Review case threshold in OIMomentumConfig"));
            }
        }

        if (!rejects.isEmpty()) {
            Map<String, Long> topRejects = rejects.stream()
                    .collect(Collectors.groupingBy(SignalTuningCsvLoader.OiMomentumRejectRow::rejectReason,
                            Collectors.counting()));
            String top = topRejects.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(en -> en.getKey() + "(" + en.getValue() + ")")
                    .orElse("—");
            Map<String, Long> topBlocks = rejects.stream()
                    .filter(r -> r.blockDetail() != null && !r.blockDetail().isBlank())
                    .collect(Collectors.groupingBy(SignalTuningCsvLoader.OiMomentumRejectRow::blockDetail,
                            Collectors.counting()));
            String topBlock = topBlocks.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(en -> en.getKey() + "(" + en.getValue() + ")")
                    .orElse("—");
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_funnel",
                    rejects.size() + " sampled reject rows (5s matrix / 30s other); top reason: " + top
                            + "; top blockDetail: " + topBlock,
                    "See oi-momentum-rejects.csv (rangePct30m, atmPeLast, blockDetail columns)"));
        }

        if (list.isEmpty() && !trades.isEmpty()) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_momentum",
                    trades.size() + " OI signal(s) captured with execution + exit linkage",
                    "Continue paper validation per entryCase"));
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
            List<SignalTuningAnalyzer.Recommendation> recommendations
    ) {
        static OiReport empty() {
            return new OiReport(0, 0, 0, 0, Map.of(), List.of(), List.of());
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
