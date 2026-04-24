package com.algo.trade.data;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.InstrumentCache;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ZerodhaOptionDatasetAppendService {

    private static final Logger log = LoggerFactory.getLogger(ZerodhaOptionDatasetAppendService.class);
    private static final String HEADER = "timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest";
    private static final int CHUNK_DAYS_MINUTE = 59;
    private static final int CHUNK_DAYS_HIGHER = 99;
    private static final long RATE_LIMIT_SLEEP_MS = 400L;
    private static final Path DEFAULT_OUTPUT_ROOT = Path.of("C:/data/backtest/imports/global-datafeeds");
    private static final DateTimeFormatter GLOBAL_DATAFEEDS_EXPIRY =
            DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH);

    private final BrokerClient brokerClient;
    private final InstrumentCache instrumentCache;
    private final TradingProperties properties;

    public ZerodhaOptionDatasetAppendService(BrokerClient brokerClient, InstrumentCache instrumentCache,
                                             TradingProperties properties) {
        this.brokerClient = brokerClient;
        this.instrumentCache = instrumentCache;
        this.properties = properties;
    }

    public AppendResult append(AppendRequest request) throws IOException {
        AppendRequest effective = request == null ? new AppendRequest(null, null, null, null, null, null, null, null,
                null, null, null, null, null) : request;
        UnderlyingSymbol underlying = effective.underlying() == null ? UnderlyingSymbol.NIFTY : effective.underlying();
        Timeframe timeframe = effective.timeframe() == null ? Timeframe.ONE_MINUTE : effective.timeframe();
        Path outputRoot = effective.outputRoot() == null || effective.outputRoot().isBlank()
                ? DEFAULT_OUTPUT_ROOT
                : Path.of(effective.outputRoot());
        LocalDate latestExistingDay = latestExistingDay(outputRoot);
        LocalDate from = effective.from() != null
                ? effective.from()
                : latestExistingDay == null ? null : latestExistingDay.plusDays(1);
        LocalDate to = effective.to() != null ? effective.to() : LocalDate.now(properties.timezone());
        if (from == null) {
            throw new IllegalArgumentException("from is required when no existing by-day files are present");
        }
        if (from.isAfter(to)) {
            return new AppendResult("skipped", "No missing dates to append", outputRoot.toString(), from, to,
                    latestExistingDay, 0, 0, 0, List.of());
        }

        List<Instrument> instruments = selectedInstruments(underlying, effective);
        if (instruments.isEmpty()) {
            throw new IllegalArgumentException("No matching option instruments found for " + underlying);
        }

        int maxInstruments = effective.maxInstruments() == null ? instruments.size() : effective.maxInstruments();
        List<Instrument> limited = instruments.stream().limit(maxInstruments).toList();
        if (Boolean.TRUE.equals(effective.dryRun())) {
            List<InstrumentAppendResult> dryRunInstruments = limited.stream()
                    .map(instrument -> new InstrumentAppendResult(instrument.instrumentToken(),
                            instrument.instrumentKey(), instrument.expiry().orElse(null),
                            instrument.strike().orElse(null), instrument.optionType().orElse(null), 0, 0))
                    .toList();
            return new AppendResult("dry-run", "Resolved instruments and dates; no Zerodha download or file append was performed",
                    outputRoot.toString(), from, to, latestExistingDay, limited.size(), 0, 0, dryRunInstruments);
        }
        Files.createDirectories(outputRoot.resolve("by-day"));
        Files.createDirectories(outputRoot.resolve("by-contract"));

        int instrumentsDownloaded = 0;
        int candlesDownloaded = 0;
        int candlesAppended = 0;
        List<InstrumentAppendResult> instrumentResults = new ArrayList<>();
        for (Instrument instrument : limited) {
            List<Candle> candles = downloadCandles(instrument, from, to, timeframe);
            instrumentsDownloaded++;
            candlesDownloaded += candles.size();
            int appended = appendCandles(outputRoot, instrument, candles);
            candlesAppended += appended;
            instrumentResults.add(new InstrumentAppendResult(instrument.instrumentToken(), instrument.instrumentKey(),
                    instrument.expiry().orElse(null), instrument.strike().orElse(null),
                    instrument.optionType().orElse(null), candles.size(), appended));
            if (instrumentsDownloaded < limited.size()) {
                sleep();
            }
        }

        String message = "Appended Zerodha option candles to converted Global Datafeeds dataset";
        log.info("{}: outputRoot={}, from={}, to={}, instruments={}, candlesDownloaded={}, candlesAppended={}",
                message, outputRoot, from, to, instrumentsDownloaded, candlesDownloaded, candlesAppended);
        return new AppendResult("ok", message, outputRoot.toString(), from, to, latestExistingDay,
                instrumentsDownloaded, candlesDownloaded, candlesAppended, instrumentResults);
    }

    private List<Instrument> selectedInstruments(UnderlyingSymbol underlying, AppendRequest request) {
        if (instrumentCache.all().isEmpty()) {
            instrumentCache.refresh();
        }
        LocalDate from = request.from();
        LocalDate expiry = request.expiry();
        List<OptionType> optionTypes = request.optionTypes() == null || request.optionTypes().isEmpty()
                ? List.of(OptionType.CE, OptionType.PE)
                : request.optionTypes();
        List<Instrument> options = instrumentCache.all().stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .filter(instrument -> instrument.optionType().filter(optionTypes::contains).isPresent())
                .filter(instrument -> instrument.expiry().isPresent())
                .filter(instrument -> instrument.strike().isPresent())
                .filter(instrument -> expiry == null || instrument.expiry().filter(expiry::equals).isPresent())
                .filter(instrument -> request.minStrike() == null
                        || instrument.strike().orElseThrow().compareTo(request.minStrike()) >= 0)
                .filter(instrument -> request.maxStrike() == null
                        || instrument.strike().orElseThrow().compareTo(request.maxStrike()) <= 0)
                .toList();

        if (expiry != null || Boolean.TRUE.equals(request.allExpiries())) {
            return sortForAppend(options, underlying, request);
        }

        LocalDate referenceDate = from != null ? from : LocalDate.now(properties.timezone());
        Optional<LocalDate> nearestExpiry = options.stream()
                .flatMap(instrument -> instrument.expiry().stream())
                .filter(candidate -> !candidate.isBefore(referenceDate))
                .min(Comparator.naturalOrder());
        if (nearestExpiry.isEmpty()) {
            return sortForAppend(options, underlying, request);
        }
        List<Instrument> nearestExpiryOptions = options.stream()
                .filter(instrument -> instrument.expiry().filter(nearestExpiry.get()::equals).isPresent())
                .toList();
        return sortForAppend(nearestExpiryOptions, underlying, request);
    }

    private List<Instrument> sortForAppend(List<Instrument> instruments, UnderlyingSymbol underlying,
                                           AppendRequest request) {
        BigDecimal center = centerStrike(underlying, request);
        return instruments.stream()
                .sorted(Comparator.comparing((Instrument instrument) -> instrument.expiry().orElse(LocalDate.MAX))
                        .thenComparing(instrument -> strikeDistance(instrument, center))
                        .thenComparing(instrument -> instrument.strike().orElse(BigDecimal.ZERO))
                        .thenComparing(instrument -> instrument.optionType().map(Enum::name).orElse("")))
                .toList();
    }

    private BigDecimal centerStrike(UnderlyingSymbol underlying, AppendRequest request) {
        if (request.centerStrike() != null) {
            return request.centerStrike();
        }
        if (request.minStrike() != null && request.maxStrike() != null) {
            return request.minStrike().add(request.maxStrike()).divide(BigDecimal.valueOf(2), RoundingMode.HALF_UP);
        }
        String spotQuoteKey = properties.symbols().spotQuoteKeys().get(underlying);
        if (spotQuoteKey == null || spotQuoteKey.isBlank()) {
            return BigDecimal.ZERO;
        }
        return brokerClient.quote(spotQuoteKey)
                .map(quote -> quote.lastPrice())
                .orElseThrow(() -> new IllegalArgumentException("Unable to resolve spot quote for " + underlying
                        + ". Pass centerStrike or explicit minStrike/maxStrike for dataset append."));
    }

    private BigDecimal strikeDistance(Instrument instrument, BigDecimal center) {
        BigDecimal strike = instrument.strike().orElse(BigDecimal.ZERO);
        if (center == null) {
            return strike;
        }
        return strike.subtract(center).abs();
    }

    private List<Candle> downloadCandles(Instrument instrument, LocalDate from, LocalDate to, Timeframe timeframe) {
        int chunkDays = timeframe == Timeframe.ONE_MINUTE ? CHUNK_DAYS_MINUTE : CHUNK_DAYS_HIGHER;
        List<Candle> all = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate chunkEnd = cursor.plusDays(chunkDays);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            Instant fromInstant = cursor.atTime(LocalTime.of(9, 0)).atZone(properties.timezone()).toInstant();
            Instant toInstant = chunkEnd.atTime(LocalTime.of(15, 30)).atZone(properties.timezone()).toInstant();
            log.info("Appending dataset candles: instrument={}, token={}, from={}, to={}, timeframe={}",
                    instrument.instrumentKey(), instrument.instrumentToken(), cursor, chunkEnd, timeframe);
            all.addAll(brokerClient.historicalCandles(new HistoricalDataRequest(
                    String.valueOf(instrument.instrumentToken()), fromInstant, toInstant, timeframe, true)));
            cursor = chunkEnd.plusDays(1);
            if (!cursor.isAfter(to)) {
                sleep();
            }
        }
        return List.copyOf(all);
    }

    private int appendCandles(Path outputRoot, Instrument instrument, List<Candle> candles) throws IOException {
        if (candles.isEmpty()) {
            return 0;
        }
        String instrumentKey = globalDatafeedsInstrumentKey(instrument);
        List<NormalizedCandle> normalized = candles.stream()
                .map(candle -> new NormalizedCandle(instrumentKey, candle))
                .sorted(Comparator.comparing(NormalizedCandle::timestamp))
                .toList();

        int appended = 0;
        Map<LocalDate, List<NormalizedCandle>> byDay = normalized.stream()
                .collect(Collectors.groupingBy(candle -> LocalDate.ofInstant(candle.timestamp(), properties.timezone()),
                        LinkedHashMap::new, Collectors.toList()));
        for (Map.Entry<LocalDate, List<NormalizedCandle>> entry : byDay.entrySet()) {
            Path dailyPath = outputRoot.resolve("by-day")
                    .resolve(String.valueOf(entry.getKey().getYear()))
                    .resolve(entry.getKey() + ".csv");
            appended += appendUnique(dailyPath, entry.getValue());
        }

        Path contractPath = outputRoot.resolve("by-contract").resolve(safeFileName(instrumentKey) + ".csv");
        appended += appendUnique(contractPath, normalized);
        return appended;
    }

    private int appendUnique(Path path, List<NormalizedCandle> candles) throws IOException {
        ensureHeader(path);
        Set<String> existingKeys = existingKeys(path);
        List<String> newLines = candles.stream()
                .filter(candle -> existingKeys.add(candle.uniqueKey()))
                .map(NormalizedCandle::csvLine)
                .toList();
        if (!newLines.isEmpty()) {
            Files.write(path, newLines.stream().map(line -> line + System.lineSeparator()).toList(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return newLines.size();
    }

    private Set<String> existingKeys(Path path) throws IOException {
        if (Files.notExists(path) || Files.size(path) == 0L) {
            return new HashSet<>();
        }
        return Files.readAllLines(path).stream()
                .skip(1)
                .filter(line -> !line.isBlank())
                .map(line -> {
                    String[] columns = line.split(",", -1);
                    return columns.length < 2 ? line : columns[0] + "|" + columns[1];
                })
                .collect(Collectors.toCollection(HashSet::new));
    }

    private void ensureHeader(Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(path) || Files.size(path) == 0L) {
            Files.writeString(path, HEADER + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    private LocalDate latestExistingDay(Path outputRoot) throws IOException {
        Path byDayRoot = outputRoot.resolve("by-day");
        if (!Files.isDirectory(byDayRoot)) {
            return null;
        }
        try (var stream = Files.walk(byDayRoot)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".csv"))
                    .map(path -> path.getFileName().toString().replace(".csv", ""))
                    .map(this::parseDate)
                    .flatMap(Optional::stream)
                    .max(Comparator.naturalOrder())
                    .orElse(null);
        }
    }

    private Optional<LocalDate> parseDate(String value) {
        try {
            return Optional.of(LocalDate.parse(value));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static String safeFileName(String value) {
        return value.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static String globalDatafeedsInstrumentKey(Instrument instrument) {
        String underlying = instrument.underlying()
                .map(Enum::name)
                .orElse(instrument.name());
        String expiry = instrument.expiry()
                .map(date -> date.format(GLOBAL_DATAFEEDS_EXPIRY).toUpperCase(Locale.ROOT))
                .orElse("");
        String strike = instrument.strike()
                .map(value -> value.stripTrailingZeros().toPlainString())
                .orElse("");
        String optionType = instrument.optionType()
                .map(Enum::name)
                .orElse("");
        return underlying + expiry + strike + optionType + "." + instrument.exchange();
    }

    private static void sleep() {
        try {
            Thread.sleep(RATE_LIMIT_SLEEP_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public record AppendRequest(
            UnderlyingSymbol underlying,
            Timeframe timeframe,
            LocalDate from,
            LocalDate to,
            String outputRoot,
            LocalDate expiry,
            List<OptionType> optionTypes,
            BigDecimal minStrike,
            BigDecimal maxStrike,
            BigDecimal centerStrike,
            Integer maxInstruments,
            Boolean allExpiries,
            Boolean dryRun
    ) {}

    public record AppendResult(
            String status,
            String message,
            String outputRoot,
            LocalDate from,
            LocalDate to,
            LocalDate latestExistingDay,
            int instrumentsDownloaded,
            int candlesDownloaded,
            int candlesAppended,
            List<InstrumentAppendResult> instruments
    ) {}

    public record InstrumentAppendResult(
            long instrumentToken,
            String instrumentKey,
            LocalDate expiry,
            BigDecimal strike,
            OptionType optionType,
            int candlesDownloaded,
            int candlesAppended
    ) {}

    private record NormalizedCandle(String instrumentKey, Candle candle) {
        Instant timestamp() {
            return candle.timestamp();
        }

        String uniqueKey() {
            return candle.timestamp() + "|" + instrumentKey;
        }

        String csvLine() {
            return String.join(",",
                    candle.timestamp().toString(),
                    instrumentKey,
                    candle.timeframe().name(),
                    candle.open().toPlainString(),
                    candle.high().toPlainString(),
                    candle.low().toPlainString(),
                    candle.close().toPlainString(),
                    Long.toString(candle.volume()),
                    Long.toString(candle.openInterest())
            );
        }
    }
}
