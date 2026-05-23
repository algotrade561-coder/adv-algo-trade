package com.algo.trade.marketdata;

import com.algo.trade.util.IstDateTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Structured post-market log for OI REST fallback windows and batch outcomes.
 */
@Component
public class OiRestFallbackEventLog {

    private static final Logger log = LoggerFactory.getLogger(OiRestFallbackEventLog.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Path DIR = Path.of("reports", "entry-signals");
    private static final Path EVENTS = DIR.resolve("oi-rest-fallback-events.csv");

    private static final String HEADER = String.join(",",
            "timestamp", "marketTime", "event", "wsStale", "staleInstrumentCount",
            "requested", "received", "updated", "restCallCount", "detail"
    ) + System.lineSeparator();

    public void log(String event, boolean wsStale, int staleInstrumentCount,
                    int requested, int received, int updated, int restCallCount, String detail) {
        try {
            Files.createDirectories(DIR);
            String row = String.join(",",
                    csv(IstDateTimes.formatInstant(Instant.now())),
                    csv(IstDateTimes.formatLocalTime(LocalTime.now(IST))),
                    csv(event),
                    csv(wsStale),
                    csv(staleInstrumentCount),
                    csv(requested),
                    csv(received),
                    csv(updated),
                    csv(restCallCount),
                    csv(detail == null ? "" : detail)
            ) + System.lineSeparator();
            append(row);
        } catch (IOException ex) {
            log.warn("[OiRestFallback] Event log write failed: {}", ex.getMessage());
        }
    }

    private void append(String row) throws IOException {
        if (Files.notExists(EVENTS) || Files.size(EVENTS) == 0) {
            Files.writeString(EVENTS, HEADER, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        Files.writeString(EVENTS, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
