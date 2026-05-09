package com.algo.trade.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Serializes ChainSnapshot to GZIP-compressed JSON files with date-based directory structure.
 *
 * File layout: {base-dir}/YYYY-MM-DD/{underlying}_{HHmm}.json.gz
 * Example:     data/chain-snapshots/2026-05-09/NIFTY_0915.json.gz
 */
@Component
public class SnapshotFileWriter {

    private static final Logger log = LoggerFactory.getLogger(SnapshotFileWriter.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HHmm");

    private final ObjectMapper objectMapper;
    private final Path baseDir;

    public SnapshotFileWriter(ObjectMapper objectMapper,
                              @Value("${snapshot.base-dir:data/chain-snapshots}") String baseDirStr) {
        this.objectMapper = objectMapper;
        this.baseDir = Path.of(baseDirStr);
    }

    /**
     * Write a snapshot to disk as GZIP-compressed JSON.
     * Path: {base-dir}/YYYY-MM-DD/{underlying}_{HHmm}.json.gz
     *
     * @return the Path written, or empty if write failed
     */
    public Optional<Path> write(ChainSnapshot snapshot) {
        if (snapshot == null || snapshot.underlying() == null || snapshot.timestamp() == null) {
            log.warn("[SnapshotFileWriter] Cannot write null or incomplete snapshot");
            return Optional.empty();
        }

        try {
            LocalDate date = snapshot.timestamp().atZone(IST).toLocalDate();
            String time = snapshot.timestamp().atZone(IST).toLocalTime().format(TIME_FMT);
            String fileName = snapshot.underlying() + "_" + time + ".json.gz";

            Path dateDir = baseDir.resolve(date.toString());
            Files.createDirectories(dateDir);

            Path filePath = dateDir.resolve(fileName);

            try (OutputStream os = Files.newOutputStream(filePath);
                 GZIPOutputStream gzos = new GZIPOutputStream(os)) {
                objectMapper.writeValue(gzos, snapshot);
            }

            log.debug("[SnapshotFileWriter] Written: {}", filePath);
            return Optional.of(filePath);

        } catch (IOException e) {
            log.error("[SnapshotFileWriter] Write failed for {}: {}", snapshot.underlying(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Read a snapshot from disk (for backtest engine consumption).
     */
    public Optional<ChainSnapshot> read(Path snapshotFile) {
        if (snapshotFile == null || !Files.exists(snapshotFile)) {
            return Optional.empty();
        }

        try (InputStream is = Files.newInputStream(snapshotFile);
             GZIPInputStream gzis = new GZIPInputStream(is)) {
            ChainSnapshot snapshot = objectMapper.readValue(gzis, ChainSnapshot.class);
            return Optional.ofNullable(snapshot);
        } catch (IOException e) {
            log.error("[SnapshotFileWriter] Read failed for {}: {}", snapshotFile, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * List all snapshot files for a given date and underlying, sorted by filename (time order).
     */
    public List<Path> listSnapshots(LocalDate date, String underlying) {
        Path dateDir = baseDir.resolve(date.toString());
        if (!Files.isDirectory(dateDir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> files = Files.list(dateDir)) {
            return files
                    .filter(p -> p.getFileName().toString().startsWith(underlying + "_"))
                    .filter(p -> p.getFileName().toString().endsWith(".json.gz"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("[SnapshotFileWriter] List failed for {} on {}: {}", underlying, date, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * List all snapshot files for a given date (all underlyings), sorted by filename.
     */
    public List<Path> listAllSnapshots(LocalDate date) {
        Path dateDir = baseDir.resolve(date.toString());
        if (!Files.isDirectory(dateDir)) {
            return Collections.emptyList();
        }

        try (Stream<Path> files = Files.list(dateDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json.gz"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.error("[SnapshotFileWriter] List all failed for {}: {}", date, e.getMessage());
            return Collections.emptyList();
        }
    }

    /** Get the base directory path (for retention cleaner). */
    public Path getBaseDir() {
        return baseDir;
    }
}
