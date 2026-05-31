package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Shadow confirmation gates per fired signal — no production gating.
 */
@Component
public class OiShiftTrapConfirmationRecorder {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapConfirmationRecorder.class);
    private static final Path FILE = Path.of("reports", "entry-signals", "oi-shift-trap-confirmations.csv");

    private static final String HEADER = String.join(",",
            "decisionKey", "signalAt", "underlying", "trapSide", "trapStrike",
            "confirm_momentumDecelerating", "confirm_spotStalled", "confirm_oiStillBuilding",
            "confirm_oppositeOiFlushing", "confirm_priceRetraced", "confirm_proximityTightening",
            "confirm_volumeSpike", "confirm_pcrAligned", "confirmationsPassedCount",
            "spotVelocity1m", "spotVelocity3m", "spotAcceleration"
    ) + System.lineSeparator();

    public void record(String decisionKey, Instant signalAt, String underlying, String trapSide,
                       int trapStrike,
                       ShiftTrapConfirmationEvaluator.Confirmations c,
                       ShiftTrapVelocityCalculator.Velocity velocity) {
        if (decisionKey == null || decisionKey.isBlank() || c == null) {
            return;
        }
        try {
            Files.createDirectories(FILE.getParent());
            String row = String.join(",",
                    csv(decisionKey),
                    csv(IstDateTimes.formatInstant(signalAt)),
                    csv(underlying),
                    csv(trapSide),
                    csv(trapStrike),
                    csv(c.momentumDecelerating()),
                    csv(c.spotStalled()),
                    csv(c.oiStillBuilding()),
                    csv(c.oppositeOiFlushing()),
                    csv(c.priceRetraced()),
                    csv(c.proximityTightening()),
                    csv(c.volumeSpike()),
                    csv(c.pcrAligned()),
                    csv(c.passedCount()),
                    csv(String.format("%.4f", velocity.spotVelocity1m())),
                    csv(String.format("%.4f", velocity.spotVelocity3m())),
                    csv(String.format("%.4f", velocity.spotAcceleration()))
            ) + System.lineSeparator();
            if (!Files.exists(FILE)) {
                Files.writeString(FILE, HEADER, StandardOpenOption.CREATE);
            }
            Files.writeString(FILE, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.warn("[OiShiftTrapConfirm] record failed: {}", ex.getMessage());
        }
    }

    private static String csv(Object value) {
        if (value == null) {
            return "";
        }
        String s = String.valueOf(value);
        if (s.contains("\"") || s.contains(",") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
