package com.algo.trade.tuning.store;

/**
 * Wraps any failure that occurs while running a DuckDB query through
 * {@link TuningEventStore}. Carries the original SQL for debugging.
 */
public class TuningQueryException extends RuntimeException {

    private final String sql;

    public TuningQueryException(String message, String sql, Throwable cause) {
        super(message + " — sql=" + truncate(sql), cause);
        this.sql = sql;
    }

    public String sql() {
        return sql;
    }

    private static String truncate(String s) {
        if (s == null) return "<null>";
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
