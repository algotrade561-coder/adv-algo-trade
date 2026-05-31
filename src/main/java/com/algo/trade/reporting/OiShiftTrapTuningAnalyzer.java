package com.algo.trade.reporting;

import com.algo.trade.strategy.oishifttrap.OiShiftTrapForwardRecorder;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * End-of-day analysis for {@code oi-shift-trap-*.csv} files.
 */
final class OiShiftTrapTuningAnalyzer {

    private static final Path SIGNAL_DIR = Path.of("reports", "entry-signals");

    private OiShiftTrapTuningAnalyzer() {
    }

    static TrapReport analyze(
            List<SignalTuningCsvLoader.OiShiftTrapEvalRow> evaluations,
            List<SignalTuningCsvLoader.OiShiftTrapNearMissRow> nearMisses,
            List<SignalTuningCsvLoader.OiShiftTrapSignalRow> trapSignals,
            List<SignalTuningCsvLoader.SignalRow> genericSignals,
            Map<String, SignalTuningCsvLoader.ExecutionRow> entryExecByKey) {

        List<TrapExitRow> exits = loadExits();
        List<TrapForwardRow> forwards = loadForwards();
        List<TrapConfirmationRow> confirmations = loadConfirmations();

        if (evaluations.isEmpty() && nearMisses.isEmpty() && trapSignals.isEmpty()
                && exits.isEmpty()) {
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

        Map<String, TrapExitRow> exitByKey = exits.stream()
                .filter(e -> e.decisionKey() != null && !e.decisionKey().isBlank())
                .collect(Collectors.toMap(TrapExitRow::decisionKey, e -> e, (a, b) -> b, LinkedHashMap::new));

        Map<String, TrapConfirmationRow> confirmByKey = confirmations.stream()
                .collect(Collectors.toMap(TrapConfirmationRow::decisionKey, c -> c, (a, b) -> b, LinkedHashMap::new));

        Map<String, List<BucketStats>> scoreBucketsByIndex = bucketStats(trapSignals, exitByKey, "score");
        Map<String, List<BucketStats>> imbalanceBucketsByIndex = bucketStats(trapSignals, exitByKey, "imbalance");
        Map<String, List<BucketStats>> proximityBucketsByIndex = bucketStats(trapSignals, exitByKey, "proximity");
        DrawdownStats drawdown = drawdownStats(exits);
        List<ConfirmationComboStats> confirmationCombos = confirmationEffectiveness(trapSignals, exitByKey, confirmByKey);
        List<LateEntrySimRow> lateEntrySim = lateEntrySimulation(forwards, exitByKey);
        Map<String, Double> slippageByScoreBand = slippageByScoreBand(trapSignals, entryExecByKey);
        Map<String, Double> fillRatioByScoreBand = fillRatioByScoreBand(trapSignals, entryExecByKey);

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
                noAffordable, lowVolume, blockers, outcomes, nearMissByUnderlying, drawdown, lateEntrySim);

        return new TrapReport(evaluations.size(), nearMisses.size(), trapSignals.size(),
                blockers, outcomes, nearMisses, trapSignals, recs,
                scoreBucketsByIndex, imbalanceBucketsByIndex, proximityBucketsByIndex,
                drawdown, confirmationCombos, lateEntrySim, slippageByScoreBand, fillRatioByScoreBand, exits.size());
    }

    private static Map<String, List<BucketStats>> bucketStats(
            List<SignalTuningCsvLoader.OiShiftTrapSignalRow> signals,
            Map<String, TrapExitRow> exitByKey,
            String dimension) {

        Map<String, List<SignalTuningCsvLoader.OiShiftTrapSignalRow>> byIndex = signals.stream()
                .collect(Collectors.groupingBy(SignalTuningCsvLoader.OiShiftTrapSignalRow::underlying));

        Map<String, List<BucketStats>> out = new LinkedHashMap<>();
        for (var e : byIndex.entrySet()) {
            Map<String, List<SignalTuningCsvLoader.OiShiftTrapSignalRow>> bands = e.getValue().stream()
                    .collect(Collectors.groupingBy(s -> bandLabel(dimension, s)));
            List<BucketStats> stats = new ArrayList<>();
            for (var b : bands.entrySet()) {
                List<TrapExitRow> bandExits = b.getValue().stream()
                        .map(s -> exitByKey.get(s.decisionKey()))
                        .filter(x -> x != null)
                        .toList();
                long wins = bandExits.stream().filter(x -> x.realizedPnl().signum() > 0).count();
                double avgPnl = bandExits.stream().mapToDouble(x -> x.realizedPnl().doubleValue()).average().orElse(0);
                double avgMae = bandExits.stream().mapToDouble(TrapExitRow::mae).average().orElse(0);
                List<Long> ttm = bandExits.stream().map(TrapExitRow::timeToMaeSec).sorted().toList();
                List<Long> ttf = bandExits.stream().map(TrapExitRow::timeToMfeSec).sorted().toList();
                stats.add(new BucketStats(
                        e.getKey(), b.getKey(), b.getValue().size(), bandExits.size(),
                        bandExits.isEmpty() ? 0 : wins * 100.0 / bandExits.size(),
                        avgPnl, avgMae, medianLong(ttm), medianLong(ttf)));
            }
            out.put(e.getKey(), stats);
        }
        return out;
    }

    private static String bandLabel(String dimension, SignalTuningCsvLoader.OiShiftTrapSignalRow s) {
        return switch (dimension) {
            case "score" -> scoreBand(s.score());
            case "imbalance" -> imbalanceBand(s.imbalance());
            default -> proximityBand(s.proximityPct());
        };
    }

    private static String scoreBand(int score) {
        if (score < 60) return "50-59";
        if (score < 70) return "60-69";
        if (score < 80) return "70-79";
        return "80-90";
    }

    private static String imbalanceBand(double imb) {
        if (imb < 2.0) return "1.5-2.0";
        if (imb < 3.0) return "2.0-3.0";
        if (imb < 4.0) return "3.0-4.0";
        return "4.0+";
    }

    private static String proximityBand(double prox) {
        if (prox < 0.15) return "0.0-0.15";
        if (prox < 0.30) return "0.15-0.30";
        if (prox < 0.50) return "0.30-0.50";
        return "0.50-0.75";
    }

    private static DrawdownStats drawdownStats(List<TrapExitRow> exits) {
        if (exits.isEmpty()) {
            return DrawdownStats.empty();
        }
        List<Double> maes = exits.stream().map(TrapExitRow::mae).sorted().toList();
        Map<String, List<Double>> byScore = exits.stream()
                .collect(Collectors.groupingBy(e -> scoreBand(e.score()),
                        Collectors.mapping(TrapExitRow::mae, Collectors.toList())));
        Map<String, Percentiles> byScorePct = new LinkedHashMap<>();
        for (var e : byScore.entrySet()) {
            byScorePct.put(e.getKey(), percentiles(e.getValue()));
        }
        return new DrawdownStats(percentiles(maes), byScorePct);
    }

    private static Percentiles percentiles(List<Double> values) {
        if (values.isEmpty()) {
            return new Percentiles(0, 0, 0, 0);
        }
        List<Double> sorted = values.stream().sorted().toList();
        return new Percentiles(
                pct(sorted, 25), pct(sorted, 50), pct(sorted, 75), pct(sorted, 90));
    }

    private static double pct(List<Double> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = Math.min(sorted.size() - 1, Math.max(0, (int) Math.ceil(p / 100.0 * sorted.size()) - 1));
        return sorted.get(idx);
    }

    private static long medianLong(List<Long> sorted) {
        if (sorted.isEmpty()) return 0;
        return sorted.get(sorted.size() / 2);
    }

    private static List<ConfirmationComboStats> confirmationEffectiveness(
            List<SignalTuningCsvLoader.OiShiftTrapSignalRow> signals,
            Map<String, TrapExitRow> exitByKey,
            Map<String, TrapConfirmationRow> confirmByKey) {

        String[] gates = {
                "momentumDecelerating", "spotStalled", "oiStillBuilding", "oppositeOiFlushing",
                "priceRetraced", "volumeSpike", "pcrAligned"
        };
        List<ConfirmationComboStats> combos = new ArrayList<>();
        int n = gates.length;
        for (int mask = 1; mask < (1 << n); mask++) {
            if (Integer.bitCount(mask) > 3) continue;
            List<String> required = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) required.add(gates[i]);
            }
            List<TrapExitRow> retained = new ArrayList<>();
            List<TrapExitRow> rejected = new ArrayList<>();
            for (SignalTuningCsvLoader.OiShiftTrapSignalRow sig : signals) {
                TrapConfirmationRow c = confirmByKey.get(sig.decisionKey());
                TrapExitRow exit = exitByKey.get(sig.decisionKey());
                if (c == null || exit == null) continue;
                if (passes(c, required)) retained.add(exit);
                else rejected.add(exit);
            }
            if (retained.isEmpty() && rejected.isEmpty()) continue;
            double retainedPct = signals.isEmpty() ? 0 : retained.size() * 100.0 / signals.size();
            double avgMaeRet = retained.stream().mapToDouble(TrapExitRow::mae).average().orElse(0);
            double avgMaeRej = rejected.stream().mapToDouble(TrapExitRow::mae).average().orElse(0);
            double avgPnlRet = retained.stream().mapToDouble(x -> x.realizedPnl().doubleValue()).average().orElse(0);
            double avgPnlRej = rejected.stream().mapToDouble(x -> x.realizedPnl().doubleValue()).average().orElse(0);
            double score = (avgPnlRet - avgPnlRej) * Math.max(0, avgMaeRej - avgMaeRet);
            combos.add(new ConfirmationComboStats(
                    String.join("+", required), retained.size(), rejected.size(), retainedPct,
                    avgMaeRet, avgMaeRej, avgPnlRet, avgPnlRej, score));
        }
        combos.sort(Comparator.comparingDouble(ConfirmationComboStats::rankScore).reversed());
        return combos.stream().limit(10).toList();
    }

    private static boolean passes(TrapConfirmationRow c, List<String> required) {
        for (String g : required) {
            boolean ok = switch (g) {
                case "momentumDecelerating" -> c.momentumDecelerating();
                case "spotStalled" -> c.spotStalled();
                case "oiStillBuilding" -> c.oiStillBuilding();
                case "oppositeOiFlushing" -> c.oppositeOiFlushing();
                case "priceRetraced" -> c.priceRetraced();
                case "volumeSpike" -> c.volumeSpike();
                case "pcrAligned" -> c.pcrAligned();
                default -> false;
            };
            if (!ok) return false;
        }
        return true;
    }

    private static List<LateEntrySimRow> lateEntrySimulation(
            List<TrapForwardRow> forwards, Map<String, TrapExitRow> exitByKey) {
        List<LateEntrySimRow> rows = new ArrayList<>();
        String[] checkpoints = {"1m", "3m", "5m", "10m", "15m", "30m"};
        for (TrapForwardRow fwd : forwards) {
            TrapExitRow exit = exitByKey.get(fwd.decisionKey());
            if (exit == null) continue;
            for (String cp : checkpoints) {
                Double spotMove = fwd.spotMovePct(cp);
                Double oiDelta = fwd.trappedOiDelta(cp);
                if (spotMove == null || oiDelta == null) continue;
                boolean decel = spotMove > fwd.spotMovePct("1m") - 0.05;
                boolean oiBuild = oiDelta > 0;
                if (decel && oiBuild) {
                    rows.add(new LateEntrySimRow(
                            fwd.decisionKey(), cp, exit.mae(), exit.realizedPnl(),
                            spotMove, oiDelta));
                    break;
                }
            }
        }
        return rows;
    }

    private static Map<String, Double> slippageByScoreBand(
            List<SignalTuningCsvLoader.OiShiftTrapSignalRow> signals,
            Map<String, SignalTuningCsvLoader.ExecutionRow> entryExecByKey) {
        Map<String, List<BigDecimal>> slips = new LinkedHashMap<>();
        for (SignalTuningCsvLoader.OiShiftTrapSignalRow sig : signals) {
            SignalTuningCsvLoader.ExecutionRow exec = entryExecByKey.get(sig.decisionKey());
            BigDecimal slip = computeSlippagePct(sig.signalPremium(), exec);
            if (slip == null) {
                continue;
            }
            slips.computeIfAbsent(scoreBand(sig.score()), k -> new ArrayList<>()).add(slip);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : slips.entrySet()) {
            out.put(e.getKey(), e.getValue().stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0));
        }
        return out;
    }

    private static Map<String, Double> fillRatioByScoreBand(
            List<SignalTuningCsvLoader.OiShiftTrapSignalRow> signals,
            Map<String, SignalTuningCsvLoader.ExecutionRow> entryExecByKey) {
        Map<String, List<Double>> ratios = new LinkedHashMap<>();
        for (SignalTuningCsvLoader.OiShiftTrapSignalRow sig : signals) {
            SignalTuningCsvLoader.ExecutionRow exec = entryExecByKey.get(sig.decisionKey());
            if (exec == null || exec.requestedQuantity() <= 0) {
                continue;
            }
            double fillRatio = exec.filledQuantity() * 100.0 / exec.requestedQuantity();
            ratios.computeIfAbsent(scoreBand(sig.score()), k -> new ArrayList<>()).add(fillRatio);
        }
        Map<String, Double> out = new LinkedHashMap<>();
        for (var e : ratios.entrySet()) {
            out.put(e.getKey(), e.getValue().stream().mapToDouble(x -> x).average().orElse(0));
        }
        return out;
    }

    private static BigDecimal computeSlippagePct(BigDecimal signalPremium,
                                                 SignalTuningCsvLoader.ExecutionRow exec) {
        if (signalPremium == null || signalPremium.signum() <= 0 || exec == null) {
            return null;
        }
        BigDecimal fillPrice = exec.averageFillPrice();
        if (fillPrice == null || fillPrice.signum() <= 0) {
            return null;
        }
        return fillPrice.subtract(signalPremium)
                .multiply(BigDecimal.valueOf(100))
                .divide(signalPremium, 2, RoundingMode.HALF_UP);
    }

    private static List<TrapExitRow> loadExits() {
        return loadCsv(SIGNAL_DIR.resolve("oi-shift-trap-exits.csv"), OiShiftTrapTuningAnalyzer::parseExit);
    }

    private static List<TrapForwardRow> loadForwards() {
        return loadCsv(SIGNAL_DIR.resolve("oi-shift-trap-forward.csv"), OiShiftTrapTuningAnalyzer::parseForward);
    }

    private static List<TrapConfirmationRow> loadConfirmations() {
        return loadCsv(SIGNAL_DIR.resolve("oi-shift-trap-confirmations.csv"), OiShiftTrapTuningAnalyzer::parseConfirmation);
    }

    private static <T> List<T> loadCsv(Path path, RowParser<T> parser) {
        if (!Files.isRegularFile(path)) {
            return List.of();
        }
        List<T> rows = new ArrayList<>();
        try (InputStream in = Files.newInputStream(path)) {
            for (Map<String, String> r : SignalTuningCsvLoader.Csv.read(in).rows()) {
                rows.add(parser.parse(r));
            }
        } catch (IOException ex) {
            return List.of();
        }
        return rows;
    }

    @FunctionalInterface
    private interface RowParser<T> {
        T parse(Map<String, String> r);
    }

    private static TrapExitRow parseExit(Map<String, String> r) {
        return new TrapExitRow(
                r.getOrDefault("decisionKey", ""),
                parseDouble(r.get("mae")),
                parseLong(r.get("timeToMaeSec")),
                parseLong(r.get("timeToMfeSec")),
                decimal(r.get("realizedPnl")),
                (int) parseLong(r.get("score")));
    }

    private static TrapForwardRow parseForward(Map<String, String> r) {
        Map<String, Double> spotMove = new LinkedHashMap<>();
        Map<String, Double> oiDelta = new LinkedHashMap<>();
        for (String cp : OiShiftTrapForwardRecorder.CHECKPOINTS) {
            spotMove.put(cp, parseDoubleNull(r.get("spotMovePct_" + cp)));
            oiDelta.put(cp, parseDoubleNull(r.get("trappedOiDelta_" + cp)));
        }
        return new TrapForwardRow(r.getOrDefault("decisionKey", ""), spotMove, oiDelta);
    }

    private static TrapConfirmationRow parseConfirmation(Map<String, String> r) {
        return new TrapConfirmationRow(
                r.getOrDefault("decisionKey", ""),
                bool(r.get("confirm_momentumDecelerating")),
                bool(r.get("confirm_spotStalled")),
                bool(r.get("confirm_oiStillBuilding")),
                bool(r.get("confirm_oppositeOiFlushing")),
                bool(r.get("confirm_priceRetraced")),
                bool(r.get("confirm_volumeSpike")),
                bool(r.get("confirm_pcrAligned")));
    }

    private static double parseDouble(String v) {
        if (v == null || v.isBlank()) return 0;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException ex) { return 0; }
    }

    private static Double parseDoubleNull(String v) {
        if (v == null || v.isBlank()) return null;
        try { return Double.parseDouble(v.trim()); } catch (NumberFormatException ex) { return null; }
    }

    private static long parseLong(String v) {
        if (v == null || v.isBlank()) return 0;
        try { return Long.parseLong(v.trim().split("\\.")[0]); } catch (NumberFormatException ex) { return 0; }
    }

    private static BigDecimal decimal(String v) {
        if (v == null || v.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v.trim()); } catch (NumberFormatException ex) { return BigDecimal.ZERO; }
    }

    private static boolean bool(String v) {
        return v != null && ("true".equalsIgnoreCase(v.trim()) || "1".equals(v.trim()));
    }

    private static List<SignalTuningAnalyzer.Recommendation> buildRecommendations(
            long evalSamples, long trapSignalRows, long nearMissRows, long genericBuys,
            long noAffordable, long lowVolume, Map<String, Long> blockers, Map<String, Long> outcomes,
            Map<String, Long> nearMissByUnderlying, DrawdownStats drawdown, List<LateEntrySimRow> lateEntry) {

        List<SignalTuningAnalyzer.Recommendation> list = new ArrayList<>();

        if (trapSignalRows == 0 && genericBuys == 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_shift_trap",
                    "Zero OI Shift Trap BUY signals in period",
                    "Review oi-shift-trap-evaluations.csv primaryBlocker column"));
        }

        if (drawdown.overall().p50() < -10) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_shift_trap_drawdown",
                    "Median MAE " + fmt(drawdown.overall().p50()) + "% — early-entry drawdown confirmed",
                    "Run Eval 5 confirmation gate table before changing production gates"));
        }

        if (!lateEntry.isEmpty()) {
            double avgActualMae = lateEntry.stream().mapToDouble(LateEntrySimRow::actualMae).average().orElse(0);
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap_late_entry",
                    lateEntry.size() + " signal(s) with simulated delayed entry checkpoint",
                    "Median actual MAE " + fmt(avgActualMae) + "% — see late-entry table in report"));
        }

        if (noAffordable > 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.WARN, "oi_shift_trap_execution",
                    noAffordable + " NO_TRADE row(s) with noAffordableOption",
                    "Trap may fire but premium/risk caps block entry"));
        }

        String topBlocker = blockers.entrySet().stream()
                .max(Map.Entry.comparingByValue())
                .map(e -> e.getKey() + "(" + e.getValue() + ")")
                .orElse("—");
        if (!blockers.isEmpty()) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap_funnel",
                    evalSamples + " eval episode(s); top blocker: " + topBlocker,
                    "Episode-deduped evaluations with forward returns after 61 min"));
        }

        if (nearMissRows > 0) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap_near_miss",
                    nearMissRows + " near-miss strike(s) (score 40–49)",
                    "See oi-shift-trap-near-miss.csv"));
        }

        long flatTrend = outcomes.getOrDefault("TREND_FLAT", 0L);
        if (flatTrend > evalSamples * 0.25 && evalSamples > 5) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap",
                    flatTrend + " samples with TREND_FLAT outcome",
                    "Review detectShortTermTrend in chop"));
        }

        if (list.isEmpty()) {
            list.add(new SignalTuningAnalyzer.Recommendation(
                    SignalTuningAnalyzer.Severity.INFO, "oi_shift_trap",
                    trapSignalRows + " trap signal(s), " + genericBuys + " generic BUY(s)",
                    "Continue EOD review of exits + forward checkpoints"));
        }
        return list;
    }

    private static String fmt(double v) {
        return BigDecimal.valueOf(v).setScale(1, RoundingMode.HALF_UP).toPlainString();
    }

    record TrapExitRow(String decisionKey, double mae, long timeToMaeSec, long timeToMfeSec,
                       BigDecimal realizedPnl, int score) {}

    record TrapForwardRow(String decisionKey, Map<String, Double> spotMovePct, Map<String, Double> trappedOiDelta) {
        Double spotMovePct(String cp) { return spotMovePct.get(cp); }
        Double trappedOiDelta(String cp) { return trappedOiDelta.get(cp); }
    }

    record TrapConfirmationRow(String decisionKey, boolean momentumDecelerating, boolean spotStalled,
                               boolean oiStillBuilding, boolean oppositeOiFlushing, boolean priceRetraced,
                               boolean volumeSpike, boolean pcrAligned) {}

    record BucketStats(String index, String band, int signals, int closed, double winPct,
                       double avgPnl, double avgMae, long medianTimeToMae, long medianTimeToMfe) {}

    record Percentiles(double p25, double p50, double p75, double p90) {}

    record DrawdownStats(Percentiles overall, Map<String, Percentiles> byScoreBand) {
        static DrawdownStats empty() {
            return new DrawdownStats(new Percentiles(0, 0, 0, 0), Map.of());
        }
    }

    record ConfirmationComboStats(String gates, int retained, int rejected, double retainedPct,
                                  double avgMaeRetained, double avgMaeRejected,
                                  double avgPnlRetained, double avgPnlRejected, double rankScore) {}

    record LateEntrySimRow(String decisionKey, String checkpoint, double actualMae,
                           BigDecimal actualPnl, double spotMoveAtCp, double oiDeltaAtCp) {}

    record TrapReport(
            long evaluationSamples,
            long nearMissRows,
            long trapSignalRows,
            Map<String, Long> blockersByGate,
            Map<String, Long> outcomes,
            List<SignalTuningCsvLoader.OiShiftTrapNearMissRow> nearMisses,
            List<SignalTuningCsvLoader.OiShiftTrapSignalRow> signals,
            List<SignalTuningAnalyzer.Recommendation> recommendations,
            Map<String, List<BucketStats>> scoreBucketsByIndex,
            Map<String, List<BucketStats>> imbalanceBucketsByIndex,
            Map<String, List<BucketStats>> proximityBucketsByIndex,
            DrawdownStats drawdownStats,
            List<ConfirmationComboStats> confirmationCombos,
            List<LateEntrySimRow> lateEntrySimulation,
            Map<String, Double> slippageByScoreBand,
            Map<String, Double> fillRatioByScoreBand,
            long exitRows
    ) {
        static TrapReport empty() {
            return new TrapReport(0, 0, 0, Map.of(), Map.of(), List.of(), List.of(), List.of(),
                    Map.of(), Map.of(), Map.of(), DrawdownStats.empty(), List.of(), List.of(),
                    Map.of(), Map.of(), 0);
        }
    }
}
