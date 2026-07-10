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
 * P2 / §3c #2 — <b>Blocker opportunity-cost</b>, the highest-value section of the loop: for each reject
 * reason, what did the market actually do afterwards? It answers "which gate is throwing away winners?".
 *
 * <p>Reads the per-reject {@code forward_checkpoint} rows produced by {@code ForwardCheckpointService}
 * (P1.3) — they carry {@code source=reject} and the {@code blocker} in {@code attr_extra}, plus the 30-min
 * forward MFE/MAE — so this is a direct read, no join. Per blocker it reports the sampled count and the
 * distribution of forward MFE/MAE, and flags the share that cleared a "would-have-been-profitable"
 * threshold (default ≥0.30% 30-min forward MFE — roughly the move a long ATM option needs to beat costs).
 *
 * <p>A high <em>would-profit %</em> on a high-volume gate (e.g. {@code charges_filter}) is the signal to
 * loosen it. Until the P1.3 reject-checkpoint data accumulates this renders an explanatory placeholder
 * rather than a false empty. Per §10b, only blockers with n ≥ {@link #MIN_SAMPLE} are shown, and the count
 * is displayed so you don't act on noise.
 */
@Component
public class BlockerOpportunityCostAnalyzerPlugin implements GenericStrategyAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(BlockerOpportunityCostAnalyzerPlugin.class);

    /** A 30-min forward MFE at/above this (in %) is treated as "a long option would likely have profited". */
    private static final double PROFIT_MFE_PCT = 0.30;
    /** §10b sample-size guard — don't surface a recommendation off a handful of rejects. */
    private static final int MIN_SAMPLE = 10;

    @Override
    public int order() {
        return 20;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query, StrategyType strategy) {
        return List.of(opportunityCostSection(query, strategy));
    }

    AnalyzerSection opportunityCostSection(TuningEventQuery query, StrategyType strategy) {
        final String title = "Blocker opportunity-cost (did a gate reject winners?)";
        try {
            if (!EventScan.hasData(query, strategy, TuningEventType.FORWARD_CHECKPOINT)) {
                return AnalyzerSection.htmlOnly(title, placeholder());
            }

            String sql = ""
                    + "SELECT "
                    + "  json_extract_string(attr_extra,'$.blocker') AS blocker, "
                    + "  COUNT(*) AS n, "
                    + "  ROUND(AVG(TRY_CAST(fwdMfe30mPct AS DOUBLE)),3) AS avg_mfe, "
                    + "  ROUND(AVG(TRY_CAST(fwdMae30mPct AS DOUBLE)),3) AS avg_mae, "
                    + "  ROUND(MEDIAN(TRY_CAST(fwdMfe30mPct AS DOUBLE)),3) AS med_mfe, "
                    // Direction-agnostic: a rejected BEARISH setup that fell (negative MAE) is a PE-buy
                    // winner too — count "moved >= threshold in EITHER direction" so up-only MFE no longer
                    // hides ~half of the rejected winners. (2026-07-02)
                    + "  ROUND(100.0*AVG(CASE WHEN GREATEST(TRY_CAST(fwdMfe30mPct AS DOUBLE), "
                    + "        -1.0*TRY_CAST(fwdMae30mPct AS DOUBLE)) >= " + PROFIT_MFE_PCT
                    + "        THEN 1 ELSE 0 END),1) AS pct_would_profit "
                    + "FROM " + EventScan.source(query, strategy, TuningEventType.FORWARD_CHECKPOINT) + " "
                    + "WHERE json_extract_string(attr_extra,'$.source') = 'reject' "
                    + "  AND fwdMfe30mPct IS NOT NULL "
                    + "GROUP BY 1 "
                    + "HAVING COUNT(*) >= " + MIN_SAMPLE + " "
                    + "ORDER BY pct_would_profit DESC, n DESC";

            List<Map<String, Object>> rows = query.store().query(sql);
            return render(title, rows);
        } catch (TuningQueryException ex) {
            log.warn("[BlockerOpportunityCostAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Opportunity-cost query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    AnalyzerSection render(String title, List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return AnalyzerSection.htmlOnly(title, placeholder());
        }
        StringBuilder h = new StringBuilder(1024);
        h.append("<p>For each reject reason, the 30-min forward move of the setups it blocked "
                + "(sampled). <strong>would-profit %</strong> = share that moved ≥ ")
                .append(pct(PROFIT_MFE_PCT)).append(" in <em>either</em> direction (a rejected bearish setup "
                + "that fell is a PE-buy winner) — a high value on a high-volume gate means it is discarding "
                + "winners and should be loosened. Only blockers with n ≥ ").append(MIN_SAMPLE)
                .append(" shown.</p>");
        h.append("<table class='opp-cost'><thead><tr>"
                + "<th>Blocker</th><th>n (sampled)</th><th>avg fwd MFE</th><th>median MFE</th>"
                + "<th>avg fwd MAE</th><th>would-profit %</th><th>read</th></tr></thead><tbody>");
        for (Map<String, Object> r : rows) {
            String blocker = str(r.get("blocker"));
            long n = asLong(r.get("n"));
            double avgMfe = asDouble(r.get("avg_mfe"));
            double medMfe = asDouble(r.get("med_mfe"));
            double avgMae = asDouble(r.get("avg_mae"));
            double wouldProfit = asDouble(r.get("pct_would_profit"));
            String flag = wouldProfit >= 30.0 ? "🔴 likely over-blocking"
                    : wouldProfit >= 15.0 ? "🟠 review"
                    : "🟢 ok";
            h.append("<tr><td>").append(escape(blocker)).append("</td>")
                    .append("<td>").append(n).append("</td>")
                    .append("<td>").append(signedPct(avgMfe)).append("</td>")
                    .append("<td>").append(signedPct(medMfe)).append("</td>")
                    .append("<td>").append(signedPct(avgMae)).append("</td>")
                    .append("<td>").append(pct(wouldProfit / 100.0)).append("</td>")
                    .append("<td>").append(flag).append("</td></tr>");
        }
        h.append("</tbody></table>");
        return new AnalyzerSection(title, h.toString(), Map.of("rows", rows));
    }

    private static String placeholder() {
        return "<p><em>No per-reject forward checkpoints in this window yet.</em> This section lights up once "
                + "<code>tuning.forward.checkpoint-rejects-enabled</code> (P1.3) has run for a session or two — "
                + "the sweep forward-checkpoints a sample of rejected evaluations so their 30-min outcome can be "
                + "measured. Until then there is no outcome to attribute to each blocker.</p>";
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static String pct(double frac) {
        return String.format(Locale.ROOT, "%.1f%%", frac * 100.0);
    }

    private static String signedPct(double v) {
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
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
