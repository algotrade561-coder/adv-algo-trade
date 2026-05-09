package com.algo.trade.data;

import com.algo.trade.monitoring.SchedulerRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.stream.Stream;

/**
 * Deletes snapshot directories older than the configured retention period.
 * Runs daily at 00:30 IST.
 */
@Component
public class SnapshotRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(SnapshotRetentionCleaner.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String TASK_NAME = "snapshotRetentionCleaner";

    private final SnapshotFileWriter snapshotFileWriter;
    private final SchedulerRegistry schedulerRegistry;

    @Value("${snapshot.retention-days:60}")
    private int retentionDays;

    public SnapshotRetentionCleaner(SnapshotFileWriter snapshotFileWriter,
                                     SchedulerRegistry schedulerRegistry) {
        this.snapshotFileWriter = snapshotFileWriter;
        this.schedulerRegistry = schedulerRegistry;
    }

    @PostConstruct
    void register() {
        schedulerRegistry.register(TASK_NAME,
                "Snapshot retention cleaner (deletes dirs older than " + retentionDays + " days)",
                86_400_000L, this::cleanExpiredSnapshots);
    }

    /**
     * Runs daily at 00:30 IST. Deletes date directories older than retention period.
     */
    @Scheduled(cron = "0 30 0 * * *", zone = "Asia/Kolkata")
    public void cleanExpiredSnapshots() {
        if (!schedulerRegistry.isEnabled(TASK_NAME)) return;

        Path baseDir = snapshotFileWriter.getBaseDir();
        if (!Files.isDirectory(baseDir)) {
            log.debug("[RetentionCleaner] Base directory does not exist: {}", baseDir);
            schedulerRegistry.recordRun(TASK_NAME);
            return;
        }

        LocalDate cutoff = LocalDate.now(IST).minusDays(retentionDays);
        int deleted = 0;

        try (Stream<Path> dirs = Files.list(baseDir)) {
            for (Path dateDir : dirs.toList()) {
                if (!Files.isDirectory(dateDir)) continue;

                try {
                    LocalDate dirDate = LocalDate.parse(dateDir.getFileName().toString());
                    if (dirDate.isBefore(cutoff)) {
                        deleteDirectoryRecursively(dateDir);
                        deleted++;
                        log.info("[RetentionCleaner] Deleted expired snapshot directory: {}", dateDir);
                    }
                } catch (DateTimeParseException e) {
                    // Skip non-date directories
                }
            }
        } catch (IOException e) {
            log.error("[RetentionCleaner] Failed to list snapshot directories: {}", e.getMessage());
            schedulerRegistry.recordError(TASK_NAME, e.getMessage());
            return;
        }

        if (deleted > 0) {
            log.info("[RetentionCleaner] Cleaned {} expired directories (cutoff={})", deleted, cutoff);
        }
        schedulerRegistry.recordRun(TASK_NAME);
    }

    private void deleteDirectoryRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
