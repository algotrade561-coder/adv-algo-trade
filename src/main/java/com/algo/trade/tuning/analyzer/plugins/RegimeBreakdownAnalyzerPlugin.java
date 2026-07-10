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
 * P2 / §3c #4 — <b>Regime breakdown</b>. Signal outcomes bucketed by VIX × realised-30m-range, the two axes
 * that decide whether momentum has anything to trade. Shows, per regime cell, the sample size, the average
 * 30-min forward magnitude, and the hit-rate (|move| ≥ 0.30%). The point: confirm quantitatively where the
 * strategy has an edge (high-VIX / wide-range) vs where it's whipsawed (low-VIX / dead-range) — the evidence
 * behind a stand-down gate. Reads signal⋈forward by correlationKey. Fail-safe; placeholder until data lands.
 */
@Component
public class RegimeBreakdownAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(RegimeBreakdownAnalyzerPlugin.class);

    private static final double HIT_PCT = 0.30;

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public int order() {
        return 50;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(section(query));
    }

    AnalyzerSection section(TuningEventQuery query) {
        final String title = "Regime breakdown (edge by VIX × realised range)";
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL)
                    || !EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT)) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>Needs signals joined to forward checkpoints — not present in this window yet.</em></p>");
            }
            String sigSource = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL);
            String fwdSource = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT);
            String sql = ""
                    + "WITH j AS ("
                    + "  SELECT "
                    + "    TRY_CAST(json_extract_string(s.attr_extra,'$.vix') AS DOUBLE) AS vix, "
                    + "    TRY_CAST(json_extract_string(s.attr_extra,'$.rangePct30m') AS DOUBLE) AS rng, "
                    + "    GREATEST(ABS(TRY_CAST(f.fwdMfe30mPct AS DOUBLE)), ABS(TRY_CAST(f.fwdMae30mPct AS DOUBLE))) AS mag "
                    + "  FROM " + sigSource + " s "
                    + "  JOIN " + fwdSource + " f USING (correlationKey) "
                    + "  WHERE f.fwdMfe30mPct IS NOT NULL"
                    + "), banded AS ("
                    + "  SELECT "
                    + "    CASE WHEN vix IS NULL THEN 'vix?' WHEN vix < 12 THEN 'VIX<12' "
                    + "         WHEN vix < 15 THEN 'VIX 12–15' WHEN vix < 18 THEN 'VIX 15–18' ELSE 'VIX 18+' END AS vix_band, "
                    + "    CASE WHEN rng IS NULL THEN 'rng?' WHEN rng < 0.2 THEN 'range<0.2%' "
                    + "         WHEN rng < 0.4 THEN 'range 0.2–0.4%' WHEN rng < 0.7 THEN 'range 0.4–0.7%' ELSE 'range 0.7%+' END AS rng_band, "
                    + "    mag "
                    + "  FROM j"
                    + ") SELECT vix_band, rng_band, COUNT(*) AS n, ROUND(AVG(mag),3) AS avg_mag, "
                    + "    ROUND(100.0*AVG(CASE WHEN mag >= " + HIT_PCT + " THEN 1 ELSE 0 END),1) AS hit_pct "
                    + "  FROM banded GROUP BY vix_band, rng_band ORDER BY vix_band, rng_band";
            List<Map<String, Object>> rows = query.store().query(sql);
            return render(title, rows);
        } catch (TuningQueryException ex) {
            log.warn("[RegimeBreakdownAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Regime query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    AnalyzerSection render(String title, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return AnalyzerSection.htmlOnly(title, "<p><em>No joined signal/outcome rows yet.</em></p>");
        }
        StringBuilder h = new StringBuilder(1024);
        h.append("<p>Average 30-min forward |move| and hit-rate (|move| ≥ ")
                .append(String.format(Locale.ROOT, "%.2f%%", HIT_PCT))
                .append(") per regime. Cells with a high hit-rate are where there's something to trade; "
                + "low-hit cells (typically low-VIX / narrow-range) are stand-down candidates.</p>");
        h.append("<table class='regime'><thead><tr><th>VIX</th><th>Realised range</th><th>n</th>"
                + "<th>avg fwd |move|</th><th>hit %</th><th>read</th></tr></thead><tbody>");
        for (Map<String, Object> r : rows) {
            long n = asLong(r.get("n"));
            double hit = asDouble(r.get("hit_pct"));
            String flag = n < 10 ? "· thin" : hit >= 55 ? "🟢 tradeable" : hit <= 35 ? "🔴 stand down" : "🟠 marginal";
            h.append("<tr><td>").append(escape(str(r.get("vix_band")))).append("</td>")
                    .append("<td>").append(escape(str(r.get("rng_band")))).append("</td>")
                    .append("<td>").append(n).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.2f%%", asDouble(r.get("avg_mag")))).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f%%", hit)).append("</td>")
                    .append("<td>").append(flag).append("</td></tr>");
        }
        h.append("</tbody></table>");
        return new AnalyzerSection(title, h.toString(), Map.of("rows", rows));
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
