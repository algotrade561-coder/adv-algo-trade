package com.algo.trade.reporting;

import com.algo.trade.data.SnapshotFileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds ZIP downloads for today's analysis artifacts (IST calendar day).
 * Does not delete or move source files (unlike {@link ReportingService#archiveEntrySignalReports()}).
 */
@Service
public class DailyReportBundleService {

    private static final Logger log = LoggerFactory.getLogger(DailyReportBundleService.class);
    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final Path entrySignalsDir;
    private final Path logsRoot;
    private final SnapshotFileWriter snapshotFileWriter;

    public DailyReportBundleService(
            SnapshotFileWriter snapshotFileWriter,
            @Value("${daily-reports.entry-signals-dir:reports/entry-signals}") String entrySignalsDir,
            @Value("${daily-reports.logs-dir:logs}") String logsRoot) {
        this.snapshotFileWriter = snapshotFileWriter;
        this.entrySignalsDir = Path.of(entrySignalsDir);
        this.logsRoot = Path.of(logsRoot);
    }

    public LocalDate todayIst() {
        return LocalDate.now(IST);
    }

    public BundleSummary summary(LocalDate date) {
        List<BundleFile> signals = collectEntrySignalFiles(date);
        List<BundleFile> logs = collectLogFiles(date);
        List<BundleFile> chains = collectChainSnapshotFiles(date);
        return new BundleSummary(
                date.toString(),
                toSection("signals", signals),
                toSection("logs", logs),
                toSection("chainSnapshots", chains),
                signals.size() + logs.size() + chains.size(),
                signals.stream().mapToLong(BundleFile::sizeBytes).sum()
                        + logs.stream().mapToLong(BundleFile::sizeBytes).sum()
                        + chains.stream().mapToLong(BundleFile::sizeBytes).sum()
        );
    }

    public void writeSignalsZip(LocalDate date, OutputStream out) {
        writeZip(collectEntrySignalFiles(date), out);
    }

    public void writeLogsZip(LocalDate date, OutputStream out) {
        writeZip(collectLogFiles(date), out);
    }

    public void writeChainSnapshotsZip(LocalDate date, OutputStream out) {
        writeZip(collectChainSnapshotFiles(date), out);
    }

    public void writeFullAnalysisZip(LocalDate date, OutputStream out) {
        List<BundleFile> all = new ArrayList<>();
        all.addAll(collectEntrySignalFiles(date));
        all.addAll(collectLogFiles(date));
        all.addAll(collectChainSnapshotFiles(date));
        writeZip(all, out);
    }

    public String zipFilename(String prefix, LocalDate date) {
        return prefix + "-" + date + ".zip";
    }

    List<BundleFile> collectEntrySignalFiles(LocalDate date) {
        if (!Files.isDirectory(entrySignalsDir)) {
            return List.of();
        }
        Instant start = startOfDay(date);
        Instant end = startOfDay(date.plusDays(1));
        List<BundleFile> files = new ArrayList<>();
        try (var stream = Files.list(entrySignalsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".csv"))
                    .filter(p -> isWithinDay(p, start, end, date))
                    .sorted()
                    .forEach(p -> files.add(toBundleFile(entrySignalsDir, p)));
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to list entry signal files", ex);
        }
        return files;
    }

    List<BundleFile> collectLogFiles(LocalDate date) {
        if (!Files.isDirectory(logsRoot)) {
            return List.of();
        }
        Instant start = startOfDay(date);
        Instant end = startOfDay(date.plusDays(1));
        String dateToken = DATE_FMT.format(date);
        List<BundleFile> files = new ArrayList<>();
        try (var walk = Files.walk(logsRoot)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".log") || name.endsWith(".log.gz");
                    })
                    .filter(p -> isWithinDay(p, start, end, date) || p.getFileName().toString().contains(dateToken))
                    .sorted()
                    .forEach(p -> files.add(toBundleFile(logsRoot, p)));
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to list log files", ex);
        }
        return files;
    }

    List<BundleFile> collectChainSnapshotFiles(LocalDate date) {
        return snapshotFileWriter.listAllSnapshots(date).stream()
                .map(p -> toBundleFile(snapshotFileWriter.getBaseDir(), p))
                .toList();
    }

    private static boolean isWithinDay(Path path, Instant start, Instant end, LocalDate date) {
        try {
            Instant modified = Files.getLastModifiedTime(path).toInstant();
            if (!modified.isBefore(start) && modified.isBefore(end)) {
                return true;
            }
        } catch (IOException ex) {
            log.debug("Could not read mtime for {}: {}", path, ex.getMessage());
        }
        return path.getFileName().toString().contains(DATE_FMT.format(date));
    }

    private static Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(IST).toInstant();
    }

    private static BundleFile toBundleFile(Path root, Path file) {
        String zipPath = root.relativize(file).toString().replace('\\', '/');
        try {
            return new BundleFile(file, zipPath, Files.size(file));
        } catch (IOException ex) {
            return new BundleFile(file, zipPath, 0);
        }
    }

    private static BundleSection toSection(String key, List<BundleFile> files) {
        long bytes = files.stream().mapToLong(BundleFile::sizeBytes).sum();
        List<String> names = files.stream().map(BundleFile::zipEntryPath).toList();
        return new BundleSection(key, files.size(), bytes, names);
    }

    private void writeZip(List<BundleFile> files, OutputStream out) {
        if (files.isEmpty()) {
            throw new IllegalArgumentException("No files found for the selected bundle and date");
        }
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            byte[] buffer = new byte[8192];
            for (BundleFile file : files) {
                ZipEntry entry = new ZipEntry(file.zipEntryPath());
                zip.putNextEntry(entry);
                try (var in = Files.newInputStream(file.path())) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        zip.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to build ZIP download", ex);
        }
        log.info("Daily report ZIP streamed: files={}, bytes≈{}", files.size(),
                files.stream().mapToLong(BundleFile::sizeBytes).sum());
    }

    public record BundleFile(Path path, String zipEntryPath, long sizeBytes) {}

    public record BundleSection(String name, int fileCount, long totalBytes, List<String> fileNames) {}

    public record BundleSummary(
            String date,
            BundleSection signals,
            BundleSection logs,
            BundleSection chainSnapshots,
            int totalFiles,
            long totalBytes
    ) {
        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("date", date);
            map.put("signals", sectionMap(signals));
            map.put("logs", sectionMap(logs));
            map.put("chainSnapshots", sectionMap(chainSnapshots));
            map.put("totalFiles", totalFiles);
            map.put("totalBytes", totalBytes);
            return map;
        }

        private static Map<String, Object> sectionMap(BundleSection section) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("fileCount", section.fileCount());
            m.put("totalBytes", section.totalBytes());
            m.put("fileNames", section.fileNames());
            return m;
        }
    }
}
