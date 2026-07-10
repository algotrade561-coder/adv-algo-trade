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
    /** Max distance (s) the anchor snapshot may be from the signal time before the whole checkpoint is
     *  discarded — prevents a next-morning warm-up anchoring a stale signal to a cross-day spot (#162). */
    static final long MAX_ANCHOR_DELTA_SEC = 120;
    /** Max distance (s) a snapshot may be from a checkpoint offset (30s/1m/…) to count as that fwdSpot —
     *  stops a 5-min warm-up point being reported as the +30s spot (#113). */
    static final long MAX_CHECKPOINT_DELTA_SEC = 90;
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

    /**
     * P1.3 (2026-06-26): also forward-checkpoint a sample of REJECTED evaluations, not just signals.
     * Without this, the blocker opportunity-cost analysis (did a gate reject a setup that would have
     * worked?) is impossible — today's ~18–20k daily rejects have no forward outcome at all. Sampled
     * (1 in {@code rejectSampleRate}, deterministic by correlationKey hash → idempotent) to bound volume.
     */
    @Value("${tuning.forward.checkpoint-rejects-enabled:true}")
    private boolean checkpointRejectsEnabled = true;
    @Value("${tuning.forward.reject-sample-rate:50}")
    private int rejectSampleRate = 50;

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
                if (checkpointRejectsEnabled) {
                    total += backfillRejectsForDate(strategy, date, now);
                }
            }
        }
        return total;
    }

    /**
     * P1.3 — forward-checkpoint a deterministic sample of rejected evaluations so the report can measure
     * the opportunity cost of each gate (what did the market do after we rejected?). Reads
     * {@code evaluation.csv} (columns: eventTime, recordedAtDeltaUs, index, correlationKey, outcome,
     * blocker, …), samples 1-in-{@code rejectSampleRate} by correlationKey hash (stable across sweeps →
     * idempotent), and writes checkpoints tagged {@code source=reject} + the blocker. Same ripeness /
     * snapshot-buffer rules as signals.
     */
    int backfillRejectsForDate(StrategyType strategy, LocalDate date, Instant now) {
        Path strategyDir = baseDir.resolve(date.toString())
                .resolve(strategy.name().toLowerCase(Locale.ROOT));
        Path evalCsv = strategyDir.resolve("evaluation.csv");
        if (!Files.exists(evalCsv)) {
            return 0;
        }
        Path forwardCsv = strategyDir.resolve("forward_checkpoint.csv");
        Set<String> alreadyDone = readCorrelationKeysFromCsv(forwardCsv);
        // Per-strategy ADAPTIVE sample rate (2026-07-02): a single global 1-in-50 starved low-cadence
        // strategies (scalping ~179 evals/day → ~4 samples), leaving their opportunity-cost empty for weeks.
        // Scale to the day's eval volume — sample ~all of a low-cadence strategy, keep the configured cap
        // (50) for oi_momentum (~30k/day). Deterministic hash sampling keeps it idempotent across sweeps.
        long evalRowCount = countDataRows(evalCsv);
        int rate = adaptiveRejectRate(evalRowCount);
        int written = 0;
        try (Stream<String> lines = Files.lines(evalCsv)) {
            boolean header = true;
            for (String line : (Iterable<String>) lines::iterator) {
                if (header) { header = false; continue; }
                String[] cols = splitFirstColumns(line, 6);
                if (cols.length < 6) { continue; }
                String correlationKey = cols[3];
                // Deterministic sample — same rows chosen on every sweep, so idempotent + cheap to skip.
                if (Math.floorMod(correlationKey.hashCode(), rate) != 0) { continue; }
                if (alreadyDone.contains(correlationKey) || processedKeys.contains(correlationKey)) {
                    processedKeys.add(correlationKey);
                    continue;
                }
                // Only REJECTED/SKIPPED evals belong in the reject arm — FIRED signals are covered by the
                // signal arm, and tagging them source=reject double-counts + contaminates the outcome stats.
                String outcome = cols[4] == null ? "" : cols[4].trim();
                if ("FIRED".equalsIgnoreCase(outcome)) { continue; }
                Instant evalTime;
                IndexType index;
                try {
                    evalTime = Instant.parse(cols[0]);
                    index = IndexType.valueOf(cols[2]);
                } catch (Exception ex) {
                    continue;
                }
                if (evalTime.plus(RIPENESS_MARGIN).isAfter(now)) { continue; } // not ripe yet
                String blocker = cols[5] == null ? "" : cols[5].trim();
                if (blocker.isEmpty()) {
                    // SKIPPED gating rows (e.g. the spread family) carry their reason in
                    // attr_extra.firstFailedFilter, not the blocker column — pull + normalize it so the
                    // opportunity-cost table names the real gate (ivRankTooHigh) instead of a blank bucket.
                    String fff = extractFirstFailedFilter(line);
                    if (fff != null && !fff.isBlank()) { blocker = normalizeSkipBlocker(fff); }
                }
                java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
                attrs.put("source", "reject");
                attrs.put("blocker", blocker);
                attrs.put("outcome", outcome);
                Optional<ForwardCheckpointEvent> event = computeCheckpoint(strategy, index, evalTime,
                        correlationKey, now, attrs);
                if (event.isPresent()) {
                    recorder.record(event.get());
                    processedKeys.add(correlationKey);
                    written++;
                }
            }
        } catch (IOException ex) {
            log.warn("[ForwardCheckpointService] failed to read {}: {}", evalCsv, ex.getMessage());
        }
        return written;
    }

    /** Data-row count (excluding header) for a CSV — used to scale the reject sample rate to eval volume. */
    private long countDataRows(Path csv) {
        try (Stream<String> s = Files.lines(csv)) {
            return Math.max(0, s.count() - 1);
        } catch (IOException e) {
            return 0;
        }
    }

    /**
     * Reject sample rate scaled to the day's eval volume: 1-in-ceil(evals/600), clamped to
     * [1, {@link #rejectSampleRate}]. Low-cadence strategies (few hundred evals) → rate 1 (sample ~all so
     * their opportunity-cost fills within a session or two); oi_momentum (~30k) → the configured 50.
     */
    private int adaptiveRejectRate(long evalRows) {
        int cap = Math.max(1, rejectSampleRate);
        if (evalRows <= 0) {
            return cap;
        }
        int adaptive = (int) Math.ceil(evalRows / 600.0);
        return Math.max(1, Math.min(cap, adaptive));
    }

    private static final java.util.regex.Pattern FFF_PATTERN =
            java.util.regex.Pattern.compile("firstFailedFilter\"+\\s*:\\s*\"+([^\"]+)");

    /** Pull {@code firstFailedFilter} out of the CSV-quoted attr_extra JSON tail of a raw eval line. */
    static String extractFirstFailedFilter(String rawLine) {
        if (rawLine == null) {
            return null;
        }
        java.util.regex.Matcher m = FFF_PATTERN.matcher(rawLine);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Normalize a SKIPPED gating reason into a stable blocker token: strip a category prefix like
     * {@code spreadEntryBlocked:} and any parenthesized params/timers, so
     * {@code spreadEntryBlocked:ivRankTooHigh(ivRank=25.0,max=25.0)} → {@code ivRankTooHigh} and the
     * {@code insufficientTrendCandlesForSqueeze(n=8)/(n=7)} variants collapse into one group.
     */
    static String normalizeSkipBlocker(String fff) {
        if (fff == null) {
            return "";
        }
        String s = fff.trim();
        int colon = s.indexOf(':');
        if (colon > 0 && s.substring(0, colon).endsWith("Blocked")) {
            s = s.substring(colon + 1);
        }
        int paren = s.indexOf('(');
        if (paren > 0) {
            s = s.substring(0, paren);
        }
        return s.trim();
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
        return computeCheckpoint(strategy, index, signalTime, correlationKey, now, Map.of());
    }

    Optional<ForwardCheckpointEvent> computeCheckpoint(StrategyType strategy, IndexType index,
                                                        Instant signalTime, String correlationKey,
                                                        Instant now, Map<String, Object> extraAttrs) {
        Optional<MarketSnapshot> anchor = buffer.nearest(index, signalTime);
        if (anchor.isEmpty()) {
            return Optional.empty();
        }
        // Anchor-distance guard (#162/#113): if the nearest snapshot to the signal time is more than
        // MAX_ANCHOR_DELTA_SEC away, this is a cross-day / big-gap situation (e.g. a next-morning warm-up
        // backfill anchoring yesterday's 15:20 signal to today's 09:15 spot) — skip rather than write a
        // fabricated forward path.
        long anchorDeltaSec = Math.abs(java.time.Duration.between(anchor.get().at(), signalTime).getSeconds());
        if (anchorDeltaSec > MAX_ANCHOR_DELTA_SEC) {
            return Optional.empty();
        }
        double spotAtSignal = anchor.get().spot();
        if (spotAtSignal <= 0) {
            return Optional.empty();
        }

        Double[] fwdSpots = new Double[CHECKPOINTS.length];
        for (int i = 0; i < CHECKPOINTS.length; i++) {
            Instant target = signalTime.plus(CHECKPOINTS[i]);
            Optional<MarketSnapshot> at = buffer.nearest(index, target);
            // Only record a forward spot if the nearest snapshot is actually CLOSE to the target offset — a
            // 5-min-cadence warm-up buffer (after a restart) would otherwise report a point minutes away as
            // the +30s/+1m spot, silently corrupting the early-path data.
            fwdSpots[i] = at
                    .filter(s -> Math.abs(java.time.Duration.between(s.at(), target).getSeconds()) <= MAX_CHECKPOINT_DELTA_SEC)
                    .map(MarketSnapshot::spot).orElse(null);
        }

        // MFE / MAE over the 30-minute window (relative to spot at signal time).
        List<MarketSnapshot> range = buffer.entriesBetween(index,
                signalTime, signalTime.plus(FORWARD_WINDOW));
        Double mfePct = null;
        Double maePct = null;
        int rangePoints = 0;
        long maxGapSec = 0;
        if (!range.isEmpty()) {
            double maxSpot = Double.NEGATIVE_INFINITY;
            double minSpot = Double.POSITIVE_INFINITY;
            Instant prevAt = null;
            for (MarketSnapshot s : range) {
                // Max inter-point gap (any spot, incl. garbage-skipped) — a warm-up-sparse window has big
                // gaps; the plugin can down-weight/exclude rows whose MFE came from a coarse sample.
                if (prevAt != null) {
                    maxGapSec = Math.max(maxGapSec,
                            Math.abs(java.time.Duration.between(prevAt, s.at()).getSeconds()));
                }
                prevAt = s.at();
                // Skip garbage spots (0 / negative) — a single bad tick would otherwise drag minSpot
                // to ~0 and produce a wildly wrong MAE.
                if (s.spot() <= 0) continue;
                maxSpot = Math.max(maxSpot, s.spot());
                minSpot = Math.min(minSpot, s.spot());
                rangePoints++;
            }
            if (rangePoints > 0) {
                mfePct = (maxSpot - spotAtSignal) / spotAtSignal * 100.0;
                maePct = (minSpot - spotAtSignal) / spotAtSignal * 100.0;
            }
        }

        java.util.Map<String, Object> attrs = new java.util.LinkedHashMap<>();
        attrs.put("spotAtSignal", spotAtSignal);
        // Provenance / quality stamps (#162/#113) so the report can weight or exclude degraded rows:
        // how far the anchor was from the signal, how many valid points the MFE window had, and the largest
        // gap between points (a coarse warm-up window has few points / big gaps).
        attrs.put("anchorDeltaSec", anchorDeltaSec);
        attrs.put("rangePoints", rangePoints);
        attrs.put("maxGapSec", maxGapSec);
        if (extraAttrs != null) {
            attrs.putAll(extraAttrs);
        }
        return Optional.of(new ForwardCheckpointEvent(
                signalTime, now, strategy, index, correlationKey,
                fwdSpots[0], fwdSpots[1], fwdSpots[2], fwdSpots[3], fwdSpots[4],
                mfePct, maePct,
                attrs
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
