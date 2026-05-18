package com.algo.trade.execution.exit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * ML-style shadow log for spread exit evaluations (no decision impact).
 */
@Component
public class SpreadExitShadowRecorder {

    private static final Logger log = LoggerFactory.getLogger(SpreadExitShadowRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Path OUTPUT_DIR = Path.of("reports/ml-shadow");
    private static final Path CSV = OUTPUT_DIR.resolve("ml-spread-exit-shadow.csv");

    private static final String HEADER = String.join(",",
            "timestamp", "groupId", "strategyType", "underlying",
            "profitPercent", "peakProfitPercent", "effectiveSl", "effectiveTarget",
            "exitMode", "atrUsed", "decision", "detail") + System.lineSeparator();

    private final Map<String, Instant> lastRecorded = new ConcurrentHashMap<>();

    public void record(ExitEvaluationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        Instant now = Instant.now();
        Instant last = lastRecorded.get(snapshot.positionId());
        if (last != null && now.isBefore(last.plusSeconds(60))) {
            return;
        }
        lastRecorded.put(snapshot.positionId(), now);
        try {
            ensureHeader();
            String line = String.join(",",
                    now.toString(),
                    csv(snapshot.positionId()),
                    csv(snapshot.strategyType()),
                    csv(snapshot.underlying()),
                    fmt(snapshot.profitPercent()),
                    fmt(snapshot.peakProfitPercent()),
                    fmt(snapshot.effectiveStopLossPercent()),
                    fmt(snapshot.effectiveTargetPercent()),
                    snapshot.exitMode().name(),
                    Boolean.toString(snapshot.atrUsed()),
                    csv(snapshot.decision()),
                    csv(snapshot.detail())) + System.lineSeparator();
            Files.writeString(CSV, line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.debug("Spread exit shadow write failed: {}", ex.getMessage());
        }
    }

    private void ensureHeader() throws IOException {
        Files.createDirectories(OUTPUT_DIR);
        if (!Files.exists(CSV) || Files.size(CSV) == 0) {
            Files.writeString(CSV, HEADER, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
    }

    private static String csv(String v) {
        if (v == null) {
            return "";
        }
        return v.contains(",") ? "\"" + v.replace("\"", "\"\"") + "\"" : v;
    }

    private static String fmt(double v) {
        return String.format("%.2f", v);
    }
}
