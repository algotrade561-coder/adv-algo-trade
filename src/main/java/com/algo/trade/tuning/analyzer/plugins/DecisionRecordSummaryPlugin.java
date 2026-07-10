package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.DecisionRecord;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * §3b — surfaces the canonical {@link DecisionRecord} grains in the report (counts + a sample) and points to
 * the CSV export ({@code /reports/tuning/decision-record}) used for offline analysis / ML. Two grains:
 * the trade record (signal → exec → exit → forward) and the eval record (evaluation → reject-forward).
 * Also flags the exit-key-coverage caveat the join exposes. Fail-safe; renders last.
 */
@Component
public class DecisionRecordSummaryPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(DecisionRecordSummaryPlugin.class);

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public int order() {
        return 80;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(section(query));
    }

    AnalyzerSection section(TuningEventQuery query) {
        final String title = "Decision record (canonical per-decision join)";
        try {
            StringBuilder h = new StringBuilder(512);
            h.append("<p>The canonical join downstream analysis / offline ML reads. Two grains (eval and "
                    + "signal keys don't overlap). Export both as CSV at "
                    + "<code>/reports/tuning/decision-record?from=&amp;to=&amp;grain=trade|eval</code>.</p>");

            if (EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL)) {
                Map<String, Object> t = one(query.store().query(
                        "SELECT COUNT(*) AS n, SUM(executed) AS executed, COUNT(exitReason) AS exits, "
                        + "COUNT(fwdMfe30m) AS with_fwd FROM (" + DecisionRecord.tradeRecordSql(query) + ")"));
                long n = asLong(t.get("n")), exe = asLong(t.get("executed")),
                     exits = asLong(t.get("exits")), fwd = asLong(t.get("with_fwd"));
                h.append("<p><strong>Trade grain:</strong> ").append(n).append(" signals · ")
                        .append(exe).append(" filled · ").append(exits).append(" with exit · ")
                        .append(fwd).append(" with forward outcome. ")
                        .append("<em>(\"filled\" counts ORDER_FILLED executions, not ORDER_OPEN placeholders or "
                        + "rejects; the signal→fill drop is the normal setup-to-trade funnel, not a capture gap.)</em></p>");
                // Only a FILLED trade is expected to have an exit. Compare exits against FILLS (not against
                // all signals/executions, which would look broken forever). A shortfall here is a genuine
                // exit-capture gap. Note: trades adopted/synced OUTSIDE a signal (orphan adoption, broker
                // position sync) have no signal row and are invisible to this signal-based grain by design.
                if (exe > 0 && exits < exe) {
                    long missing = exe - exits;
                    h.append("<p class='error'>⚠ ").append(missing).append(" of ").append(exe)
                            .append(" filled trades have no matching exit row (exit-capture gap). Every closed "
                            + "trade should emit an ExitEvent keyed by its entry correlationKey — audit the close "
                            + "path that produced these (still-open trades at report time are expected).</p>");
                }
            } else {
                h.append("<p><em>No signals in window — trade grain empty.</em></p>");
            }

            if (EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION)) {
                Map<String, Object> e = one(query.store().query(
                        "SELECT COUNT(*) AS n, COUNT(rejFwdMfe30m) AS with_rej FROM ("
                        + DecisionRecord.evalRecordSql(query) + ")"));
                h.append("<p><strong>Eval grain:</strong> ").append(asLong(e.get("n")))
                        .append(" evaluations · ").append(asLong(e.get("with_rej")))
                        .append(" with a reject forward-checkpoint (opportunity-cost coverage).</p>");
            }
            return AnalyzerSection.htmlOnly(title, h.toString());
        } catch (Exception ex) {
            log.warn("[DecisionRecordSummaryPlugin] failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Decision-record summary failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    private static Map<String, Object> one(List<Map<String, Object>> rows) {
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
