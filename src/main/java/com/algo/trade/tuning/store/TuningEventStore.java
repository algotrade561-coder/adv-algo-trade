package com.algo.trade.tuning.store;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DuckDB-backed query layer over the tuning event store.
 *
 * <h2>Where it runs</h2>
 * <ul>
 *   <li>Trading JVM — dashboard / per-strategy detail pages issue cheap aggregate
 *       queries through this store with conservative {@code memory_limit} (default
 *       256 MB native off-heap). Heap impact: negligible — DuckDB native memory is
 *       not in the JVM heap.</li>
 *   <li>Forked analyzer JVM (Phase 6) — runs the full EOD report. Overrides
 *       {@code tuning.store.memory-limit} to 1 GB via its dedicated Spring profile.
 *       Path-prunes Parquet files for sub-second aggregations over years of data.</li>
 * </ul>
 *
 * <h2>Connection model</h2>
 * Each query opens a fresh in-memory DuckDB connection ({@code jdbc:duckdb:}), applies
 * the configured settings, runs the query, and closes the connection. No long-lived
 * connection state — DuckDB is configured per-call. This trades ~1 ms connection setup
 * for full thread-safety + zero shared mutable state.
 *
 * <h2>File discovery</h2>
 * {@link #listEventFiles} walks the on-disk layout from § 4.2.1 of the design:
 * {@code reports/tuning/events/&lt;date&gt;/&lt;strategy&gt;/&lt;event_type&gt;.csv}. The forked
 * analyzer's Parquet archive ({@code reports/tuning/archive/strategy=…/event=…/year=…/…})
 * is queried directly via DuckDB's glob patterns once {@link ParquetRollerService}
 * lands (Commit 9). For Phase 1 only CSV files are present, so the discovery walks
 * the per-date / per-strategy directories.
 *
 * <h2>Failure mode</h2>
 * Every {@link SQLException} is wrapped in {@link TuningQueryException} with the
 * offending SQL truncated for the log. The trading thread never gets a raw JDBC
 * exception bubbling up.
 */
@Component
public class TuningEventStore {

    private static final Logger log = LoggerFactory.getLogger(TuningEventStore.class);
    private static final String DUCKDB_URL = "jdbc:duckdb:";
    private static final String DRIVER_CLASS = "org.duckdb.DuckDBDriver";

    private final Path baseDir;
    private final TuningEventStoreProperties properties;

    @org.springframework.beans.factory.annotation.Autowired
    public TuningEventStore(@Value("${tuning.capture.base-dir:reports/tuning/events}") String baseDirPath,
                             TuningEventStoreProperties properties) {
        this(Path.of(baseDirPath), properties);
    }

    /** Test-friendly constructor. */
    public TuningEventStore(Path baseDir, TuningEventStoreProperties properties) {
        this.baseDir = baseDir;
        this.properties = properties;
        // Force-load the driver so a missing native lib surfaces at startup, not on first query.
        try {
            Class.forName(DRIVER_CLASS);
        } catch (ClassNotFoundException ex) {
            throw new IllegalStateException("DuckDB JDBC driver " + DRIVER_CLASS + " not on classpath", ex);
        }
        // Pre-create the DuckDB temp directory at startup so the Tuning Capture Health
        // widget shows OK from boot — otherwise it stays "Missing" until the first
        // query that spills to disk.
        String tempDir = properties.getTempDirectory();
        if (tempDir != null && !tempDir.isBlank()) {
            try {
                java.nio.file.Files.createDirectories(Path.of(tempDir));
            } catch (java.io.IOException ex) {
                log.warn("[TuningEventStore] failed to pre-create DuckDB temp dir {}: {}",
                        tempDir, ex.getMessage());
            }
        }
    }

    // ── File discovery ────────────────────────────────────────────────────

    /**
     * Lists existing CSV files for {@code strategy} of type {@code type} over the
     * inclusive date range {@code [from, to]}. Returns an empty list for any date that
     * has no matching file. Always returns a fresh, mutable {@link ArrayList}.
     */
    public List<Path> listEventFiles(StrategyType strategy, TuningEventType type,
                                      LocalDate from, LocalDate to) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.isAfter(to)) {
            return new ArrayList<>();
        }
        List<Path> out = new ArrayList<>();
        String strategyDir = strategy.name().toLowerCase(Locale.ROOT);
        String filename = type.fileBaseName() + ".csv";
        for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
            Path file = baseDir.resolve(d.toString()).resolve(strategyDir).resolve(filename);
            if (Files.exists(file)) {
                out.add(file);
            }
        }
        return out;
    }

    // ── Query API ─────────────────────────────────────────────────────────

    /**
     * Executes {@code sql} with optional positional parameters and returns each row as
     * a {@code LinkedHashMap} preserving column order. Empty result set returns an
     * empty list.
     */
    public List<Map<String, Object>> query(String sql, Object... params) {
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            applyTimeout(ps);
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                return resultSetToList(rs);
            }
        } catch (SQLException ex) {
            throw new TuningQueryException("query failed", sql, ex);
        }
    }

    /**
     * Executes a query and maps each row via {@code mapper}. Useful for typed records.
     */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
        Objects.requireNonNull(mapper, "mapper");
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            applyTimeout(ps);
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(mapper.map(rs));
                }
                return out;
            }
        } catch (SQLException ex) {
            throw new TuningQueryException("query failed", sql, ex);
        }
    }

    /** Executes a non-query statement (DDL, COPY, etc.). */
    public void execute(String sql) {
        try (Connection conn = openConnection();
             Statement st = conn.createStatement()) {
            applyTimeout(st);
            st.execute(sql);
        } catch (SQLException ex) {
            throw new TuningQueryException("execute failed", sql, ex);
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    Connection openConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(DUCKDB_URL);
        try (Statement st = conn.createStatement()) {
            st.execute("SET memory_limit = '" + properties.getMemoryLimit() + "'");
            st.execute("SET threads = " + properties.getThreads());
            // Spill to disk before OOM rather than failing the query.
            String tempDir = properties.getTempDirectory();
            if (tempDir != null && !tempDir.isBlank()) {
                Files.createDirectories(Path.of(tempDir));
                st.execute("SET temp_directory = '" + tempDir + "'");
            }
        } catch (SQLException | java.io.IOException ex) {
            try { conn.close(); } catch (SQLException ignore) {}
            if (ex instanceof SQLException sql) throw sql;
            throw new SQLException("failed to initialise DuckDB connection", ex);
        }
        return conn;
    }

    private void applyTimeout(Statement st) throws SQLException {
        int sec = properties.getStatementTimeoutSec();
        if (sec > 0) {
            st.setQueryTimeout(sec);
        }
    }

    private static void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            ps.setObject(i + 1, params[i]);
        }
    }

    private static List<Map<String, Object>> resultSetToList(ResultSet rs) throws SQLException {
        ResultSetMetaData meta = rs.getMetaData();
        int n = meta.getColumnCount();
        List<Map<String, Object>> out = new ArrayList<>();
        while (rs.next()) {
            Map<String, Object> row = new LinkedHashMap<>(n);
            for (int i = 1; i <= n; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            out.add(row);
        }
        return out;
    }

    /** Functional interface for typed row mapping. */
    @FunctionalInterface
    public interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}
