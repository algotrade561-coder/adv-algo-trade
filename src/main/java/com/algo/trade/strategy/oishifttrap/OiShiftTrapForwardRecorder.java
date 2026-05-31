package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * One row per fired signal — forward checkpoints backfilled by {@link ShiftTrapForwardBackfillService}.
 */
@Component
public class OiShiftTrapForwardRecorder {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapForwardRecorder.class);
    static final Path FILE = Path.of("reports", "entry-signals", "oi-shift-trap-forward.csv");

    public static final List<String> CHECKPOINTS = List.of("30s", "1m", "3m", "5m", "10m", "15m", "30m");

    static String header() {
        List<String> cols = new ArrayList<>(List.of(
                "decisionKey", "signalAt", "underlying", "trapSide", "trapStrike", "spotAtSignal", "trappedOiAtSignal"));
        for (String cp : CHECKPOINTS) {
            cols.add("spot_" + cp);
            cols.add("spotMovePct_" + cp);
            cols.add("trappedOi_" + cp);
            cols.add("trappedOiDelta_" + cp);
            cols.add("oppositeOi_" + cp);
            cols.add("imbalance_" + cp);
            cols.add("atmCeLtp_" + cp);
            cols.add("atmPeLtp_" + cp);
        }
        return String.join(",", cols) + System.lineSeparator();
    }

    public void registerSignal(String decisionKey, Instant signalAt, String underlying, String trapSide,
                               BigDecimal trapStrike, BigDecimal spotAtSignal, long trappedOiAtSignal) {
        if (decisionKey == null || decisionKey.isBlank()) {
            return;
        }
        try {
            Files.createDirectories(FILE.getParent());
            List<String> cols = new ArrayList<>(List.of(
                    csv(decisionKey),
                    csv(IstDateTimes.formatInstant(signalAt)),
                    csv(underlying),
                    csv(trapSide),
                    csv(trapStrike),
                    csv(spotAtSignal),
                    csv(trappedOiAtSignal)
            ));
            for (int i = 0; i < CHECKPOINTS.size() * 8; i++) {
                cols.add("");
            }
            String row = String.join(",", cols) + System.lineSeparator();
            if (!Files.exists(FILE)) {
                Files.writeString(FILE, header(), StandardOpenOption.CREATE);
            }
            Files.writeString(FILE, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("[OiShiftTrapForward] register failed: {}", ex.getMessage());
        }
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
