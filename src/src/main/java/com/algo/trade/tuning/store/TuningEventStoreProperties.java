package com.algo.trade.tuning.store;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DuckDB engine settings for {@link TuningEventStore}. Registered via
 * {@code @EnableConfigurationProperties} on the application class.
 *
 * <p>Defaults are conservative — sized for the trading JVM, where this store is only
 * used for cheap dashboard / drill-down queries against summary tables. The forked
 * analyzer JVM (Phase 6) will override {@code tuning.store.memory-limit} to {@code 1GB}
 * via its dedicated Spring profile so heavy report queries get the headroom they need.</p>
 *
 * <pre>
 * tuning:
 *   store:
 *     memory-limit: 256MB        # DuckDB native (off-heap)
 *     threads: 2
 *     temp-directory: /var/tmp/duckdb
 *     statement-timeout-sec: 60
 * </pre>
 */
@ConfigurationProperties(prefix = "tuning.store")
public class TuningEventStoreProperties {

    /** DuckDB native off-heap memory cap. Note: this is NOT JVM heap — DuckDB runs in C++. */
    private String memoryLimit = "256MB";

    /** Threads DuckDB may use for query execution. Keep low in trading JVM. */
    private int threads = 2;

    /** Spill-to-disk directory used when a query exceeds {@link #memoryLimit}. */
    /**
     * Spill-to-disk directory used when a query exceeds {@link #memoryLimit}. Relative
     * paths resolve from the JVM working dir so the default stays inside the app tree
     * (matches {@code reports/tuning/html} and {@code reports/tuning/archive}).
     */
    private String tempDirectory = "reports/tuning/duckdb-tmp";

    /** Per-query timeout. 0 disables. */
    private int statementTimeoutSec = 60;

    public String getMemoryLimit() { return memoryLimit; }
    public void setMemoryLimit(String v) { this.memoryLimit = v; }

    public int getThreads() { return threads; }
    public void setThreads(int v) { this.threads = Math.max(1, v); }

    public String getTempDirectory() { return tempDirectory; }
    public void setTempDirectory(String v) { this.tempDirectory = v; }

    public int getStatementTimeoutSec() { return statementTimeoutSec; }
    public void setStatementTimeoutSec(int v) { this.statementTimeoutSec = Math.max(0, v); }
}
