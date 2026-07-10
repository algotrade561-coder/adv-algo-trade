package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.GenericStrategyAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P2 / §3c #5 — <b>Exit attribution</b>. Per exit reason: realized P&amp;L, the peak it reached (MFE), the
 * worst it saw (MAE), and the <em>give-back</em> (MFE − realized) — i.e. how much of the best price each
 * exit style handed back. High give-back ⇒ exits are trailing too loose / too late; high MFE with low
 * realized and a low win-rate ⇒ winners are being cut early. This is the measurement behind the exit-profile
 * tuning (tiered trailing, progressive booking) so it's adjusted on evidence, not feel.
 *
 * <p>Single-table read over the {@code exit} events ({@code exitReason}, {@code realizedPnlPct},
 * {@code maePct}, {@code mfePct}) — works on today's data. Fail-safe.
 */
@Component
public class ExitAttributionAnalyzerPlugin implements GenericStrategyAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(ExitAttributionAnalyzerPlugin.class);

    @Override
    public int order() {
        return 30;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query, StrategyType strategy) {
        return List.of(exitAttributionSection(query, strategy));
    }

    AnalyzerSection exitAttributionSection(TuningEventQuery query, StrategyType strategy) {
        final String title = "Exit attribution (give-back & early cuts by exit reason)";
        try {
            if (!EventScan.hasData(query, strategy, TuningEventType.EXIT)) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No " + strategy.displayName() + " exits in the selected window.</em></p>");
            }
            String sql = ""
                    + "SELECT exitReason, "
                    + "  COUNT(*) AS n, "
                    + "  ROUND(AVG(TRY_CAST(realizedPnlPct AS DOUBLE)),2) AS avg_pnl, "
                    + "  ROUND(AVG(TRY_CAST(mfePct AS DOUBLE)),2) AS avg_peak, "
                    + "  ROUND(AVG(TRY_CAST(maePct AS DOUBLE)),2) AS avg_worst, "
                    + "  ROUND(AVG(TRY_CAST(mfePct AS DOUBLE) - TRY_CAST(realizedPnlPct AS DOUBLE)),2) AS avg_giveback, "
                    + "  ROUND(100.0*AVG(CASE WHEN TRY_CAST(realizedPnlPct AS DOUBLE) > 0 THEN 1 ELSE 0 END),0) AS win_pct "
                    + "FROM " + EventScan.source(query, strategy, TuningEventType.EXIT) + " "
                    + "GROUP BY exitReason ORDER BY n DESC";
            List<Map<String, Object>> rows = query.store().query(sql);
            return render(title, rows);
        } catch (TuningQueryException ex) {
            log.warn("[ExitAttributionAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Exit-attribution query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    AnalyzerSection render(String title, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return AnalyzerSection.htmlOnly(title, "<p><em>No exits matched the query.</em></p>");
        }
        StringBuilder h = new StringBuilder(1024);
        h.append("<p><strong>give-back</strong> = peak (MFE) − realized: how much of the best price the exit "
                + "handed back. High give-back on a reason ⇒ trail tighter / book sooner; high peak with low "
                + "realized + low win% ⇒ winners cut early.</p>");
        h.append("<table class='exit-attr'><thead><tr>"
                + "<th>Exit reason</th><th>n</th><th>avg realized</th><th>avg peak (MFE)</th>"
                + "<th>avg worst (MAE)</th><th>avg give-back</th><th>win %</th><th>read</th></tr></thead><tbody>");
        for (Map<String, Object> r : rows) {
            double giveback = asDouble(r.get("avg_giveback"));
            double avgPnl = asDouble(r.get("avg_pnl"));
            double winPct = asDouble(r.get("win_pct"));
            String flag = giveback >= 5.0 ? "🔴 gives back peak"
                    : (avgPnl < 0 ? "🔴 net loser"
                    : (winPct < 40.0 ? "🟠 low win%" : "🟢 ok"));
            h.append("<tr><td>").append(escape(str(r.get("exitReason")))).append("</td>")
                    .append("<td>").append(asLong(r.get("n"))).append("</td>")
                    .append("<td>").append(signed(avgPnl)).append("</td>")
                    .append("<td>").append(signed(asDouble(r.get("avg_peak")))).append("</td>")
                    .append("<td>").append(signed(asDouble(r.get("avg_worst")))).append("</td>")
                    .append("<td>").append(signed(giveback)).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.0f%%", winPct)).append("</td>")
                    .append("<td>").append(flag).append("</td></tr>");
        }
        h.append("</tbody></table>");
        return new AnalyzerSection(title, h.toString(), Map.of("rows", rows));
    }

    private static String signed(double v) {
        return String.format(Locale.ROOT, "%+.2f%%", v);
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
