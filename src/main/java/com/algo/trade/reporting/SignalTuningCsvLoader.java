package com.algo.trade.reporting;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.Timeframe;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads entry-signals, execution outcomes, and forward candles from {@code reports/entry-signals}
 * and optional zip archives under {@code reports/archive}.
 */
final class SignalTuningCsvLoader {

    private static final Logger log = LoggerFactory.getLogger(SignalTuningCsvLoader.class);
    static final Path ACTIVE_DIR = Path.of("reports", "entry-signals");
    static final Path ARCHIVE_DIR = Path.of("reports", "archive");

    private SignalTuningCsvLoader() {
    }

    static Loaded load() {
        Map<String, SignalRow> signals = new LinkedHashMap<>();
        Map<String, ExecutionRow> entries = new LinkedHashMap<>();
        Map<String, ExecutionRow> exits = new LinkedHashMap<>();
        Map<String, Map<Instant, Candle>> optionCandles = new LinkedHashMap<>();
        Map<String, List<ChainLevelRow>> chainByDecision = new LinkedHashMap<>();

        loadDir(ACTIVE_DIR, signals, entries, exits, optionCandles, chainByDecision);
        loadRecentArchives(signals, entries, exits, optionCandles, chainByDecision);

        Map<String, List<Candle>> finalizedCandles = new LinkedHashMap<>();
        for (var e : optionCandles.entrySet()) {
            finalizedCandles.put(e.getKey(),
                    e.getValue().values().stream().sorted(Comparator.comparing(Candle::timestamp)).toList());
        }
        log.info("Signal tuning data loaded: signals={}, entryOutcomes={}, exitOutcomes={}, optionSeries={}, chainKeys={}",
                signals.size(), entries.size(), exits.size(), finalizedCandles.size(), chainByDecision.size());
        return new Loaded(List.copyOf(signals.values()), List.copyOf(entries.values()), List.copyOf(exits.values()),
                finalizedCandles, chainByDecision);
    }

    private static void loadRecentArchives(Map<String, SignalRow> signals, Map<String, ExecutionRow> entries,
                                           Map<String, ExecutionRow> exits,
                                           Map<String, Map<Instant, Candle>> optionCandles,
                                           Map<String, List<ChainLevelRow>> chainByDecision) {
        if (!Files.isDirectory(ARCHIVE_DIR)) {
            return;
        }
        try {
            List<Path> zips = Files.list(ARCHIVE_DIR)
                    .filter(p -> p.getFileName().toString().endsWith(".zip"))
                    .sorted(Comparator.comparing(Path::getFileName).reversed())
                    .limit(3)
                    .toList();
            for (Path zip : zips) {
                loadZip(zip, signals, entries, exits, optionCandles, chainByDecision);
            }
        } catch (IOException ex) {
            log.warn("Failed to load signal tuning archives: {}", ex.getMessage());
        }
    }

    private static void loadDir(Path dir, Map<String, SignalRow> signals, Map<String, ExecutionRow> entries,
                                Map<String, ExecutionRow> exits, Map<String, Map<Instant, Candle>> optionCandles,
                                Map<String, List<ChainLevelRow>> chainByDecision) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        try {
            Path signalsFile = dir.resolve("entry-signals.csv");
            if (Files.exists(signalsFile)) {
                readSignals(Files.newInputStream(signalsFile), signals);
            }
            Path entryOutcomes = dir.resolve("entry-execution-outcomes.csv");
            if (Files.exists(entryOutcomes)) {
                readExecutions(Files.newInputStream(entryOutcomes), entries, true);
            }
            Path exitOutcomes = dir.resolve("exit-execution-outcomes.csv");
            if (Files.exists(exitOutcomes)) {
                readExecutions(Files.newInputStream(exitOutcomes), exits, false);
            }
            Path candles = dir.resolve("entry-candles.csv");
            if (Files.exists(candles)) {
                readCandles(Files.newInputStream(candles), optionCandles);
            }
            Path chainLevels = dir.resolve("option-chain-levels.csv");
            if (Files.exists(chainLevels)) {
                readChainLevels(Files.newInputStream(chainLevels), chainByDecision);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load signal tuning CSV from " + dir, ex);
        }
    }

    private static void loadZip(Path zipPath, Map<String, SignalRow> signals, Map<String, ExecutionRow> entries,
                                Map<String, ExecutionRow> exits, Map<String, Map<Instant, Candle>> optionCandles,
                                Map<String, List<ChainLevelRow>> chainByDecision) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry s = zip.getEntry("entry-signals.csv");
            if (s != null) {
                readSignals(zip.getInputStream(s), signals);
            }
            ZipEntry entry = zip.getEntry("entry-execution-outcomes.csv");
            if (entry != null) {
                readExecutions(zip.getInputStream(entry), entries, true);
            }
            ZipEntry exit = zip.getEntry("exit-execution-outcomes.csv");
            if (exit != null) {
                readExecutions(zip.getInputStream(exit), exits, false);
            }
            ZipEntry candles = zip.getEntry("entry-candles.csv");
            if (candles != null) {
                readCandles(zip.getInputStream(candles), optionCandles);
            }
            ZipEntry chain = zip.getEntry("option-chain-levels.csv");
            if (chain != null) {
                readChainLevels(zip.getInputStream(chain), chainByDecision);
            }
        } catch (IOException ex) {
            log.warn("Failed to read archive {}: {}", zipPath.getFileName(), ex.getMessage());
        }
    }

    private static void readChainLevels(InputStream input, Map<String, List<ChainLevelRow>> chainByDecision)
            throws IOException {
        Map<String, List<ChainLevelRow>> grouped = new LinkedHashMap<>();
        for (Map<String, String> r : Csv.read(input).rows()) {
            String key = firstText(r.get("decisionKey"), "");
            if (key.isBlank()) {
                continue;
            }
            ChainLevelRow row = new ChainLevelRow(
                    key,
                    parseInstant(r.get("evaluationTimestamp")),
                    firstText(r.get("underlying"), ""),
                    firstText(r.get("optionType"), ""),
                    longValue(r.get("strike")),
                    decimal(r.get("spotPrice")),
                    longValue(r.get("callOpenInterest")),
                    longValue(r.get("putOpenInterest")),
                    longValue(r.get("callOpenInterestChange")),
                    longValue(r.get("putOpenInterestChange")),
                    decimal(r.get("callLastPrice")),
                    decimal(r.get("putLastPrice"))
            );
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        for (var e : grouped.entrySet()) {
            e.getValue().sort(Comparator.comparingLong(ChainLevelRow::strike));
            chainByDecision.put(e.getKey(), List.copyOf(e.getValue()));
        }
    }

    private static void readSignals(InputStream input, Map<String, SignalRow> signals) throws IOException {
        for (Map<String, String> r : Csv.read(input).rows()) {
            String key = firstText(r.get("decisionKey"), "");
            if (key.isBlank()) {
                continue;
            }
            Instant ts = parseInstant(r.get("timestamp"));
            signals.put(key, new SignalRow(
                    key,
                    ts,
                    firstText(r.get("strategyType"), "UNKNOWN"),
                    firstText(r.get("signalType"), "NO_TRADE"),
                    firstText(r.get("underlying"), ""),
                    firstText(r.get("optionType"), ""),
                    firstText(r.get("selectedInstrumentKey"), ""),
                    decimal(r.get("optionLastPrice")),
                    decimalOr(r.get("minSignalScorePercent"), new BigDecimal("70")),
                    decimal(r.get("confidenceScore")),
                    firstText(r.get("firstFailedFilter"), inferFailedFilter(r.get("reasons"))),
                    firstText(r.get("reasons"), ""),
                    bool(r.get("vwapPassed")),
                    bool(r.get("breakoutPassed")),
                    bool(r.get("volumeSpike")),
                    bool(r.get("oiPassed")),
                    bool(r.get("ivPassed")),
                    bool(r.get("liquidityPassed")),
                    bool(r.get("timePassed"))
            ));
        }
    }

    private static void readExecutions(InputStream input, Map<String, ExecutionRow> rows, boolean entry)
            throws IOException {
        for (Map<String, String> r : Csv.read(input).rows()) {
            String key = firstText(r.get("decisionKey"), "");
            if (key.isBlank()) {
                continue;
            }
            rows.put(key, new ExecutionRow(
                    key,
                    parseInstant(r.get("timestamp")),
                    firstText(r.get("stage"), ""),
                    bool(r.get("accepted")),
                    firstText(r.get("signalType"), ""),
                    firstText(r.get("underlying"), ""),
                    firstText(r.get("brokerRejectionReason"), ""),
                    firstText(r.get("reasons"), "")
            ));
        }
    }

    private static void readCandles(InputStream input, Map<String, Map<Instant, Candle>> optionCandles) throws IOException {
        for (Map<String, String> r : Csv.read(input).rows()) {
            if (!"MARKET".equalsIgnoreCase(firstText(r.get("candleRole"), ""))) {
                continue;
            }
            String instrument = firstText(r.get("selectedInstrumentKey"), "");
            if (instrument.isBlank()) {
                continue;
            }
            Candle candle = new Candle(
                    instrument,
                    parseInstant(r.get("candleTimestamp")),
                    parseTimeframe(r.get("timeframe")),
                    decimal(r.get("open")),
                    decimal(r.get("high")),
                    decimal(r.get("low")),
                    decimal(r.get("close")),
                    longValue(r.get("volume")),
                    longValue(r.get("openInterest"))
            );
            optionCandles.computeIfAbsent(instrument, k -> new TreeMap<>()).put(candle.timestamp(), candle);
        }
    }

    static String inferFailedFilter(String reasons) {
        if (reasons == null || reasons.isBlank()) {
            return "unknown";
        }
        if (reasons.contains("Signal score failed")) {
            return "signalScore";
        }
        if (reasons.contains("RSI momentum gate failed")) {
            return "rsi";
        }
        if (reasons.contains("Entry time window failed") || reasons.contains("timeWindow")) {
            return "timeWindow";
        }
        if (reasons.contains("Trend condition failed")) {
            return "trend";
        }
        if (reasons.contains("Breakout condition failed")) {
            return "breakout";
        }
        if (reasons.contains("Volume spike missing")) {
            return "volumeSpike";
        }
        if (reasons.contains("OI behavior does not support")) {
            return "oi";
        }
        if (reasons.contains("Side-specific entry filter failed")) {
            return "sideFilter";
        }
        if (reasons.contains("Environment score")) {
            return "environmentScore";
        }
        if (reasons.contains("noEmaCross") || reasons.contains("insufficientCandles")) {
            return "scalpSetup";
        }
        if (reasons.contains("rocTooWeak")) {
            return "momentumRoc";
        }
        if (reasons.contains("ivRankTooHigh")) {
            return "ivRank";
        }
        return "other";
    }

    private static Timeframe parseTimeframe(String value) {
        if (value == null || value.isBlank()) {
            return Timeframe.ONE_MINUTE;
        }
        try {
            return Timeframe.valueOf(value);
        } catch (Exception ex) {
            return Timeframe.ONE_MINUTE;
        }
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Instant.EPOCH;
        }
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            return Instant.EPOCH;
        }
    }

    private static String firstText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(value);
        } catch (Exception ex) {
            return BigDecimal.ZERO;
        }
    }

    private static BigDecimal decimalOr(String value, BigDecimal fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return new BigDecimal(value);
        } catch (Exception ex) {
            return fallback;
        }
    }

    private static boolean bool(String value) {
        return Boolean.parseBoolean(value == null ? "false" : value);
    }

    private static long longValue(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (Exception ex) {
            return 0L;
        }
    }

    record SignalRow(
            String decisionKey,
            Instant timestamp,
            String strategyType,
            String signalType,
            String underlying,
            String optionType,
            String instrumentKey,
            BigDecimal optionPrice,
            BigDecimal minSignalScore,
            BigDecimal confidenceScore,
            String firstFailedFilter,
            String reasons,
            boolean vwapPassed,
            boolean breakoutPassed,
            boolean volumeSpike,
            boolean oiPassed,
            boolean ivPassed,
            boolean liquidityPassed,
            boolean timePassed
    ) {
        boolean isBuy() {
            return signalType != null && (signalType.startsWith("BUY_") || signalType.startsWith("SELL_"));
        }

        boolean isNoTrade() {
            return "NO_TRADE".equalsIgnoreCase(signalType);
        }
    }

    record ExecutionRow(
            String decisionKey,
            Instant timestamp,
            String stage,
            boolean accepted,
            String signalType,
            String underlying,
            String brokerRejectionReason,
            String reasons
    ) {
    }

    record ChainLevelRow(
            String decisionKey,
            Instant evaluationTimestamp,
            String underlying,
            String optionType,
            long strike,
            BigDecimal spotPrice,
            long callOpenInterest,
            long putOpenInterest,
            long callOpenInterestChange,
            long putOpenInterestChange,
            BigDecimal callLastPrice,
            BigDecimal putLastPrice
    ) {
    }

    record Loaded(
            List<SignalRow> signals,
            List<ExecutionRow> entryOutcomes,
            List<ExecutionRow> exitOutcomes,
            Map<String, List<Candle>> optionCandlesByInstrument,
            Map<String, List<ChainLevelRow>> chainLevelsByDecisionKey
    ) {
        Loaded(List<SignalRow> signals, List<ExecutionRow> entryOutcomes, List<ExecutionRow> exitOutcomes,
               Map<String, List<Candle>> optionCandlesByInstrument) {
            this(signals, entryOutcomes, exitOutcomes, optionCandlesByInstrument, Map.of());
        }
    }

    static final class Csv {
        private final List<Map<String, String>> rows;

        private Csv(List<Map<String, String>> rows) {
            this.rows = rows;
        }

        List<Map<String, String>> rows() {
            return rows;
        }

        static Csv read(InputStream input) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String headerLine = reader.readLine();
                if (headerLine == null) {
                    return new Csv(List.of());
                }
                List<String> headers = split(headerLine);
                if (!headers.isEmpty() && headers.get(0).startsWith("\uFEFF")) {
                    headers.set(0, headers.get(0).substring(1));
                }
                List<Map<String, String>> rows = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    List<String> values = split(line);
                    Map<String, String> row = new LinkedHashMap<>();
                    int offset = 0;
                    boolean headerHasKey = headers.contains("decisionKey");
                    boolean rowHasLeadingKey = !headerHasKey
                            && values.size() > 1
                            && !looksLikeInstant(values.get(0))
                            && looksLikeInstant(values.get(1));
                    if (rowHasLeadingKey || (!headerHasKey && values.size() == headers.size() + 1)) {
                        row.put("decisionKey", values.get(0));
                        offset = 1;
                    }
                    for (int i = 0; i < headers.size(); i++) {
                        row.put(headers.get(i), i + offset < values.size() ? values.get(i + offset) : "");
                    }
                    rows.add(row);
                }
                return new Csv(rows);
            }
        }

        private static List<String> split(String line) {
            List<String> out = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = !quoted;
                    }
                } else if (c == ',' && !quoted) {
                    out.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            out.add(cur.toString());
            return out;
        }

        private static boolean looksLikeInstant(String value) {
            if (value == null || value.isBlank()) {
                return false;
            }
            try {
                Instant.parse(value);
                return true;
            } catch (Exception ex) {
                return false;
            }
        }
    }
}
