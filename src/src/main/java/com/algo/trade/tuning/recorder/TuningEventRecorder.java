package com.algo.trade.tuning.recorder;

import com.algo.trade.tuning.TuningEvent;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.capture.CaptureToggleService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Single write entry point for all tuning events. Sync, no queue, no worker thread.
 *
 * <h2>Routing</h2>
 * Events land at {@code <baseDir>/<IST_date>/<strategy>/<event_type>.csv}. Strategy
 * and event type are encoded in the path — not the row body — so we don't repeat
 * them on every line.
 *
 * <h2>Concurrency</h2>
 * One {@link ReentrantLock} per file path, held for the duration of the
 * {@code Files.writeString} call (sub-millisecond on local SSD). Per-strategy
 * subdirectories from {@link CaptureToggleService}-driven
 * adapters mean cross-strategy contention is impossible by construction; the lock
 * only protects same-strategy same-event-type concurrent writes.
 *
 * <h2>Day rotation</h2>
 * No explicit rotation step — the path is recomputed per write from the event's
 * {@link TuningEvent#eventTime()}, so the first write of a new IST day lands at a
 * new path. Each new file is initialized with its header line on first touch.
 *
 * <h2>Capture toggle</h2>
 * Every {@link #record(TuningEvent)} call first asks {@link CaptureToggleService}
 * whether capture is on for {@code (strategy, eventType)}. If not, the call is a
 * no-op — zero allocation, no file IO. This is the only gate; defaults are OFF, so
 * a fresh deployment writes nothing until you flip a toggle in the UI.
 *
 * <h2>Failures</h2>
 * Disk-write failures are logged at WARN and silently swallowed. Trading capture is
 * advisory data; we never propagate IOException to the trading thread.
 */
@Service
public class TuningEventRecorder {

    private static final Logger log = LoggerFactory.getLogger(TuningEventRecorder.class);

    private final Path baseDir;
    private final CaptureToggleService captureToggle;
    private final TuningEventCsvWriter csvWriter;
    private final IstDayClock clock;

    /** Per-file mutex. New entries appear only as new files are first written. */
    private final ConcurrentHashMap<Path, ReentrantLock> fileLocks = new ConcurrentHashMap<>();

    /** Set of paths whose header has been written this JVM session. */
    private final java.util.Set<Path> headerWritten =
            ConcurrentHashMap.newKeySet();

    private final AtomicLong totalWrites = new AtomicLong(0);
    private final AtomicLong totalWriteNanos = new AtomicLong(0);
    private final AtomicLong totalFailures = new AtomicLong(0);

    /**
     * Drop counters by (reason|strategy|type). Drives both the dashboard
     * snapshot AND a throttled WARN log so any silent drop in the unified
     * pipeline appears in the running log within 30 seconds.
     */
    private final ConcurrentHashMap<String, AtomicLong> droppedByReason = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastLoggedMs = new ConcurrentHashMap<>();
    private static final long DROP_LOG_THROTTLE_MS = 30_000L;

    @org.springframework.beans.factory.annotation.Autowired
    public TuningEventRecorder(@Value("${tuning.capture.base-dir:reports/tuning/events}") String baseDirPath,
                                CaptureToggleService captureToggle,
                                IstDayClock clock) {
        this(Path.of(baseDirPath), captureToggle, new TuningEventCsvWriter(), clock);
    }

    /** Test-friendly constructor. */
    public TuningEventRecorder(Path baseDir,
                                CaptureToggleService captureToggle,
                                TuningEventCsvWriter csvWriter,
                                IstDayClock clock) {
        this.baseDir = baseDir;
        this.captureToggle = captureToggle;
        this.csvWriter = csvWriter;
        this.clock = clock;
    }

    /**
     * Records a single event. If capture is OFF for the strategy/event-type, the call
     * is a no-op. Otherwise the row is appended synchronously to the per-strategy /
     * per-type CSV for the event's IST date.
     */
    public void record(TuningEvent event) {
        if (event == null) {
            countDrop("null_event", "<null>", null);
            return;
        }
        if (!captureToggle.isEnabled(event.strategy(), event.type())) {
            countDrop("toggle_off", event.strategy().name(), event.type());
            return;
        }
        Path file = resolvePath(event);
        ReentrantLock lock = fileLocks.computeIfAbsent(file, k -> new ReentrantLock());
        long start = System.nanoTime();
        lock.lock();
        try {
            ensureHeader(file, event.type());
            String row = csvWriter.format(event);
            Files.writeString(file, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            totalWrites.incrementAndGet();
            totalWriteNanos.addAndGet(System.nanoTime() - start);
        } catch (IOException ex) {
            totalFailures.incrementAndGet();
            countDrop("io_error", event.strategy().name(), event.type());
            log.warn("[TuningEventRecorder] write failed for {}: {}", file, ex.getMessage());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Resolves the target file path:
     * {@code <baseDir>/<YYYY-MM-DD IST>/<strategy_lowercase>/<event_type>.csv}.
     */
    Path resolvePath(TuningEvent event) {
        String date = clock.istDate(event.eventTime()).toString();
        String strategyDir = event.strategy().name().toLowerCase(Locale.ROOT);
        String fileName = event.type().fileBaseName() + ".csv";
        return baseDir.resolve(date).resolve(strategyDir).resolve(fileName);
    }

    /** Writes the CSV header if this file has not been touched in this JVM session. */
    private void ensureHeader(Path file, TuningEventType type) throws IOException {
        if (headerWritten.contains(file)) {
            return;
        }
        if (Files.exists(file)) {
            // File already has its header from a previous JVM run — trust the existing header.
            headerWritten.add(file);
            return;
        }
        Files.createDirectories(file.getParent());
        Files.writeString(file, csvWriter.headerFor(type), StandardOpenOption.CREATE);
        headerWritten.add(file);
    }

    // ── Lightweight observability (used by Phase 6 live dashboard) ────────

    public long totalWrites() { return totalWrites.get(); }
    public long totalFailures() { return totalFailures.get(); }

    /** Average write latency in microseconds (cheap rolling indicator, not a histogram). */
    public double avgWriteLatencyMicros() {
        long n = totalWrites.get();
        if (n == 0) return 0.0;
        return totalWriteNanos.get() / (double) n / 1_000.0;
    }

    /** Per-reason drop counters — visible to the dashboard / diagnostics endpoint. */
    public java.util.Map<String, Long> droppedSnapshot() {
        java.util.Map<String, Long> snap = new java.util.LinkedHashMap<>();
        droppedByReason.forEach((k, v) -> snap.put(k, v.get()));
        return snap;
    }

    /**
     * Bumps a drop counter and emits a throttled WARN per distinct
     * (reason, strategy, type) so the running log reveals silent drops
     * without spamming. Throttled to once per 30 seconds per pattern.
     */
    private void countDrop(String reason, String strategy, TuningEventType type) {
        String key = reason + "|" + strategy + "|" + (type == null ? "-" : type.name());
        droppedByReason.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        long now = System.currentTimeMillis();
        AtomicLong last = lastLoggedMs.computeIfAbsent(key, k -> new AtomicLong(0));
        long prev = last.get();
        if (now - prev >= DROP_LOG_THROTTLE_MS && last.compareAndSet(prev, now)) {
            long count = droppedByReason.get(key).get();
            log.warn("[TuningEventRecorder] DROPPED reason={} strategy={} type={} count_so_far={}",
                    reason, strategy, type, count);
        }
    }
}
