package com.algo.trade.backtest.v2;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.data.SnapshotFileWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Loads ChainSnapshot data from disk for backtesting.
 * Delegates to SnapshotFileWriter for actual file I/O.
 */
@Component
public class SnapshotLoader {

    private static final Logger log = LoggerFactory.getLogger(SnapshotLoader.class);

    private final SnapshotFileWriter snapshotFileWriter;

    public SnapshotLoader(SnapshotFileWriter snapshotFileWriter) {
        this.snapshotFileWriter = snapshotFileWriter;
    }

    /**
     * Load all snapshots for a given date and underlying, sorted by timestamp.
     *
     * @param date       Date to load snapshots for
     * @param underlying Underlying symbol (NIFTY, BANKNIFTY, etc.)
     * @return List of snapshots in chronological order
     */
    public List<ChainSnapshot> loadDay(LocalDate date, String underlying) {
        List<Path> files = snapshotFileWriter.listSnapshots(date, underlying);
        if (files.isEmpty()) {
            log.debug("[SnapshotLoader] No snapshots found for {} on {}", underlying, date);
            return Collections.emptyList();
        }

        List<ChainSnapshot> snapshots = new ArrayList<>();
        for (Path file : files) {
            Optional<ChainSnapshot> snapshot = snapshotFileWriter.read(file);
            snapshot.ifPresent(snapshots::add);
        }

        log.info("[SnapshotLoader] Loaded {} snapshots for {} on {}", snapshots.size(), underlying, date);
        return snapshots;
    }

    /**
     * Load snapshots for a date range.
     *
     * @param from       Start date (inclusive)
     * @param to         End date (inclusive)
     * @param underlying Underlying symbol
     * @return All snapshots in chronological order
     */
    public List<ChainSnapshot> loadRange(LocalDate from, LocalDate to, String underlying) {
        List<ChainSnapshot> allSnapshots = new ArrayList<>();
        LocalDate current = from;

        while (!current.isAfter(to)) {
            // Skip weekends
            if (current.getDayOfWeek().getValue() <= 5) {
                allSnapshots.addAll(loadDay(current, underlying));
            }
            current = current.plusDays(1);
        }

        log.info("[SnapshotLoader] Loaded {} total snapshots for {} from {} to {}",
                allSnapshots.size(), underlying, from, to);
        return allSnapshots;
    }

    /**
     * Check if snapshots exist for a given date and underlying.
     */
    public boolean hasData(LocalDate date, String underlying) {
        return !snapshotFileWriter.listSnapshots(date, underlying).isEmpty();
    }

    /**
     * Count available trading days with snapshot data in a date range.
     */
    public int countAvailableDays(LocalDate from, LocalDate to, String underlying) {
        int count = 0;
        LocalDate current = from;
        while (!current.isAfter(to)) {
            if (current.getDayOfWeek().getValue() <= 5 && hasData(current, underlying)) {
                count++;
            }
            current = current.plusDays(1);
        }
        return count;
    }
}
