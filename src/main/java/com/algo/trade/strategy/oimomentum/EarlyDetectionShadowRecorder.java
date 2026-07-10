package com.algo.trade.strategy.oimomentum;

import jakarta.annotation.PreDestroy;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SHADOW recorder for the early-detection module (Workstream D).
 *
 * <p>Persists <b>virtual</b> early-detection signals to
 * {@code data/tuning/early-detection-shadow-&lt;date&gt;.csv}. This is a research log only:
 * it places no orders and is read by the offline event study (Workstream C) to validate
 * whether the signals actually lead price. It is parallel to the ML shadow recorders
 * ({@code MlShadowRecorder}, {@code MlExitShadowRecorder}) — a dedicated, side-effect-free
 * shadow path.</p>
 *
 * <p>Append-only CSV; a future {@code MicrostructureParquetRoller}-style sweep can roll it
 * to Parquet. Buffered + flushed off the caller's thread so the 1-second detector loop
 * never blocks on I/O.</p>
 */
@Component
public class EarlyDetectionShadowRecorder {

    private static final Logger log = LoggerFactory.getLogger(EarlyDetectionShadowRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String HEADER =
            "recvEpochMs,index,direction,oiVelocityPct,dominantSide,supportStrike,"
            + "resistanceStrike,atmStrike,spot,coilRangePct,imbalance,trigger\n";

    private final ConcurrentLinkedQueue<String> buffer = new ConcurrentLinkedQueue<>();

    /** A single virtual early-detection signal. No execution semantics whatsoever. */
    public record VirtualSignal(
            Instant at,
            String index,
            int direction,
            double oiVelocityPct,
            String dominantSide,
            int supportStrike,
            int resistanceStrike,
            int atmStrike,
            double spot,
            double coilRangePct,
            double imbalance,
            String trigger) {}

    /** Enqueue a virtual signal (non-blocking, exception-safe). */
    public void record(VirtualSignal s) {
        try {
            buffer.add(s.at().toEpochMilli() + "," + s.index() + "," + s.direction() + ","
                    + fmt(s.oiVelocityPct()) + "," + s.dominantSide() + "," + s.supportStrike() + ","
                    + s.resistanceStrike() + "," + s.atmStrike() + "," + fmt(s.spot()) + ","
                    + fmt(s.coilRangePct()) + "," + fmt(s.imbalance()) + "," + s.trigger() + "\n");
        } catch (Exception e) {
            log.debug("[EarlyDetectShadow] record skipped: {}", e.toString());
        }
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 15_000)
    public void flush() {
        if (buffer.isEmpty()) return;
        List<String> batch = new ArrayList<>();
        for (String row; (row = buffer.poll()) != null; ) {
            batch.add(row);
            if (batch.size() >= 10_000) break;
        }
        if (batch.isEmpty()) return;
        try {
            Path dir = Path.of("data", "tuning");
            Files.createDirectories(dir);
            Path file = dir.resolve("early-detection-shadow-" + LocalDate.now(IST) + ".csv");
            boolean fresh = !Files.exists(file);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) w.write(HEADER);
                for (String row : batch) w.write(row);
            }
        } catch (IOException e) {
            log.warn("[EarlyDetectShadow] flush failed (rows requeued): {}", e.getMessage());
            buffer.addAll(batch);
        }
    }

    @PreDestroy
    void onShutdown() {
        flush();
    }

    private static String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.4f", d);
    }
}
