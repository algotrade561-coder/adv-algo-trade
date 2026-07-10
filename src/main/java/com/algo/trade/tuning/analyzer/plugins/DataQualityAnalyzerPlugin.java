package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P2 / §10a — <b>Data-Quality header &amp; gate</b>. Renders as the first thing in the report so you can
 * see, before reading any analysis, whether the day's captured data is trustworthy. Every integrity
 * problem the 06/22–24 audit found (lost day, 14:55 truncation, 40–63% zero features, missing exits) was
 * invisible until hand-queried — this surfaces them on demand.
 *
 * <p>For the selected window it reports, per the captured OI-Momentum events:
 * <ul>
 *   <li><b>Session coverage</b> — distinct minutes captured vs the ~375 market minutes (09:15–15:30), and
 *       first/last event time (catches the 14:55 truncation and a late open).</li>
 *   <li><b>Feature integrity</b> — % rows with {@code dataQuality=OK}, % {@code feedFresh}, and the
 *       {@code NO_LTP}/{@code NO_SPOT}/{@code NO_RANGE} breakdown (the P1.2 flags). High NO_LTP ⇒ a stale
 *       option feed, not real zeros.</li>
 *   <li><b>Schema</b> — count of legacy rows with no {@code schemaVer} (pre-P1.2 capture).</li>
 *   <li><b>Outcome labelling</b> — executions vs exit events (parity ⇒ exit capture is complete; the audit
 *       saw 30 vs 11), and signals vs reject/forward-checkpoint coverage.</li>
 * </ul>
 *
 * <p>Each metric is flagged GREEN/RED against a threshold so a bad-capture day reads at a glance. The whole
 * section is best-effort: any query failure renders an inline notice rather than breaking the report.
 */
@Component
public class DataQualityAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(DataQualityAnalyzerPlugin.class);

    /** ~375 tradable minutes in a 09:15–15:30 IST session. */
    private static final int MARKET_MINUTES = 375;
    /** Thresholds for the RED/GREEN flags. */
    private static final double MIN_COVERAGE_PCT = 90.0;
    private static final double MIN_OK_PCT = 90.0;

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    /** Negative → renders before the standard breakdowns, so the trust gate is the first thing read. */
    @Override
    public int order() {
        return -100;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(dataQualitySection(query));
    }

    AnalyzerSection dataQualitySection(TuningEventQuery query) {
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION)) {
                return AnalyzerSection.htmlOnly("Data health (OI Momentum)",
                        "<p class='error'><strong>NO EVALUATION DATA</strong> in the selected window — "
                        + "the report below has nothing to analyze. Check capture is ON and the day rolled.</p>");
            }

            String sql = ""
                    + "SELECT "
                    + "  COUNT(*) AS evals, "
                    + "  COUNT(DISTINCT date_trunc('minute', TRY_CAST(eventTime AS TIMESTAMP))) AS minutes, "
                    + "  COUNT(DISTINCT CAST(TRY_CAST(eventTime AS TIMESTAMP) AS DATE)) AS days, "
                    + "  CAST(MIN(TRY_CAST(eventTime AS TIMESTAMP)) AS VARCHAR) AS first_ev, "
                    + "  CAST(MAX(TRY_CAST(eventTime AS TIMESTAMP)) AS VARCHAR) AS last_ev, "
                    + "  ROUND(100.0*AVG(CASE WHEN json_extract_string(attr_extra,'$.dataQuality')='OK' THEN 1 ELSE 0 END),1) AS pct_ok, "
                    + "  ROUND(100.0*AVG(CASE WHEN json_extract_string(attr_extra,'$.dataQuality')='NO_LTP' THEN 1 ELSE 0 END),1) AS pct_no_ltp, "
                    + "  ROUND(100.0*AVG(CASE WHEN json_extract_string(attr_extra,'$.dataQuality')='NO_SPOT' THEN 1 ELSE 0 END),1) AS pct_no_spot, "
                    + "  ROUND(100.0*AVG(CASE WHEN json_extract_string(attr_extra,'$.dataQuality')='NO_RANGE' THEN 1 ELSE 0 END),1) AS pct_no_range, "
                    + "  ROUND(100.0*AVG(CASE WHEN json_extract_string(attr_extra,'$.feedFresh')='true' THEN 1 ELSE 0 END),1) AS pct_fresh, "
                    + "  SUM(CASE WHEN json_extract_string(attr_extra,'$.schemaVer') IS NULL THEN 1 ELSE 0 END) AS legacy_rows, "
                    + "  SUM(CASE WHEN blocker='premium_unavailable' THEN 1 ELSE 0 END) AS premium_unavail, "
                    + "  SUM(CASE WHEN blocker='entry_cutoff' THEN 1 ELSE 0 END) AS entry_cutoff_rows "
                    + "FROM " + EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION);

            List<Map<String, Object>> rows = query.store().query(sql);
            long fills = countFills(query);
            long exits = countRows(query, TuningEventType.EXIT);
            long signals = countRows(query, TuningEventType.SIGNAL);
            long fwd = countRows(query, TuningEventType.FORWARD_CHECKPOINT);
            return render(rows.isEmpty() ? Map.of() : rows.get(0), fills, exits, signals, fwd);
        } catch (TuningQueryException ex) {
            log.warn("[DataQualityAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("Data health (OI Momentum)",
                    "<p class='error'>Data-health query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    /** Count actual FILL executions (stage=ORDER_FILLED) — the real trade count, not ORDER_OPEN/reject rows. */
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
            log.debug("[DataQualityAnalyzerPlugin] countFills failed: {}", ex.getMessage());
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
            log.debug("[DataQualityAnalyzerPlugin] count {} failed: {}", type, ex.getMessage());
            return 0;
        }
    }

    AnalyzerSection render(Map<String, Object> m, long fills, long exits, long signals, long fwd) {
        long evals = asLong(m.get("evals"));
        long minutes = asLong(m.get("minutes"));
        long days = Math.max(1, asLong(m.get("days")));   // multi-day range: scale the denominator by trading days
        long expectedMinutes = days * MARKET_MINUTES;
        double coverage = expectedMinutes == 0 ? 0 : 100.0 * minutes / expectedMinutes;
        double pctOk = asDouble(m.get("pct_ok"));
        double pctFresh = asDouble(m.get("pct_fresh"));
        long legacy = asLong(m.get("legacy_rows"));

        StringBuilder h = new StringBuilder(1024);
        h.append("<table class='data-health'><thead><tr><th>Metric</th><th>Value</th><th>Status</th></tr></thead><tbody>");
        row(h, "Evaluations captured", String.valueOf(evals), evals > 0);
        row(h, "Session coverage",
                String.format(Locale.ROOT, "%d / %d min over %d day(s) (%.0f%%)", minutes, expectedMinutes, days, coverage),
                coverage >= MIN_COVERAGE_PCT);
        row(h, "First → last event",
                str(m.get("first_ev")) + " → " + str(m.get("last_ev")) + " (UTC; +5:30 = IST)", true);
        row(h, "Feature integrity (dataQuality=OK)",
                pct(pctOk) + "  [NO_LTP " + pct(asDouble(m.get("pct_no_ltp")))
                        + ", NO_SPOT " + pct(asDouble(m.get("pct_no_spot")))
                        + ", NO_RANGE " + pct(asDouble(m.get("pct_no_range"))) + "]",
                pctOk >= MIN_OK_PCT);
        row(h, "Feed fresh", pct(pctFresh), pctFresh >= MIN_OK_PCT);
        row(h, "premium_unavailable rejects (stale LTP, not cost)",
                String.valueOf(asLong(m.get("premium_unavail"))), true);
        row(h, "Post-cutoff heartbeat rows (14:55→close)",
                String.valueOf(asLong(m.get("entry_cutoff_rows"))), asLong(m.get("entry_cutoff_rows")) > 0);
        // Parity = closed trades captured. Compare exits against actual FILLS (ORDER_FILLED), NOT all
        // execution rows (which include ORDER_OPEN placeholders + rejects and would never match). Green
        // when exits ≥ fills − open-trades; a large shortfall is a real exit-capture gap.
        row(h, "Exit-vs-fill parity",
                exits + " exits / " + fills + " fills",
                fills == 0 || exits >= fills);
        row(h, "Reject/forward-checkpoint coverage",
                fwd + " checkpoints / " + signals + " signals (+ sampled rejects)", true);
        row(h, "Legacy rows (no schemaVer — pre-P1.2 capture)",
                String.valueOf(legacy), legacy == 0);
        h.append("</tbody></table>");

        boolean trustworthy = evals > 0 && coverage >= MIN_COVERAGE_PCT && pctOk >= MIN_OK_PCT
                && (fills == 0 || exits >= fills);
        h.insert(0, trustworthy
                ? "<p class='ok'><strong>✅ Data looks trustworthy for this window.</strong></p>"
                : "<p class='error'><strong>⚠ Data quality issues — treat the analysis below with caution "
                        + "(see red rows).</strong></p>");
        return AnalyzerSection.htmlOnly("Data health (OI Momentum)", h.toString());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static void row(StringBuilder h, String metric, String value, boolean ok) {
        h.append("<tr><td>").append(escape(metric)).append("</td><td>").append(escape(value))
                .append("</td><td>").append(ok ? "🟢" : "🔴").append("</td></tr>");
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v);
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
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
