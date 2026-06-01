package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 2 analyzer plugin for {@link StrategyType#OI_SHIFT_TRAP}. Contributes two
 * custom sections beyond the standard auto-breakdowns:
 *
 * <ol>
 *   <li><b>Confirmation-effectiveness ranker</b> — enumerates shadow-gate combinations
 *       (subsets of the 8 declared gates, max size 3) and ranks them by retained vs
 *       rejected PnL / MAE (Eval 5 design).</li>
 *   <li><b>Late-entry simulator</b> — scans forward checkpoints for the earliest
 *       deceleration + OI-build moment and compares actual vs simulated PnL for the
 *       worst-drawdown signals.</li>
 * </ol>
 */
@Component
public class OiShiftTrapAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapAnalyzerPlugin.class);

    /** Gate names from {@code OiShiftTrapCaptureAdapter#shadowGates()}. */
    static final String[] SHADOW_GATES = {
            "confirm_momentumDecelerating",
            "confirm_spotStalled",
            "confirm_oiStillBuilding",
            "confirm_oppositeOiFlushing",
            "confirm_priceRetraced",
            "confirm_proximityTightening",
            "confirm_volumeSpike",
            "confirm_pcrAligned"
    };

    static final String[] FORWARD_CHECKPOINTS = {"30s", "1m", "5m", "15m", "30m"};
    static final String[] FORWARD_SPOT_COLS = {
            "fwdSpot30s", "fwdSpot1m", "fwdSpot5m", "fwdSpot15m", "fwdSpot30m"
    };

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_SHIFT_TRAP;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_SHIFT_TRAP)) {
            return List.of();
        }
        List<AnalyzerSection> sections = new ArrayList<>();
        sections.add(confirmationEffectivenessRanker(query));
        sections.add(lateEntrySimulation(query));
        sections.add(ladderEffectiveness(query));
        return sections;
    }

    // ── Section 3: ladder effectiveness ──────────────────────────────────

    /**
     * Reads the {@code ladderEvent}, {@code ladderDiscountVsArm}, and per-tier
     * fill attributes from SIGNAL events and reports tier coverage, average
     * discount, and discount distribution. Empty when the ladder is not in
     * use (no SIGNAL events carry {@code ladderEvent}).
     */
    public AnalyzerSection ladderEffectiveness(TuningEventQuery query) {
        try {
            List<Path> signalFiles = query.store().listEventFiles(
                    StrategyType.OI_SHIFT_TRAP, TuningEventType.SIGNAL,
                    query.fromDate(), query.toDate());
            if (signalFiles.isEmpty()) {
                return AnalyzerSection.htmlOnly("Ladder effectiveness",
                        "<p><em>No SIGNAL events in the selected window.</em></p>");
            }
            String glob = globOf(signalFiles);
            String sql = "SELECT correlationKey, attr_extra "
                    + "FROM read_csv_auto([" + glob + "], header=true)";
            List<Map<String, Object>> raw = query.store().query(sql);

            int armCount = 0, fillCount = 0, cancelCount = 0;
            int tier1Hits = 0, tier2Hits = 0, tier3Hits = 0;
            List<Double> discounts = new ArrayList<>();
            Map<String, Integer> cancelReasons = new LinkedHashMap<>();

            for (Map<String, Object> r : raw) {
                String event = jsonString(r.get("attr_extra"), "ladderEvent");
                if (event == null) continue;
                if ("ARMED".equals(event)) armCount++;
                else if ("TIER_FILLED".equals(event)) {
                    fillCount++;
                    Double d = jsonDouble(r.get("attr_extra"), "ladderDiscountVsArm");
                    if (d != null) discounts.add(d);
                    Double t1 = jsonDouble(r.get("attr_extra"), "ladderTier1FillQty");
                    Double t2 = jsonDouble(r.get("attr_extra"), "ladderTier2FillQty");
                    Double t3 = jsonDouble(r.get("attr_extra"), "ladderTier3FillQty");
                    if (t1 != null && t1 > 0) tier1Hits++;
                    if (t2 != null && t2 > 0) tier2Hits++;
                    if (t3 != null && t3 > 0) tier3Hits++;
                } else if ("CANCELLED".equals(event)) {
                    cancelCount++;
                    String reason = jsonString(r.get("attr_extra"), "ladderCancelReason");
                    if (reason != null) {
                        cancelReasons.merge(reason, 1, Integer::sum);
                    }
                }
            }
            if (armCount == 0 && fillCount == 0 && cancelCount == 0) {
                return AnalyzerSection.htmlOnly("Ladder effectiveness",
                        "<p><em>No ladder events found — ladder is likely OFF.</em></p>");
            }
            double medDisc = median(discounts);
            double fillRate = armCount == 0 ? 0 : 100.0 * fillCount / Math.max(1, armCount);

            StringBuilder html = new StringBuilder(1024);
            html.append("<table class='ladder-summary'><thead><tr>")
                    .append("<th>Metric</th><th>Value</th></tr></thead><tbody>")
                    .append(row("Arms", armCount))
                    .append(row("Tier fills", fillCount))
                    .append(row("Cancellations", cancelCount))
                    .append(row("Fill rate (fills / arms)", String.format(Locale.ROOT, "%.1f%%", fillRate)))
                    .append(row("Tier 1 hits", tier1Hits))
                    .append(row("Tier 2 hits", tier2Hits))
                    .append(row("Tier 3 hits", tier3Hits))
                    .append(row("Median discount vs arm",
                            String.format(Locale.ROOT, "%.2f%%", medDisc * 100)))
                    .append("</tbody></table>");
            if (!cancelReasons.isEmpty()) {
                html.append("<h4>Cancel reasons</h4><table><thead><tr><th>Reason</th><th>Count</th></tr></thead><tbody>");
                for (var e : cancelReasons.entrySet()) {
                    html.append(row(e.getKey(), e.getValue()));
                }
                html.append("</tbody></table>");
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("arms", armCount);
            data.put("fills", fillCount);
            data.put("cancels", cancelCount);
            data.put("tier1Hits", tier1Hits);
            data.put("tier2Hits", tier2Hits);
            data.put("tier3Hits", tier3Hits);
            data.put("fillRatePct", fillRate);
            data.put("medianDiscount", medDisc);
            data.put("cancelReasons", cancelReasons);
            return new AnalyzerSection("Ladder effectiveness", html.toString(), data);
        } catch (TuningQueryException ex) {
            log.warn("[OiShiftTrapAnalyzerPlugin] ladder effectiveness failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("Ladder effectiveness",
                    "<p class='error'>Query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    private static String row(String label, Object value) {
        return "<tr><td>" + escape(label) + "</td><td>" + escape(String.valueOf(value)) + "</td></tr>";
    }

    private static String jsonString(Object jsonCol, String key) {
        if (jsonCol == null) return null;
        String json = jsonCol.toString();
        if (json.isBlank()) return null;
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) return null;
        int start = idx + needle.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '"') return null;
        int end = ++start;
        while (end < json.length() && json.charAt(end) != '"') end++;
        return end <= json.length() ? json.substring(start, end) : null;
    }

    // ── Section 1: confirmation-effectiveness ranker ──────────────────────

    public AnalyzerSection confirmationEffectivenessRanker(TuningEventQuery query) {
        try {
            List<Path> shadowFiles = query.store().listEventFiles(
                    StrategyType.OI_SHIFT_TRAP, TuningEventType.SHADOW_GATE,
                    query.fromDate(), query.toDate());
            if (shadowFiles.isEmpty()) {
                return AnalyzerSection.htmlOnly("Confirmation-effectiveness ranker",
                        "<p><em>No OI Shift Trap shadow-gate data in the selected window.</em></p>");
            }

            List<Path> exitFiles = query.store().listEventFiles(
                    StrategyType.OI_SHIFT_TRAP, TuningEventType.EXIT,
                    query.fromDate(), query.toDate());
            List<Path> signalFiles = query.store().listEventFiles(
                    StrategyType.OI_SHIFT_TRAP, TuningEventType.SIGNAL,
                    query.fromDate(), query.toDate());

            List<SignalOutcomeRow> rows = loadSignalOutcomes(query, shadowFiles, exitFiles);
            if (rows.isEmpty()) {
                return AnalyzerSection.htmlOnly("Confirmation-effectiveness ranker",
                        "<p><em>No signals with both shadow gates and exit outcomes.</em></p>");
            }

            int signalCount = countSignals(query, signalFiles);
            if (signalCount <= 0) {
                signalCount = rows.size();
            }

            List<ConfirmationComboRow> combos = rankConfirmationCombos(rows, signalCount);
            return renderConfirmationRanker(combos);
        } catch (TuningQueryException ex) {
            log.warn("[OiShiftTrapAnalyzerPlugin] confirmation ranker failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("Confirmation-effectiveness ranker",
                    "<p class='error'>Query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    List<SignalOutcomeRow> loadSignalOutcomes(TuningEventQuery query,
                                              List<Path> shadowFiles,
                                              List<Path> exitFiles) throws TuningQueryException {
        if (exitFiles.isEmpty()) {
            return List.of();
        }
        String shadowGlob = globOf(shadowFiles);
        String exitGlob = globOf(exitFiles);
        String pivotCols = buildGatePivotColumns();
        String sql = ""
                + "WITH gate_pivot AS ("
                + "  SELECT correlationKey, " + pivotCols
                + "  FROM read_csv_auto([" + shadowGlob + "], header=true)"
                + "  GROUP BY correlationKey"
                + "), exits AS ("
                + "  SELECT correlationKey, realizedPnlPct, maePct "
                + "  FROM read_csv_auto([" + exitGlob + "], header=true)"
                + ") "
                + "SELECT e.correlationKey, e.realizedPnlPct, e.maePct, " + selectGateAliases()
                + " FROM exits e JOIN gate_pivot p USING (correlationKey)";
        List<Map<String, Object>> raw = query.store().query(sql);
        List<SignalOutcomeRow> rows = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            boolean[] gates = new boolean[SHADOW_GATES.length];
            boolean complete = true;
            for (int i = 0; i < SHADOW_GATES.length; i++) {
                Boolean v = boolObj(r.get("g" + i));
                if (v == null) {
                    complete = false;
                    break;
                }
                gates[i] = v;
            }
            if (!complete) {
                continue;
            }
            double pnl = num(r.get("realizedPnlPct"));
            double mae = num(r.get("maePct"));
            rows.add(new SignalOutcomeRow((String) r.get("correlationKey"), pnl, mae, gates));
        }
        return rows;
    }

    static List<ConfirmationComboRow> rankConfirmationCombos(List<SignalOutcomeRow> rows, int signalCount) {
        List<ConfirmationComboRow> combos = new ArrayList<>();
        int n = SHADOW_GATES.length;
        for (int mask = 1; mask < (1 << n); mask++) {
            if (Integer.bitCount(mask) > 3) {
                continue;
            }
            List<String> required = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if ((mask & (1 << i)) != 0) {
                    required.add(SHADOW_GATES[i]);
                }
            }
            List<Double> retainedMae = new ArrayList<>();
            List<Double> rejectedMae = new ArrayList<>();
            List<Double> retainedPnl = new ArrayList<>();
            List<Double> rejectedPnl = new ArrayList<>();
            for (SignalOutcomeRow row : rows) {
                if (passes(row.gates(), mask)) {
                    retainedMae.add(Math.abs(row.maePct()));
                    retainedPnl.add(row.realizedPnlPct());
                } else {
                    rejectedMae.add(Math.abs(row.maePct()));
                    rejectedPnl.add(row.realizedPnlPct());
                }
            }
            if (retainedMae.isEmpty() && rejectedMae.isEmpty()) {
                continue;
            }
            double retainedMaeMedian = median(retainedMae);
            double rejectedMaeMedian = median(rejectedMae);
            double retainedPnlMedian = median(retainedPnl);
            double rejectedPnlMedian = median(rejectedPnl);
            double maeReduction = 0;
            if (Math.abs(rejectedMaeMedian) > 1e-9) {
                maeReduction = (rejectedMaeMedian - retainedMaeMedian) / Math.abs(rejectedMaeMedian);
            }
            double score = (retainedPnlMedian - rejectedPnlMedian) * Math.max(0, maeReduction);
            combos.add(new ConfirmationComboRow(
                    String.join("+", required),
                    retainedMae.size(),
                    rejectedMae.size(),
                    signalCount == 0 ? 0 : retainedMae.size() * 100.0 / signalCount,
                    retainedMaeMedian,
                    rejectedMaeMedian,
                    retainedPnlMedian,
                    rejectedPnlMedian,
                    score));
        }
        combos.sort(Comparator.comparingDouble(ConfirmationComboRow::score).reversed());
        return combos.stream().limit(10).toList();
    }

    AnalyzerSection renderConfirmationRanker(List<ConfirmationComboRow> combos) {
        if (combos.isEmpty()) {
            return AnalyzerSection.htmlOnly("Confirmation-effectiveness ranker",
                    "<p><em>No gate combinations produced retained/rejected splits.</em></p>");
        }
        StringBuilder html = new StringBuilder(1024);
        html.append("<table class='confirmation-ranker'><thead><tr>")
                .append("<th>Gates</th><th>Retained</th><th>Rejected</th><th>Retained %</th>")
                .append("<th>Retained MAE med</th><th>Retained PnL med</th>")
                .append("<th>Rejected PnL med</th><th>Score</th>")
                .append("</tr></thead><tbody>");
        for (ConfirmationComboRow c : combos) {
            html.append("<tr>")
                    .append("<td>").append(escape(c.gates())).append("</td>")
                    .append("<td>").append(c.retained()).append("</td>")
                    .append("<td>").append(c.rejected()).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.0f", c.retainedPct())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f", c.retainedMaeMedian())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f", c.retainedPnlMedian())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f", c.rejectedPnlMedian())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.2f", c.score())).append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>");
        List<Map<String, Object>> dataRows = combos.stream()
                .map(ConfirmationComboRow::toMap)
                .toList();
        return new AnalyzerSection("Confirmation-effectiveness ranker",
                html.toString(),
                Map.of("rows", dataRows));
    }

    // ── Section 2: late-entry simulation ──────────────────────────────────

    public AnalyzerSection lateEntrySimulation(TuningEventQuery query) {
        try {
            List<Path> forwardFiles = query.store().listEventFiles(
                    StrategyType.OI_SHIFT_TRAP, TuningEventType.FORWARD_CHECKPOINT,
                    query.fromDate(), query.toDate());
            List<Path> exitFiles = query.store().listEventFiles(
                    StrategyType.OI_SHIFT_TRAP, TuningEventType.EXIT,
                    query.fromDate(), query.toDate());
            if (forwardFiles.isEmpty() || exitFiles.isEmpty()) {
                return AnalyzerSection.htmlOnly("Late-entry simulation",
                        "<p><em>No forward checkpoint or exit data in the selected window.</em></p>");
            }

            List<Path> shadowFiles = query.store().listEventFiles(
                    StrategyType.OI_SHIFT_TRAP, TuningEventType.SHADOW_GATE,
                    query.fromDate(), query.toDate());
            List<Path> signalFiles = query.store().listEventFiles(
                    StrategyType.OI_SHIFT_TRAP, TuningEventType.SIGNAL,
                    query.fromDate(), query.toDate());

            List<LateEntryRow> rows = simulateLateEntries(
                    query, forwardFiles, exitFiles, shadowFiles, signalFiles);
            return renderLateEntry(rows);
        } catch (TuningQueryException ex) {
            log.warn("[OiShiftTrapAnalyzerPlugin] late-entry sim failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("Late-entry simulation",
                    "<p class='error'>Query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    List<LateEntryRow> simulateLateEntries(TuningEventQuery query,
                                           List<Path> forwardFiles,
                                           List<Path> exitFiles,
                                           List<Path> shadowFiles,
                                           List<Path> signalFiles) throws TuningQueryException {
        String forwardGlob = globOf(forwardFiles);
        String exitGlob = globOf(exitFiles);
        String shadowJoin = shadowFiles.isEmpty()
                ? ""
                : ", gate_pivot AS ("
                + "  SELECT correlationKey, "
                + "    MAX(CASE WHEN gateName = 'confirm_momentumDecelerating' "
                + "      THEN CAST(passed AS BOOLEAN) END) AS momentumGate, "
                + "    MAX(CASE WHEN gateName = 'confirm_oiStillBuilding' "
                + "      THEN CAST(passed AS BOOLEAN) END) AS oiGate "
                + "  FROM read_csv_auto([" + globOf(shadowFiles) + "], header=true)"
                + "  GROUP BY correlationKey"
                + ") ";
        String signalJoin = signalFiles.isEmpty()
                ? ""
                : ", signals AS ("
                + "  SELECT correlationKey, entryPremium "
                + "  FROM read_csv_auto([" + globOf(signalFiles) + "], header=true)"
                + ") ";
        String gateSelect = shadowFiles.isEmpty()
                ? "TRUE AS momentumGate, TRUE AS oiGate"
                : "COALESCE(g.momentumGate, FALSE) AS momentumGate, COALESCE(g.oiGate, FALSE) AS oiGate";
        String gateFrom = shadowFiles.isEmpty() ? "" : " LEFT JOIN gate_pivot g USING (correlationKey)";
        String premiumSelect = signalFiles.isEmpty()
                ? "NULL AS entryPremium"
                : "s.entryPremium";
        String premiumFrom = signalFiles.isEmpty() ? "" : " LEFT JOIN signals s USING (correlationKey)";

        String sql = ""
                + "WITH forwards AS ("
                + "  SELECT correlationKey, fwdSpot30s, fwdSpot1m, fwdSpot5m, fwdSpot15m, fwdSpot30m, attr_extra "
                + "  FROM read_csv_auto([" + forwardGlob + "], header=true)"
                + "), exits AS ("
                + "  SELECT correlationKey, realizedPnlPct, maePct, entryPrice, exitPrice "
                + "  FROM read_csv_auto([" + exitGlob + "], header=true)"
                + ") "
                + shadowJoin
                + signalJoin
                + "SELECT f.correlationKey, f.fwdSpot30s, f.fwdSpot1m, f.fwdSpot5m, f.fwdSpot15m, f.fwdSpot30m, "
                + "  f.attr_extra, e.realizedPnlPct, e.maePct, e.entryPrice, e.exitPrice, "
                + premiumSelect + ", " + gateSelect
                + " FROM forwards f"
                + " JOIN exits e USING (correlationKey)"
                + gateFrom
                + premiumFrom;

        List<Map<String, Object>> raw = query.store().query(sql);
        List<LateEntryRow> simulated = new ArrayList<>();
        for (Map<String, Object> r : raw) {
            if (!bool(r.get("momentumGate")) || !bool(r.get("oiGate"))) {
                continue;
            }
            Double spotAtSignal = jsonDouble(r.get("attr_extra"), "spotAtSignal");
            if (spotAtSignal == null || spotAtSignal <= 0) {
                continue;
            }
            Double spot1m = spotMovePct(numObj(r.get("fwdSpot1m")), spotAtSignal);
            if (spot1m == null) {
                continue;
            }
            LateEntryRow hit = null;
            for (int i = 0; i < FORWARD_CHECKPOINTS.length; i++) {
                Double spotMove = spotMovePct(numObj(r.get(FORWARD_SPOT_COLS[i])), spotAtSignal);
                if (spotMove == null) {
                    continue;
                }
                Double oiDelta = jsonDouble(r.get("attr_extra"),
                        "trappedOiDelta_" + FORWARD_CHECKPOINTS[i]);
                boolean decel = spotMove > spot1m - 0.05;
                boolean oiBuild = oiDelta != null ? oiDelta > 0 : bool(r.get("oiGate"));
                if (decel && oiBuild) {
                    double actualMae = Math.abs(num(r.get("maePct")));
                    double actualPnl = num(r.get("realizedPnlPct"));
                    double simPnl = simulateDelayedPnl(r, spotMove);
                    hit = new LateEntryRow(
                            (String) r.get("correlationKey"),
                            FORWARD_CHECKPOINTS[i],
                            actualMae,
                            actualPnl,
                            simPnl,
                            spotMove,
                            oiDelta != null ? oiDelta : 0);
                    break;
                }
            }
            if (hit != null) {
                simulated.add(hit);
            }
        }
        simulated.sort(Comparator.comparingDouble(LateEntryRow::actualMae).reversed());
        return simulated.stream().limit(20).toList();
    }

    static double simulateDelayedPnl(Map<String, Object> row, double spotMoveAtCp) {
        double actualPnl = num(row.get("realizedPnlPct"));
        double entryPrice = num(row.get("entryPrice"));
        double exitPrice = num(row.get("exitPrice"));
        Object premiumObj = row.get("entryPremium");
        double signalPremium = premiumObj == null || premiumObj.toString().isBlank()
                ? entryPrice : num(premiumObj);
        if (signalPremium <= 0 || exitPrice <= 0) {
            return actualPnl;
        }
        double delayedEntry = signalPremium * (1.0 + spotMoveAtCp / 100.0);
        if (delayedEntry <= 0) {
            return actualPnl;
        }
        return (exitPrice - delayedEntry) / delayedEntry * 100.0;
    }

    AnalyzerSection renderLateEntry(List<LateEntryRow> rows) {
        if (rows.isEmpty()) {
            return AnalyzerSection.htmlOnly("Late-entry simulation",
                    "<p><em>No late-entry checkpoints matched ideal confirmation gates.</em></p>");
        }
        StringBuilder html = new StringBuilder(1024);
        html.append("<table class='late-entry'><thead><tr>")
                .append("<th>Signal</th><th>Checkpoint</th><th>Actual MAE</th>")
                .append("<th>Actual PnL %</th><th>Sim PnL %</th>")
                .append("<th>Spot move %</th><th>OI delta</th>")
                .append("</tr></thead><tbody>");
        for (LateEntryRow r : rows) {
            html.append("<tr>")
                    .append("<td>").append(escape(r.correlationKey())).append("</td>")
                    .append("<td>").append(escape(r.checkpoint())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f", r.actualMae())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f", r.actualPnlPct())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f", r.simulatedPnlPct())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.2f", r.spotMoveAtCp())).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.0f", r.oiDeltaAtCp())).append("</td>")
                    .append("</tr>");
        }
        html.append("</tbody></table>");
        List<Map<String, Object>> dataRows = rows.stream().map(LateEntryRow::toMap).toList();
        return new AnalyzerSection("Late-entry simulation", html.toString(), Map.of("rows", dataRows));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String buildGatePivotColumns() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SHADOW_GATES.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("MAX(CASE WHEN gateName = '").append(SHADOW_GATES[i])
                    .append("' THEN CAST(passed AS BOOLEAN) END) AS g").append(i);
        }
        return sb.toString();
    }

    private static String selectGateAliases() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < SHADOW_GATES.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("p.g").append(i);
        }
        return sb.toString();
    }

    private static int countSignals(TuningEventQuery query, List<Path> signalFiles)
            throws TuningQueryException {
        if (signalFiles.isEmpty()) {
            return 0;
        }
        String sql = "SELECT COUNT(*) AS cnt FROM read_csv_auto(["
                + globOf(signalFiles) + "], header=true)";
        List<Map<String, Object>> rows = query.store().query(sql);
        if (rows.isEmpty()) {
            return 0;
        }
        return (int) num(rows.get(0).get("cnt"));
    }

    private static boolean passes(boolean[] gates, int mask) {
        for (int i = 0; i < gates.length; i++) {
            if ((mask & (1 << i)) != 0 && !gates[i]) {
                return false;
            }
        }
        return true;
    }

    static double median(List<Double> values) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        if (n % 2 == 1) {
            return sorted.get(n / 2);
        }
        return (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0;
    }

    private static Double spotMovePct(Double spotAtCp, double spotAtSignal) {
        if (spotAtCp == null || spotAtCp <= 0) {
            return null;
        }
        return (spotAtCp - spotAtSignal) / spotAtSignal * 100.0;
    }

    private static Double numObj(Object v) {
        if (v == null || v.toString().isBlank()) {
            return null;
        }
        return num(v);
    }

    private static double num(Object v) {
        if (v == null) {
            return 0;
        }
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private static boolean bool(Object v) {
        if (v == null) {
            return false;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        String s = v.toString().trim();
        return "true".equalsIgnoreCase(s) || "1".equals(s);
    }

    private static Boolean boolObj(Object v) {
        if (v == null) {
            return null;
        }
        return bool(v);
    }

    private static Double jsonDouble(Object jsonCol, String key) {
        if (jsonCol == null) {
            return null;
        }
        String json = jsonCol.toString();
        if (json.isBlank()) {
            return null;
        }
        String needle = "\"" + key + "\":";
        int idx = json.indexOf(needle);
        if (idx < 0) {
            return null;
        }
        int start = idx + needle.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}') {
                break;
            }
            end++;
        }
        try {
            return Double.parseDouble(json.substring(start, end).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    static String globOf(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("'").append(files.get(i).toString().replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    record SignalOutcomeRow(String correlationKey, double realizedPnlPct, double maePct, boolean[] gates) {}

    record ConfirmationComboRow(
            String gates,
            int retained,
            int rejected,
            double retainedPct,
            double retainedMaeMedian,
            double rejectedMaeMedian,
            double retainedPnlMedian,
            double rejectedPnlMedian,
            double score) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("gates", gates);
            m.put("retained", retained);
            m.put("rejected", rejected);
            m.put("retainedPct", retainedPct);
            m.put("retainedMaeMedian", retainedMaeMedian);
            m.put("rejectedMaeMedian", rejectedMaeMedian);
            m.put("retainedPnlMedian", retainedPnlMedian);
            m.put("rejectedPnlMedian", rejectedPnlMedian);
            m.put("score", score);
            return m;
        }
    }

    record LateEntryRow(
            String correlationKey,
            String checkpoint,
            double actualMae,
            double actualPnlPct,
            double simulatedPnlPct,
            double spotMoveAtCp,
            double oiDeltaAtCp) {

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("correlationKey", correlationKey);
            m.put("checkpoint", checkpoint);
            m.put("actualMae", actualMae);
            m.put("actualPnlPct", actualPnlPct);
            m.put("simulatedPnlPct", simulatedPnlPct);
            m.put("spotMoveAtCp", spotMoveAtCp);
            m.put("oiDeltaAtCp", oiDeltaAtCp);
            return m;
        }
    }
}
