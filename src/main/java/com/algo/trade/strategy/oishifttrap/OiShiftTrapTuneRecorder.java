package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.reporting.SignalDecisionKey;
import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dedicated CSV capture for OI Shift Trap — tuning only.
 */
@Component
public class OiShiftTrapTuneRecorder {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapTuneRecorder.class);
    private static final Path DIR = Path.of("reports", "entry-signals");
    private static final Path EVALUATIONS = DIR.resolve("oi-shift-trap-evaluations.csv");
    private static final Path NEAR_MISS = DIR.resolve("oi-shift-trap-near-miss.csv");
    private static final Path SIGNALS = DIR.resolve("oi-shift-trap-signals.csv");

    private static final Duration EVAL_SAMPLE_INTERVAL = Duration.ofSeconds(30);
    private static final Duration NEAR_MISS_COOLDOWN = Duration.ofMinutes(5);

    private static final String EVAL_HEADER = String.join(",",
            "timestamp", "marketTime", "underlying", "spot", "trendDir", "volumeMode", "latestVolume",
            "outcome", "primaryBlocker", "chainLevels",
            "bestCeStrike", "bestCeOi", "bestCeChange", "bestCeImbalance", "bestCeProximityPct", "bestCeScore", "bestCeFailedGate",
            "bestPeStrike", "bestPeOi", "bestPeChange", "bestPeImbalance", "bestPeProximityPct", "bestPeScore", "bestPeFailedGate",
            "signalGenerated", "trapSide", "signalStrike", "signalScore"
    ) + System.lineSeparator();

    private static final String NEAR_HEADER = String.join(",",
            "timestamp", "marketTime", "underlying", "side", "strike", "trappedOi", "oiChange", "imbalance",
            "proximityPct", "score", "failedGate", "trendDir", "spot"
    ) + System.lineSeparator();

    private static final String SIGNAL_HEADER = String.join(",",
            "decisionKey", "timestamp", "marketTime", "underlying", "signalType", "trapSide", "strike", "spot",
            "score", "imbalance", "proximityPct", "trappedOi", "oiChange", "trendDir", "reasons"
    ) + System.lineSeparator();

    private final Map<String, Instant> lastEvalByUnderlying = new ConcurrentHashMap<>();
    private final Map<String, Instant> lastNearMissByKey = new ConcurrentHashMap<>();

    public void recordEvaluation(OiShiftTrapDiagnostics diag) {
        if (diag == null || diag.underlying() == null || diag.underlying().isBlank()) {
            return;
        }
        Instant now = Instant.now();
        Instant last = lastEvalByUnderlying.get(diag.underlying());
        if (last != null && Duration.between(last, now).compareTo(EVAL_SAMPLE_INTERVAL) < 0) {
            return;
        }
        try {
            Files.createDirectories(DIR);
            OiShiftTrapDiagnostics.CandidateSnapshot ce = diag.bestCe() != null
                    ? diag.bestCe() : OiShiftTrapDiagnostics.CandidateSnapshot.empty();
            OiShiftTrapDiagnostics.CandidateSnapshot pe = diag.bestPe() != null
                    ? diag.bestPe() : OiShiftTrapDiagnostics.CandidateSnapshot.empty();
            String row = String.join(",",
                    csv(IstDateTimes.formatInstant(now)),
                    csv(IstDateTimes.formatLocalTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))),
                    csv(diag.underlying()),
                    csv(diag.spot()),
                    csv(diag.trendDirection()),
                    csv(diag.volumeMode()),
                    csv(diag.latestVolume()),
                    csv(diag.outcome()),
                    csv(diag.primaryBlocker()),
                    csv(diag.chainLevels()),
                    csv(ce.strike()), csv(ce.trappedOi()), csv(ce.oiChange()), csv(ce.imbalance()),
                    csv(ce.proximityPct()), csv(ce.score()), csv(ce.failedGate()),
                    csv(pe.strike()), csv(pe.trappedOi()), csv(pe.oiChange()), csv(pe.imbalance()),
                    csv(pe.proximityPct()), csv(pe.score()), csv(pe.failedGate()),
                    csv(diag.signalGenerated()),
                    csv(diag.trapSide()),
                    csv(diag.signalStrike()),
                    csv(diag.signalScore())
            ) + System.lineSeparator();
            append(EVALUATIONS, EVAL_HEADER, row);
            recordNearMissIfApplicable(diag, now);
            lastEvalByUnderlying.put(diag.underlying(), now);
        } catch (IOException ex) {
            log.warn("[OiShiftTrapTune] evaluation record failed: {}", ex.getMessage());
        }
    }

    public void recordScanBlocked(String underlying, String outcome, String blocker, BigDecimal spot) {
        recordEvaluation(OiShiftTrapDiagnostics.blocked(underlying, outcome, blocker, spot));
    }

    public String recordSignal(StrategyDecision decision, OiShiftTrapDiagnostics diag) {
        String decisionKey = SignalDecisionKey.from(decision);
        try {
            Files.createDirectories(DIR);
            OiShiftTrapDiagnostics.CandidateSnapshot snap = "CE".equals(diag.trapSide())
                    ? diag.bestCe() : diag.bestPe();
            String row = String.join(",",
                    csv(decisionKey),
                    csv(IstDateTimes.formatInstant(decision.timestamp())),
                    csv(IstDateTimes.formatLocalTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))),
                    csv(decision.underlying()),
                    csv(decision.signalType()),
                    csv(diag.trapSide()),
                    csv(decision.selectedStrike().orElse(null)),
                    csv(decision.underlyingPrice()),
                    csv(diag.signalScore()),
                    csv(snap != null ? snap.imbalance() : ""),
                    csv(snap != null ? snap.proximityPct() : ""),
                    csv(snap != null ? snap.trappedOi() : ""),
                    csv(snap != null ? snap.oiChange() : ""),
                    csv(diag.trendDirection()),
                    csv(String.join("; ", decision.reasons()))
            ) + System.lineSeparator();
            append(SIGNALS, SIGNAL_HEADER, row);
        } catch (IOException ex) {
            log.warn("[OiShiftTrapTune] signal record failed: {}", ex.getMessage());
        }
        return decisionKey;
    }

    private void recordNearMissIfApplicable(OiShiftTrapDiagnostics diag, Instant now) throws IOException {
        if (diag.signalGenerated()) {
            return;
        }
        for (String side : new String[]{"CE", "PE"}) {
            OiShiftTrapDiagnostics.CandidateSnapshot c = "CE".equals(side) ? diag.bestCe() : diag.bestPe();
            if (c == null || !c.present() || c.score() < 40) {
                continue;
            }
            String key = diag.underlying() + "|" + side + "|" + c.strike();
            Instant last = lastNearMissByKey.get(key);
            if (last != null && Duration.between(last, now).compareTo(NEAR_MISS_COOLDOWN) < 0) {
                continue;
            }
            String row = String.join(",",
                    csv(IstDateTimes.formatInstant(now)),
                    csv(IstDateTimes.formatLocalTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))),
                    csv(diag.underlying()),
                    csv(side),
                    csv(c.strike()),
                    csv(c.trappedOi()),
                    csv(c.oiChange()),
                    csv(c.imbalance()),
                    csv(c.proximityPct()),
                    csv(c.score()),
                    csv(c.failedGate()),
                    csv(diag.trendDirection()),
                    csv(diag.spot())
            ) + System.lineSeparator();
            append(NEAR_MISS, NEAR_HEADER, row);
            lastNearMissByKey.put(key, now);
        }
    }

    private static void append(Path file, String header, String row) throws IOException {
        if (!Files.exists(file)) {
            Files.writeString(file, header, StandardOpenOption.CREATE);
        }
        Files.writeString(file, row, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
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
