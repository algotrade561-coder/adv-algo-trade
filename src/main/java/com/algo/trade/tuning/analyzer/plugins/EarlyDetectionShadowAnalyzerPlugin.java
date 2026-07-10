package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Early-detection shadow analyzer — folds the SHADOW OI-velocity early detector (Workstream D) and the
 * head-to-head OI-prediction shadow into the on-demand tuning report, so the detector's edge is decided
 * from the same "Generate report" output as everything else (not a separate Microstructure page).
 *
 * <p>The detector writes <b>virtual</b> signals to {@code data/tuning/early-detection-shadow-<date>.csv}
 * (it has no order path). Those files live OUTSIDE the unified event store, so the standard analyzers
 * never see them. This plugin reads them directly for the report's date range and answers the two
 * questions that gate a go-live decision:</p>
 *
 * <ol>
 *   <li><b>Does the detector lead real price?</b> Each virtual signal's 30-minute forward <i>favorable</i>
 *       excursion (in the signalled direction) is measured against the per-minute spot path reconstructed
 *       from the OI-Momentum evaluation events. Reported as hit-rate (≥0.30% / ≥0.40%) and average
 *       favorable move, per index and per trigger. A flat/low hit-rate ⇒ the detector is noise; a high
 *       one ⇒ it's catching ignition the matrix is missing.</li>
 *   <li><b>Which detector predicts OI better?</b> From {@code oi-prediction-shadow-<date>.csv}, the
 *       synthetic (volume) vs early (OI-sampled) detector accuracy at calling the next real OI print.</li>
 * </ol>
 *
 * <p>Fail-safe: if the shadow files don't exist yet (detector disabled, or no clean session captured),
 * each section renders an inline notice rather than breaking the report.</p>
 */
@Component
public class EarlyDetectionShadowAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(EarlyDetectionShadowAnalyzerPlugin.class);

    /** Where the shadow recorders write their daily CSVs (mirrors EarlyDetectionShadowRecorder). */
    @Value("${tuning.shadow-dir:data/tuning}")
    private String shadowDir;

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    /** After the entry-miss (75) / decision-record (80) sections — it complements the catch-side analysis. */
    @Override
    public int order() {
        return 78;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(forwardEdgeSection(query), predictionHeadToHeadSection(query));
    }

    // ── Section 1: does the early detector lead real price? ────────────────────

    AnalyzerSection forwardEdgeSection(TuningEventQuery query) {
        final String title = "Early detector — does it lead real moves? (shadow)";
        try {
            List<Path> files = shadowFiles("early-detection-shadow-", query);
            if (files.isEmpty()) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No early-detection shadow data for this window. Enable "
                        + "<code>oi-momentum.early-detection.enabled</code> (shadow-only) so virtual signals "
                        + "are logged, then re-run after a session.</em></p>");
            }
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION)) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>Shadow signals are present but there is no OI-Momentum evaluation capture in "
                        + "this window to reconstruct the spot path — forward outcomes can't be computed. "
                        + "Deploy the per-second eval capture and re-run.</em></p>");
            }

            String evalSrc = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION);
            String shadow = "read_csv_auto([" + globOf(files) + "], header=true, union_by_name=true)";
            // Spot path per index from eval events; signal base spot from the shadow row itself.
            // Forward favorable excursion = dir * (fwdSpot - base)/base over (sm, sm+30] minutes, same index.
            String base = ""
                    + "WITH spotmin AS ("
                    + "  SELECT index AS idx, "
                    + "    CAST(epoch(date_trunc('minute', TRY_CAST(eventTime AS TIMESTAMP))) AS BIGINT)/60 AS me, "
                    + "    AVG(CASE WHEN TRY_CAST(json_extract_string(attr_extra,'$.spot') AS DOUBLE) > 0 "
                    + "             THEN TRY_CAST(json_extract_string(attr_extra,'$.spot') AS DOUBLE) END) AS spot "
                    + "  FROM " + evalSrc + " GROUP BY 1,2"
                    + "), sig AS ("
                    + "  SELECT index AS idx, CAST(recvEpochMs AS BIGINT)/60000 AS sm, "
                    + "    TRY_CAST(direction AS INTEGER) AS dir, TRY_CAST(spot AS DOUBLE) AS base, "
                    + "    COALESCE(NULLIF(CAST(trigger AS VARCHAR),''),'(none)') AS trig "
                    + "  FROM " + shadow
                    + "  WHERE TRY_CAST(spot AS DOUBLE) > 0 AND TRY_CAST(direction AS INTEGER) <> 0"
                    + "), fwd AS ("
                    + "  SELECT s.idx, s.trig, s.sm, s.dir, s.base, "
                    + "    MAX(s.dir * (m.spot - s.base)/s.base*100.0) AS mfe "
                    + "  FROM sig s JOIN spotmin m "
                    + "    ON m.idx = s.idx AND m.me > s.sm AND m.me <= s.sm + 30 AND m.spot > 0 "
                    + "  GROUP BY 1,2,3,4,5"
                    + ") ";
            String byIndex = base
                    + "SELECT idx AS index, COUNT(*) AS signals, "
                    + "  ROUND(AVG(mfe),3) AS avg_fwd_favorable_pct, "
                    + "  ROUND(100.0*AVG(CASE WHEN mfe >= 0.30 THEN 1 ELSE 0 END),1) AS hit_030_pct, "
                    + "  ROUND(100.0*AVG(CASE WHEN mfe >= 0.40 THEN 1 ELSE 0 END),1) AS hit_040_pct "
                    + "FROM fwd GROUP BY 1 ORDER BY signals DESC";
            String byTrigger = base
                    + "SELECT trig AS trigger, COUNT(*) AS signals, "
                    + "  ROUND(AVG(mfe),3) AS avg_fwd_favorable_pct, "
                    + "  ROUND(100.0*AVG(CASE WHEN mfe >= 0.30 THEN 1 ELSE 0 END),1) AS hit_030_pct "
                    + "FROM fwd GROUP BY 1 ORDER BY signals DESC";

            List<Map<String, Object>> idxRows = query.store().query(byIndex);
            List<Map<String, Object>> trigRows = query.store().query(byTrigger);

            StringBuilder h = new StringBuilder(1024);
            h.append("<p>Virtual early-detection signals scored by their 30-min <b>forward favorable</b> spot "
                    + "move (in the signalled direction), against the eval-reconstructed spot path. "
                    + "hit% = share reaching that move. A flat/low hit-rate ⇒ the detector is a coin-flip; "
                    + "a high one ⇒ it's catching ignition worth wiring live. <em>Shadow only — no orders.</em></p>");
            h.append("<h4>By index</h4>").append(tableHtml(idxRows));
            h.append("<h4>By trigger</h4>").append(tableHtml(trigRows));
            return AnalyzerSection.htmlOnly(title, h.toString());
        } catch (TuningQueryException ex) {
            log.warn("[EarlyDetectionShadowAnalyzerPlugin] forward-edge query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Query failed: " + escape(ex.getMessage()) + "</p>");
        } catch (Exception ex) {
            log.warn("[EarlyDetectionShadowAnalyzerPlugin] forward-edge section failed: {}", ex.toString());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Section failed: " + escape(String.valueOf(ex.getMessage())) + "</p>");
        }
    }

    // ── Section 2: synthetic vs early detector — who predicts OI better? ────────

    AnalyzerSection predictionHeadToHeadSection(TuningEventQuery query) {
        final String title = "OI-prediction shadow — synthetic vs early detector (accuracy)";
        try {
            List<Path> files = shadowFiles("oi-prediction-shadow-", query);
            if (files.isEmpty()) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No OI-prediction shadow data for this window. Enable "
                        + "<code>oi-prediction-shadow.enabled</code> (and both detectors) to log the "
                        + "head-to-head, then re-run after a session.</em></p>");
            }
            String src = "read_csv_auto([" + globOf(files) + "], header=true, union_by_name=true)";
            // synthHit / earlyHit are written as booleans/strings — normalise to 1/0.
            String hit = "CASE WHEN lower(CAST(%s AS VARCHAR)) IN ('true','1','y','yes') THEN 1 ELSE 0 END";
            String sql = ""
                    + "WITH p AS (SELECT * FROM " + src + ") "
                    + "SELECT index AS index, COUNT(*) AS predictions, "
                    + "  ROUND(100.0*AVG(" + String.format(hit, "synthHit") + "),1) AS synthetic_acc_pct, "
                    + "  ROUND(100.0*AVG(" + String.format(hit, "earlyHit") + "),1) AS early_acc_pct "
                    + "FROM p GROUP BY 1 ORDER BY predictions DESC";
            List<Map<String, Object>> rows = query.store().query(sql);
            StringBuilder h = new StringBuilder(512);
            h.append("<p>Each row = one OI-print interval. Accuracy = share where the detector's predicted "
                    + "direction matched the next <b>real</b> OI print. The higher column is the better "
                    + "next-print predictor; ~50% is a coin-flip. <em>Research log — no orders.</em></p>");
            h.append(tableHtml(rows));
            return AnalyzerSection.htmlOnly(title, h.toString());
        } catch (TuningQueryException ex) {
            log.warn("[EarlyDetectionShadowAnalyzerPlugin] prediction query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Query failed: " + escape(ex.getMessage()) + "</p>");
        } catch (Exception ex) {
            log.warn("[EarlyDetectionShadowAnalyzerPlugin] prediction section failed: {}", ex.toString());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Section failed: " + escape(String.valueOf(ex.getMessage())) + "</p>");
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Existing daily shadow CSVs ({@code <prefix><date>.csv}) within the report's [from,to] range. */
    private List<Path> shadowFiles(String prefix, TuningEventQuery query) {
        List<Path> files = new ArrayList<>();
        LocalDate from = query.fromDate();
        LocalDate to = query.toDate();
        if (from == null || to == null) {
            return files;
        }
        Path dir = Path.of(shadowDir);
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Path f = dir.resolve(prefix + d + ".csv");
            if (Files.isRegularFile(f)) {
                files.add(f);
            }
        }
        return files;
    }

    private static String globOf(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('\'').append(files.get(i).toString().replace("\\", "/").replace("'", "''")).append('\'');
        }
        return sb.toString();
    }

    static String tableHtml(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "<p><em>No rows.</em></p>";
        }
        Map<String, Object> first = rows.get(0);
        StringBuilder html = new StringBuilder("<table><thead><tr>");
        for (String col : first.keySet()) {
            html.append("<th>").append(escape(col)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (Map<String, Object> row : rows) {
            html.append("<tr>");
            for (String col : first.keySet()) {
                Object v = row.get(col);
                html.append("<td>").append(v == null ? "" : escape(String.valueOf(v))).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
