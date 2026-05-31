package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Captures spike episodes (entered or deduped) for forward-return validation.
 */
@Component
public class SpikeEpisodeRecorder {

    private static final Logger log = LoggerFactory.getLogger(SpikeEpisodeRecorder.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Path DIR = Path.of("data", "spike-episodes");

    private static final String HEADER = String.join(",",
            "episodeId", "firstAt", "indexType", "direction", "magnitudePct", "entered",
            "spot", "fwdSpot5m", "fwdSpot15m", "fwdSpot30m"
    ) + System.lineSeparator();

    public void record(IndexType indexType, TickMomentumDetector.MomentumSignal spike,
                       boolean entered, Instant at) {
        try {
            Files.createDirectories(DIR);
            Path out = DIR.resolve(LocalDate.now(IST) + ".csv");
            boolean writeHeader = !Files.exists(out) || Files.size(out) == 0;
            String episodeId = OiMomentumEntryDiagnostics.spikeEpisodeId(indexType, spike);
            String row = String.join(",",
                    csv(episodeId),
                    csv(IstDateTimes.formatInstant(at)),
                    csv(indexType.name()),
                    csv(spike.direction()),
                    csv(spike.magnitude()),
                    csv(entered),
                    csv(spike.spotPrice()),
                    csv(""),
                    csv(""),
                    csv("")
            ) + System.lineSeparator();
            if (writeHeader) {
                Files.writeString(out, HEADER, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
            Files.writeString(out, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("[SpikeEpisode] record failed: {}", ex.getMessage());
        }
    }

    private static String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        if (value instanceof Boolean b) {
            return b ? "true" : "false";
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
