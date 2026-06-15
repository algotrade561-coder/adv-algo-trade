package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Phase 3 analyzer plugin for {@link StrategyType#DIRECTIONAL_BUY}. Renders the
 * six-filter pass/fail funnel (VWAP → breakout → volume → OI → IV → RSI → score).
 */
@Component
public class DirectionalBuyAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(DirectionalBuyAnalyzerPlugin.class);

    static final String[] FUNNEL_STAGES = {
            "vwap", "breakout", "volume", "oi", "iv", "rsi", "score"
    };

    static final String[] FUNNEL_LABELS = {
            "VWAP / trend", "Breakout", "Volume spike", "OI", "IV", "RSI", "Min score"
    };

    @Override
    public StrategyType strategy() {
        return StrategyType.DIRECTIONAL_BUY;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.DIRECTIONAL_BUY)) {
            return List.of();
        }
        return List.of(sixFilterFunnel(query));
    }

    public AnalyzerSection sixFilterFunnel(TuningEventQuery query) {
        try {
            List<Path> evalFiles = query.store().listEventFiles(
                    StrategyType.DIRECTIONAL_BUY, TuningEventType.EVALUATION,
                    query.fromDate(), query.toDate());
            if (evalFiles.isEmpty()) {
                return AnalyzerSection.htmlOnly("6-filter pass/fail funnel",
                        "<p><em>No Directional Buy evaluation data in the selected window.</em></p>");
            }
            String glob = globOf(evalFiles);
            StringBuilder funnelCols = new StringBuilder();
            for (int i = 0; i < FUNNEL_STAGES.length; i++) {
                if (i > 0) {
                    funnelCols.append(", ");
                }
                String stage = FUNNEL_STAGES[i];
                funnelCols.append("AVG(CASE WHEN TRY_CAST(json_extract_string(attr_extra, '$.funnel_")
                        .append(stage)
                        .append("') AS BOOLEAN) THEN 1.0 ELSE 0.0 END) AS pass_")
                        .append(stage);
            }

            String sql = ""
                    + "WITH evals AS ("
                    + "  SELECT outcome, blocker, attr_extra "
                    + "  FROM read_csv_auto([" + glob + "], header=true)"
                    + ") "
                    + "SELECT COUNT(*) AS total, "
                    + funnelCols
                    + " FROM evals";

            List<Map<String, Object>> summaryRows = query.store().query(sql);
            if (summaryRows.isEmpty()) {
                return AnalyzerSection.htmlOnly("6-filter pass/fail funnel",
                        "<p><em>No evaluations matched the query.</em></p>");
            }

            Map<String, Object> summary = summaryRows.get(0);
            long total = ((Number) summary.get("total")).longValue();

            String blockerSql = ""
                    + "SELECT COALESCE(blocker, 'unknown') AS blocker, COUNT(*) AS cnt "
                    + "FROM read_csv_auto([" + glob + "], header=true) "
                    + "WHERE outcome = 'BLOCKED' "
                    + "GROUP BY 1 ORDER BY cnt DESC LIMIT 10";
            List<Map<String, Object>> blockers = query.store().query(blockerSql);

            return renderFunnel(total, summary, blockers);
        } catch (TuningQueryException ex) {
            log.warn("[DirectionalBuyAnalyzerPlugin] funnel query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly("6-filter pass/fail funnel",
                    "<p class='error'>Query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    AnalyzerSection renderFunnel(long total,
                                 Map<String, Object> passRates,
                                 List<Map<String, Object>> blockers) {
        StringBuilder html = new StringBuilder(1024);
        html.append("<p>Evaluations: <strong>").append(total).append("</strong></p>");
        html.append("<table class='funnel'><thead><tr>")
                .append("<th>Stage</th><th>Pass rate</th>")
                .append("</tr></thead><tbody>");
        for (int i = 0; i < FUNNEL_STAGES.length; i++) {
            Object rateObj = passRates.get("pass_" + FUNNEL_STAGES[i]);
            double rate = rateObj == null ? 0 : ((Number) rateObj).doubleValue() * 100.0;
            html.append("<tr><td>").append(FUNNEL_LABELS[i]).append("</td><td>")
                    .append(String.format(Locale.ROOT, "%.0f%%", rate))
                    .append("</td></tr>");
        }
        html.append("</tbody></table>");

        html.append("<h4>Top blockers</h4><table><thead><tr>")
                .append("<th>Blocker</th><th>Count</th></tr></thead><tbody>");
        if (blockers.isEmpty()) {
            html.append("<tr><td colspan='2'><em>No blocked evaluations</em></td></tr>");
        } else {
            for (Map<String, Object> row : blockers) {
                html.append("<tr><td>").append(escape(String.valueOf(row.get("blocker"))))
                        .append("</td><td>").append(row.get("cnt")).append("</td></tr>");
            }
        }
        html.append("</tbody></table>");

        List<Map<String, Object>> dataRows = new ArrayList<>();
        for (int i = 0; i < FUNNEL_STAGES.length; i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("stage", FUNNEL_STAGES[i]);
            row.put("label", FUNNEL_LABELS[i]);
            Object rateObj = passRates.get("pass_" + FUNNEL_STAGES[i]);
            row.put("passRate", rateObj == null ? 0.0 : ((Number) rateObj).doubleValue());
            dataRows.add(row);
        }

        return new AnalyzerSection("6-filter pass/fail funnel",
                html.toString(),
                Map.of("total", total, "stages", dataRows, "blockers", blockers));
    }

    private static String globOf(List<Path> files) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("'").append(files.get(i).toString().replace("'", "''")).append("'");
        }
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
