package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P2 / §3c #6 — <b>OI-signal edge</b>: does the OI thesis actually predict the forward move? Joins each
 * signal's features ({@code operatorScore}, {@code sustainedDriftPct}) to its 30-min forward checkpoint and
 * bands them against forward magnitude (MFE) + a hit-rate (share with |fwd move| ≥ 0.30%). A flat profile —
 * high-score bands no better than low — is the honest signal that the feature is a coin-flip and shouldn't
 * be tuned (per the prior analyses: the stable edge is VIX→magnitude, not OI→direction). A rising profile
 * means the feature earns its weight.
 *
 * <p>Reads signals ⋈ forward_checkpoint by correlationKey (signal checkpoints; reject checkpoints have
 * different keys and don't match). Works once signals have forward checkpoints; placeholder until then.
 * Fail-safe.
 */
@Component
public class OiSignalEdgeAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(OiSignalEdgeAnalyzerPlugin.class);

    /** |forward 30m move| at/above this (%) counts as a "hit" (a tradeable-size move). */
    private static final double HIT_PCT = 0.30;

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public int order() {
        return 60;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(edgeSection(query));
    }

    AnalyzerSection edgeSection(TuningEventQuery query) {
        final String title = "OI-signal edge (does operator score / drift predict the move?)";
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL)
                    || !EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT)) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>Needs signals with forward checkpoints in the window — not present yet. "
                        + "Lights up once a session's signals have ripened (≈31 min) and the forward sweep has run.</em></p>");
            }
            String sig = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL);
            String fwd = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT);

            String opTable = bandQuery(sig, fwd, "operatorScore",
                    "CASE WHEN v IS NULL THEN 'unknown' WHEN v < 40 THEN '<40' WHEN v < 60 THEN '40–59' "
                    + "WHEN v < 80 THEN '60–79' ELSE '80+' END");
            String driftTable = bandQuery(sig, fwd, "sustainedDriftPct",
                    "CASE WHEN v IS NULL THEN 'unknown' WHEN ABS(v) < 0.1 THEN '~0' WHEN ABS(v) < 0.25 THEN '0.1–0.25' "
                    + "WHEN ABS(v) < 0.5 THEN '0.25–0.5' ELSE '0.5+' END");

            List<Map<String, Object>> opRows = query.store().query(opTable);
            List<Map<String, Object>> driftRows = query.store().query(driftTable);

            StringBuilder h = new StringBuilder(1024);
            h.append("<p>Each signal's feature vs its 30-min forward magnitude. A <em>flat</em> column "
                    + "(high bands no better than low) ⇒ the feature is a coin-flip — don't tune it. "
                    + "hit% = share with |fwd move| ≥ ").append(pct(HIT_PCT)).append(".</p>");
            h.append("<h4>By operator-framework score</h4>");
            h.append(renderBandTable(opRows, "operator score"));
            h.append("<h4>By sustained-drift %</h4>");
            h.append(renderBandTable(driftRows, "sustained drift"));
            return new AnalyzerSection(title, h.toString(),
                    Map.of("operator", opRows, "drift", driftRows));
        } catch (TuningQueryException ex) {
            log.warn("[OiSignalEdgeAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>OI-edge query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    /** Builds the signal⋈forward join, bands the named feature, aggregates forward magnitude + hit-rate. */
    private String bandQuery(String sigSource, String fwdSource, String feature, String bandExpr) {
        return ""
                + "WITH sig AS ("
                + "  SELECT correlationKey, TRY_CAST(json_extract_string(attr_extra,'$." + feature + "') AS DOUBLE) AS v "
                + "  FROM " + sigSource
                + "), fwd AS ("
                + "  SELECT correlationKey, TRY_CAST(fwdMfe30mPct AS DOUBLE) AS mfe, TRY_CAST(fwdMae30mPct AS DOUBLE) AS mae "
                + "  FROM " + fwdSource
                + "), j AS ("
                + "  SELECT s.v, GREATEST(ABS(f.mfe), ABS(f.mae)) AS mag "
                + "  FROM sig s JOIN fwd f USING (correlationKey) WHERE f.mfe IS NOT NULL"
                + "), banded AS (SELECT " + bandExpr + " AS band, mag FROM j)"
                + "SELECT band, COUNT(*) AS n, ROUND(AVG(mag),3) AS avg_mag, "
                + "  ROUND(100.0*AVG(CASE WHEN mag >= " + HIT_PCT + " THEN 1 ELSE 0 END),1) AS hit_pct "
                + "FROM banded GROUP BY band ORDER BY band";
    }

    private String renderBandTable(List<Map<String, Object>> rows, String label) {
        if (rows.isEmpty()) {
            return "<p><em>No joined signal/outcome rows for " + escape(label) + " yet.</em></p>";
        }
        StringBuilder h = new StringBuilder(512);
        h.append("<table class='edge'><thead><tr><th>").append(escape(label))
                .append("</th><th>n</th><th>avg fwd |move|</th><th>hit %</th></tr></thead><tbody>");
        for (Map<String, Object> r : rows) {
            h.append("<tr><td>").append(escape(str(r.get("band")))).append("</td>")
                    .append("<td>").append(asLong(r.get("n"))).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.2f%%", asDouble(r.get("avg_mag")))).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f%%", asDouble(r.get("hit_pct")))).append("</td></tr>");
        }
        h.append("</tbody></table>");
        return h.toString();
    }

    private static String pct(double frac) {
        return String.format(Locale.ROOT, "%.2f%%", frac);
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }

    private static String str(Object o) {
        return o == null ? "—" : o.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
