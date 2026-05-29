package com.algo.trade.strategy.oimomentum.v3;

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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * V3 OPERATOR — Decision recorder.
 *
 * <p>Writes one CSV row per V3 entry evaluation to {@code data/v3-decisions/YYYY-MM-DD.csv}.
 * Flushes asynchronously every 5 seconds to keep the hot path lock-free.</p>
 */
@Component
public class V3DecisionRecorder {

    private static final Logger log = LoggerFactory.getLogger(V3DecisionRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${oi-momentum.v3.decision-log-dir:data/v3-decisions}")
    private String decisionLogDir;

    @Value("${oi-momentum.v3.decision-log-enabled:true}")
    private boolean enabled;

    private final ConcurrentLinkedQueue<String> pendingRows = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService flusher = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "v3-decision-flusher");
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
            log.warn("[V3Recorder] cannot create decision log dir {}: {}", decisionLogDir, e.getMessage());
        }
        flusher.scheduleAtFixedRate(this::flush, 5, 5, TimeUnit.SECONDS);
    }

    public void record(V3DecisionRecord r) {
        if (!enabled || r == null) return;
        pendingRows.offer(r.toCsv());
    }

    private void flush() {
        if (pendingRows.isEmpty()) return;
        try {
            LocalDate today = LocalDate.now(IST);
            Path out = Path.of(decisionLogDir, today.toString() + ".csv");
            boolean firstWrite = !Files.exists(out);
            StringBuilder sb = new StringBuilder();
            if (firstWrite || !today.equals(lastHeaderDay)) {
                sb.append(V3DecisionRecord.CSV_HEADER).append('\n');
                lastHeaderDay = today;
            }
            String row;
            while ((row = pendingRows.poll()) != null) {
                sb.append(row).append('\n');
            }
            Files.writeString(out, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("[V3Recorder] flush failed: {}", e.getMessage());
        }
    }
}
