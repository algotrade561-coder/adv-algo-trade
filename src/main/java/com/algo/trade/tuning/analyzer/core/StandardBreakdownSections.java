package com.algo.trade.tuning.analyzer.core;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** DuckDB-backed standard report sections for Phase 6. */
public final class StandardBreakdownSections {

    private StandardBreakdownSections() {
    }

    public static AnalyzerSection byDay(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "By day",
                "SELECT outcome, COUNT(*) AS n FROM evals GROUP BY outcome ORDER BY n DESC",
                evalFiles(query, strategy));
    }

    public static AnalyzerSection byIndex(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "By index",
                "SELECT index, COUNT(*) AS n FROM evals GROUP BY index ORDER BY n DESC",
                evalFiles(query, strategy));
    }

    public static AnalyzerSection signalSummary(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "Signals",
                "SELECT COUNT(*) AS signals, AVG(entryPremium) AS avg_premium FROM sigs",
                signalFiles(query, strategy));
    }

    public static AnalyzerSection exitSummary(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "Exits",
                "SELECT exitReason, COUNT(*) AS n, AVG(realizedPnlPct) AS avg_pnl "
                        + "FROM exits GROUP BY exitReason ORDER BY n DESC",
                exitFiles(query, strategy));
    }

    public static AnalyzerSection fillRatio(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "Execution fill ratio",
                "SELECT stage, COUNT(*) AS n, AVG(CAST(filledQty AS DOUBLE) / NULLIF(requestedQty, 0)) AS fill_ratio "
                        + "FROM execs WHERE requestedQty > 0 GROUP BY stage ORDER BY n DESC",
                executionFiles(query, strategy));
    }

    private static AnalyzerSection querySection(TuningEventQuery query, StrategyType strategy,
                                                  String title, String sql, List<Path> files) {
        if (files.isEmpty()) {
            return AnalyzerSection.htmlOnly(title,
                    "<p><em>No data for " + strategy.displayName() + " in this window.</em></p>");
        }
        try {
            String glob = globOf(files);
            String wrapped = switch (title) {
                case "Signals" -> ""
                        + "WITH sigs AS (SELECT * FROM read_csv_auto([" + glob + "], header=true)) "
                        + sql;
                case "Exits" -> ""
                        + "WITH exits AS (SELECT * FROM read_csv_auto([" + glob + "], header=true)) "
                        + sql;
                case "Execution fill ratio" -> ""
                        + "WITH execs AS (SELECT * FROM read_csv_auto([" + glob + "], header=true)) "
                        + sql;
                default -> ""
                        + "WITH evals AS (SELECT * FROM read_csv_auto([" + glob + "], header=true)) "
                        + sql;
            };
            List<Map<String, Object>> rows = query.store().query(wrapped);
            return AnalyzerSection.htmlOnly(title, tableHtml(rows));
        } catch (TuningQueryException ex) {
            return AnalyzerSection.htmlOnly(title,
                    "<p class=\"error\">Query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    private static List<Path> evalFiles(TuningEventQuery query, StrategyType strategy) {
        return query.store().listEventFiles(strategy, TuningEventType.EVALUATION,
                query.fromDate(), query.toDate());
    }

    private static List<Path> signalFiles(TuningEventQuery query, StrategyType strategy) {
        return query.store().listEventFiles(strategy, TuningEventType.SIGNAL,
                query.fromDate(), query.toDate());
    }

    private static List<Path> exitFiles(TuningEventQuery query, StrategyType strategy) {
        return query.store().listEventFiles(strategy, TuningEventType.EXIT,
                query.fromDate(), query.toDate());
    }

    private static List<Path> executionFiles(TuningEventQuery query, StrategyType strategy) {
        return query.store().listEventFiles(strategy, TuningEventType.EXECUTION,
                query.fromDate(), query.toDate());
    }

    private static String globOf(List<Path> files) {
        return files.stream()
                .map(p -> "'" + p.toString().replace("\\", "/") + "'")
                .reduce((a, b) -> a + ", " + b)
                .orElse("''");
    }

    static String tableHtml(List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return "<p><em>No rows.</em></p>";
        }
        Map<String, Object> first = rows.getFirst();
        StringBuilder html = new StringBuilder("<table><thead><tr>");
        for (String col : first.keySet()) {
            html.append("<th>").append(escape(col)).append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (Map<String, Object> row : rows) {
            html.append("<tr>");
            for (String col : first.keySet()) {
                Object v = row.get(col);
                html.append("<td>").append(v == null ? "" : escape(String.valueOf(v))).append("</td>");
            }
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        return html.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;");
    }
}
