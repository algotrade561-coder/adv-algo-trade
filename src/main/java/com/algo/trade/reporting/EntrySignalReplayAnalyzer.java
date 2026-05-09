package com.algo.trade.reporting;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.util.IstDateTimes;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class EntrySignalReplayAnalyzer {

    private static final MathContext MATH = MathContext.DECIMAL64;
    private static final BigDecimal MIN_HEADROOM = new BigDecimal("0.20");
    private static final Path ACTIVE_DIR = Path.of("reports", "entry-signals");
    private static final Path ARCHIVE_DIR = Path.of("reports", "archive");

    private final Config config;

    public EntrySignalReplayAnalyzer(Config config) {
        this.config = config;
    }

    public Summary analyze() {
        Loaded loaded = load();
        List<Row> rows = loaded.rows.values().stream().sorted(Comparator.comparing(Row::timestamp)).toList();
        int accepted = 0;
        int baseBlocked = 0;
        int breakoutBlocked = 0;
        int oiBlocked = 0;
        int headroomBlocked = 0;
        List<Row> tradable = new ArrayList<>();
        for (Row row : rows) {
            Decision d = decide(row, loaded.underlyingCandles.getOrDefault(row.key, List.of()));
            if (d.accepted) {
                accepted++;
                tradable.add(row);
            } else {
                if (!d.base) baseBlocked++;
                if (!d.breakoutConfirmed) breakoutBlocked++;
                if (!row.oiPassed) oiBlocked++;
                if (!d.headroom) headroomBlocked++;
            }
        }
        Replay replay = replay(tradable, loaded.marketCandles);
        return new Summary(rows.size(), accepted, baseBlocked, breakoutBlocked, oiBlocked, headroomBlocked,
                replay.sizingRejected, replay.openTradeBlocked, replay.trades.size(),
                replay.trades.stream().filter(t -> t.pnl.signum() > 0).count(),
                replay.trades.stream().filter(t -> t.pnl.signum() < 0).count(),
                replay.trades.stream().map(t -> t.pnl).reduce(BigDecimal.ZERO, BigDecimal::add),
                replay.trades);
    }

    private Replay replay(List<Row> tradable, Map<String, List<Candle>> marketCandles) {
        List<Trade> trades = new ArrayList<>();
        int sizingRejected = 0;
        int openTradeBlocked = 0;
        Instant openUntil = null;
        for (Row row : tradable) {
            if (config.maxOpenTrades <= 1 && openUntil != null && !row.entryTime.isAfter(openUntil)) {
                openTradeBlocked++;
                continue;
            }
            int lot = row.underlying == UnderlyingSymbol.BANKNIFTY ? config.bankNiftyLotSize : config.niftyLotSize;
            int qty = quantity(row.optionPrice, lot);
            if (qty <= 0) {
                sizingRejected++;
                continue;
            }
            Trade trade = replayTrade(row, qty, marketCandles.getOrDefault(row.instrument, List.of()));
            trades.add(trade);
            openUntil = trade.exitTime;
        }
        return new Replay(trades, sizingRejected, openTradeBlocked);
    }

    private Trade replayTrade(Row row, int quantity, List<Candle> candles) {
        BigDecimal entry = row.optionPrice;
        BigDecimal stop = entry.multiply(BigDecimal.ONE.subtract(config.stopLoss.movePointLeft(2)), MATH);
        BigDecimal target = entry.multiply(BigDecimal.ONE.add(config.target.movePointLeft(2)), MATH);
        BigDecimal high = entry;
        BigDecimal trailing = null;
        for (Candle candle : candles) {
            if (!candle.timestamp().isAfter(row.entryTime)) continue;
            high = high.max(candle.high());
            BigDecimal activation = entry.multiply(BigDecimal.ONE.add(config.trailingActivation.movePointLeft(2)), MATH);
            if (high.compareTo(activation) >= 0) {
                BigDecimal candidate = high.multiply(BigDecimal.ONE.subtract(config.trailingGap.movePointLeft(2)), MATH);
                trailing = trailing == null ? candidate : trailing.max(candidate);
            }
            LocalTime marketTime = LocalTime.ofInstant(candle.timestamp(), IstDateTimes.IST);
            if (!marketTime.isBefore(config.forcedExitTime)) {
                return close(row, quantity, candle.timestamp(), candle.close(), "Configured forced square-off");
            }
            if (config.maxHoldMinutes > 0 && Duration.between(row.entryTime, candle.timestamp()).toMinutes() >= config.maxHoldMinutes) {
                return close(row, quantity, candle.timestamp(), candle.close(), "Max hold time exceeded");
            }
            if (candle.low().compareTo(stop) <= 0) {
                return close(row, quantity, candle.timestamp(), stop, "Hard stop loss");
            }
            if (candle.high().compareTo(target) >= 0) {
                return close(row, quantity, candle.timestamp(), target, "Target hit");
            }
            if (trailing != null && candle.close().compareTo(trailing) <= 0) {
                return close(row, quantity, candle.timestamp(), trailing, "Trailing stop hit");
            }
        }
        if (candles.isEmpty()) {
            return close(row, quantity, row.entryTime, entry, "No forward candle data");
        }
        Candle last = candles.get(candles.size() - 1);
        return close(row, quantity, last.timestamp(), last.close(), "End of captured data square-off");
    }

    private Trade close(Row row, int quantity, Instant exitTime, BigDecimal exitPrice, String reason) {
        BigDecimal pnl = exitPrice.subtract(row.optionPrice).multiply(BigDecimal.valueOf(quantity), MATH);
        return new Trade(row.key, row.instrument, row.optionType, quantity, row.entryTime, row.optionPrice, exitTime, exitPrice, pnl, reason);
    }

    private int quantity(BigDecimal premium, int lotSize) {
        if (premium == null || premium.signum() <= 0 || lotSize <= 0) return 0;
        BigDecimal risk = config.totalCapital.multiply(config.maxRiskPerTradePercent, MATH).divide(BigDecimal.valueOf(100), MATH);
        BigDecimal lossPerUnit = premium.multiply(config.stopLoss, MATH).divide(BigDecimal.valueOf(100), MATH);
        if (lossPerUnit.signum() <= 0) return 0;
        int raw = risk.divide(lossPerUnit, MATH).intValue();
        int qty = (raw / lotSize) * lotSize;
        BigDecimal cost = premium.multiply(BigDecimal.valueOf(qty), MATH);
        if (cost.compareTo(config.totalCapital) > 0) {
            int affordableLots = config.totalCapital.divide(premium.multiply(BigDecimal.valueOf(lotSize), MATH), MATH).intValue();
            qty = affordableLots * lotSize;
        }
        return Math.max(qty, 0);
    }

    private Decision decide(Row row, List<Candle> underlyingCandles) {
        boolean rsiPassed = !row.reasons.contains("RSI momentum gate failed");
        boolean base = row.timePassed && row.ivPassed && row.liquidityPassed && rsiPassed
                && row.score.compareTo(row.minScore) >= 0;
        boolean breakoutConfirmed = breakoutConfirmed(row, underlyingCandles);
        boolean headroom = headroom(row);
        boolean accepted = base && row.vwapPassed && row.breakoutPassed && breakoutConfirmed
                && row.volumeSpike && row.oiPassed && headroom;
        return new Decision(accepted, base, breakoutConfirmed, headroom);
    }

    private boolean breakoutConfirmed(Row row, List<Candle> candles) {
        if (candles.size() < 2) return false;
        Candle prev = candles.get(candles.size() - 2);
        Candle last = candles.get(candles.size() - 1);
        if (row.optionType == OptionType.CE) {
            BigDecimal threshold = row.resistance != null && last.close().compareTo(applyPositive(row.resistance, row.breakoutBuffer)) > 0
                    ? applyPositive(row.resistance, row.breakoutBuffer)
                    : applyPositive(maxClose(candles.subList(0, candles.size() - 2)), row.breakoutBuffer);
            return prev.close().compareTo(threshold) > 0 && last.close().compareTo(threshold) > 0;
        }
        BigDecimal threshold = row.support != null && last.close().compareTo(applyNegative(row.support, row.breakoutBuffer)) < 0
                ? applyNegative(row.support, row.breakoutBuffer)
                : applyNegative(minClose(candles.subList(0, candles.size() - 2)), row.breakoutBuffer);
        return prev.close().compareTo(threshold) < 0 && last.close().compareTo(threshold) < 0;
    }

    private boolean headroom(Row row) {
        BigDecimal ref = row.optionType == OptionType.CE ? row.resistance : row.support;
        if (ref == null || row.underlyingPrice.signum() <= 0) return true;
        if (row.optionType == OptionType.CE && ref.compareTo(row.underlyingPrice) <= 0) return true;
        if (row.optionType == OptionType.PE && ref.compareTo(row.underlyingPrice) >= 0) return true;
        BigDecimal distance = ref.subtract(row.underlyingPrice).multiply(BigDecimal.valueOf(100), MATH).divide(row.underlyingPrice, MATH);
        return row.optionType == OptionType.CE ? distance.compareTo(MIN_HEADROOM) >= 0 : distance.compareTo(MIN_HEADROOM.negate()) <= 0;
    }

    private Loaded load() {
        Map<String, Row> rows = new LinkedHashMap<>();
        Map<String, Map<Instant, Candle>> underlying = new HashMap<>();
        Map<String, Map<Instant, Candle>> market = new HashMap<>();
        loadDir(ACTIVE_DIR, rows, underlying, market);
        if (Files.isDirectory(ARCHIVE_DIR)) {
            try {
                Files.list(ARCHIVE_DIR).filter(p -> p.getFileName().toString().endsWith(".zip")).sorted().forEach(p -> loadZip(p, rows, underlying, market));
            } catch (IOException e) {
                throw new IllegalStateException("Failed to list archives", e);
            }
        }
        Map<String, List<Candle>> finalizedUnderlying = new HashMap<>();
        for (var e : underlying.entrySet()) finalizedUnderlying.put(e.getKey(), e.getValue().values().stream().sorted(Comparator.comparing(Candle::timestamp)).toList());
        Map<String, List<Candle>> finalizedMarket = new HashMap<>();
        for (var e : market.entrySet()) finalizedMarket.put(e.getKey(), e.getValue().values().stream().sorted(Comparator.comparing(Candle::timestamp)).toList());
        return new Loaded(rows, finalizedUnderlying, finalizedMarket);
    }

    private void loadDir(Path dir, Map<String, Row> rows, Map<String, Map<Instant, Candle>> underlying, Map<String, Map<Instant, Candle>> market) {
        if (!Files.isDirectory(dir)) return;
        try {
            Path s = dir.resolve("entry-signals.csv");
            if (Files.exists(s)) readSignals(Files.newInputStream(s), rows);
            Path c = dir.resolve("entry-candles.csv");
            if (Files.exists(c)) readCandles(Files.newInputStream(c), underlying, market);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load report dir " + dir, e);
        }
    }

    private void loadZip(Path zipPath, Map<String, Row> rows, Map<String, Map<Instant, Candle>> underlying, Map<String, Map<Instant, Candle>> market) {
        try (ZipFile zip = new ZipFile(zipPath.toFile())) {
            ZipEntry s = zip.getEntry("entry-signals.csv");
            if (s != null) readSignals(zip.getInputStream(s), rows);
            ZipEntry c = zip.getEntry("entry-candles.csv");
            if (c != null) readCandles(zip.getInputStream(c), underlying, market);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load zip " + zipPath, e);
        }
    }

    private void readSignals(InputStream input, Map<String, Row> rows) throws IOException {
        Csv csv = Csv.read(input);
        for (Map<String, String> r : csv.rows) {
            Instant timestamp = firstInstant(r.get("timestamp"), r.get("decisionKey"));
            String instrument = firstText(r.get("selectedInstrumentKey"), "");
            String optionTypeText = firstText(r.get("optionType"), "CE");
            String key = firstText(r.get("decisionKey"),
                    Integer.toUnsignedString((timestamp + "|" + instrument + "|" + optionTypeText).hashCode(), 16));
            Row row = new Row(
                    key,
                    timestamp,
                    timestamp,
                    UnderlyingSymbol.valueOf(firstText(r.get("underlying"), "NIFTY")),
                    OptionType.valueOf(optionTypeText),
                    instrument,
                    decimal(r.get("underlyingPrice")),
                    decimal(r.get("optionLastPrice")),
                    decimalOr(r.get("breakoutBufferPercent"), new BigDecimal("0.05")),
                    decimalOr(r.get("minSignalScorePercent"), new BigDecimal("70")),
                    decimal(r.get("confidenceScore")),
                    nullable(r.get("resistanceStrike")),
                    nullable(r.get("supportStrike")),
                    bool(r.get("vwapPassed")),
                    bool(r.get("breakoutPassed")),
                    bool(r.get("volumeSpike")),
                    bool(r.get("oiPassed")),
                    bool(r.get("ivPassed")),
                    bool(r.get("liquidityPassed")),
                    bool(r.get("timePassed")),
                    r.getOrDefault("reasons", "")
            );
            rows.put(row.key, row);
        }
    }

    private void readCandles(InputStream input, Map<String, Map<Instant, Candle>> underlying, Map<String, Map<Instant, Candle>> market) throws IOException {
        Csv csv = Csv.read(input);
        for (Map<String, String> r : csv.rows) {
            String key = firstText(r.get("decisionKey"), firstText(r.get("evaluationId"), ""));
            Candle candle = new Candle(
                    r.get("selectedInstrumentKey"),
                    parseInstant(r.get("candleTimestamp")),
                    Timeframe.valueOf(r.get("timeframe")),
                    decimal(r.get("open")),
                    decimal(r.get("high")),
                    decimal(r.get("low")),
                    decimal(r.get("close")),
                    longValue(r.get("volume")),
                    longValue(r.get("openInterest"))
            );
            if ("UNDERLYING".equals(r.get("candleRole"))) {
                underlying.computeIfAbsent(key, k -> new TreeMap<>()).put(candle.timestamp(), candle);
            } else {
                market.computeIfAbsent(candle.instrumentKey(), k -> new TreeMap<>()).put(candle.timestamp(), candle);
            }
        }
    }

    private BigDecimal applyPositive(BigDecimal value, BigDecimal pct) { return value.multiply(BigDecimal.ONE.add(pct.movePointLeft(2)), MATH); }
    private BigDecimal applyNegative(BigDecimal value, BigDecimal pct) { return value.multiply(BigDecimal.ONE.subtract(pct.movePointLeft(2)), MATH); }
    private BigDecimal maxClose(List<Candle> candles) { return candles.stream().map(Candle::close).max(BigDecimal::compareTo).orElse(BigDecimal.ZERO); }
    private BigDecimal minClose(List<Candle> candles) { return candles.stream().map(Candle::close).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO); }
    private static Instant parseInstant(String v) { return v == null || v.isBlank() ? Instant.EPOCH : Instant.parse(v); }
    private static Instant firstInstant(String... values) {
        for (String value : values) {
            if (value == null || value.isBlank()) continue;
            try {
                return Instant.parse(value);
            } catch (Exception ignored) {
            }
        }
        return Instant.EPOCH;
    }
    private static String firstText(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
    private static BigDecimal decimal(String v) {
        if (v == null || v.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(v); } catch (Exception ignored) { return BigDecimal.ZERO; }
    }
    private static BigDecimal decimalOr(String v, BigDecimal fallback) {
        if (v == null || v.isBlank()) return fallback;
        try { return new BigDecimal(v); } catch (Exception ignored) { return fallback; }
    }
    private static BigDecimal nullable(String v) {
        if (v == null || v.isBlank()) return null;
        try { return new BigDecimal(v); } catch (Exception ignored) { return null; }
    }
    private static boolean bool(String v) { return Boolean.parseBoolean(v == null ? "false" : v); }
    private static long longValue(String v) { return v == null || v.isBlank() ? 0L : Long.parseLong(v); }

    public record Config(BigDecimal totalCapital, BigDecimal maxRiskPerTradePercent, BigDecimal stopLoss, BigDecimal target,
                         BigDecimal trailingActivation, BigDecimal trailingGap, LocalTime forcedExitTime, int maxHoldMinutes,
                         int maxOpenTrades, int niftyLotSize, int bankNiftyLotSize) {}
    public record Summary(int totalEvaluations, int acceptedByFilters, int blockedByBaseConditions,
                          int blockedByBreakoutConfirmation, int blockedByOiSupport, int blockedByHeadroom,
                          int sizingRejected, int blockedByOpenTrade, int executedTrades, long winningTrades,
                          long losingTrades, BigDecimal totalPnl, List<Trade> trades) {}
    public record Trade(String decisionKey, String instrument, OptionType optionType, int quantity, Instant entryTime,
                        BigDecimal entryPrice, Instant exitTime, BigDecimal exitPrice, BigDecimal pnl, String exitReason) {}
    private record Replay(List<Trade> trades, int sizingRejected, int openTradeBlocked) {}
    private record Decision(boolean accepted, boolean base, boolean breakoutConfirmed, boolean headroom) {}
    private record Loaded(Map<String, Row> rows, Map<String, List<Candle>> underlyingCandles, Map<String, List<Candle>> marketCandles) {}
    private record Row(String key, Instant timestamp, Instant entryTime, UnderlyingSymbol underlying, OptionType optionType,
                       String instrument, BigDecimal underlyingPrice, BigDecimal optionPrice, BigDecimal breakoutBuffer,
                       BigDecimal minScore, BigDecimal score, BigDecimal resistance, BigDecimal support,
                       boolean vwapPassed, boolean breakoutPassed, boolean volumeSpike, boolean oiPassed,
                       boolean ivPassed, boolean liquidityPassed, boolean timePassed, String reasons) {}

    private static final class Csv {
        private final List<Map<String, String>> rows;
        private Csv(List<Map<String, String>> rows) { this.rows = rows; }
        static Csv read(InputStream input) throws IOException {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String header = reader.readLine();
                if (header == null) return new Csv(List.of());
                List<String> headers = split(header);
                if (!headers.isEmpty() && headers.get(0).startsWith("\uFEFF")) {
                    headers.set(0, headers.get(0).substring(1));
                }
                List<Map<String, String>> rows = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) continue;
                    List<String> values = split(line);
                    Map<String, String> row = new LinkedHashMap<>();
                    int offset = 0;
                    boolean headerHasId = headers.contains("decisionKey") || headers.contains("evaluationId");
                    boolean rowHasLeadingDecisionKey = !headerHasId
                            && values.size() > 1
                            && !looksLikeInstant(values.get(0))
                            && looksLikeInstant(values.get(1));
                    if (rowHasLeadingDecisionKey || (!headerHasId && values.size() == headers.size() + 1)) {
                        row.put("decisionKey", values.get(0));
                        offset = 1;
                    }
                    for (int i = 0; i < headers.size(); i++) row.put(headers.get(i), i + offset < values.size() ? values.get(i + offset) : "");
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
                    if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else { quoted = !quoted; }
                } else if (c == ',' && !quoted) {
                    out.add(cur.toString()); cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            out.add(cur.toString());
            return out;
        }
        private static boolean looksLikeInstant(String value) {
            if (value == null || value.isBlank()) return false;
            try {
                Instant.parse(value);
                return true;
            } catch (Exception ignored) {
                return false;
            }
        }
    }
}
