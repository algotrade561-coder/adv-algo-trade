package com.algo.trade.tuning.analyzer;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the DuckDB table-expression that reads a strategy's events of a given type over the report window,
 * spanning <b>both</b> the recent un-rolled CSVs ({@code events/<date>/…}) and the rolled Parquet archive
 * ({@code archive/…}) — so a report works for historical multi-day ranges, not just today.
 *
 * <p><b>Regression-safe by design:</b> when only CSVs exist (the common recent-day case) it returns the exact
 * same {@code read_csv_auto([...], header=true, union_by_name=true)} expression the analyzers used before, so
 * recent-day reports are byte-for-byte unchanged. Parquet is layered in only when a rolled day is in range,
 * via {@code UNION ALL BY NAME} (column-aligned, missing columns → NULL). The only new risk surface is the
 * historical/UNION path; the recent-day path is untouched.
 */
public final class EventScan {

    private EventScan() {}

    /**
     * When true (default), every analyzer source is scoped to a real trading session — 09:15–15:30 IST,
     * Mon–Fri — so off-hours / weekend rows (e.g. the bot scanning on a Sunday with a dead feed, which
     * produced thousands of {@code data_stale} rows that inflated the metric) never reach the report.
     * Override with {@code -Dtuning.report.market-hours-only=false} to analyze raw 24x7 capture.
     * NOTE: weekday exchange holidays are not excluded here (would need the holiday calendar) — a small
     * residual; weekends + the session window remove the dominant off-hours noise.
     */
    private static final boolean MARKET_HOURS_ONLY =
            !"false".equalsIgnoreCase(System.getProperty("tuning.report.market-hours-only", "true"));

    /** SQL predicate (on {@code eventTime}, stored UTC) that keeps only Mon–Fri 09:15–15:30 IST rows. */
    private static final String MARKET_HOURS_PREDICATE =
            "TRY_CAST(eventTime AS TIMESTAMP) IS NOT NULL "
            + "AND EXTRACT(dow FROM (TRY_CAST(eventTime AS TIMESTAMP) + INTERVAL '5 hours 30 minutes')) NOT IN (0, 6) "
            + "AND CAST((TRY_CAST(eventTime AS TIMESTAMP) + INTERVAL '5 hours 30 minutes') AS TIME) "
            + "    BETWEEN TIME '09:15:00' AND TIME '15:30:00'";

    /** Wrap a data source expression in the market-hours filter (no-op when the flag is off). */
    private static String scoped(String inner) {
        return MARKET_HOURS_ONLY ? "(SELECT * FROM " + inner + " WHERE " + MARKET_HOURS_PREDICATE + ")" : inner;
    }

    /** True if any CSV or Parquet data exists for this strategy/type in the window. */
    public static boolean hasData(TuningEventQuery q, StrategyType strat, TuningEventType type) {
        return !q.store().listEventFiles(strat, type, q.fromDate(), q.toDate()).isEmpty()
                || !q.store().listArchiveFiles(strat, type, q.fromDate(), q.toDate()).isEmpty();
    }

    /**
     * SQL table-expression for {@code strat}/{@code type} over the window. Caller is expected to have checked
     * {@link #hasData}; if nothing exists this returns an empty-but-valid relation so a query won't crash, but
     * column references would be unknown — so always guard with {@link #hasData} first (analyzers do).
     */
    public static String source(TuningEventQuery q, StrategyType strat, TuningEventType type) {
        List<Path> csv = q.store().listEventFiles(strat, type, q.fromDate(), q.toDate());
        List<Path> pq = q.store().listArchiveFiles(strat, type, q.fromDate(), q.toDate());
        // Single-source must stay a BARE table function so `FROM read_csv_auto(...)` (and `... s` / JOIN)
        // parse. The UNION must wrap each table function in `SELECT * FROM …` and parenthesise the whole —
        // DuckDB rejects `read_parquet(...) UNION ALL BY NAME read_csv_auto(...)` directly. (Both forms
        // validated against the real 06-22 Parquet ∪ 06-24 CSV.)
        String csvBare = csv.isEmpty() ? null
                : "read_csv_auto([" + glob(csv) + "], header=true, union_by_name=true)";
        String pqBare = pq.isEmpty() ? null
                : "read_parquet([" + glob(pq) + "], union_by_name=true)";
        if (csvBare != null && pqBare != null) {
            return scoped("(SELECT * FROM " + pqBare + " UNION ALL BY NAME SELECT * FROM " + csvBare + ")");
        }
        if (pqBare != null) {
            return scoped(pqBare);
        }
        if (csvBare != null) {
            return scoped(csvBare);
        }
        return "(SELECT NULL WHERE 1=0)"; // empty sentinel — no eventTime column, leave unscoped
    }

    private static String glob(List<Path> files) {
        List<String> quoted = new ArrayList<>(files.size());
        for (Path p : files) {
            quoted.add("'" + p.toString().replace("'", "''") + "'");
        }
        return String.join(",", quoted);
    }
}
