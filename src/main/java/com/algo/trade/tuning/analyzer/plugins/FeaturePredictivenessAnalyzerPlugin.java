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
 * P2 / §3c #3 — <b>Feature predictiveness (information coefficient)</b>. For each captured signal feature,
 * the correlation between the feature value and the realised 30-min forward magnitude — overall and split
 * by VIX regime (the prior analyses found the only stable edge is VIX→magnitude, so the split matters).
 *
 * <p>This is the "stop tuning coin-flips" table: a feature whose |IC| is ~0 in both regimes carries no
 * predictive signal and shouldn't be weighted. Computed with DuckDB {@code corr()} over signal⋈forward by
 * correlationKey. Per §10b each cell shows the sample {@code n}; treat |IC| below ~0.1 as noise. Fail-safe;
 * placeholder until signals have forward checkpoints.
 */
@Component
public class FeaturePredictivenessAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(FeaturePredictivenessAnalyzerPlugin.class);

    /** Low/high VIX split (India VIX). Low-vol vs high-vol regime. */
    private static final double VIX_SPLIT = 14.0;

    /** Numeric candidate features (attr_extra keys) to score against forward magnitude. */
    private static final String[] FEATURES = {
            "biasScore", "operatorScore", "momentumMagnitudePct", "pcr", "pcrSlope5m",
            "sustainedDriftPct", "breakoutDistancePct", "rangePct30m", "vix"
    };

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public int order() {
        return 40;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(section(query));
    }

    AnalyzerSection section(TuningEventQuery query) {
        final String title = "Feature predictiveness (IC vs 30-min forward magnitude)";
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL)
                    || !EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT)) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>Needs signals joined to forward checkpoints — not present in this window yet.</em></p>");
            }
            String sigSource = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL);
            String fwdSource = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT);

            StringBuilder sel = new StringBuilder();
            sel.append("COUNT(*) AS n, ")
               .append("SUM(CASE WHEN vix < ").append(VIX_SPLIT).append(" THEN 1 ELSE 0 END) AS n_lo, ")
               .append("SUM(CASE WHEN vix >= ").append(VIX_SPLIT).append(" THEN 1 ELSE 0 END) AS n_hi");
            for (String f : FEATURES) {
                sel.append(", ROUND(corr(").append(f).append(", mag),3) AS ic_").append(f);
                sel.append(", ROUND(corr(").append(f).append(", mag) FILTER (WHERE vix < ").append(VIX_SPLIT)
                   .append("),3) AS iclo_").append(f);
                sel.append(", ROUND(corr(").append(f).append(", mag) FILTER (WHERE vix >= ").append(VIX_SPLIT)
                   .append("),3) AS ichi_").append(f);
            }

            StringBuilder jcols = new StringBuilder();
            for (String f : FEATURES) {
                jcols.append("TRY_CAST(json_extract_string(s.attr_extra,'$.").append(f).append("') AS DOUBLE) AS ")
                     .append(f).append(", ");
            }

            String sql = ""
                    + "WITH j AS ("
                    + "  SELECT " + jcols
                    + "    GREATEST(ABS(TRY_CAST(f.fwdMfe30mPct AS DOUBLE)), ABS(TRY_CAST(f.fwdMae30mPct AS DOUBLE))) AS mag"
                    + "  FROM " + sigSource + " s"
                    + "  JOIN " + fwdSource + " f USING (correlationKey)"
                    + "  WHERE f.fwdMfe30mPct IS NOT NULL"
                    + ") SELECT " + sel + " FROM j";

            List<Map<String, Object>> rows = query.store().query(sql);
            return render(title, rows.isEmpty() ? Map.of() : rows.get(0));
        } catch (TuningQueryException ex) {
            log.warn("[FeaturePredictivenessAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Feature-IC query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    AnalyzerSection render(String title, Map<String, Object> m) {
        long n = asLong(m.get("n"));
        if (n == 0) {
            return AnalyzerSection.htmlOnly(title, "<p><em>No joined signal/outcome rows yet.</em></p>");
        }
        long nLo = asLong(m.get("n_lo"));
        long nHi = asLong(m.get("n_hi"));
        StringBuilder h = new StringBuilder(1024);
        h.append("<p>IC = correlation of the feature with the 30-min forward |move|. <strong>|IC| &lt; ~0.10 "
                + "is noise — a coin-flip you shouldn't tune.</strong> Split by VIX ")
                .append(String.format(Locale.ROOT, "(low &lt; %.0f, n=%d; high ≥ %.0f, n=%d).</p>",
                        VIX_SPLIT, nLo, VIX_SPLIT, nHi));
        h.append("<table class='feature-ic'><thead><tr><th>Feature</th>"
                + "<th>IC (all, n=").append(n).append(")</th><th>IC (low-VIX)</th><th>IC (high-VIX)</th>"
                + "<th>read</th></tr></thead><tbody>");
        // Rank by |overall IC| desc.
        Map<String, Double> absIc = new LinkedHashMap<>();
        for (String f : FEATURES) {
            absIc.put(f, Math.abs(asDoubleN(m.get("ic_" + f))));
        }
        List<String> ordered = new ArrayList<>(absIc.keySet());
        ordered.sort((a, b) -> Double.compare(absIc.get(b), absIc.get(a)));
        for (String f : ordered) {
            double ic = asDoubleN(m.get("ic_" + f));
            double icLo = asDoubleN(m.get("iclo_" + f));
            double icHi = asDoubleN(m.get("ichi_" + f));
            String flag = Math.abs(ic) >= 0.15 ? "🟢 predictive"
                    : Math.abs(ic) >= 0.10 ? "🟠 weak" : "🔴 coin-flip";
            h.append("<tr><td>").append(escape(f)).append("</td>")
                    .append("<td>").append(fmt(ic)).append("</td>")
                    .append("<td>").append(fmt(icLo)).append("</td>")
                    .append("<td>").append(fmt(icHi)).append("</td>")
                    .append("<td>").append(flag).append("</td></tr>");
        }
        h.append("</tbody></table>");
        return new AnalyzerSection(title, h.toString(), Map.of("n", n));
    }

    private static String fmt(double v) {
        return Double.isNaN(v) ? "n/a" : String.format(Locale.ROOT, "%+.3f", v);
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    /** NaN when null/absent (corr returns null for a constant/empty group). */
    private static double asDoubleN(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : Double.NaN;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
