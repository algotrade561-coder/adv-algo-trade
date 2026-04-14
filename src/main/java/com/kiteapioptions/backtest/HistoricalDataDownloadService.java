package com.kiteapioptions.backtest;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.HistoricalDataRequest;
import com.kiteapioptions.domain.Instrument;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.UnderlyingSymbol;
import com.kiteapioptions.marketdata.InstrumentCache;
import com.kiteapioptions.util.Validation;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Downloads Zerodha historical candles in 59-day chunks and writes them to the backtest CSV input.
 * Zerodha limits: 1-min = 60 days, 5/15-min = 100 days per request.
 */
@Service
public class HistoricalDataDownloadService {

    private static final Logger log = LoggerFactory.getLogger(HistoricalDataDownloadService.class);
    private static final int CHUNK_DAYS_MINUTE = 59;
    private static final int CHUNK_DAYS_HIGHER = 99;
    private static final long RATE_LIMIT_SLEEP_MS = 400;
    private static final int MAX_AUTO_STRIKE_DOWNLOADS = 20;
    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;

    private final BrokerClient brokerClient;
    private final TradingProperties properties;
    private final InstrumentCache instrumentCache;

    public HistoricalDataDownloadService(BrokerClient brokerClient, TradingProperties properties,
                                         InstrumentCache instrumentCache) {
        this.brokerClient = brokerClient;
        this.properties = properties;
        this.instrumentCache = instrumentCache;
    }

    /**
     * Downloads candles for the given instrument token between from/to dates and writes to
     * the configured backtest CSV import path. Returns the total number of candles written.
     * All chunks are collected in memory first; the file is only written once all chunks
     * succeed, so a partial failure never leaves a truncated CSV on disk.
     */
    public int download(String instrumentToken, LocalDate from, LocalDate to, Timeframe timeframe) throws IOException {
        List<Candle> all = downloadCandles(instrumentToken, from, to, timeframe);
        Path outputPath = outputPath(instrumentToken, timeframe);
        writeCsv(all, outputPath);
        log.info("Historical data download complete: token={}, timeframe={}, totalCandles={}, outputPath={}",
                instrumentToken, timeframe, all.size(), outputPath);
        return all.size();
    }

    private List<Candle> downloadCandles(String instrumentToken, LocalDate from, LocalDate to, Timeframe timeframe) {
        Validation.notBlank(instrumentToken, "instrumentToken");
        Validation.notNull(from, "from");
        Validation.notNull(to, "to");
        Validation.notNull(timeframe, "timeframe");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }
        ZoneId zone = properties.timezone();
        int chunkDays = timeframe == Timeframe.ONE_MINUTE ? CHUNK_DAYS_MINUTE : CHUNK_DAYS_HIGHER;

        List<Candle> all = new ArrayList<>();
        LocalDate cursor = from;
        while (!cursor.isAfter(to)) {
            LocalDate chunkEnd = cursor.plusDays(chunkDays);
            if (chunkEnd.isAfter(to)) {
                chunkEnd = to;
            }
            Instant fromInstant = cursor.atTime(LocalTime.of(9, 0)).atZone(zone).toInstant();
            Instant toInstant = chunkEnd.atTime(LocalTime.of(15, 30)).atZone(zone).toInstant();

            log.info("Downloading candles chunk: token={}, from={}, to={}, timeframe={}", instrumentToken, cursor, chunkEnd, timeframe);
            List<Candle> chunk = brokerClient.historicalCandles(
                    new HistoricalDataRequest(instrumentToken, fromInstant, toInstant, timeframe, true));
            log.info("Chunk downloaded: token={}, candles={}", instrumentToken, chunk.size());
            all.addAll(chunk);

            cursor = chunkEnd.plusDays(1);
            if (!cursor.isAfter(to)) {
                sleep();
            }
        }

        return List.copyOf(all);
    }

    public Path outputPath(Timeframe timeframe) {
        Validation.notNull(timeframe, "timeframe");
        return BacktestDataFileResolver.forTimeframe(properties.backtest().csvImportPath(), timeframe);
    }

    public Path outputPath(String instrumentToken, Timeframe timeframe) {
        Validation.notBlank(instrumentToken, "instrumentToken");
        Validation.notNull(timeframe, "timeframe");
        return BacktestDataFileResolver.forToken(properties.backtest().csvImportPath(), instrumentToken, timeframe);
    }

    public Path outputPath(UnderlyingSymbol underlying, OptionType optionType, Timeframe timeframe) {
        Validation.notNull(underlying, "underlying");
        Validation.notNull(optionType, "optionType");
        Validation.notNull(timeframe, "timeframe");
        return BacktestDataFileResolver.forSelection(properties.backtest().csvImportPath(), underlying, optionType,
                timeframe);
    }

    public DownloadResult downloadSelectedOption(
            UnderlyingSymbol underlying,
            OptionType optionType,
            LocalDate from,
            LocalDate to,
            Timeframe timeframe,
            LocalDate expiry,
            BigDecimal strike,
            BigDecimal underlyingPrice
    ) throws IOException {
        return downloadSelectedOption(underlying, optionType, from, to, timeframe, expiry, strike, underlyingPrice,
                properties);
    }

    public DownloadResult downloadSelectedOption(
            UnderlyingSymbol underlying,
            OptionType optionType,
            LocalDate from,
            LocalDate to,
            Timeframe timeframe,
            LocalDate expiry,
            BigDecimal strike,
            BigDecimal underlyingPrice,
            TradingProperties activeProperties
    ) throws IOException {
        Validation.notNull(underlying, "underlying");
        Validation.notNull(optionType, "optionType");
        Validation.notNull(from, "from");
        Validation.notNull(to, "to");
        Validation.notNull(timeframe, "timeframe");
        Validation.notNull(activeProperties, "activeProperties");
        if (from.isAfter(to)) {
            throw new IllegalArgumentException("from must be on or before to");
        }

        List<Instrument> candidates = selectOptionCandidates(activeProperties, underlying, optionType, expiry, strike,
                underlyingPrice);
        Instrument selected = null;
        List<Candle> candles = List.of();
        List<String> rejected = new ArrayList<>();
        for (Instrument candidate : candidates) {
            List<Candle> candidateCandles = downloadCandles(String.valueOf(candidate.instrumentToken()), from, to,
                    timeframe);
            if (candidateCandles.isEmpty()) {
                rejected.add(candidate.tradingSymbol() + " had no candles");
            } else if (hasTradableLot(activeProperties, candidateCandles)) {
                selected = candidate;
                candles = candidateCandles;
                break;
            } else {
                rejected.add(candidate.tradingSymbol() + " min premium " + minClose(candidateCandles));
            }
            if (strike != null) {
                break;
            }
            sleep();
        }
        if (selected == null) {
            throw new IllegalArgumentException("No selected " + underlying + " " + optionType
                    + " option produced a tradable lot for current backtest risk settings. Max tradable premium is "
                    + maxTradablePremium(activeProperties) + ". Tried: " + String.join("; ", rejected)
                    + ". Pass an explicit farther OTM strike, increase total-capital/max-risk-per-trade-percent, "
                    + "or reduce lot-size/stop-loss-percent.");
        }
        Path outputPath = outputPath(underlying, optionType, timeframe);
        writeCsv(candles, outputPath);
        log.info("Selected option data download complete: underlying={}, optionType={}, token={}, tradingSymbol={}, timeframe={}, candles={}, outputPath={}",
                underlying, optionType, selected.instrumentToken(), selected.tradingSymbol(), timeframe, candles.size(),
                outputPath);
        return new DownloadResult(
                selected.instrumentToken(),
                selected.instrumentKey(),
                selected.tradingSymbol(),
                selected.expiry().orElse(null),
                selected.strike().orElse(null),
                selected.optionType().orElse(null),
                timeframe,
                outputPath,
                candles.size()
        );
    }

    private List<Instrument> selectOptionCandidates(
            TradingProperties activeProperties,
            UnderlyingSymbol underlying,
            OptionType optionType,
            LocalDate requestedExpiry,
            BigDecimal requestedStrike,
            BigDecimal requestedUnderlyingPrice
    ) {
        List<Instrument> instruments = instruments();
        LocalDate expiry = requestedExpiry != null
                ? requestedExpiry
                : nearestExpiry(instruments, underlying)
                .orElseThrow(() -> new IllegalArgumentException("No current option expiry found for " + underlying
                        + ". Zerodha's current instrument master does not include old expired option contracts."));

        List<Instrument> options = instruments.stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .filter(instrument -> instrument.expiry().filter(expiry::equals).isPresent())
                .filter(instrument -> instrument.optionType().filter(optionType::equals).isPresent())
                .filter(instrument -> instrument.strike().isPresent())
                .sorted(Comparator.comparing(instrument -> instrument.strike().orElse(BigDecimal.ZERO)))
                .toList();
        if (options.isEmpty()) {
            throw new IllegalArgumentException("No option instruments found for " + underlying + " " + optionType
                    + " expiry " + expiry);
        }

        BigDecimal selectedStrike = requestedStrike != null ? requestedStrike : selectedStrike(activeProperties,
                optionType, options, requestedUnderlyingPrice != null
                        ? requestedUnderlyingPrice
                        : currentUnderlyingPrice(activeProperties, underlying));
        if (requestedStrike != null) {
            Instrument selected = options.stream()
                    .filter(instrument -> instrument.strike().filter(selectedStrike::equals).isPresent())
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("No " + underlying + " " + optionType
                            + " option found for expiry " + expiry + " and strike " + selectedStrike));
            return List.of(selected);
        }
        return autoStrikeCandidates(optionType, options, selectedStrike).stream()
                .limit(MAX_AUTO_STRIKE_DOWNLOADS)
                .toList();
    }

    private List<Instrument> instruments() {
        List<Instrument> instruments = instrumentCache.all();
        return instruments.isEmpty() ? instrumentCache.refresh() : instruments;
    }

    private Optional<LocalDate> nearestExpiry(List<Instrument> instruments, UnderlyingSymbol underlying) {
        LocalDate today = LocalDate.now(properties.timezone());
        return instruments.stream()
                .filter(Instrument::tradable)
                .filter(instrument -> instrument.underlying().filter(underlying::equals).isPresent())
                .flatMap(instrument -> instrument.expiry().stream())
                .filter(candidate -> !candidate.isBefore(today))
                .min(Comparator.naturalOrder());
    }

    private BigDecimal currentUnderlyingPrice(TradingProperties activeProperties, UnderlyingSymbol underlying) {
        String quoteKey = activeProperties.symbols().spotQuoteKeys().get(underlying);
        if (quoteKey == null || quoteKey.isBlank()) {
            throw new IllegalArgumentException("No spot quote key configured for " + underlying);
        }
        return brokerClient.quote(quoteKey)
                .orElseThrow(() -> new IllegalArgumentException("No current spot quote available for " + quoteKey
                        + ". Pass underlyingPrice explicitly."))
                .lastPrice();
    }

    private BigDecimal selectedStrike(TradingProperties activeProperties, OptionType optionType, List<Instrument> options,
                                      BigDecimal underlyingPrice) {
        List<BigDecimal> strikes = options.stream()
                .flatMap(instrument -> instrument.strike().stream())
                .distinct()
                .sorted()
                .toList();
        BigDecimal atm = strikes.stream()
                .min(Comparator.comparing(strike -> strike.subtract(underlyingPrice).abs()))
                .orElseThrow(() -> new IllegalArgumentException("No strikes available"));
        int atmIndex = strikes.indexOf(atm);
        return switch (activeProperties.strike().selectionMode()) {
            case ATM -> atm;
            case ONE_STRIKE_ITM -> optionType == OptionType.CE
                    ? strikes.get(Math.max(0, atmIndex - 1))
                    : strikes.get(Math.min(strikes.size() - 1, atmIndex + 1));
            case ONE_STRIKE_OTM -> optionType == OptionType.CE
                    ? strikes.get(Math.min(strikes.size() - 1, atmIndex + 1))
                    : strikes.get(Math.max(0, atmIndex - 1));
        };
    }

    private List<Instrument> autoStrikeCandidates(OptionType optionType, List<Instrument> options,
                                                  BigDecimal selectedStrike) {
        Comparator<Instrument> comparator = optionType == OptionType.CE
                ? Comparator.comparing((Instrument instrument) -> instrument.strike().orElse(BigDecimal.ZERO))
                : Comparator.comparing((Instrument instrument) -> instrument.strike().orElse(BigDecimal.ZERO)).reversed();
        List<Instrument> otmDirection = options.stream()
                .filter(instrument -> optionType == OptionType.CE
                        ? instrument.strike().orElse(BigDecimal.ZERO).compareTo(selectedStrike) >= 0
                        : instrument.strike().orElse(BigDecimal.ZERO).compareTo(selectedStrike) <= 0)
                .sorted(comparator)
                .toList();
        List<Instrument> fallbackDirection = options.stream()
                .filter(instrument -> optionType == OptionType.CE
                        ? instrument.strike().orElse(BigDecimal.ZERO).compareTo(selectedStrike) < 0
                        : instrument.strike().orElse(BigDecimal.ZERO).compareTo(selectedStrike) > 0)
                .sorted(comparator.reversed())
                .toList();
        List<Instrument> candidates = new ArrayList<>(otmDirection.size() + fallbackDirection.size());
        candidates.addAll(otmDirection);
        candidates.addAll(fallbackDirection);
        return List.copyOf(candidates);
    }

    private boolean hasTradableLot(TradingProperties activeProperties, List<Candle> candles) {
        BigDecimal maxTradablePremium = maxTradablePremium(activeProperties);
        return candles.stream()
                .map(Candle::close)
                .anyMatch(close -> close.signum() > 0 && close.compareTo(maxTradablePremium) <= 0);
    }

    private BigDecimal minClose(List<Candle> candles) {
        return candles.stream().map(Candle::close).min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
    }

    private BigDecimal maxTradablePremium(TradingProperties activeProperties) {
        BigDecimal riskAmount = activeProperties.risk().totalCapital()
                .multiply(activeProperties.risk().maxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        BigDecimal stopLossFactor = activeProperties.exit().stopLossPercent().movePointLeft(2);
        if (riskAmount.signum() <= 0 || stopLossFactor.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return riskAmount.divide(stopLossFactor.multiply(BigDecimal.valueOf(activeProperties.backtest().lotSize()),
                MATH_CONTEXT), MATH_CONTEXT);
    }

    public ArchiveResult archiveDownloadedOption(UnderlyingSymbol underlying, OptionType optionType,
                                                 Timeframe timeframe, DownloadResult download,
                                                 LocalDate from, LocalDate to) throws IOException {
        Validation.notNull(underlying, "underlying");
        Validation.notNull(optionType, "optionType");
        Validation.notNull(timeframe, "timeframe");
        Validation.notNull(download, "download");
        Validation.notNull(from, "from");
        Validation.notNull(to, "to");
        List<Candle> candles = new CandleCsvReader().read(download.outputPath(), timeframe);
        return archiveSelectedOptionCandles(underlying, optionType, timeframe, download.tradingSymbol(), candles, from,
                to);
    }

    private ArchiveResult archiveSelectedOptionCandles(UnderlyingSymbol underlying, OptionType optionType,
                                                       Timeframe timeframe, String tradingSymbol,
                                                       List<Candle> candles, LocalDate from, LocalDate to)
            throws IOException {
        Path archiveDirectory = archiveDirectory(underlying, optionType, timeframe);
        Files.createDirectories(archiveDirectory);

        String safeTradingSymbol = safeName(tradingSymbol);
        Path rangePath = archiveDirectory.resolve(safeTradingSymbol + "-" + from + "-to-" + to + ".csv");
        writeCsv(candles, rangePath);

        Path cumulativePath = archiveDirectory.resolve(safeTradingSymbol + "-cumulative.csv");
        List<Candle> contractMerged = mergeCandles(cumulativePath, candles, timeframe);
        writeCsv(contractMerged, cumulativePath);

        Path sideCumulativePath = archiveDirectory.resolve(safeName(underlying.name().toLowerCase(Locale.ROOT) + "-"
                + optionType.name().toLowerCase(Locale.ROOT) + "-"
                + timeframe.name().toLowerCase(Locale.ROOT).replace('_', '-') + "-cumulative.csv"));
        List<Candle> sideMerged = mergeCandles(sideCumulativePath, candles, timeframe);
        writeCsv(sideMerged, sideCumulativePath);

        log.info("Selected option data archived: tradingSymbol={}, rangePath={}, cumulativePath={}, cumulativeCandles={}, sideCumulativePath={}, sideCumulativeCandles={}",
                tradingSymbol, rangePath, cumulativePath, contractMerged.size(), sideCumulativePath, sideMerged.size());
        return new ArchiveResult(rangePath, cumulativePath, contractMerged.size(), sideCumulativePath,
                sideMerged.size());
    }

    private Path archiveDirectory(UnderlyingSymbol underlying, OptionType optionType, Timeframe timeframe) {
        Path configured = Path.of(properties.backtest().csvImportPath());
        Path parent = configured.getParent();
        Path base = parent == null ? Path.of("archive") : parent.resolve("archive");
        return base.resolve(safeName(underlying.name().toLowerCase(Locale.ROOT) + "-"
                + optionType.name().toLowerCase(Locale.ROOT) + "-"
                + timeframe.name().toLowerCase(Locale.ROOT).replace('_', '-')));
    }

    private List<Candle> mergeCandles(Path cumulativePath, List<Candle> newCandles, Timeframe timeframe)
            throws IOException {
        Map<String, Candle> byKey = new LinkedHashMap<>();
        if (Files.exists(cumulativePath)) {
            for (Candle candle : new CandleCsvReader().read(cumulativePath, timeframe)) {
                byKey.put(candleKey(candle), candle);
            }
        }
        for (Candle candle : newCandles) {
            byKey.put(candleKey(candle), candle);
        }
        return byKey.values().stream()
                .sorted(Comparator.comparing(Candle::timestamp)
                        .thenComparing(Candle::instrumentKey)
                        .thenComparing(candle -> candle.timeframe().name()))
                .toList();
    }

    private String candleKey(Candle candle) {
        return candle.instrumentKey() + "|" + candle.timeframe() + "|" + candle.timestamp();
    }

    private String safeName(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private void writeCsv(List<Candle> candles, Path path) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Sort by timestamp to ensure correct chronological order across chunks
        List<Candle> sorted = candles.stream()
                .sorted(java.util.Comparator.comparing(Candle::timestamp))
                .toList();
        StringBuilder sb = new StringBuilder("timestamp,instrumentKey,timeframe,open,high,low,close,volume,openInterest\n");
        for (Candle c : sorted) {
            sb.append(c.timestamp()).append(',')
              .append(c.instrumentKey()).append(',')
              .append(c.timeframe()).append(',')
              .append(c.open().toPlainString()).append(',')
              .append(c.high().toPlainString()).append(',')
              .append(c.low().toPlainString()).append(',')
              .append(c.close().toPlainString()).append(',')
              .append(c.volume()).append(',')
              .append(c.openInterest()).append('\n');
        }
        Files.writeString(path, sb.toString());
    }

    private void sleep() {
        try {
            Thread.sleep(RATE_LIMIT_SLEEP_MS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    public record DownloadResult(
            long instrumentToken,
            String instrumentKey,
            String tradingSymbol,
            LocalDate expiry,
            BigDecimal strike,
            OptionType optionType,
            Timeframe timeframe,
            Path outputPath,
            int candlesWritten
    ) {}

    public record ArchiveResult(
            Path rangePath,
            Path cumulativePath,
            int cumulativeCandles,
            Path sideCumulativePath,
            int sideCumulativeCandles
    ) {}
}
