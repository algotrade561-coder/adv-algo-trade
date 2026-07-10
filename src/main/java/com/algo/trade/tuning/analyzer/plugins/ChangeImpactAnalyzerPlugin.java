package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.DecisionRecord;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.change.ConfigChangeLog;
import com.algo.trade.tuning.change.ConfigChangeService;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §10c — the "measure" half of the loop. Lists the tuning config changes tagged in the report window and, for
 * each, a before/after expectancy split (trades before vs after the change timestamp) so you can see whether
 * a change actually helped. This is what turns "I lowered the charges floor" into "…and expectancy went from
 * X to Y." Read-only; pairs with the guarded change-tagging in {@code /admin/config-changes}.
 */
@Component
public class ChangeImpactAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(ChangeImpactAnalyzerPlugin.class);

    private final ConfigChangeService changeService;

    public ChangeImpactAnalyzerPlugin(ConfigChangeService changeService) {
        this.changeService = changeService;
    }

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public int order() {
        return 90; // last
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(section(query));
    }

    AnalyzerSection section(TuningEventQuery query) {
        final String title = "Change impact (did a tuning change help?)";
        try {
            List<ConfigChangeLog> changes = changeService.inWindow(query.fromDate(), query.toDate());
            if (changes.isEmpty()) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No config changes tagged in this window.</em> Record one at "
                        + "<code>POST /admin/config-changes</code> (after applying it in Settings) so its impact "
                        + "can be measured here next run.</p>");
            }
            boolean haveTrades = EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL);
            StringBuilder h = new StringBuilder(1024);
            h.append("<table class='change-impact'><thead><tr><th>When (UTC)</th><th>Area / field</th>"
                    + "<th>Change</th><th>Note</th><th>Before → After (avg P&L · win%)</th></tr></thead><tbody>");
            for (ConfigChangeLog c : changes) {
                String beforeAfter = haveTrades ? beforeAfter(query, c.getChangedAt().toString()) : "n/a";
                h.append("<tr><td>").append(escape(c.getChangedAt().toString())).append("</td>")
                        .append("<td>").append(escape(nz(c.getArea()))).append(" / ").append(escape(nz(c.getField()))).append("</td>")
                        .append("<td>").append(escape(nz(c.getOldValue()))).append(" → ").append(escape(nz(c.getNewValue())))
                        .append(c.isReverted() ? " <em>(reverted)</em>" : "").append("</td>")
                        .append("<td>").append(escape(nz(c.getNote()))).append("</td>")
                        .append("<td>").append(beforeAfter).append("</td></tr>");
            }
            h.append("</tbody></table>");
            h.append("<p class='hint'>Before/after splits the window's exited trades at the change time. Treat "
                    + "small-n splits as directional only — a real verdict needs multiple post-change sessions.</p>");
            return AnalyzerSection.htmlOnly(title, h.toString());
        } catch (Exception ex) {
            log.warn("[ChangeImpactAnalyzerPlugin] failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Change-impact failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    /** Avg realized P&L and win% for exited trades before vs after the change timestamp (UTC ISO). */
    private String beforeAfter(TuningEventQuery query, String changeInstantIso) {
        try {
            String sql = "SELECT "
                    + "CASE WHEN TRY_CAST(eventTime AS TIMESTAMP) < TIMESTAMP '" + changeInstantIso.replace("'", "")
                    + "' THEN 'before' ELSE 'after' END AS period, "
                    + "COUNT(*) AS n, ROUND(AVG(realizedPnlPct),2) AS avg_pnl, "
                    + "ROUND(100.0*AVG(CASE WHEN realizedPnlPct > 0 THEN 1 ELSE 0 END),0) AS win_pct "
                    + "FROM (" + DecisionRecord.tradeRecordSql(query) + ") "
                    + "WHERE realizedPnlPct IS NOT NULL GROUP BY 1";
            String before = "—", after = "—";
            for (Map<String, Object> r : query.store().query(sql)) {
                String s = String.format(Locale.ROOT, "%+.2f%% · %.0f%% (n=%d)",
                        asDouble(r.get("avg_pnl")), asDouble(r.get("win_pct")), asLong(r.get("n")));
                if ("before".equals(r.get("period"))) before = s; else after = s;
            }
            return before + " → " + after;
        } catch (Exception ex) {
            return "n/a";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static long asLong(Object o) { return (o instanceof Number n) ? n.longValue() : 0L; }
    private static double asDouble(Object o) { return (o instanceof Number n) ? n.doubleValue() : 0.0; }
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
