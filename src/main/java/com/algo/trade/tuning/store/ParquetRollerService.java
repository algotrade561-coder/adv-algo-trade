package com.algo.trade.tuning.store;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.recorder.IstDayClock;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily IST-midnight CSV → Parquet rollup plus retention enforcement and a minimal
 * summary-table builder.
 *
 * <h2>What rolls</h2>
 * Yesterday's live-day CSV files at {@code reports/tuning/events/&lt;date&gt;/&lt;strategy&gt;/
 * &lt;event_type&gt;.csv} get converted to Parquet at the Hive-partitioned archive layout
 * {@code reports/tuning/archive/strategy=&lt;name&gt;/event=&lt;type&gt;/year=&lt;yyyy&gt;/month=&lt;mm&gt;/
 * day=&lt;dd&gt;/data.parquet}. Path partitioning lets DuckDB prune entire files when the
 * forked analyzer (Phase 6) issues {@code WHERE strategy = …} or date-range queries.
 *
 * <h2>Verification + deletion</h2>
 * After writing the Parquet file, the service verifies that its row count matches the
 * source CSV's (minus the header). Only then is the CSV deleted (controlled by
 * {@code tuning.roller.delete-csv-after-roll}, default true). On mismatch, the CSV is
 * kept and a WARN is logged — the next sweep will retry.
 *
 * <h2>Summary table</h2>
 * Phase 1 ships a minimal summary: per-day {@code summary/&lt;date&gt;.parquet} with one
 * row per {@code (strategy, event_type, index)} carrying just the event count. Phase 2
 * adapter integration will extend this with per-bucket aggregations (win rate, MAE
 * distribution, slippage, etc.) — additive, no schema break.
 *
 * <h2>Retention</h2>
 * Per-event-type retention from {@link ParquetRollerProperties} (defaults in § 12.2 of
 * the design). Parquet files whose date is older than the per-type cutoff are deleted
 * after the daily roll completes.
 *
 * <h2>Cadence</h2>
 * Scheduled at 00:30 IST every day (Cron {@code 0 30 0 * * *}). Late enough to avoid
 * racing the day-rollover writes, early enough to free disk before market open.
 */
@Component
public class ParquetRollerService {

    /**
     * Snapshot of the most-recent {@link #scheduledRoll()} run. Read by
     * {@link com.algo.trade.controller.TuningHealthController} to power the
     * "Last roller run" line in the dashboard health widget.
     */
    public record LastRun(java.time.Instant at,
                          int datesProcessed,
                          int filesRolled,
                          int retentionPurged,
                          boolean success,
                          String errorMessage) {}

    private volatile LastRun lastRun;

    /**
     * Boot-time catch-up. The 15:30 cron self-heals backlog, but only if the app is healthy at that one
     * slot — the day it isn't (e.g. 2026-06-25's auth incident), the previous day's CSV (2026-06-24) stays
     * orphaned until some later 15:30 succeeds. A startup sweep makes recovery happen at boot too. Default on.
     */
    @Value("${tuning.roller.startup-catchup-enabled:true}")
    private boolean startupCatchupEnabled = true;

    public LastRun lastRun() {
        return lastRun;
    }


    private static final Logger log = LoggerFactory.getLogger(ParquetRollerService.class);

    private final Path eventsBaseDir;
    private final ParquetRollerProperties properties;
    private final TuningEventStore store;
    private final IstDayClock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public ParquetRollerService(@Value("${tuning.capture.base-dir:reports/tuning/events}") String eventsBaseDir,
                                 ParquetRollerProperties properties,
                                 TuningEventStore store,
                                 IstDayClock clock) {
        this(Path.of(eventsBaseDir), properties, store, clock);
    }

    /** Test-friendly constructor. */
    public ParquetRollerService(Path eventsBaseDir,
                                 ParquetRollerProperties properties,
                                 TuningEventStore store,
                                 IstDayClock clock) {
        this.eventsBaseDir = eventsBaseDir;
        this.properties = properties;
        this.store = store;
        this.clock = clock;
    }

    /**
     * Post-market scheduled rollup at 15:30 IST Mon–Fri.
     *
     * <p>EC2 instance is up 08:45–15:45 IST only, so the original midnight cron would
     * never fire. 15:30 IST is right after market close — trading orders have
     * settled, today's CSVs are NOT touched (their forward checkpoints land tomorrow
     * morning when EC2 restarts), and we have a 15-min runway before EC2 shutdown
     * at 15:45 (typical run is < 5 sec).</p>
     *
     * <p>Walks <strong>every</strong> {@code events/&lt;date&gt;/} directory whose date is
     * older than today and rolls each one. This handles weekends + holidays: Friday's
     * CSVs don't get lost because Saturday/Sunday EC2 is down — Monday's 15:30 sweep
     * picks up the Friday backlog. Idempotent: re-rolling a date whose Parquets
     * already exist is a per-file no-op.</p>
     */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledRoll() {
        if (!properties.isEnabled()) {
            log.debug("[ParquetRoller] disabled — skipping scheduled roll");
            return;
        }
        LocalDate today = clock.todayIst();
        java.time.Instant startedAt = java.time.Instant.now();
        try {
            int rolled = 0;
            List<LocalDate> dates = listPastDateDirs(today);
            for (LocalDate date : dates) {
                rolled += rollDate(date);
                buildDailySummary(date);
            }
            int purged = enforceRetention(today);
            log.info("[ParquetRoller] post-market roll done: {} dates processed, {} files rolled, {} purged for retention",
                    dates.size(), rolled, purged);
            lastRun = new LastRun(startedAt, dates.size(), rolled, purged, true, null);
        } catch (Exception ex) {
            log.warn("[ParquetRoller] post-market roll failed (non-fatal): {}", ex.getMessage());
            lastRun = new LastRun(startedAt, 0, 0, 0, false, ex.getMessage());
        }
        warnOnOrphanedDateDirs(today);
    }

    /**
     * Boot-time catch-up sweep. Rolls genuine backlog — date dirs older than <em>yesterday</em> — so an
     * orphan from a missed 15:30 (e.g. 2026-06-24, stranded when 2026-06-25's roll never ran) is recovered
     * at the next boot instead of waiting for another healthy 15:30. We deliberately exclude
     * {@code today} and {@code yesterday}: yesterday's forward-checkpoint backfill runs this same morning and
     * is still appending to its CSV, so rolling it now would archive an incomplete file. The 15:30 cron
     * handles yesterday after that backfill settles. Idempotent (re-rolling an existing Parquet is a no-op).
     */
    @EventListener(ApplicationReadyEvent.class)
    public void catchUpOnStartup() {
        if (!startupCatchupEnabled || !properties.isEnabled()) {
            return;
        }
        LocalDate cutoffExclusive = clock.todayIst().minusDays(1); // roll strictly-before-yesterday
        try {
            int rolled = 0;
            List<LocalDate> backlog = listPastDateDirs(clock.todayIst()).stream()
                    .filter(d -> d.isBefore(cutoffExclusive))
                    .toList();
            for (LocalDate date : backlog) {
                rolled += rollDate(date);
                buildDailySummary(date);
            }
            if (!backlog.isEmpty()) {
                log.info("[ParquetRoller] startup catch-up rolled backlog: {} dates, {} files ({})",
                        backlog.size(), rolled, backlog);
            }
        } catch (Exception ex) {
            log.warn("[ParquetRoller] startup catch-up failed (non-fatal): {}", ex.getMessage());
        }
    }

    /**
     * On-demand roll for the given dates — invoked by report generation so a report for an unrolled day
     * (e.g. one stranded by a missed 15:30) archives it first instead of reading nothing. Idempotent and
     * safe to call any time; returns the number of files rolled. Builds the daily summary for each date too.
     */
    public int rollDatesOnDemand(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return 0;
        }
        int rolled = 0;
        for (LocalDate date : dates) {
            try {
                rolled += rollDate(date);
                buildDailySummary(date);
            } catch (Exception ex) {
                log.warn("[ParquetRoller] on-demand roll failed for {} (non-fatal): {}", date, ex.getMessage());
            }
        }
        log.info("[ParquetRoller] on-demand roll: {} files across {} dates", rolled, dates.size());
        return rolled;
    }

    /**
     * Loud data-quality signal: any past-date {@code events/<date>/} dir that still holds CSVs after a roll
     * attempt means an archive is stuck (verify-mismatch, IO error, or an interrupted run). Surfacing it here
     * is what would have flagged 2026-06-24 the day it was orphaned, instead of weeks later by hand.
     */
    private void warnOnOrphanedDateDirs(LocalDate today) {
        try {
            for (LocalDate date : listPastDateDirs(today)) {
                Path dateDir = eventsBaseDir.resolve(date.toString());
                try (Stream<Path> walk = Files.walk(dateDir)) {
                    boolean hasCsv = walk.anyMatch(p -> p.getFileName().toString().endsWith(".csv"));
                    if (hasCsv) {
                        log.warn("[ParquetRoller] ⚠ ORPHANED event archive: {} still has un-rolled CSVs — "
                                + "data will be missing from reports until this rolls. Investigate.", dateDir);
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("[ParquetRoller] orphan check failed (non-fatal): {}", ex.getMessage());
        }
    }

    /**
     * Lists every {@code events/&lt;date&gt;/} directory whose date is strictly before
     * {@code today}. Returns dates in chronological order so the oldest backlog gets
     * rolled first. Empty list if {@code events/} doesn't exist.
     */
    List<LocalDate> listPastDateDirs(LocalDate today) {
        if (!Files.isDirectory(eventsBaseDir)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(eventsBaseDir)) {
            return dirs
                    .filter(Files::isDirectory)
                    .map(p -> parseLocalDateSafe(p.getFileName().toString()))
                    .filter(java.util.Objects::nonNull)
                    .filter(d -> d.isBefore(today))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            log.warn("[ParquetRoller] failed to list {}: {}", eventsBaseDir, ex.getMessage());
            return List.of();
        }
    }

    private static LocalDate parseLocalDateSafe(String s) {
        try {
            return LocalDate.parse(s);
        } catch (Exception ex) {
            return null;
        }
    }

    // ── Rollup ────────────────────────────────────────────────────────────

    /**
     * Rolls every CSV file under {@code events/&lt;date&gt;/&lt;strategy&gt;/} into Parquet
     * archive paths. Returns the number of files successfully rolled.
     */
    public int rollDate(LocalDate date) {
        Path dateDir = eventsBaseDir.resolve(date.toString());
        if (!Files.isDirectory(dateDir)) {
            return 0;
        }
        int rolled = 0;
        try (Stream<Path> strategyDirs = Files.list(dateDir)) {
            for (Path strategyDir : (Iterable<Path>) strategyDirs::iterator) {
                if (!Files.isDirectory(strategyDir)) continue;
                StrategyType strategy = parseStrategy(strategyDir.getFileName().toString());
                if (strategy == null) continue;
                try (Stream<Path> files = Files.list(strategyDir)) {
                    for (Path csv : (Iterable<Path>) files::iterator) {
                        if (!csv.getFileName().toString().endsWith(".csv")) continue;
                        TuningEventType type = parseEventType(csv.getFileName().toString());
                        if (type == null) continue;
                        if (rollOne(csv, strategy, type, date)) {
                            rolled++;
                        }
                    }
                }
                // Prune empty strategy directories after roll.
                pruneIfEmpty(strategyDir);
            }
        } catch (IOException ex) {
            log.warn("[ParquetRoller] failed to list {}: {}", dateDir, ex.getMessage());
        }
        pruneIfEmpty(dateDir);
        return rolled;
    }

    /** Returns true if the CSV was successfully rolled to Parquet (and optionally deleted). */
    boolean rollOne(Path csv, StrategyType strategy, TuningEventType type, LocalDate date) {
        Path parquet = parquetPathFor(strategy, type, date);
        if (Files.exists(parquet)) {
            log.debug("[ParquetRoller] Parquet already exists, skipping: {}", parquet);
            // Still consider the CSV processed — delete it if requested.
            maybeDeleteCsv(csv);
            return false;
        }
        try {
            Files.createDirectories(parquet.getParent());
            // DuckDB does CSV → Parquet in one statement; we don't need to load into Java.
            String sql = "COPY (SELECT * FROM read_csv_auto('" + csv.toAbsolutePath().toString().replace("'", "''")
                    + "', header=true)) TO '" + parquet.toString().replace("'", "''") + "' (FORMAT 'parquet')";
            store.execute(sql);
            if (verifyRowCounts(csv, parquet)) {
                maybeDeleteCsv(csv);
                return true;
            } else {
                log.warn("[ParquetRoller] row count mismatch for {} → {}; keeping CSV",
                        csv, parquet);
                Files.deleteIfExists(parquet);
                return false;
            }
        } catch (Exception ex) {
            log.warn("[ParquetRoller] failed to roll {}: {}", csv, ex.getMessage());
            return false;
        }
    }

    private boolean verifyRowCounts(Path csv, Path parquet) throws IOException {
        long csvRows;
        try (Stream<String> lines = Files.lines(csv)) {
            csvRows = Math.max(0, lines.count() - 1);   // subtract header
        }
        Long parquetRows = countParquetRows(parquet);
        if (parquetRows == null) return false;
        if (csvRows != parquetRows) {
            log.warn("[ParquetRoller] row count mismatch: csv={} parquet={} for {}",
                    csvRows, parquetRows, csv);
            return false;
        }
        return true;
    }

    private Long countParquetRows(Path parquet) {
        try {
            List<Map<String, Object>> r = store.query(
                    "SELECT COUNT(*) AS n FROM read_parquet(?)", parquet.toString());
            if (r.isEmpty() || r.get(0).get("n") == null) return null;
            return ((Number) r.get(0).get("n")).longValue();
        } catch (TuningQueryException ex) {
            log.warn("[ParquetRoller] failed to count parquet rows for {}: {}", parquet, ex.getMessage());
            return null;
        }
    }

    private void maybeDeleteCsv(Path csv) {
        if (!properties.isDeleteCsvAfterRoll()) return;
        try {
            Files.deleteIfExists(csv);
        } catch (IOException ex) {
            log.debug("[ParquetRoller] failed to delete csv {}: {}", csv, ex.getMessage());
        }
    }

    private void pruneIfEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            if (entries.findAny().isEmpty()) {
                Files.deleteIfExists(dir);
            }
        } catch (IOException ignore) {
            // best-effort prune
        }
    }

    // ── Summary builder ──────────────────────────────────────────────────

    /**
     * Builds the minimal Phase 1 summary file at {@code summary/&lt;date&gt;.parquet}:
     * one row per {@code (strategy, event_type, index)} with event counts. Phase 2
     * adapters extend with bucket aggregations.
     */
    public void buildDailySummary(LocalDate date) {
        Path archiveBase = Path.of(properties.getArchiveBaseDir());
        if (!Files.isDirectory(archiveBase)) {
            return;
        }
        Path summaryDir = Path.of(properties.getSummaryBaseDir());
        Path summaryFile = summaryDir.resolve(date + ".parquet");
        try {
            Files.createDirectories(summaryDir);
            // Use DuckDB's glob over the day's Parquet files. The hive_partitioning option
            // exposes strategy, event, year, month, day as columns.
            String glob = archiveBase.toString()
                    + "/strategy=*/event=*/year=" + date.getYear()
                    + "/month=" + String.format(Locale.ROOT, "%02d", date.getMonthValue())
                    + "/day=" + String.format(Locale.ROOT, "%02d", date.getDayOfMonth())
                    + "/data.parquet";
            // First check whether any matching files exist — if not, skip rather than write
            // an empty file.
            long fileCount;
            try {
                List<Map<String, Object>> r = store.query(
                        "SELECT COUNT(*) AS n FROM glob(?)", glob);
                fileCount = r.isEmpty() ? 0L : ((Number) r.get(0).get("n")).longValue();
            } catch (TuningQueryException ex) {
                fileCount = 0L;
            }
            if (fileCount == 0) {
                log.debug("[ParquetRoller] no archive files for {} — skipping summary", date);
                return;
            }
            String sql =
                    "COPY ("
                            + "  SELECT strategy, event, index AS underlying_index, COUNT(*) AS event_count "
                            + "  FROM read_parquet('" + glob.replace("'", "''")
                            + "', hive_partitioning=true) "
                            + "  GROUP BY strategy, event, index"
                            + ") TO '" + summaryFile.toString().replace("'", "''") + "' (FORMAT 'parquet')";
            store.execute(sql);
            log.info("[ParquetRoller] wrote summary {}", summaryFile);
        } catch (Exception ex) {
            log.warn("[ParquetRoller] failed to build summary for {}: {}", date, ex.getMessage());
        }
    }

    // ── Retention ────────────────────────────────────────────────────────

    /**
     * Walks the archive and deletes Parquet files whose date is older than the
     * per-event-type retention. Returns the number of files deleted.
     */
    public int enforceRetention(LocalDate today) {
        Path archiveBase = Path.of(properties.getArchiveBaseDir());
        if (!Files.isDirectory(archiveBase)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> walk = Files.walk(archiveBase)) {
            // Collect first; deleting during traversal can confuse the walker.
            List<Path> files = walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".parquet"))
                    .toList();
            for (Path parquet : files) {
                TuningEventType type = parseEventTypeFromArchivePath(parquet);
                LocalDate date = parseDateFromArchivePath(parquet);
                if (type == null || date == null) continue;
                int retention = properties.retentionDaysFor(type);
                if (date.isBefore(today.minusDays(retention))) {
                    try {
                        Files.deleteIfExists(parquet);
                        deleted++;
                    } catch (IOException ex) {
                        log.debug("[ParquetRoller] failed to delete {}: {}", parquet, ex.getMessage());
                    }
                }
            }
        } catch (IOException ex) {
            log.warn("[ParquetRoller] retention walk failed: {}", ex.getMessage());
        }
        if (deleted > 0) {
            cleanupEmptyDirectories(archiveBase);
        }
        return deleted;
    }

    private void cleanupEmptyDirectories(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            // Reverse order so deepest leaves get pruned first.
            List<Path> dirs = walk
                    .filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path d : dirs) {
                if (d.equals(root)) continue;
                pruneIfEmpty(d);
            }
        } catch (IOException ignore) {
            // best-effort
        }
    }

    // ── Path + name parsing ──────────────────────────────────────────────

    private Path parquetPathFor(StrategyType strategy, TuningEventType type, LocalDate date) {
        return Path.of(properties.getArchiveBaseDir())
                .resolve("strategy=" + strategy.name().toLowerCase(Locale.ROOT))
                .resolve("event=" + type.fileBaseName())
                .resolve("year=" + date.getYear())
                .resolve("month=" + String.format(Locale.ROOT, "%02d", date.getMonthValue()))
                .resolve("day=" + String.format(Locale.ROOT, "%02d", date.getDayOfMonth()))
                .resolve("data.parquet");
    }

    private static StrategyType parseStrategy(String dirName) {
        try {
            return StrategyType.valueOf(dirName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static TuningEventType parseEventType(String csvFileName) {
        String base = csvFileName.endsWith(".csv")
                ? csvFileName.substring(0, csvFileName.length() - 4) : csvFileName;
        for (TuningEventType t : TuningEventType.values()) {
            if (t.fileBaseName().equals(base)) {
                return t;
            }
        }
        return null;
    }

    private static TuningEventType parseEventTypeFromArchivePath(Path parquet) {
        for (Path p : parquet) {
            String s = p.toString();
            if (s.startsWith("event=")) {
                String base = s.substring("event=".length());
                for (TuningEventType t : TuningEventType.values()) {
                    if (t.fileBaseName().equals(base)) return t;
                }
            }
        }
        return null;
    }

    private static LocalDate parseDateFromArchivePath(Path parquet) {
        int year = -1, month = -1, day = -1;
        for (Path p : parquet) {
            String s = p.toString();
            if (s.startsWith("year=")) year = parseIntSafe(s.substring(5));
            else if (s.startsWith("month=")) month = parseIntSafe(s.substring(6));
            else if (s.startsWith("day=")) day = parseIntSafe(s.substring(4));
        }
        if (year < 0 || month < 0 || day < 0) return null;
        try {
            return LocalDate.of(year, month, day);
        } catch (Exception ex) {
            return null;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    /** Exposed for tests + manual triggers. */
    public List<Path> existingParquetFiles() {
        Path archiveBase = Path.of(properties.getArchiveBaseDir());
        if (!Files.isDirectory(archiveBase)) {
            return List.of();
        }
        try (Stream<Path> walk = Files.walk(archiveBase)) {
            List<Path> out = new ArrayList<>();
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".parquet")) {
                    out.add(p);
                }
            }
            return out;
        } catch (IOException ex) {
            return List.of();
        }
    }
}
