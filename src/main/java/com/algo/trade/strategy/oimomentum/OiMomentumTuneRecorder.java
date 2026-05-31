package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.OiRestFallbackService;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.reporting.SignalDecisionKey;
import com.algo.trade.util.IstDateTimes;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * CSV capture for OI Momentum tuning — does not affect entry/exit logic.
 */
@Component
public class OiMomentumTuneRecorder {

    private static final Logger log = LoggerFactory.getLogger(OiMomentumTuneRecorder.class);
    private static final Path DIR = Path.of("reports", "entry-signals");
    private static final Path SIGNALS = DIR.resolve("oi-momentum-signals.csv");
    private static final Path REJECTS = DIR.resolve("oi-momentum-rejects.csv");
    private static final Path EXITS = DIR.resolve("oi-momentum-exits.csv");
    private static final Path CHAIN_LEVELS = DIR.resolve("option-chain-levels.csv");

    private static final String SIGNAL_HEADER = String.join(",",
            "decisionKey", "signalId", "timestamp", "marketTime", "indexType", "underlying", "signalType", "optionType",
            "instrumentKey", "strike", "spot", "premium", "spreadPct", "quantityLots",
            "entryCase", "momentumDir", "momentumType", "momentumMagnitudePct",
            "oiDir", "pcrDir", "pcr", "ceOiChange", "peOiChange", "oiAvailable",
            "spot30mHigh", "spot30mLow", "breakoutDistancePct", "spikeEpisodeId",
            "vix", "daysToExpiry", "isExpiryDay", "paperTrading", "reasons",
            "timeOfDayMode", "matrixCase", "entryPath", "operatorScore", "biasScore"
    ) + System.lineSeparator();

    private static final String REJECT_HEADER = String.join(",",
            "timestamp", "marketTime", "indexType", "rejectReason", "wouldBeCase", "blockDetail",
            "momentumDir", "momentumType", "momentumMagnitudePct", "pcr", "pcrDir",
            "oiDir", "ceOiChange", "peOiChange", "oiAvailable", "oiAdvanced",
            "spot", "atm", "spot30mHigh", "spot30mLow", "rangePct30m", "breakoutDistancePct",
            "atmCeLast", "atmPeLast", "vix", "daysToExpiry", "isExpiryDay",
            "restFallbackActive", "maxAtmOiStaleSec", "wsTickAgeSec",
            "timeOfDayMode", "matrixCase", "entryPath", "operatorScore", "biasScore"
    ) + System.lineSeparator();

    private static final String EXIT_HEADER = String.join(",",
            "decisionKey", "tradeId", "timestamp", "indexType", "instrumentKey",
            "entryPrice", "exitPrice", "profitPct", "realizedPnl", "holdSeconds",
            "exitReason", "entryCase", "reversal"
    ) + System.lineSeparator();

    private static final String CHAIN_HEADER = String.join(",",
            "decisionKey", "evaluationTimestamp", "underlying", "optionType",
            "selectedInstrumentKey", "spotPrice", "strike",
            "callOpenInterest", "putOpenInterest",
            "callOpenInterestChange", "putOpenInterestChange",
            "callLastPrice", "putLastPrice"
    ) + System.lineSeparator();

    private final LiveInstrumentCache liveInstrumentCache;
    private final OiRestFallbackService oiRestFallbackService;
    private final OIMomentumConfig config;

    /**
     * Per-JVM cache of files whose existing header has been verified against the
     * current code's expected header. Avoids hitting disk on every reject write.
     */
    private final java.util.Set<Path> headerVerified = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public OiMomentumTuneRecorder(LiveInstrumentCache liveInstrumentCache,
                                  OIMomentumConfig config,
                                  @org.springframework.beans.factory.annotation.Autowired(required = false)
                                  OiRestFallbackService oiRestFallbackService) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.config = config;
        this.oiRestFallbackService = oiRestFallbackService;
    }

    public String recordBuy(StrategyDecision decision, OiMomentumEntryDiagnostics diag,
                            Quote entryQuote, int atm, Double spreadPct, int lotSize) {
        String decisionKey = SignalDecisionKey.from(decision);
        String signalId = java.util.UUID.randomUUID().toString().substring(0, 12);
        try {
            Files.createDirectories(DIR);
            String row = String.join(",",
                    csv(decisionKey),
                    csv(signalId),
                    csv(IstDateTimes.formatInstant(decision.timestamp())),
                    csv(IstDateTimes.formatLocalTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))),
                    csv(diag.indexType().name()),
                    csv(decision.underlying()),
                    csv(decision.signalType()),
                    csv(decision.optionType().map(Enum::name).orElse(null)),
                    csv(decision.selectedInstrumentKey().orElse(null)),
                    csv(atm),
                    csv(decision.underlyingPrice()),
                    csv(entryQuote.lastPrice()),
                    csv(spreadPct),
                    csv(lotSize),
                    csv(diag.entryCase()),
                    csv(diag.momentumDir()),
                    csv(diag.momentumType()),
                    csv(diag.momentumMagnitudePct()),
                    csv(diag.oiDir()),
                    csv(diag.pcrDir()),
                    csv(diag.pcr()),
                    csv(diag.ceOiChange()),
                    csv(diag.peOiChange()),
                    csv(diag.oiAvailable()),
                    csv(diag.spot30mHigh()),
                    csv(diag.spot30mLow()),
                    csv(diag.breakoutDistancePct()),
                    csv(diag.spikeEpisodeId()),
                    csv(diag.vix()),
                    csv(diag.daysToExpiry()),
                    csv(diag.expiryDay()),
                    csv(diag.paperTrading()),
                    csv(decision.reasons().isEmpty() ? diag.signalReason() : String.join("; ", decision.reasons())),
                    csv(diag.timeOfDayMode()),
                    csv(diag.matrixCase()),
                    csv(diag.entryPath()),
                    csv(diag.operatorScore()),
                    csv(diag.biasScore())
            ) + System.lineSeparator();
            append(SIGNALS, SIGNAL_HEADER, row);
            writeChainLevels(decisionKey, decision, diag, atm);
        } catch (IOException ex) {
            log.warn("[OiMomentumTune] BUY record failed: {}", ex.getMessage());
        }
        return decisionKey;
    }

    /** @return sample time if a row was written (for throttle state). */
    public Instant recordReject(IndexType indexType, Instant lastSampleTime, String rejectReason,
                                OiMomentumEntryDiagnostics partial) {
        if (!shouldSampleReject(lastSampleTime, rejectReason)) {
            return lastSampleTime;
        }
        Instant now = Instant.now();
        try {
            Files.createDirectories(DIR);
            String row = buildRejectRow(now, indexType, rejectReason, partial);
            append(REJECTS, REJECT_HEADER, row);
            return now;
        } catch (IOException ex) {
            log.warn("[OiMomentumTune] reject record failed: {}", ex.getMessage());
        }
        return lastSampleTime;
    }

    private boolean shouldSampleReject(Instant lastSampleTime, String rejectReason) {
        if (config.isRecordEveryReject()) {
            return true;
        }
        Duration interval = rejectReason != null && rejectReason.startsWith("matrix_skip:")
                ? Duration.ofSeconds(Math.max(1, config.getMatrixRejectSampleIntervalSeconds()))
                : Duration.ofSeconds(Math.max(1, config.getRejectSampleIntervalSeconds()));
        Instant now = Instant.now();
        return lastSampleTime == null || Duration.between(lastSampleTime, now).compareTo(interval) >= 0;
    }

    private String buildRejectRow(Instant now, IndexType indexType, String rejectReason,
                                  OiMomentumEntryDiagnostics partial) {
        return String.join(",",
                csv(IstDateTimes.formatInstant(now)),
                csv(IstDateTimes.formatLocalTime(java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata")))),
                csv(indexType.name()),
                csv(rejectReason),
                csv(partial != null ? partial.entryCase() : ""),
                csv(partial != null ? partial.blockDetail() : ""),
                csv(partial != null ? partial.momentumDir() : ""),
                csv(partial != null ? partial.momentumType() : ""),
                csv(partial != null ? partial.momentumMagnitudePct() : ""),
                csv(partial != null ? partial.pcr() : ""),
                csv(partial != null ? partial.pcrDir() : ""),
                csv(partial != null ? partial.oiDir() : ""),
                csv(partial != null ? partial.ceOiChange() : ""),
                csv(partial != null ? partial.peOiChange() : ""),
                csv(partial != null ? partial.oiAvailable() : ""),
                csv(partial != null ? partial.oiAdvanced() : ""),
                csv(partial != null ? partial.spot() : ""),
                csv(partial != null ? partial.atm() : ""),
                csv(partial != null ? partial.spot30mHigh() : ""),
                csv(partial != null ? partial.spot30mLow() : ""),
                csv(partial != null ? partial.rangePct30m() : ""),
                csv(partial != null ? partial.breakoutDistancePct() : ""),
                csv(partial != null ? partial.atmCeLast() : ""),
                csv(partial != null ? partial.atmPeLast() : ""),
                csv(partial != null ? partial.vix() : ""),
                csv(partial != null ? partial.daysToExpiry() : ""),
                csv(partial != null ? partial.expiryDay() : ""),
                csv(oiRestFallbackService != null && oiRestFallbackService.isRestFallbackActive()),
                csv(oiRestFallbackService != null ? oiRestFallbackService.getMaxAtmOiSampleAgeSec(indexType) : ""),
                csv(oiRestFallbackService != null ? oiRestFallbackService.getWsTickAgeSec() : ""),
                csv(partial != null ? partial.timeOfDayMode() : ""),
                csv(partial != null ? partial.matrixCase() : ""),
                csv(partial != null ? partial.entryPath() : ""),
                csv(partial != null ? partial.operatorScore() : ""),
                csv(partial != null ? partial.biasScore() : "")
        ) + System.lineSeparator();
    }

    public void recordExit(String decisionKey, IndexType indexType, TradeEntity trade,
                           double exitPrice, String exitReason, OiMomentumEntryDiagnostics entryDiag,
                           boolean reversal) {
        if (decisionKey == null || decisionKey.isBlank()) {
            return;
        }
        double entry = trade.getEntryPrice().doubleValue();
        double profitPct = entry > 0 ? (exitPrice - entry) / entry * 100 : 0;
        double pnl = (exitPrice - entry) * trade.getQuantity();
        long holdSeconds = trade.getEntryTime() != null
                ? Duration.between(trade.getEntryTime(), Instant.now()).getSeconds()
                : 0;
        try {
            Files.createDirectories(DIR);
            String row = String.join(",",
                    csv(decisionKey),
                    csv(trade.getTradeId()),
                    csv(IstDateTimes.formatInstant(Instant.now())),
                    csv(indexType.name()),
                    csv(trade.getInstrumentKey()),
                    csv(entry),
                    csv(exitPrice),
                    csv(profitPct),
                    csv(pnl),
                    csv(holdSeconds),
                    csv(exitReason),
                    csv(entryDiag != null ? entryDiag.entryCase() : ""),
                    csv(reversal)
            ) + System.lineSeparator();
            append(EXITS, EXIT_HEADER, row);
        } catch (IOException ex) {
            log.warn("[OiMomentumTune] exit record failed: {}", ex.getMessage());
        }
    }

    private void writeChainLevels(String decisionKey, StrategyDecision decision,
                                  OiMomentumEntryDiagnostics diag, int atm) {
        IndexType indexType = diag.indexType();
        int interval = indexType.strikeInterval();
        StringBuilder rows = new StringBuilder();
        BigDecimal spot = decision.underlyingPrice();
        OptionType optType = decision.optionType().orElse(OptionType.CE);
        for (int i = -3; i <= 3; i++) {
            int strike = atm + i * interval;
            long ceOi = 0, peOi = 0, ceCh = 0, peCh = 0;
            BigDecimal cePrice = BigDecimal.ZERO;
            BigDecimal pePrice = BigDecimal.ZERO;
            for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
                if (opt.getIndexType() != indexType) {
                    continue;
                }
                if (opt.getStrikePrice() != strike) {
                    continue;
                }
                if ("CE".equals(opt.getOptionType())) {
                    ceOi = opt.getOpenInterest();
                    ceCh = opt.getOiChangeSince(3);
                    cePrice = BigDecimal.valueOf(opt.getLastPrice());
                } else {
                    peOi = opt.getOpenInterest();
                    peCh = opt.getOiChangeSince(3);
                    pePrice = BigDecimal.valueOf(opt.getLastPrice());
                }
            }
            rows.append(String.join(",",
                    csv(decisionKey),
                    csv(IstDateTimes.formatInstant(decision.timestamp())),
                    csv(decision.underlying()),
                    csv(optType.name()),
                    csv(decision.selectedInstrumentKey().orElse(null)),
                    csv(spot),
                    csv(strike),
                    csv(ceOi), csv(peOi), csv(ceCh), csv(peCh),
                    csv(cePrice), csv(pePrice)
            )).append(System.lineSeparator());
        }
        if (!rows.isEmpty()) {
            try {
                append(CHAIN_LEVELS, CHAIN_HEADER, rows.toString());
            } catch (IOException ex) {
                log.warn("[OiMomentumTune] chain levels failed: {}", ex.getMessage());
            }
        }
    }

    private void append(Path path, String header, String rows) throws IOException {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        boolean writeHeader = false;
        if (Files.notExists(path) || Files.size(path) == 0) {
            writeHeader = true;
        } else if (!headerVerified.contains(path)) {
            String existing = readFirstLine(path);
            String expected = stripTrailingNewline(header);
            if (!expected.equals(existing)) {
                Path backup = rotateLegacy(path);
                log.warn("[OiMomentumTune] CSV schema drift at {} — rotated to {} and started fresh "
                        + "(existing header={} cols, expected={} cols)",
                        path, backup, countCols(existing), countCols(expected));
                writeHeader = true;
            }
        }
        if (writeHeader) {
            Files.writeString(path, header, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        headerVerified.add(path);
        Files.writeString(path, rows, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static String readFirstLine(Path path) throws IOException {
        try (java.util.stream.Stream<String> lines = Files.lines(path)) {
            return lines.findFirst().orElse("");
        }
    }

    private static String stripTrailingNewline(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\n' || s.charAt(end - 1) == '\r')) {
            end--;
        }
        return s.substring(0, end);
    }

    private static int countCols(String headerLine) {
        if (headerLine == null || headerLine.isEmpty()) return 0;
        int n = 1;
        for (int i = 0; i < headerLine.length(); i++) {
            if (headerLine.charAt(i) == ',') n++;
        }
        return n;
    }

    private static Path rotateLegacy(Path path) throws IOException {
        String fileName = path.getFileName().toString();
        String stem = fileName.endsWith(".csv") ? fileName.substring(0, fileName.length() - 4) : fileName;
        String stamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path backup = path.resolveSibling(stem + ".legacy-" + stamp + ".csv");
        Files.move(path, backup);
        return backup;
    }

    private static String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        if (value instanceof Boolean b) {
            return csv(b ? "true" : "false");
        }
        String text = String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\n")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
