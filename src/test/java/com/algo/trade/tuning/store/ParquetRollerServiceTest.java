package com.algo.trade.tuning.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.tuning.recorder.IstDayClock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 1, Commit 9 — Parquet roller. End-to-end fixture tests using real DuckDB
 * to convert fixture CSV → Parquet, verify row counts, build summary, and enforce
 * retention.
 */
class ParquetRollerServiceTest {

    @TempDir Path tempDir;

    private Path eventsDir;
    private Path archiveDir;
    private Path summaryDir;

    private TuningEventStoreProperties storeProps;
    private TuningEventStore store;
    private ParquetRollerProperties rollerProps;
    private ParquetRollerService roller;
    private IstDayClock clock;

    @BeforeEach
    void setUp() {
        eventsDir = tempDir.resolve("events");
        archiveDir = tempDir.resolve("archive");
        summaryDir = tempDir.resolve("summary");

        storeProps = new TuningEventStoreProperties();
        storeProps.setMemoryLimit("64MB");
        storeProps.setThreads(1);
        storeProps.setTempDirectory(tempDir.resolve("duckdb-tmp").toString());
        storeProps.setStatementTimeoutSec(15);
        store = new TuningEventStore(eventsDir, storeProps);

        rollerProps = new ParquetRollerProperties();
        rollerProps.setArchiveBaseDir(archiveDir.toString());
        rollerProps.setSummaryBaseDir(summaryDir.toString());
        rollerProps.setDeleteCsvAfterRoll(true);

        clock = new IstDayClock(Clock.fixed(Instant.parse("2026-06-02T00:35:00+05:30"),
                ZoneId.of("Asia/Kolkata")));
        roller = new ParquetRollerService(eventsDir, rollerProps, store, clock);
    }

    @Test
    void emptyEventsDir_rollDateIsNoop() {
        int n = roller.rollDate(LocalDate.of(2026, 6, 1));
        assertThat(n).isZero();
        assertThat(roller.existingParquetFiles()).isEmpty();
    }

    @Test
    void rollOneCsvProducesParquetAtArchivePath_andDeletesCsv() throws Exception {
        Path csv = writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                """
                eventTime,index,correlationKey,score
                2026-06-01T09:30:00Z,NIFTY,d1,72
                2026-06-01T09:35:00Z,NIFTY,d2,80
                2026-06-01T09:40:00Z,SENSEX,d3,65
                """);

        int rolled = roller.rollDate(LocalDate.of(2026, 6, 1));

        assertThat(rolled).isEqualTo(1);
        Path parquet = archiveDir
                .resolve("strategy=oi_momentum")
                .resolve("event=signal")
                .resolve("year=2026").resolve("month=06").resolve("day=01")
                .resolve("data.parquet");
        assertThat(parquet).exists();
        assertThat(csv).doesNotExist();

        // Verify Parquet content matches.
        List<Map<String, Object>> rows = store.query(
                "SELECT COUNT(*) AS n FROM read_parquet(?)", parquet.toString());
        assertThat(((Number) rows.get(0).get("n")).longValue()).isEqualTo(3L);
    }

    @Test
    void deleteCsvDisabled_keepsCsvAfterRoll() throws Exception {
        rollerProps.setDeleteCsvAfterRoll(false);
        Path csv = writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                """
                eventTime,index,correlationKey,score
                2026-06-01T09:30:00Z,NIFTY,d1,72
                """);

        roller.rollDate(LocalDate.of(2026, 6, 1));

        assertThat(csv).exists();
        Path parquet = archiveDir.resolve("strategy=oi_momentum").resolve("event=signal")
                .resolve("year=2026").resolve("month=06").resolve("day=01").resolve("data.parquet");
        assertThat(parquet).exists();
    }

    @Test
    void idempotency_existingParquetSkipsReroll() throws Exception {
        Path csv = writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                """
                eventTime,index,correlationKey,score
                2026-06-01T09:30:00Z,NIFTY,d1,72
                """);

        int first = roller.rollDate(LocalDate.of(2026, 6, 1));
        // Re-write the CSV (simulating a second run encountering an already-rolled file).
        Files.writeString(csv, "eventTime,index,correlationKey,score\n");

        int second = roller.rollDate(LocalDate.of(2026, 6, 1));

        // First produces 1 rolled file; second sees existing Parquet, skips.
        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
    }

    @Test
    void multipleStrategies_multipleEventTypes_allGetRolled() throws Exception {
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                "eventTime,index,correlationKey\n2026-06-01T09:30:00Z,NIFTY,d1\n");
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "exit",
                "eventTime,index,correlationKey\n2026-06-01T10:30:00Z,NIFTY,d1\n");
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_shift_trap", "signal",
                "eventTime,index,correlationKey\n2026-06-01T11:00:00Z,SENSEX,d2\n");

        int n = roller.rollDate(LocalDate.of(2026, 6, 1));
        assertThat(n).isEqualTo(3);

        List<Path> archived = roller.existingParquetFiles();
        assertThat(archived).hasSize(3);
        assertThat(archived).anyMatch(p -> p.toString().contains("strategy=oi_momentum")
                && p.toString().contains("event=signal"));
        assertThat(archived).anyMatch(p -> p.toString().contains("strategy=oi_momentum")
                && p.toString().contains("event=exit"));
        assertThat(archived).anyMatch(p -> p.toString().contains("strategy=oi_shift_trap")
                && p.toString().contains("event=signal"));
    }

    @Test
    void emptyDateDirectoryIsPrunedAfterRoll() throws Exception {
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                "eventTime,index,correlationKey\n2026-06-01T09:30:00Z,NIFTY,d1\n");

        roller.rollDate(LocalDate.of(2026, 6, 1));

        Path dateDir = eventsDir.resolve("2026-06-01");
        assertThat(dateDir).doesNotExist();
    }

    @Test
    void unknownStrategyDirIsIgnored() throws Exception {
        // Manually create a directory that doesn't match any StrategyType.
        Path bogus = eventsDir.resolve("2026-06-01").resolve("bogus_strategy");
        Files.createDirectories(bogus);
        Files.writeString(bogus.resolve("signal.csv"),
                "eventTime,index,correlationKey\n2026-06-01T09:30:00Z,NIFTY,d1\n");

        int rolled = roller.rollDate(LocalDate.of(2026, 6, 1));
        assertThat(rolled).isZero();
        // CSV is preserved — we never touched the directory.
        assertThat(bogus.resolve("signal.csv")).exists();
    }

    @Test
    void buildDailySummary_skipsWhenNoArchiveFiles() {
        roller.buildDailySummary(LocalDate.of(2026, 6, 1));
        // No files exist; summary file should not be created.
        Path summaryFile = summaryDir.resolve("2026-06-01.parquet");
        assertThat(summaryFile).doesNotExist();
    }

    @Test
    void buildDailySummary_writesOneRowPerStrategyEventIndex() throws Exception {
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "signal",
                """
                eventTime,index,correlationKey
                2026-06-01T09:30:00Z,NIFTY,d1
                2026-06-01T09:35:00Z,NIFTY,d2
                2026-06-01T09:40:00Z,SENSEX,d3
                """);
        writeFixtureCsv(LocalDate.of(2026, 6, 1), "oi_momentum", "exit",
                """
                eventTime,index,correlationKey
                2026-06-01T10:00:00Z,NIFTY,d1
                """);
        roller.rollDate(LocalDate.of(2026, 6, 1));

        roller.buildDailySummary(LocalDate.of(2026, 6, 1));

        Path summary = summaryDir.resolve("2026-06-01.parquet");
        assertThat(summary).exists();

        List<Map<String, Object>> rows = store.query(
                "SELECT strategy, event, underlying_index, event_count FROM read_parquet(?) ORDER BY strategy, event, underlying_index",
                summary.toString());

        // Expected: 3 rows — (oi_momentum, signal, NIFTY)=2, (oi_momentum, signal, SENSEX)=1, (oi_momentum, exit, NIFTY)=1
        assertThat(rows).hasSize(3);
        assertThat(rows).extracting(r -> r.get("event_count").toString())
                .containsExactlyInAnyOrder("1", "1", "2");
    }

    @Test
    void enforceRetention_dropsOldFiles() throws Exception {
        // Configure short retention for SIGNAL to force a drop.
        rollerProps.getRetentionDays().put(com.algo.trade.tuning.TuningEventType.SIGNAL, 10);

        // Create a "fresh" Parquet (recent date) and an "old" Parquet (40 days ago).
        LocalDate today = LocalDate.of(2026, 6, 2);
        LocalDate freshDate = today.minusDays(5);
        LocalDate oldDate = today.minusDays(40);

        Path freshParquet = makeEmptyParquet("oi_momentum", "signal", freshDate);
        Path oldParquet = makeEmptyParquet("oi_momentum", "signal", oldDate);

        int deleted = roller.enforceRetention(today);

        assertThat(deleted).isEqualTo(1);
        assertThat(oldParquet).doesNotExist();
        assertThat(freshParquet).exists();
    }

    @Test
    void retentionDays_partialYamlMergesWithDefaults() {
        ParquetRollerProperties props = new ParquetRollerProperties();
        // User overrides only one event type.
        props.setRetentionDays(java.util.Map.of(
                com.algo.trade.tuning.TuningEventType.EVALUATION, 7));

        // Override took effect.
        assertThat(props.retentionDaysFor(com.algo.trade.tuning.TuningEventType.EVALUATION))
                .isEqualTo(7);
        // Other event types retain their defaults.
        assertThat(props.retentionDaysFor(com.algo.trade.tuning.TuningEventType.SIGNAL))
                .isEqualTo(365);
        assertThat(props.retentionDaysFor(com.algo.trade.tuning.TuningEventType.SHADOW_GATE))
                .isEqualTo(30);
    }

    @Test
    void scheduledRollDisabled_isNoop() {
        rollerProps.setEnabled(false);
        roller.scheduledRoll();
        assertThat(roller.existingParquetFiles()).isEmpty();
    }

    @Test
    void scheduledRoll_walksAllPastDateDirectories_includingWeekendBacklog() throws Exception {
        // Simulate a Monday clock (today = 2026-06-08 Monday).
        // Past data sitting on disk: Friday (2026-06-05) and an older holiday gap (2026-06-04).
        clock = new IstDayClock(Clock.fixed(Instant.parse("2026-06-08T15:30:00+05:30"),
                ZoneId.of("Asia/Kolkata")));
        roller = new ParquetRollerService(eventsDir, rollerProps, store, clock);

        writeFixtureCsv(LocalDate.of(2026, 6, 4), "oi_momentum", "signal",
                "eventTime,index,correlationKey\n2026-06-04T09:30:00Z,NIFTY,d-thu\n");
        writeFixtureCsv(LocalDate.of(2026, 6, 5), "oi_momentum", "signal",
                "eventTime,index,correlationKey\n2026-06-05T09:30:00Z,NIFTY,d-fri\n");
        // Today's directory exists but must NOT be rolled.
        writeFixtureCsv(LocalDate.of(2026, 6, 8), "oi_momentum", "signal",
                "eventTime,index,correlationKey\n2026-06-08T09:30:00Z,NIFTY,d-mon\n");

        roller.scheduledRoll();

        // Friday + Thursday rolled.
        assertThat(archiveDir.resolve("strategy=oi_momentum/event=signal/year=2026/month=06/day=04/data.parquet"))
                .exists();
        assertThat(archiveDir.resolve("strategy=oi_momentum/event=signal/year=2026/month=06/day=05/data.parquet"))
                .exists();
        // Today's CSV NOT rolled — it's still on disk in the events dir.
        assertThat(eventsDir.resolve("2026-06-08/oi_momentum/signal.csv")).exists();
        // Today's archive parquet was NOT created.
        assertThat(archiveDir.resolve("strategy=oi_momentum/event=signal/year=2026/month=06/day=08/data.parquet"))
                .doesNotExist();
    }

    @Test
    void listPastDateDirs_returnsChronologicalOrder() throws Exception {
        Files.createDirectories(eventsDir.resolve("2026-06-05"));
        Files.createDirectories(eventsDir.resolve("2026-06-01"));
        Files.createDirectories(eventsDir.resolve("2026-06-03"));
        Files.createDirectories(eventsDir.resolve("2026-06-08"));   // today — excluded
        // A non-date directory should be ignored without crashing.
        Files.createDirectories(eventsDir.resolve("garbage"));

        List<LocalDate> dates = roller.listPastDateDirs(LocalDate.of(2026, 6, 8));

        assertThat(dates).containsExactly(
                LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 3),
                LocalDate.of(2026, 6, 5));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private Path writeFixtureCsv(LocalDate date, String strategyDir, String type, String contents)
            throws Exception {
        Path dir = eventsDir.resolve(date.toString()).resolve(strategyDir);
        Files.createDirectories(dir);
        Path file = dir.resolve(type + ".csv");
        Files.writeString(file, contents);
        return file;
    }

    /** Creates an empty Parquet at the archive path for the given (strategy, type, date). */
    private Path makeEmptyParquet(String strategy, String type, LocalDate date) throws Exception {
        Path parquet = archiveDir
                .resolve("strategy=" + strategy)
                .resolve("event=" + type)
                .resolve("year=" + date.getYear())
                .resolve("month=" + String.format("%02d", date.getMonthValue()))
                .resolve("day=" + String.format("%02d", date.getDayOfMonth()))
                .resolve("data.parquet");
        Files.createDirectories(parquet.getParent());
        // Use DuckDB to write an empty Parquet with a known schema.
        store.execute("COPY (SELECT 1 AS x WHERE 1=0) TO '"
                + parquet.toString().replace("'", "''") + "' (FORMAT 'parquet')");
        return parquet;
    }
}
