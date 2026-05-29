package com.algo.trade.strategy.oimomentum;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Legacy CASE 1–5 + CASE 0 detection recorder.
 *
 * <p>Async-buffered CSV writer for {@link LegacyDetectionRecord}. Same shape as
 * {@code V3DecisionRecorder} — flushes every 5 s, daily rollover, header on first
 * write per day, daemon thread so it cannot block JVM shutdown.</p>
 *
 * <p>Configured via:</p>
 * <pre>
 * oi-momentum:
 *   legacy-decision-log-dir: data/oi-decisions     # default
 *   legacy-decision-log-enabled: true              # default
 * </pre>
 */
@Component
public class LegacyDetectionRecorder {

    private static final Logger log = LoggerFactory.getLogger(LegacyDetectionRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${oi-momentum.legacy-decision-log-dir:data/oi-decisions}")
    private String decisionLogDir;

    @Value("${oi-momentum.legacy-decision-log-enabled:true}")
    private boolean enabled;

    private final ConcurrentLinkedQueue<String> pendingRows = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "oi-decision-flusher");
        t.setDaemon(true);
        return t;
    });

    private volatile LocalDate lastHeaderDay = null;

    @PostConstruct
    void start() {
        if (!enabled) return;
        try {
            Files.createDirectories(Path.of(decisionLogDir));
        } catch (IOException e) {
            log.warn("[OiRecorder] cannot create decision log dir {}: {}",
                    decisionLogDir, e.getMessage());
        }
        flusher.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);
    }

    public void record(LegacyDetectionRecord r) {
        if (!enabled || r == null) return;
        try {
            pendingRows.offer(r.toCsv());
        } catch (Exception ex) {
            log.debug("[OiRecorder] enqueue failed: {}", ex.getMessage());
        }
    }

    private void flush() {
        if (pendingRows.isEmpty()) return;
        try {
            LocalDate today = LocalDate.now(IST);
            Path out = Path.of(decisionLogDir, today.toString() + ".csv");
            boolean firstWrite = !Files.exists(out);
            StringBuilder sb = new StringBuilder();
            if (firstWrite || !today.equals(lastHeaderDay)) {
                sb.append(LegacyDetectionRecord.CSV_HEADER).append('\n');
                lastHeaderDay = today;
            }
            String row;
            while ((row = pendingRows.poll()) != null) {
                sb.append(row).append('\n');
            }
            Files.writeString(out, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[OiRecorder] flush failed: {}", e.getMessage());
        }
    }
}
