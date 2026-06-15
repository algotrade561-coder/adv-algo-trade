package com.algo.trade.tuning.infra;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.ForwardCheckpointEvent;
import com.algo.trade.tuning.infra.MarketSnapshotBuffer.MarketSnapshot;
import com.algo.trade.tuning.recorder.IstDayClock;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled service that writes one {@link ForwardCheckpointEvent} per fired signal,
 * approximately 31 minutes after the signal time, carrying the spot path at fixed
 * checkpoints (+30s / +1m / +5m / +15m / +30m) and the MFE / MAE over the 30-minute
 * window.
 *
 * <p>This is the service that ultimately replaces the 750 MB {@code entry-candles.csv}
 * dependency — see {@code important/SIGNAL_CAPTURE_TUNING_REDESIGN.md} § 4.8.</p>
 *
 * <h2>Source of signals</h2>
 * Today's and yesterday's per-strategy {@code signal.csv} files in
 * {@code reports/tuning/events/&lt;date&gt;/&lt;strategy&gt;/}. The service parses only
 * the first 4 columns (eventTime, recordedAtDeltaUs, index, correlationKey) — those are
 * shared across every strategy in the canonical {@code SignalEvent} layout, so no per-
 * strategy parsing logic is required.
 *
 * <h2>Idempotency</h2>
 * Forward checkpoints land in a sibling {@code forward_checkpoint.csv} file with
 * matching {@code correlationKey}. On each sweep the service reads the existing
 * checkpoint file's correlation keys and skips signals already present there. An
 * in-memory {@link Set} caches processed keys so we don't re-read the file on every
 * sweep — but the on-disk file is the source of truth across JVM restarts.
 *
 * <h2>Ripeness</h2>
 * A signal is "ripe" once {@code signalTime + 31 minutes &lt; now}. Younger signals
 * are skipped on this sweep and revisited on the next.
 *
 * <h2>Capture gating</h2>
 * Forward checkpoints are written via {@link TuningEventRecorder#record}, which
 * already gates on {@link com.algo.trade.tuning.capture.CaptureToggleService}. If
 * the strategy doesn't have {@code capture_forward} enabled, the recorder no-ops
 * silently and the service's work is wasted — but harmless.
 */
@Component
public class ForwardCheckpointService {

    private static final Logger log = LoggerFactory.getLogger(ForwardCheckpointService.class);

    static final Duration FORWARD_WINDOW = Duration.ofMinutes(30);
    /** Wait an extra minute past the 30m checkpoint before backfilling. */
    static final Duration RIPENESS_MARGIN = Duration.ofMinutes(31);
    /**
     * Sweep window: how many past calendar days to scan for unfilled signals. Sized for
     * the worst realistic Indian market non-trading stretch (Diwali / Christmas–New
     * Year = 6 calendar days) plus ~8 days of margin for anomalies (extended EC2
     * outage, missed sweeps, manual recovery scenarios). Older than this is considered
     * permanently un-backfillable (out of {@code MarketSnapshotBuffer}'s warm-up range
     * and likely missing chain snapshots too).
     */
    static final int SWEEP_DAYS_BACK = 14;

    /** Checkpoint offsets relative to signal time. */
    static final Duration[] CHECKPOINTS = {
            Duration.ofSeconds(30),
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofMinutes(30),
    };

    private final Path baseDir;
    private final MarketSnapshotBuffer buffer;
    private final TuningEventRecorder recorder;
    private final IstDayClock clock;

    /** Correlation keys we've already processed since boot. */
    private final Set<String> processedKeys = ConcurrentHashMap.newKeySet();

    /**
     * Snapshot of the most-recent {@link #backfill()} sweep. Read by
     * {@link com.algo.trade.controller.TuningHealthController} to power the
     * "Last forward sweep" line in the dashboard health widget.
     */
    public record LastSweep(Instant at,
                            int newCheckpoints,
                            boolean success,
                            String errorMessage) {}

    private volatile LastSweep lastSweep;

    public LastSweep lastSweep() {
        return lastSweep;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public ForwardCheckpointService(@Value("${tuning.capture.base-dir:reports/tuning/events}") String baseDirPath,
                                     MarketSnapshotBuffer buffer,
                                     TuningEventRecorder recorder,
                                     IstDayClock clock) {
        this(Path.of(baseDirPath), buffer, recorder, clock);
    }

    /** Test-friendly constructor. */
    public ForwardCheckpointService(Path baseDir, MarketSnapshotBuffer buffer,
                                     TuningEventRecorder recorder, IstDayClock clock) {
        this.baseDir = baseDir;
        this.buffer = buffer;
        this.recorder = recorder;
        this.clock = clock;
    }

    /**
     * Sweep runs every 5 minutes. Initial delay 60s so the snapshot buffer is warmed
     * before the first sweep.
     */
    @Scheduled(fixedRate = 300_000, initialDelay = 60_000)
    public void backfill() {
        Instant startedAt = clock.now();
        try {
            int newCheckpoints = sweep(startedAt);
            lastSweep = new LastSweep(startedAt, newCheckpoints, true, null);
        } catch (Exception ex) {
            log.warn("[ForwardCheckpointService] sweep failed (non-fatal): {}", ex.getMessage());
            lastSweep = new LastSweep(startedAt, 0, false, ex.getMessage());
        }
    }

    /**
     * Scans the last {@link #SWEEP_DAYS_BACK} calendar days' signal CSVs and backfills
     * ripe signals. Walking multiple days handles the weekend / holiday gap: a Friday
     * 15:25 signal whose +30m checkpoint lands at 15:55 (past EC2 shutdown at 15:45)
     * gets backfilled by Monday morning's sweep.
     *
     * <p>Idempotent — existing rows in {@code forward_checkpoint.csv} are detected
     * and skipped, so re-walking the same dates on every sweep is cheap.</p>
     */
    public int sweep(Instant now) {
        LocalDate today = clock.istDate(now);
        int total = 0;
        for (int dayOffset = SWEEP_DAYS_BACK - 1; dayOffset >= 0; dayOffset--) {
            LocalDate date = today.minusDays(dayOffset);
            for (StrategyType strategy : StrategyType.values()) {
                total += backfillStrategyDate(strategy, date, now);
            }
        }
        return total;
    }

    int backfillStrategyDate(StrategyType strategy, LocalDate date, Instant now) {
        Path strategyDir = baseDir.resolve(date.toString())
                .resolve(strategy.name().toLowerCase(Locale.ROOT));
        Path signalsCsv = strategyDir.resolve("signal.csv");
        if (!Files.exists(signalsCsv)) {
            return 0;
        }
        Path forwardCsv = strategyDir.resolve("forward_checkpoint.csv");
        Set<String> alreadyDone = readCorrelationKeysFromCsv(forwardCsv);
        int written = 0;

        try (Stream<String> lines = Files.lines(signalsCsv)) {
            Iterable<String> iter = lines::iterator;
            boolean header = true;
            for (String line : iter) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] cols = splitFirstColumns(line, 4);
                if (cols.length < 4) {
                    continue;
                }
                String correlationKey = cols[3];
                if (alreadyDone.contains(correlationKey)
                        || processedKeys.contains(correlationKey)) {
                    processedKeys.add(correlationKey);
                    continue;
                }
                Instant signalTime;
                IndexType index;
                try {
                    signalTime = Instant.parse(cols[0]);
                    index = IndexType.valueOf(cols[2]);
                } catch (Exception ex) {
                    log.debug("[ForwardCheckpointService] skipping malformed signal row in {}: {}",
                            signalsCsv, ex.getMessage());
                    continue;
                }
                if (signalTime.plus(RIPENESS_MARGIN).isAfter(now)) {
                    continue;   // not ripe yet — try next sweep
                }
                Optional<ForwardCheckpointEvent> event =
                        computeCheckpoint(strategy, index, signalTime, correlationKey, now);
                if (event.isPresent()) {
                    recorder.record(event.get());
                    processedKeys.add(correlationKey);
                    written++;
                }
            }
        } catch (IOException ex) {
            log.warn("[ForwardCheckpointService] failed to read {}: {}", signalsCsv, ex.getMessage());
        }
        return written;
    }

    /**
     * Computes a forward checkpoint by querying {@link MarketSnapshotBuffer} for each
     * checkpoint offset and for the MFE / MAE range. Returns empty when no anchor
     * snapshot exists for the signal time (typically: signal is older than the buffer's
     * 3h retention and warm-up didn't reach back that far).
     */
    Optional<ForwardCheckpointEvent> computeCheckpoint(StrategyType strategy, IndexType index,
                                                        Instant signalTime, String correlationKey,
                                                        Instant now) {
        Optional<MarketSnapshot> anchor = buffer.nearest(index, signalTime);
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        double spotAtSignal = anchor.get().spot();
        if (spotAtSignal <= 0) {
            return Optional.empty();
        }

        Double[] fwdSpots = new Double[CHECKPOINTS.length];
        for (int i = 0; i < CHECKPOINTS.length; i++) {
            Optional<MarketSnapshot> at = buffer.nearest(index, signalTime.plus(CHECKPOINTS[i]));
            fwdSpots[i] = at.map(MarketSnapshot::spot).orElse(null);
        }

        // MFE / MAE over the 30-minute window (relative to spot at signal time).
        List<MarketSnapshot> range = buffer.entriesBetween(index,
                signalTime, signalTime.plus(FORWARD_WINDOW));
        Double mfePct = null;
        Double maePct = null;
        if (!range.isEmpty()) {
            double maxSpot = Double.NEGATIVE_INFINITY;
            double minSpot = Double.POSITIVE_INFINITY;
            for (MarketSnapshot s : range) {
                maxSpot = Math.max(maxSpot, s.spot());
                minSpot = Math.min(minSpot, s.spot());
            }
            mfePct = (maxSpot - spotAtSignal) / spotAtSignal * 100.0;
            maePct = (minSpot - spotAtSignal) / spotAtSignal * 100.0;
        }

        return Optional.of(new ForwardCheckpointEvent(
                signalTime, now, strategy, index, correlationKey,
                fwdSpots[0], fwdSpots[1], fwdSpots[2], fwdSpots[3], fwdSpots[4],
                mfePct, maePct,
                Map.of("spotAtSignal", spotAtSignal)
        ));
    }

    /**
     * Reads existing correlation keys from a forward_checkpoint.csv file. Returns
     * empty set when the file doesn't exist. Used for idempotency across JVM restarts.
     */
    private Set<String> readCorrelationKeysFromCsv(Path forwardCsv) {
        if (!Files.exists(forwardCsv)) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        try (Stream<String> lines = Files.lines(forwardCsv)) {
            boolean header = true;
            for (String line : (Iterable<String>) lines::iterator) {
                if (header) {
                    header = false;
                    continue;
                }
                String[] cols = splitFirstColumns(line, 4);
                if (cols.length >= 4) {
                    keys.add(cols[3]);
                }
            }
        } catch (IOException ex) {
            log.warn("[ForwardCheckpointService] failed to read existing {}: {}",
                    forwardCsv, ex.getMessage());
        }
        return keys;
    }

    /**
     * Splits a CSV line on the first {@code n} commas, returning at most {@code n+1}
     * elements. The (n+1)th element absorbs all remaining content including embedded
     * quoted JSON. Sufficient when we only need a fixed prefix of columns.
     */
    static String[] splitFirstColumns(String line, int n) {
        if (line == null || line.isEmpty()) {
            return new String[0];
        }
        return line.split(",", n + 1);
    }

    /** Test-only: exposed for unit tests to seed the processed-keys cache. */
    public int processedKeyCount() {
        return processedKeys.size();
    }
}
