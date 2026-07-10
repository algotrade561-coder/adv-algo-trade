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
 * P2 / §3c #1 — <b>Conversion funnel + outcomes</b>. The top-of-report shape of the day: how many
 * evaluations became signals, executions, exits, and wins — with the drop-off at each stage. Where the
 * blocker opportunity-cost section explains <em>why</em> the big drop (eval→signal) happened and whether it
 * was right, this shows the overall conversion at a glance.
 *
 * <p>Pure counts over the existing event streams (evaluation/signal/execution/exit) plus a win-rate and
 * average realized P&amp;L from exit rows — so it works on today's data without waiting for the P1.3
 * forward-checkpoint backfill. Fail-safe: any query issue renders an inline notice.
 */
@Component
public class FunnelAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(FunnelAnalyzerPlugin.class);

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public int order() {
        return 10;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(funnelSection(query));
    }

    AnalyzerSection funnelSection(TuningEventQuery query) {
        final String title = "Conversion funnel (eval → signal → execution → exit → win)";
        try {
            long evaluated = countRows(query, TuningEventType.EVALUATION);
            long signalled = countRows(query, TuningEventType.SIGNAL);
            // "Executed" = actual FILLS (stage=ORDER_FILLED), NOT every execution row. Counting all
            // execution rows (ORDER_OPEN placeholders + rejects) produced the nonsensical "674% of prev"
            // where executed > signalled. Fills are the real trades that can go on to exit.
            long executed = countFills(query);
            long exited = countRows(query, TuningEventType.EXIT);
            double[] outcome = exitOutcome(query); // {wins, avgPnlPct}
            long wins = (long) outcome[0];
            double avgPnlPct = outcome[1];

            if (evaluated == 0) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No OI Momentum evaluations in the selected window.</em></p>");
            }

            StringBuilder h = new StringBuilder(768);
            h.append("<table class='funnel'><thead><tr><th>Stage</th><th>Count</th>"
                    + "<th>% of prev</th><th>% of eval</th></tr></thead><tbody>");
            stage(h, "Evaluated", evaluated, evaluated, evaluated);
            stage(h, "Signalled", signalled, evaluated, evaluated);
            stage(h, "Executed", executed, signalled, evaluated);
            stage(h, "Exited", exited, executed, evaluated);
            stage(h, "Won (P&L &gt; 0)", wins, exited, evaluated);
            h.append("</tbody></table>");

            double winRate = exited > 0 ? 100.0 * wins / exited : 0.0;
            h.append("<p><strong>Win rate:</strong> ")
                    .append(exited > 0 ? String.format(Locale.ROOT, "%.0f%% (%d/%d exits)", winRate, wins, exited)
                            : "n/a (no exits)")
                    .append(" &nbsp;·&nbsp; <strong>Avg realized P&L:</strong> ")
                    .append(exited > 0 ? String.format(Locale.ROOT, "%+.2f%%", avgPnlPct) : "n/a")
                    .append("</p>");
            h.append("<p class='hint'>The eval→signal collapse is expected (most ticks aren't setups); the "
                    + "<em>blocker opportunity-cost</em> section below judges whether that filtering was correct. "
                    + "A low execution→exit ratio or missing exits points at exit-capture gaps (P1.3).</p>");
            return new AnalyzerSection(title, h.toString(),
                    Map.of("evaluated", evaluated, "signalled", signalled, "executed", executed,
                            "exited", exited, "wins", wins));
        } catch (TuningQueryException ex) {
            log.warn("[FunnelAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Funnel query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    /** Returns {wins, avgRealizedPnlPct} from exit rows; {0,0} when none. */
    private double[] exitOutcome(TuningEventQuery query) {
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.EXIT)) {
                return new double[]{0, 0};
            }
            List<Map<String, Object>> r = query.store().query(
                    "SELECT "
                    + "  SUM(CASE WHEN TRY_CAST(realizedPnlPct AS DOUBLE) > 0 THEN 1 ELSE 0 END) AS wins, "
                    + "  AVG(TRY_CAST(realizedPnlPct AS DOUBLE)) AS avg_pnl "
                    + "FROM " + EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.EXIT));
            if (r.isEmpty()) {
                return new double[]{0, 0};
            }
            return new double[]{asDouble(r.get(0).get("wins")), asDouble(r.get(0).get("avg_pnl"))};
        } catch (Exception ex) {
            log.debug("[FunnelAnalyzerPlugin] exit outcome failed: {}", ex.getMessage());
            return new double[]{0, 0};
        }
    }

    /** Count actual FILL executions (stage=ORDER_FILLED) — the real trade count for the funnel. */
    private long countFills(TuningEventQuery query) {
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.EXECUTION)) {
                return 0;
            }
            List<Map<String, Object>> r = query.store().query(
                    "SELECT COUNT(*) AS n FROM "
                    + EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.EXECUTION)
                    + " WHERE stage = 'ORDER_FILLED'");
            return r.isEmpty() || r.get(0).get("n") == null ? 0 : ((Number) r.get(0).get("n")).longValue();
        } catch (Exception ex) {
            log.debug("[FunnelAnalyzerPlugin] countFills failed: {}", ex.getMessage());
            return 0;
        }
    }

    private long countRows(TuningEventQuery query, TuningEventType type) {
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, type)) {
                return 0;
            }
            List<Map<String, Object>> r = query.store().query(
                    "SELECT COUNT(*) AS n FROM " + EventScan.source(query, StrategyType.OI_MOMENTUM, type));
            return r.isEmpty() || r.get(0).get("n") == null ? 0 : ((Number) r.get(0).get("n")).longValue();
        } catch (Exception ex) {
            log.debug("[FunnelAnalyzerPlugin] count {} failed: {}", type, ex.getMessage());
            return 0;
        }
    }

    private static void stage(StringBuilder h, String name, long count, long prev, long evalTotal) {
        String pctPrev = prev > 0 ? String.format(Locale.ROOT, "%.1f%%", 100.0 * count / prev) : "—";
        String pctEval = evalTotal > 0 ? String.format(Locale.ROOT, "%.2f%%", 100.0 * count / evalTotal) : "—";
        h.append("<tr><td>").append(name).append("</td><td>").append(count)
                .append("</td><td>").append(pctPrev).append("</td><td>").append(pctEval).append("</td></tr>");
    }

    private static double asDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
