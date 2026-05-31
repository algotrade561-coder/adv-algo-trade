package com.algo.trade.tuning.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 1, Commit 8 — DuckDB-backed store. Verifies file discovery + glob,
 * raw query + typed mapper + execute, empty-result handling, and SQL error wrapping
 * via {@link TuningQueryException}. Uses real DuckDB JDBC against fixture CSV files.
 */
class TuningEventStoreTest {

    @TempDir Path tempDir;

    private TuningEventStoreProperties properties;
    private TuningEventStore store;

    @BeforeEach
    void setUp() {
        properties = new TuningEventStoreProperties();
        properties.setMemoryLimit("64MB");                                  // conservative for tests
        properties.setThreads(1);
        properties.setTempDirectory(tempDir.resolve("duckdb-tmp").toString());
        properties.setStatementTimeoutSec(10);
        store = new TuningEventStore(tempDir, properties);
    }

    @Test
    void emptyEventsDir_listEventFilesReturnsEmpty() {
        List<Path> files = store.listEventFiles(
                StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 7));
        assertThat(files).isEmpty();
    }

    @Test
    void listEventFiles_findsOnlyExistingFilesInDateRange() throws Exception {
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                "eventTime,index,correlationKey\n"
                        + "2026-06-01T09:30:00Z,NIFTY,decision-A\n");
        writeFixtureCsv(LocalDate.of(2026, 6, 3), "oi_momentum", "signal",
                "eventTime,index,correlationKey\n"
                        + "2026-06-03T09:30:00Z,NIFTY,decision-B\n");
        // OI Shift Trap on the same day — different strategy, should not appear.
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_shift_trap", "signal",
                "eventTime,index,correlationKey\n"
                        + "2026-06-01T10:00:00Z,SENSEX,decision-C\n");

        List<Path> files = store.listEventFiles(
                StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL,
                LocalDate.of(2026, 5, 30), LocalDate.of(2026, 6, 5));

        assertThat(files).hasSize(2);
        assertThat(files).allMatch(p -> p.toString().contains("oi_momentum"));
        assertThat(files).allMatch(p -> p.toString().endsWith("signal.csv"));
    }

    @Test
    void listEventFiles_dateRangeReversed_returnsEmpty() {
        // from > to is treated as an empty range, not an error.
        List<Path> files = store.listEventFiles(
                StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL,
                LocalDate.of(2026, 6, 5), LocalDate.of(2026, 6, 1));
        assertThat(files).isEmpty();
    }

    @Test
    void rawQueryReturnsRowsAsLinkedHashMap() {
        List<Map<String, Object>> rows = store.query("SELECT 1 AS x, 'hello' AS y");
        assertThat(rows).hasSize(1);
        Map<String, Object> r = rows.get(0);
        assertThat(r.get("x").toString()).isEqualTo("1");
        assertThat(r.get("y")).isEqualTo("hello");
        // LinkedHashMap preserves insertion order.
        assertThat(r.keySet()).containsExactly("x", "y");
    }

    @Test
    void queryOverEmptyResult_returnsEmptyList() {
        List<Map<String, Object>> rows = store.query("SELECT 1 WHERE 1 = 0");
        assertThat(rows).isEmpty();
    }

    @Test
    void queryWithParameters() {
        List<Map<String, Object>> rows = store.query("SELECT ? AS n", 42);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("n").toString()).isEqualTo("42");
    }

    @Test
    void typedRowMapper() {
        List<Integer> ints = store.query(
                "SELECT * FROM (VALUES (10), (20), (30)) AS t(n)",
                rs -> rs.getInt("n"));
        assertThat(ints).containsExactly(10, 20, 30);
    }

    @Test
    void executeRunsDdl() {
        // CREATE TABLE in an in-memory connection — table disappears when the
        // connection closes, but execute() should not throw.
        store.execute("CREATE TABLE foo (x INT)");
        // Confirm by running a query against a fresh connection — table is gone.
        List<Map<String, Object>> rows = store.query("SELECT 1 AS ok");
        assertThat(rows).hasSize(1);
    }

    @Test
    void readCsvViaDuckDb_returnsHeaderAndRowsCorrectly() throws Exception {
        Path csv = writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                "eventTime,index,correlationKey,score\n"
                        + "2026-06-01T09:30:00Z,NIFTY,decision-A,72\n"
                        + "2026-06-01T09:35:00Z,NIFTY,decision-B,65\n"
                        + "2026-06-01T09:40:00Z,SENSEX,decision-C,80\n");

        List<Map<String, Object>> rows = store.query(
                "SELECT index, correlationKey, score FROM read_csv_auto(?) WHERE index = 'NIFTY'",
                csv.toString());

        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("correlationKey"))
                .containsExactlyInAnyOrder("decision-A", "decision-B");
    }

    @Test
    void aggregateQueryOverMultipleCsvFiles() throws Exception {
        // Three days of synthetic signals across two strategies.
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                "eventTime,index,correlationKey,score\n"
                        + "2026-06-01T09:30:00Z,NIFTY,d1,72\n"
                        + "2026-06-01T09:35:00Z,NIFTY,d2,80\n");
        writeFixtureCsv(LocalDate.of(2026, 6, 2), "oi_momentum", "signal",
                "eventTime,index,correlationKey,score\n"
                        + "2026-06-02T10:00:00Z,NIFTY,d3,65\n");

        List<Path> files = store.listEventFiles(
                StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 2));
        assertThat(files).hasSize(2);

        // DuckDB can read multiple CSVs via a glob pattern; we point at the per-strategy dir.
        String glob = tempDir.resolve("*/oi_momentum/signal.csv").toString();
        List<Map<String, Object>> rows = store.query(
                "SELECT COUNT(*) AS n, AVG(score) AS avg_score FROM read_csv_auto(?, header=true)",
                glob);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("n").toString()).isEqualTo("3");
        // (72 + 80 + 65) / 3 = 72.333…
        double avg = ((Number) rows.get(0).get("avg_score")).doubleValue();
        assertThat(avg).isBetween(72.0, 73.0);
    }

    @Test
    void malformedSqlIsWrappedInTuningQueryException() {
        assertThatThrownBy(() -> store.query("THIS IS NOT VALID SQL"))
                .isInstanceOf(TuningQueryException.class)
                .hasMessageContaining("query failed")
                .hasCauseInstanceOf(java.sql.SQLException.class);
    }

    @Test
    void duckDbDriverIsAvailable() throws Exception {
        // Drive-load via the same mechanism the store uses. If this throws, the JAR
        // isn't on the classpath or the native library failed to load.
        Class<?> driver = Class.forName("org.duckdb.DuckDBDriver");
        assertThat(driver).isNotNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Path writeFixtureCsv(LocalDate date, String strategyDir, String type, String contents)
            throws Exception {
        Path dir = tempDir.resolve(date.toString()).resolve(strategyDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(type + ".csv");
        Files.writeString(file, contents);
        return file;
    }
}
