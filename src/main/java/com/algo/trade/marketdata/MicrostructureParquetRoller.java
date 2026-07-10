package com.algo.trade.marketdata;

import com.algo.trade.tuning.store.TuningEventStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Nightly CSV → Parquet (zstd) roll + retention for the high-frequency ATM
 * microstructure capture written by {@link AtmMicrostructureRecorder}.
 *
 * <h2>What rolls</h2>
 * Every past-date CSV {@code data/tuning/atm-microstructure-&lt;date&gt;.csv} (date strictly
 * before today IST) is converted to a Hive-partitioned Parquet dataset at
 * {@code data/tuning/microstructure-archive/year=&lt;yyyy&gt;/month=&lt;mm&gt;/day=&lt;dd&gt;/index=&lt;IDX&gt;/data_*.parquet}.
 * Partitioning by date + {@code index} lets DuckDB prune whole files on date-range and
 * {@code WHERE index = …} queries.
 *
 * <h2>Why DuckDB COPY</h2>
 * Reuses the exact pattern already in {@code ParquetRollerService} / {@link TuningEventStore}
 * — DuckDB does the whole CSV→Parquet conversion in one statement, so no rows are loaded
 * into the JVM heap. <b>No new dependency.</b> Parquet is immutable/columnar (you cannot
 * append rows), which is why the live writer stays on CSV and we batch-convert per day.
 *
 * <h2>Verify before delete</h2>
 * The Parquet row count must equal the CSV row count (minus header) before the CSV is
 * deleted (controlled by {@code delete-csv-after-roll}). On mismatch the CSV is kept and
 * the partial Parquet day-dir removed — the next sweep retries.
 *
 * <h2>Retention</h2>
 * Archived Parquet day-dirs (and any orphaned CSVs) older than
 * {@code retention-days} (default 180 ≈ 6 months) are deleted after the roll.
 *
 * <h2>Cadence</h2>
 * 15:35 IST Mon–Fri — just after the tuning {@code ParquetRollerService} (15:30) so the
 * two don't contend, after market close, and it only ever rolls <i>past</i> dates so the
 * still-being-written current-day CSV is never touched.
 *
 * <h2>Safety</h2>
 * Fully additive and self-contained. It never touches the live capture path and the whole
 * roller is gated by {@code atm-microstructure.roller.enabled}.
 */
@Component
public class MicrostructureParquetRoller {

    private static final Logger log = LoggerFactory.getLogger(MicrostructureParquetRoller.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final MicrostructureParquetRollerProperties props;
    private final TuningEventStore store;
    private final Pattern csvDatePattern;

    public MicrostructureParquetRoller(MicrostructureParquetRollerProperties props,
                                       TuningEventStore store) {
        this.props = props;
        this.store = store;
        // e.g. "atm-microstructure-2026-06-19.csv"
        this.csvDatePattern = Pattern.compile(
                Pattern.quote(props.getCsvPrefix()) + "(\\d{4}-\\d{2}-\\d{2})\\.csv");
    }

    @Scheduled(cron = "0 35 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void scheduledRoll() {
        if (!props.isEnabled()) {
            log.debug("[MicroRoller] disabled — skipping");
            return;
        }
        LocalDate today = LocalDate.now(IST);
        int rolled = 0;
        try {
            List<LocalDate> dates = listPastCsvDates(today);
            for (LocalDate date : dates) {
                if (rollDate(date)) rolled++;
            }
            int purged = enforceRetention(today);
            log.info("[MicroRoller] roll done: {} csv day(s) rolled, {} archived file(s)/dir(s) purged",
                    rolled, purged);
        } catch (Exception ex) {
            log.warn("[MicroRoller] roll failed (non-fatal): {}", ex.getMessage());
        }
    }

    // ── Discovery ───────────────────────────────────────────────────────────

    List<LocalDate> listPastCsvDates(LocalDate today) {
        Path csvDir = Path.of(props.getCsvDir());
        if (!Files.isDirectory(csvDir)) return List.of();
        try (Stream<Path> files = Files.list(csvDir)) {
            return files
                    .filter(Files::isRegularFile)
                    .map(p -> parseCsvDate(p.getFileName().toString()))
                    .filter(java.util.Objects::nonNull)
                    .filter(d -> d.isBefore(today))
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            log.warn("[MicroRoller] failed to list {}: {}", csvDir, ex.getMessage());
            return List.of();
        }
    }

    private LocalDate parseCsvDate(String fileName) {
        Matcher m = csvDatePattern.matcher(fileName);
        if (!m.matches()) return null;
        try {
            return LocalDate.parse(m.group(1));
        } catch (Exception ex) {
            return null;
        }
    }

    // ── Roll one day ──────────────────────────────────────────────────────────

    /** Returns true if the day's CSV was rolled to Parquet this invocation. */
    boolean rollDate(LocalDate date) {
        Path csv = csvPathFor(date);
        if (!Files.isRegularFile(csv)) return false;
        Path dayDir = dayDirFor(date);
        if (Files.isDirectory(dayDir) && dirHasParquet(dayDir)) {
            // Idempotent: already archived. Clean up the CSV if requested.
            log.debug("[MicroRoller] archive already exists for {}, skipping roll", date);
            maybeDeleteCsv(csv);
            return false;
        }
        try {
            Files.createDirectories(dayDir);
            String codec = sanitizeCodec(props.getCompression());
            // PARTITION_BY (index) writes one sub-dir per underlying under the day dir.
            String sql = "COPY (SELECT * FROM read_csv_auto('" + sq(csv.toAbsolutePath().toString())
                    + "', header=true)) TO '" + sq(dayDir.toAbsolutePath().toString())
                    + "' (FORMAT 'parquet', COMPRESSION '" + codec + "', PARTITION_BY (index), OVERWRITE_OR_IGNORE)";
            store.execute(sql);
            if (verifyRowCounts(csv, dayDir)) {
                maybeDeleteCsv(csv);
                log.info("[MicroRoller] rolled {} → {} ({})", csv.getFileName(), dayDir, codec);
                return true;
            }
            log.warn("[MicroRoller] row-count mismatch for {} → {}; keeping CSV, removing partial parquet",
                    csv.getFileName(), dayDir);
            deleteRecursively(dayDir);
            return false;
        } catch (Exception ex) {
            log.warn("[MicroRoller] failed to roll {}: {}", csv.getFileName(), ex.getMessage());
            return false;
        }
    }

    private boolean verifyRowCounts(Path csv, Path dayDir) throws IOException {
        long csvRows;
        try (Stream<String> lines = Files.lines(csv)) {
            csvRows = Math.max(0, lines.count() - 1); // minus header
        }
        Long parquetRows = countParquetRows(dayDir);
        if (parquetRows == null) return false;
        if (csvRows != parquetRows) {
            log.warn("[MicroRoller] row count mismatch: csv={} parquet={} for {}",
                    csvRows, parquetRows, csv.getFileName());
            return false;
        }
        return true;
    }

    private Long countParquetRows(Path dayDir) {
        try {
            String glob = dayDir.toAbsolutePath() + "/**/*.parquet";
            List<Map<String, Object>> r = store.query(
                    "SELECT COUNT(*) AS n FROM read_parquet(?)", glob);
            if (r.isEmpty() || r.get(0).get("n") == null) return null;
            return ((Number) r.get(0).get("n")).longValue();
        } catch (Exception ex) {
            log.warn("[MicroRoller] failed to count parquet rows under {}: {}", dayDir, ex.getMessage());
            return null;
        }
    }

    // ── Retention ─────────────────────────────────────────────────────────────

    /** Deletes archived day-dirs and orphaned CSVs older than the cutoff. Returns count removed. */
    int enforceRetention(LocalDate today) {
        LocalDate cutoff = today.minusDays(props.getRetentionDays());
        int removed = 0;

        // 1) Archived Parquet day directories.
        Path archiveBase = Path.of(props.getArchiveBaseDir());
        if (Files.isDirectory(archiveBase)) {
            for (Path dayDir : findArchiveDayDirs(archiveBase)) {
                LocalDate d = parseDateFromDayDir(dayDir);
                if (d != null && d.isBefore(cutoff)) {
                    if (deleteRecursively(dayDir)) removed++;
                }
            }
            cleanupEmptyDirs(archiveBase);
        }

        // 2) Orphaned CSVs (e.g. a day that never rolled) older than the cutoff.
        Path csvDir = Path.of(props.getCsvDir());
        if (Files.isDirectory(csvDir)) {
            try (Stream<Path> files = Files.list(csvDir)) {
                for (Path p : (Iterable<Path>) files::iterator) {
                    LocalDate d = parseCsvDate(p.getFileName().toString());
                    if (d != null && d.isBefore(cutoff)) {
                        try {
                            if (Files.deleteIfExists(p)) removed++;
                        } catch (IOException ex) {
                            log.debug("[MicroRoller] failed to delete orphan csv {}: {}", p, ex.getMessage());
                        }
                    }
                }
            } catch (IOException ex) {
                log.debug("[MicroRoller] retention csv scan failed: {}", ex.getMessage());
            }
        }
        return removed;
    }

    private List<Path> findArchiveDayDirs(Path archiveBase) {
        List<Path> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(archiveBase)) {
            for (Path p : (Iterable<Path>) walk::iterator) {
                if (Files.isDirectory(p) && p.getFileName().toString().startsWith("day=")) {
                    out.add(p);
                }
            }
        } catch (IOException ex) {
            log.debug("[MicroRoller] archive walk failed: {}", ex.getMessage());
        }
        return out;
    }

    private static LocalDate parseDateFromDayDir(Path dayDir) {
        int year = -1, month = -1, day = -1;
        for (Path p : dayDir) {
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

    // ── Paths + helpers ───────────────────────────────────────────────────────

    private Path csvPathFor(LocalDate date) {
        return Path.of(props.getCsvDir()).resolve(props.getCsvPrefix() + date + ".csv");
    }

    private Path dayDirFor(LocalDate date) {
        return Path.of(props.getArchiveBaseDir())
                .resolve("year=" + date.getYear())
                .resolve("month=" + String.format(Locale.ROOT, "%02d", date.getMonthValue()))
                .resolve("day=" + String.format(Locale.ROOT, "%02d", date.getDayOfMonth()));
    }

    private boolean dirHasParquet(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.anyMatch(p -> p.getFileName().toString().endsWith(".parquet"));
        } catch (IOException ex) {
            return false;
        }
    }

    private void maybeDeleteCsv(Path csv) {
        if (!props.isDeleteCsvAfterRoll()) return;
        try {
            Files.deleteIfExists(csv);
        } catch (IOException ex) {
            log.debug("[MicroRoller] failed to delete csv {}: {}", csv, ex.getMessage());
        }
    }

    private boolean deleteRecursively(Path dir) {
        if (!Files.exists(dir)) return false;
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best-effort
                }
            });
            return true;
        } catch (IOException ex) {
            log.debug("[MicroRoller] failed to delete dir {}: {}", dir, ex.getMessage());
            return false;
        }
    }

    private void cleanupEmptyDirs(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> dirs = walk.filter(Files::isDirectory)
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path d : dirs) {
                if (d.equals(root)) continue;
                try (Stream<Path> entries = Files.list(d)) {
                    if (entries.findAny().isEmpty()) Files.deleteIfExists(d);
                } catch (IOException ignore) {
                    // best-effort
                }
            }
        } catch (IOException ignore) {
            // best-effort
        }
    }

    /** Whitelist the codec so it can never be used for SQL injection via config. */
    private static String sanitizeCodec(String codec) {
        if (codec == null) return "zstd";
        String c = codec.trim().toLowerCase(Locale.ROOT);
        return switch (c) {
            case "zstd", "snappy", "gzip", "uncompressed" -> c;
            default -> "zstd";
        };
    }

    /** Escape single quotes for inline SQL string literals. */
    private static String sq(String s) {
        return s.replace("'", "''");
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return -1;
        }
    }
}
