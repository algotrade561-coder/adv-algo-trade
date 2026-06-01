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

/** DuckDB-backed standard report sections. */
public final class StandardBreakdownSections {

    private StandardBreakdownSections() {
    }

    /** Top-level overview: episode counts, ticks, time range. */
    public static AnalyzerSection overview(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "Overview",
                "SELECT COUNT(*) AS episodes, "
                        + "SUM(episodeTickCount) AS total_ticks, "
                        + "ROUND(AVG(episodeTickCount), 1) AS avg_ticks_per_episode, "
                        + "COUNT(DISTINCT NULLIF(blocker, '')) AS distinct_blockers, "
                        + "COUNT(DISTINCT index) AS distinct_indexes, "
                        + "MIN(eventTime) AS first_at, "
                        + "MAX(eventTime) AS last_at "
                        + "FROM evals",
                evalFiles(query, strategy));
    }

    public static AnalyzerSection byDay(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "Outcomes",
                "SELECT outcome, COUNT(*) AS episodes, SUM(episodeTickCount) AS ticks "
                        + "FROM evals GROUP BY outcome ORDER BY ticks DESC",
                evalFiles(query, strategy));
    }

    public static AnalyzerSection byIndex(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "By index",
                "SELECT index, COUNT(*) AS n, SUM(episodeTickCount) AS total_ticks "
                        + "FROM evals GROUP BY index ORDER BY total_ticks DESC",
                evalFiles(query, strategy));
    }

    /**
     * Universal top-blockers ranking. COALESCE blocker -> JSON firstFailedFilter
     * -> '(skipped)' so SKIPPED rows still surface a meaningful key.
     */
    public static AnalyzerSection topBlockers(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "Top blockers",
                "SELECT COALESCE(NULLIF(blocker, ''), "
                        + "  json_extract_string(attr_extra, '$.firstFailedFilter'), "
                        + "  '(skipped)') AS blocker, "
                        + "COUNT(*) AS episodes, "
                        + "SUM(episodeTickCount) AS ticks, "
                        + "ROUND(100.0 * SUM(episodeTickCount) / NULLIF(SUM(SUM(episodeTickCount)) OVER (), 0), 1) AS tick_share_pct "
                        + "FROM evals GROUP BY 1 ORDER BY ticks DESC LIMIT 20",
                evalFiles(query, strategy));
    }

    /**
     * Blocker x index crosstab. Note: leading comma extends the WITH clause
     * that querySection prepends. Two top-level WITH would be a SQL error.
     */
    public static AnalyzerSection blockerByIndex(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "Blocker x index",
                ", ranked AS ("
                        + "  SELECT COALESCE(NULLIF(blocker, ''), "
                        + "    json_extract_string(attr_extra, '$.firstFailedFilter'), "
                        + "    '(skipped)') AS blocker, "
                        + "    SUM(episodeTickCount) AS ticks "
                        + "  FROM evals GROUP BY 1 ORDER BY ticks DESC LIMIT 12"
                        + ") "
                        + "SELECT COALESCE(NULLIF(e.blocker, ''), "
                        + "    json_extract_string(e.attr_extra, '$.firstFailedFilter'), "
                        + "    '(skipped)') AS blocker, "
                        + "  e.index, "
                        + "  COUNT(*) AS episodes, "
                        + "  SUM(e.episodeTickCount) AS ticks "
                        + "FROM evals e JOIN ranked r ON r.blocker = COALESCE(NULLIF(e.blocker, ''), "
                        + "    json_extract_string(e.attr_extra, '$.firstFailedFilter'), '(skipped)') "
                        + "GROUP BY 1, 2 ORDER BY ticks DESC LIMIT 40",
                evalFiles(query, strategy));
    }

    /** Hour-of-day tick distribution (IST). */
    public static AnalyzerSection byHourOfDay(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "By hour of day (IST)",
                "SELECT EXTRACT(hour FROM (CAST(eventTime AS TIMESTAMP) + INTERVAL '5 hours 30 minutes')) AS hour_ist, "
                        + "COUNT(*) AS episodes, SUM(episodeTickCount) AS ticks "
                        + "FROM evals GROUP BY hour_ist ORDER BY hour_ist",
                evalFiles(query, strategy));
    }

    /** Episode size distribution: 1, 2-10, 11-30, 31-60, 61+ tick buckets. */
    public static AnalyzerSection episodeSizeDistribution(TuningEventQuery query, StrategyType strategy) {
        return querySection(query, strategy, "Episode size distribution",
                "SELECT CASE "
                        + "  WHEN episodeTickCount = 1 THEN '1 tick' "
                        + "  WHEN episodeTickCount BETWEEN 2 AND 10 THEN '2-10' "
                        + "  WHEN episodeTickCount BETWEEN 11 AND 30 THEN '11-30' "
                        + "  WHEN episodeTickCount BETWEEN 31 AND 60 THEN '31-60' "
                        + "  ELSE '61+' END AS tick_bucket, "
                        + "COUNT(*) AS episodes, SUM(episodeTickCount) AS total_ticks "
                        + "FROM evals GROUP BY tick_bucket ORDER BY MIN(episodeTickCount)",
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
