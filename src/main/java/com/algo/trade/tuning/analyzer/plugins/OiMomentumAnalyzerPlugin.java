package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 2 analyzer plugin for {@link StrategyType#OI_MOMENTUM}. Contributes two
 * custom sections beyond the standard auto-breakdowns:
 *
 * <ol>
 *   <li><b>Matrix-case × bias-score heatmap</b> — 2D table showing fire count and
 *       average realized PnL per (matrixCase, biasBand) cell. Reveals which case +
 *       bias-score combinations win and lose money.</li>
 *   <li><b>V3-vs-legacy concordance</b> — joins V3 decisions and legacy detections
 *       within ±2 sec timestamps; renders a confusion matrix of who said what.
 *       Phase 2 reads V3/legacy from their existing {@code data/v3-decisions/} and
 *       {@code data/oi-decisions/} CSV files — Phase 6 cleanup migrates these into
 *       the unified event store.</li>
 * </ol>
 *
 * <h2>Failure mode</h2>
 * Each section runs in its own try-catch. If a query fails (e.g. no data, malformed
 * CSV), the section renders an inline error message rather than failing the whole
 * report.
 */
@Component
public class OiMomentumAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(OiMomentumAnalyzerPlugin.class);

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        List<AnalyzerSection> sections = new ArrayList<>();
        sections.add(matrixCaseBiasHeatmap(query));
        sections.add(v3VsLegacyConcordance(query));
        return sections;
    }

    // ── Section 1: matrix-case × bias-score heatmap ───────────────────────

    public AnalyzerSection matrixCaseBiasHeatmap(TuningEventQuery query) {
        try {
            List<Path> signalFiles = query.store().listEventFiles(
                    StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL,
                    query.fromDate(), query.toDate());
            List<Path> exitFiles = query.store().listEventFiles(
                    StrategyType.OI_MOMENTUM, TuningEventType.EXIT,
                    query.fromDate(), query.toDate());
            if (signalFiles.isEmpty()) {
                return AnalyzerSection.htmlOnly("Matrix-case × bias-score heatmap",
                        "<p><em>No OI Momentum signal data in the selected window.</em></p>");
            }

            // DuckDB query: join signal + exit by correlationKey, extract attrs from JSON,
            // group by (matrixCase, biasBand).
            String signalGlob = globOf(signalFiles);
            String exitJoin = exitFiles.isEmpty()
                    ? "(SELECT NULL AS correlationKey, 0.0 AS realizedPnlPct WHERE 1=0)"
                    : "(SELECT correlationKey, realizedPnlPct FROM read_csv_auto(['"
                        + String.join("','", exitFiles.stream().map(Path::toString).toList())
                        + "'], header=true))";

            String sql = ""
                    + "WITH signals AS ("
                    + "  SELECT correlationKey, "
                    + "    json_extract_string(attr_extra, '$.matrixCase') AS matrixCase, "
                    + "    TRY_CAST(json_extract_string(attr_extra, '$.biasScore') AS DOUBLE) AS biasScore "
                    + "  FROM read_csv_auto([" + signalGlob + "], header=true)"
                    + "), joined AS ("
                    + "  SELECT s.matrixCase, s.biasScore, e.realizedPnlPct"
                    + "  FROM signals s LEFT JOIN " + exitJoin + " e USING (correlationKey)"
                    + "), banded AS ("
                    + "  SELECT COALESCE(matrixCase, 'UNKNOWN') AS matrixCase,"
                    + "    CASE "
                    + "      WHEN biasScore IS NULL THEN 'unknown' "
                    + "      WHEN biasScore < 30 THEN '<30' "
                    + "      WHEN biasScore < 40 THEN '30–39' "
                    + "      WHEN biasScore < 50 THEN '40–49' "
                    + "      WHEN biasScore < 60 THEN '50–59' "
                    + "      WHEN biasScore < 70 THEN '60–69' "
                    + "      WHEN biasScore < 80 THEN '70–79' "
                    + "      ELSE '80+' "
                    + "    END AS biasBand,"
                    + "    realizedPnlPct"
                    + "  FROM joined"
                    + ")"
                    + "SELECT matrixCase, biasBand, COUNT(*) AS cnt, "
                    + "  AVG(realizedPnlPct) AS avgPnl "
                    + "FROM banded GROUP BY matrixCase, biasBand "
                    + "ORDER BY matrixCase, biasBand";

            List<Map<String, Object>> rows = query.store().query(sql);
            return renderHeatmap(rows);
        } catch (TuningQueryException ex) {
            log.warn("[OiMomentumAnalyzerPlugin] heatmap query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("Matrix-case × bias-score heatmap",
                    "<p class='error'>Query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    AnalyzerSection renderHeatmap(List<Map<String, Object>> rows) {
        // Build matrixCase → (biasBand → {cnt, avgPnl})
        Map<String, Map<String, double[]>> matrix = new TreeMap<>();
        java.util.Set<String> allBands = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : rows) {
            String mc = (String) r.get("matrixCase");
            String band = (String) r.get("biasBand");
            double cnt = ((Number) r.get("cnt")).doubleValue();
            Object pnlObj = r.get("avgPnl");
            double pnl = pnlObj == null ? Double.NaN : ((Number) pnlObj).doubleValue();
            matrix.computeIfAbsent(mc, k -> new TreeMap<>()).put(band, new double[]{cnt, pnl});
            allBands.add(band);
        }
        if (matrix.isEmpty()) {
            return AnalyzerSection.htmlOnly("Matrix-case × bias-score heatmap",
                    "<p><em>No signals matched the query.</em></p>");
        }

        StringBuilder html = new StringBuilder(1024);
        html.append("<table class='heatmap'><thead><tr><th>matrixCase ↓ / biasBand →</th>");
        for (String b : allBands) html.append("<th>").append(b).append("</th>");
        html.append("</tr></thead><tbody>");
        for (var entry : matrix.entrySet()) {
            html.append("<tr><th>").append(escape(entry.getKey())).append("</th>");
            for (String band : allBands) {
                double[] cell = entry.getValue().get(band);
                if (cell == null) {
                    html.append("<td>—</td>");
                } else {
                    String pnlStr = Double.isNaN(cell[1]) ? "n/a"
                            : String.format(Locale.ROOT, "%.1f%%", cell[1]);
                    html.append("<td>").append((int) cell[0])
                            .append("<br/><small>").append(pnlStr).append("</small></td>");
                }
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return new AnalyzerSection("Matrix-case × bias-score heatmap",
                html.toString(),
                Map.of("rows", rows));
    }

    // ── Section 2: V3-vs-legacy concordance ───────────────────────────────

    public AnalyzerSection v3VsLegacyConcordance(TuningEventQuery query) {
        // V3DecisionRecorder + LegacyDetectionRecorder were retired in Phase 6 — their
        // CSV files are no longer written, so concordance can never have data. The
        // section is kept (the report layout still expects it) but always renders the
        // placeholder. Section can be removed entirely once the report HTML template
        // stops referencing it.
        return AnalyzerSection.htmlOnly("V3-vs-legacy concordance",
                "<p><em>V3 or legacy decision files not present — concordance unavailable.</em></p>");
    }

    AnalyzerSection renderConcordance(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return AnalyzerSection.htmlOnly("V3-vs-legacy concordance",
                    "<p><em>No matched decisions in the selected window.</em></p>");
        }
        // Build V3 → legacy → count matrix
        Map<String, Map<String, Long>> grid = new TreeMap<>();
        java.util.Set<String> legacyVerdicts = new java.util.TreeSet<>();
        for (Map<String, Object> r : rows) {
            String v3 = (String) r.get("v3Verdict");
            String lg = (String) r.get("legacyDecision");
            long cnt = ((Number) r.get("cnt")).longValue();
            grid.computeIfAbsent(v3, k -> new LinkedHashMap<>()).put(lg, cnt);
            legacyVerdicts.add(lg);
        }
        StringBuilder html = new StringBuilder();
        html.append("<table class='concordance'><thead><tr><th>V3 ↓ / Legacy →</th>");
        for (String l : legacyVerdicts) html.append("<th>").append(escape(l)).append("</th>");
        html.append("</tr></thead><tbody>");
        for (var entry : grid.entrySet()) {
            html.append("<tr><th>").append(escape(entry.getKey())).append("</th>");
            for (String l : legacyVerdicts) {
                Long cnt = entry.getValue().get(l);
                html.append("<td>").append(cnt == null ? "0" : cnt.toString()).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return new AnalyzerSection("V3-vs-legacy concordance",
                html.toString(), Map.of("rows", rows));
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static String globOf(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("'").append(files.get(i).toString().replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
