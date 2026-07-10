package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * <b>Predictive scorecard — does conviction predict the forward move OUT-OF-SAMPLE?</b> (2026-07-02)
 *
 * <p>The other edge sections (e.g. {@link OiSignalEdgeAnalyzerPlugin}) band a feature vs the forward move
 * <em>in-sample</em> — pooled over every day in the window. That's descriptive: it will always find <em>some</em>
 * band that looks better, even on noise. This section adds the three things needed to turn description into a
 * validated edge:</p>
 * <ol>
 *   <li><b>Out-of-sample split</b> — the window's days are split into an earlier <em>in-sample</em> half and a
 *       later <em>holdout</em> half. An edge that shows in-sample but flattens on the holdout is overfit, not
 *       real. (Single-day windows can't split — the section says so.)</li>
 *   <li><b>Null baseline</b> — every conviction band is shown against the pooled "ALL signals" average for the
 *       same phase. A band only "predicts" if it beats the baseline by more than its sample noise.</li>
 *   <li><b>Verdict</b> — a plain-English call: does the top conviction band beat the baseline AND hold on the
 *       holdout? If not, conviction is a coin-flip here and sizing up on it is noise.</li>
 * </ol>
 *
 * <p>Bands the captured {@code operatorScore} (the V3 conviction driver) against the 30-min forward MFE + a
 * hit-rate (share with |move| ≥ 0.30%). Reads signals ⋈ forward_checkpoint by correlationKey. Fail-safe.</p>
 *
 * <p><b>Cost caveat:</b> the forward move is a <em>gross</em> underlying %; option round-trip costs are not
 * subtracted here (they live in option-premium terms). Treat a band as tradeable only if its edge is both large
 * AND holds out-of-sample — a marginal in-sample-only edge will not survive costs.</p>
 */
@Component
public class PredictiveScorecardAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(PredictiveScorecardAnalyzerPlugin.class);

    /** |forward 30m move| at/above this (%) counts as a tradeable-size "hit". Matches OiSignalEdge. */
    private static final double HIT_PCT = 0.30;
    /** Minimum joined rows in a phase before we trust the numbers (below this = "thin, not conclusive"). */
    private static final int MIN_N_FOR_VERDICT = 15;

    @Override public StrategyType strategy() { return StrategyType.OI_MOMENTUM; }
    @Override public int order() { return 61; } // right after OiSignalEdge (60)

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) return List.of();
        return List.of(scorecard(query));
    }

    AnalyzerSection scorecard(TuningEventQuery query) {
        final String title = "Predictive scorecard — does conviction predict OUT-OF-SAMPLE?";
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL)
                    || !EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT)) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>Needs OI-momentum signals with ripened forward checkpoints in the window. "
                        + "Lights up once a session's signals have aged ≈31 min and the forward sweep has run.</em></p>");
            }
            String sig = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL);
            String fwd = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT);

            List<Map<String, Object>> rows = query.store().query(scorecardQuery(sig, fwd));
            if (rows.isEmpty()) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No joined signal⋈forward rows with an operatorScore in the window yet.</em></p>");
            }

            // Group rows by phase → (band → row). NULL band = the pooled baseline.
            Map<String, Map<String, Map<String, Object>>> byPhase = new LinkedHashMap<>();
            boolean singleDay = false;
            for (Map<String, Object> r : rows) {
                String phase = str(r.get("phase"));
                if ("all".equals(phase)) singleDay = true;
                String band = r.get("band") == null ? "ALL (baseline)" : str(r.get("band"));
                byPhase.computeIfAbsent(phase, k -> new LinkedHashMap<>()).put(band, r);
            }

            StringBuilder h = new StringBuilder(2048);
            h.append("<p>Conviction (<code>operatorScore</code>) vs the 30-min forward move, <b>split into an "
                    + "earlier in-sample half and a later holdout half</b>. hit% = share with |move| ≥ ")
                    .append(pct(HIT_PCT)).append(". A real edge <b>rises with the band AND repeats on the holdout</b>; "
                    + "a band that only wins in-sample is overfit. Every band is shown against the pooled baseline.</p>");

            if (singleDay) {
                h.append("<p><b>⚠ Single-day window</b> — can't split in/out-of-sample. Showing pooled only; "
                        + "run the report over ≥ 2 trading days (a week is ideal) for the out-of-sample verdict.</p>");
                h.append(renderPhaseTable("Pooled (all signals in window)", byPhase.get("all")));
            } else {
                Map<String, Map<String, Object>> in = byPhase.get("1_in-sample");
                Map<String, Map<String, Object>> out = byPhase.get("2_holdout");
                h.append(renderPhaseTable("In-sample (earlier days — where you'd tune)", in));
                h.append(renderPhaseTable("Holdout (later days — the honest test)", out));
                h.append(verdict(in, out));
            }
            return new AnalyzerSection(title, h.toString(), Map.of("rows", rows));
        } catch (TuningQueryException ex) {
            log.warn("[PredictiveScorecard] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Predictive-scorecard query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    /** Signal⋈forward join, split by day into in-sample/holdout, banded by operatorScore, with a pooled baseline. */
    private String scorecardQuery(String sigSource, String fwdSource) {
        return ""
            + "WITH sig AS ("
            + "  SELECT correlationKey, CAST(eventTime AS DATE) AS d, "
            + "         TRY_CAST(json_extract_string(attr_extra,'$.operatorScore') AS DOUBLE) AS score "
            + "  FROM " + sigSource
            + "), fwd AS ("
            + "  SELECT correlationKey, TRY_CAST(fwdMfe30mPct AS DOUBLE) AS mfe, TRY_CAST(fwdMae30mPct AS DOUBLE) AS mae "
            + "  FROM " + fwdSource
            + "), j AS ("
            + "  SELECT s.score, s.d, f.mfe, f.mae "
            + "  FROM sig s JOIN fwd f USING (correlationKey) "
            + "  WHERE f.mfe IS NOT NULL AND s.score IS NOT NULL"
            + "), bounds AS (SELECT MIN(d) AS mn, MAX(d) AS mx FROM j), "
            + "split AS ("
            + "  SELECT j.mfe, j.mae, "
            + "    CASE WHEN (SELECT mx FROM bounds) = (SELECT mn FROM bounds) THEN 'all' "
            + "         WHEN j.d <= (SELECT mn + CAST((mx - mn)/2 AS INTEGER) FROM bounds) THEN '1_in-sample' "
            + "         ELSE '2_holdout' END AS phase, "
            + "    CASE WHEN j.score < 40 THEN '1:<40' WHEN j.score < 60 THEN '2:40–59' "
            + "         WHEN j.score < 80 THEN '3:60–79' ELSE '4:80+' END AS band, "
            + "    CASE WHEN ABS(j.mfe) >= " + HIT_PCT + " THEN 1 ELSE 0 END AS hit "
            + "  FROM j"
            + ") "
            + "SELECT phase, band, COUNT(*) AS n, "
            + "  ROUND(AVG(ABS(mfe)),3) AS avg_mfe, ROUND(AVG(ABS(mae)),3) AS avg_mae, "
            + "  ROUND(100.0*AVG(hit),1) AS hit_pct, ROUND(AVG(ABS(mfe))-AVG(ABS(mae)),3) AS net_tilt "
            + "FROM split GROUP BY GROUPING SETS ((phase, band), (phase)) "
            + "ORDER BY phase, band NULLS FIRST";
    }

    private String renderPhaseTable(String heading, Map<String, Map<String, Object>> bands) {
        StringBuilder h = new StringBuilder(768);
        h.append("<h4>").append(escape(heading)).append("</h4>");
        if (bands == null || bands.isEmpty()) {
            return h.append("<p><em>No rows in this phase.</em></p>").toString();
        }
        h.append("<table class='edge'><thead><tr><th>operatorScore band</th><th>n</th>"
                + "<th>avg fwd MFE</th><th>avg fwd MAE</th><th>hit %</th><th>net tilt</th></tr></thead><tbody>");
        // baseline first, then bands ascending
        Map<String, Object> base = bands.get("ALL (baseline)");
        for (Map.Entry<String, Map<String, Object>> e : bands.entrySet()) {
            String band = e.getKey();
            Map<String, Object> r = e.getValue();
            boolean isBase = "ALL (baseline)".equals(band);
            String cleanBand = band.contains(":") ? band.substring(band.indexOf(':') + 1) : band;
            h.append(isBase ? "<tr style='font-weight:600;background:#f4f4f4'>" : "<tr>")
                    .append("<td>").append(escape(cleanBand)).append("</td>")
                    .append("<td>").append(asLong(r.get("n"))).append("</td>")
                    .append("<td>").append(f2(r.get("avg_mfe"))).append("</td>")
                    .append("<td>").append(f2(r.get("avg_mae"))).append("</td>")
                    .append("<td>").append(f1(r.get("hit_pct"))).append("</td>")
                    .append("<td>").append(f2signed(r.get("net_tilt"))).append("</td></tr>");
        }
        h.append("</tbody></table>");
        return h.toString();
    }

    /** The plain-English out-of-sample call: does the top band beat baseline AND repeat on the holdout? */
    private String verdict(Map<String, Map<String, Object>> in, Map<String, Map<String, Object>> out) {
        Map<String, Object> outTop = out == null ? null : out.get("4:80+");
        Map<String, Object> outBase = out == null ? null : out.get("ALL (baseline)");
        Map<String, Object> inTop = in == null ? null : in.get("4:80+");
        Map<String, Object> inBase = in == null ? null : in.get("ALL (baseline)");
        if (outTop == null || outBase == null) {
            return "<p><b>Verdict:</b> not enough holdout data in the top (80+) band to judge — "
                    + "widen the window or wait for more high-conviction signals.</p>";
        }
        long outTopN = asLong(outTop.get("n"));
        double outTopHit = asDouble(outTop.get("hit_pct"));
        double outBaseHit = asDouble(outBase.get("hit_pct"));
        double inTopEdge = (inTop != null && inBase != null)
                ? asDouble(inTop.get("hit_pct")) - asDouble(inBase.get("hit_pct")) : 0.0;
        double outTopEdge = outTopHit - outBaseHit;

        String call;
        if (outTopN < MIN_N_FOR_VERDICT) {
            call = "⚠ <b>Inconclusive</b> — only " + outTopN + " high-conviction signals in the holdout (< "
                    + MIN_N_FOR_VERDICT + "). The edge below is not yet statistically trustworthy; accumulate more days.";
        } else if (outTopEdge >= 5.0 && inTopEdge > 0) {
            call = "✅ <b>Conviction predicts out-of-sample.</b> The 80+ band beats the baseline by "
                    + f1v(outTopEdge) + " pts of hit-rate on the holdout (" + outTopN + " signals), and the same "
                    + "direction held in-sample. Sizing up on high conviction is justified — the ConvictionSizer earns its weight.";
        } else if (inTopEdge >= 5.0 && outTopEdge < 2.0) {
            call = "❌ <b>Overfit — does NOT survive out-of-sample.</b> The 80+ band beat baseline in-sample "
                    + "(+" + f1v(inTopEdge) + " pts) but collapses to " + f1v(outTopEdge) + " pts on the holdout. "
                    + "Conviction is a coin-flip out-of-sample here — sizing up on it is noise, not edge. "
                    + "Narrow to where a real, repeatable edge shows, or stand down.";
        } else {
            call = "➖ <b>Flat / weak.</b> The 80+ band beats baseline by only " + f1v(outTopEdge)
                    + " pts on the holdout — no meaningful predictive lift from conviction. Treat sizing as risk-management, "
                    + "not alpha, until a band separates cleanly across both halves.";
        }
        return "<p style='margin-top:8px;padding:8px;border-left:4px solid #888;background:#fafafa'>"
                + "<b>Verdict:</b> " + call + "</p>";
    }

    // ── formatting helpers (mirror OiSignalEdge) ──
    private static String pct(double f) { return String.format(Locale.ROOT, "%.2f%%", f); }
    private static String f1(Object o) { return String.format(Locale.ROOT, "%.1f%%", asDouble(o)); }
    private static String f1v(double v) { return String.format(Locale.ROOT, "%.1f", v); }
    private static String f2(Object o) { return String.format(Locale.ROOT, "%.2f%%", asDouble(o)); }
    private static String f2signed(Object o) { return String.format(Locale.ROOT, "%+.2f%%", asDouble(o)); }
    private static long asLong(Object o) { return (o instanceof Number n) ? n.longValue() : 0L; }
    private static double asDouble(Object o) { return (o instanceof Number n) ? n.doubleValue() : 0.0; }
    private static String str(Object o) { return o == null ? "—" : o.toString(); }
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
