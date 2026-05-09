package com.algo.trade.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.algo.trade.config.IstTimeConfiguration;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.domain.Instrument;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.persistence.BacktestResultEntity;
import com.algo.trade.persistence.BacktestResultRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class BacktestSuiteService {

    private static final Logger log = LoggerFactory.getLogger(BacktestSuiteService.class);
    private static final DateTimeFormatter SUITE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .registerModule(IstTimeConfiguration.istModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final HistoricalDataDownloadService downloadService;
    private final BacktestEngine backtestEngine;
    private final BacktestResultRepository backtestResultRepository;
    private final TradingProperties properties;
    private final InstrumentCache instrumentCache;

    public BacktestSuiteService(HistoricalDataDownloadService downloadService, BacktestEngine backtestEngine,
                                BacktestResultRepository backtestResultRepository, TradingProperties properties,
                                InstrumentCache instrumentCache) {
        this.downloadService = downloadService;
        this.backtestEngine = backtestEngine;
        this.backtestResultRepository = backtestResultRepository;
        this.properties = properties;
        this.instrumentCache = instrumentCache;
    }

    public SuiteResult run(SuiteRequest request) throws IOException {
        UnderlyingSymbol underlying = request.underlying() != null ? request.underlying() : UnderlyingSymbol.NIFTY;
        LocalDate to = request.to() != null ? request.to() : LocalDate.now(properties.timezone());
        List<SuiteWindow> windows = request.windows() == null || request.windows().isEmpty()
                ? defaultWindows(to)
                : request.windows();
        List<OptionType> optionTypes = request.optionTypes() == null || request.optionTypes().isEmpty()
                ? List.of(OptionType.CE, OptionType.PE)
                : request.optionTypes();
        List<Timeframe> timeframes = request.timeframes() == null || request.timeframes().isEmpty()
                ? List.of(Timeframe.ONE_MINUTE, Timeframe.FIVE_MINUTE)
                : request.timeframes();
        List<SuiteVariant> variants = request.variants() == null || request.variants().isEmpty()
                ? defaultVariants()
                : request.variants();

        String suiteId = suiteId(underlying);
        Path suiteOutputDirectory = Path.of(properties.backtest().outputDirectory(), "suites", suiteId);
        Files.createDirectories(suiteOutputDirectory);
        ensureUnderlyingInputsAvailable(underlying, windows, timeframes);
        int totalRuns = windows.size() * variants.size() * optionTypes.size() * timeframes.size();
        log.info("Backtest suite starting: suiteId={}, underlying={}, windows={}, variants={}, optionTypes={}, timeframes={}, totalRuns={}, outputDirectory={}",
                suiteId, underlying, windows.size(), variants.size(), optionTypes, timeframes, totalRuns,
                suiteOutputDirectory);
        writeSuiteRequest(suiteOutputDirectory, suiteId, underlying, to, windows, optionTypes, timeframes, variants,
                request);
        log.info("Backtest suite request written: suiteId={}, path={}", suiteId,
                suiteOutputDirectory.resolve("suite-request.json"));

        List<SuiteRunResult> runs = new ArrayList<>();
        Map<DataPreparationKey, CachedDownload> preparedDataCache = new LinkedHashMap<>();
        int runNumber = 0;
        for (SuiteWindow window : windows) {
            if (window.from() == null) {
                throw new IllegalArgumentException("suite window from is required: " + window.name());
            }
            LocalDate from = window.from();
            LocalDate windowTo = window.to() != null ? window.to() : to;
            if (from.isAfter(windowTo)) {
                throw new IllegalArgumentException("suite window from must be on or before to: " + window.name());
            }
            log.info("Backtest suite window starting: suiteId={}, window={}, from={}, to={}",
                    suiteId, window.name(), from, windowTo);
            for (SuiteVariant variant : variants) {
                log.info("Backtest suite variant starting: suiteId={}, window={}, variant={}, stopLossPercent={}, targetPercent={}, totalCapital={}, riskPercent={}",
                        suiteId, window.name(), variant.name(), variant.stopLossPercent(), variant.targetPercent(),
                        variant.totalCapital(), variant.maxRiskPerTradePercent());
                for (OptionType optionType : optionTypes) {
                    for (Timeframe timeframe : timeframes) {
                        runNumber++;
                        try {
                            log.info("Backtest suite run starting: suiteId={}, run={}/{}, window={}, variant={}, optionType={}, timeframe={}, from={}, to={}",
                                    suiteId, runNumber, totalRuns, window.name(), variant.name(), optionType,
                                    timeframe, from, windowTo);
                            runs.add(runOne(suiteId, suiteOutputDirectory, underlying, optionType, timeframe, window,
                                    from, windowTo, request.expiry(), request.strike(), request.underlyingPrice(),
                                    variant, preparedDataCache));
                            SuiteRunResult completed = runs.getLast();
                            log.info("Backtest suite run completed: suiteId={}, run={}/{}, status={}, backtestId={}, trades={}, cumulativePnl={}, report={}",
                                    suiteId, runNumber, totalRuns, completed.status(), completed.backtestId(),
                                    completed.metrics() == null ? 0 : completed.metrics().totalTrades(),
                                    completed.metrics() == null ? BigDecimal.ZERO : completed.metrics().cumulativePnl(),
                                    completed.reportHtml());
                        } catch (RuntimeException | IOException ex) {
                            log.warn("Backtest suite run failed: suiteId={}, run={}/{}, window={}, variant={}, optionType={}, timeframe={}, from={}, to={}, error={}",
                                    suiteId, runNumber, totalRuns, window.name(), variant.name(), optionType,
                                    timeframe, from, windowTo, ex.getMessage());
                            runs.add(failedRun(suiteId, suiteOutputDirectory, underlying, optionType, timeframe,
                                    window, from, windowTo, variant, ex.getMessage()));
                        }
                    }
                }
            }
        }

        Path summaryCsv = writeSummaryCsv(suiteOutputDirectory, runs);
        Path summaryJson = writeSummaryJson(suiteOutputDirectory, suiteId, underlying, runs);
        Path rankingCsv = writeRankingCsv(suiteOutputDirectory, runs);
        Path periodCsv = writePeriodPerformanceCsv(suiteOutputDirectory, runs);
        Path periodJson = writePeriodPerformanceJson(suiteOutputDirectory, suiteId, underlying, runs);
        Path reportHtml = writeSuiteReportHtml(suiteOutputDirectory, suiteId, underlying, runs);
        long okRuns = runs.stream().filter(run -> "ok".equals(run.status())).count();
        long errorRuns = runs.size() - okRuns;
        log.info("Backtest suite completed: suiteId={}, totalRuns={}, okRuns={}, errorRuns={}, summaryCsv={}, summaryJson={}, rankingCsv={}, periodCsv={}, periodJson={}, reportHtml={}",
                suiteId, runs.size(), okRuns, errorRuns, summaryCsv, summaryJson, rankingCsv, periodCsv, periodJson,
                reportHtml);
        return new SuiteResult(suiteId, underlying, suiteOutputDirectory.toString(), summaryCsv.toString(),
                summaryJson.toString(), rankingCsv.toString(), periodCsv.toString(), periodJson.toString(),
                reportHtml.toString(), runs);
    }

    private void ensureUnderlyingInputsAvailable(UnderlyingSymbol underlying, List<SuiteWindow> windows,
                                                 List<Timeframe> timeframes) throws IOException {
        if (windows.isEmpty() || timeframes.isEmpty()) {
            return;
        }
        LocalDate from = windows.stream()
                .map(SuiteWindow::from)
                .filter(Objects::nonNull)
                .min(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("At least one suite window from date is required"));
        LocalDate to = windows.stream()
                .map(window -> window.to() != null ? window.to() : window.from())
                .filter(Objects::nonNull)
                .max(LocalDate::compareTo)
                .orElseThrow(() -> new IllegalArgumentException("At least one suite window to date is required"));
        String instrumentToken = underlyingInstrumentToken(underlying);
        for (Timeframe timeframe : timeframes) {
            Path outputPath = downloadService.outputPath(timeframe);
            log.info("Backtest suite preparing underlying data: underlying={}, timeframe={}, from={}, to={}, outputPath={}",
                    underlying, timeframe, from, to, outputPath);
            int candles = downloadService.download(instrumentToken, from, to, timeframe);
            log.info("Backtest suite underlying data ready: underlying={}, timeframe={}, candles={}, outputPath={}",
                    underlying, timeframe, candles, outputPath);
        }
    }

    private String underlyingInstrumentToken(UnderlyingSymbol underlying) {
        String configuredKey = properties.symbols().spotHistoricalKeys().get(underlying);
        if (configuredKey == null || configuredKey.isBlank()) {
            throw new IllegalArgumentException("No spot historical key configured for " + underlying);
        }
        if (configuredKey.chars().allMatch(Character::isDigit)) {
            return configuredKey;
        }
        if (instrumentCache.all().isEmpty()) {
            instrumentCache.refresh();
        }
        Instrument instrument = instrumentCache.findByKey(configuredKey)
                .orElseThrow(() -> new IllegalArgumentException("Unable to resolve instrument token for "
                        + underlying + " using configured key " + configuredKey));
        return String.valueOf(instrument.instrumentToken());
    }

    private SuiteRunResult runOne(String suiteId, Path suiteOutputDirectory, UnderlyingSymbol underlying,
                                  OptionType optionType, Timeframe timeframe, SuiteWindow window, LocalDate from,
                                  LocalDate to, LocalDate expiry, BigDecimal strike, BigDecimal underlyingPrice,
                                  SuiteVariant variant, Map<DataPreparationKey, CachedDownload> preparedDataCache)
            throws IOException {
        TradingProperties variantProperties = applyVariant(variant);
        DataPreparationKey dataKey = dataPreparationKey(underlying, optionType, timeframe, from, to, expiry, strike,
                variantProperties);
        CachedDownload cachedDownload = preparedDataCache.get(dataKey);
        HistoricalDataDownloadService.DownloadResult download;
        String dataSource;
        if (cachedDownload != null && Files.exists(cachedDownload.preparedPath())) {
            Path outputPath = downloadService.outputPath(underlying, optionType, timeframe);
            Files.copy(cachedDownload.preparedPath(), outputPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            download = cachedDownload.download().withOutputPath(outputPath);
            dataSource = "prepared-cache";
            log.info("Backtest suite data cache hit: suiteId={}, window={}, variant={}, optionType={}, timeframe={}, cachePath={}, outputPath={}",
                    suiteId, window.name(), variant.name(), optionType, timeframe, cachedDownload.preparedPath(),
                    outputPath);
        } else {
            var importedDownload = downloadService.prepareImportedOption(
                    underlying, optionType, from, to, timeframe, expiry, strike, variantProperties);
            download = importedDownload.orElse(null);
            dataSource = importedDownload.isPresent() ? "imported" : "zerodha";
            if (download == null) {
                log.info("Backtest suite data not found in local imports, downloading: suiteId={}, window={}, variant={}, optionType={}, timeframe={}, from={}, to={}",
                        suiteId, window.name(), variant.name(), optionType, timeframe, from, to);
                download = downloadService.downloadSelectedOption(underlying, optionType, from, to, timeframe,
                        expiry, strike, underlyingPrice, variantProperties);
            }
            Path cachePath = suiteOutputDirectory.resolve("prepared-data")
                    .resolve(shortSlug(dataKey.cacheName(), 80) + ".csv");
            Files.createDirectories(cachePath.getParent());
            Files.copy(download.outputPath(), cachePath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            preparedDataCache.put(dataKey, new CachedDownload(download.withOutputPath(cachePath), cachePath));
            log.info("Backtest suite data cached: suiteId={}, window={}, variant={}, optionType={}, timeframe={}, cachePath={}",
                    suiteId, window.name(), variant.name(), optionType, timeframe, cachePath);
        }
        log.info("Backtest suite data ready: suiteId={}, source={}, window={}, variant={}, optionType={}, timeframe={}, candles={}, tradingSymbol={}, expiry={}, strike={}, inputPath={}",
                suiteId, dataSource, window.name(), variant.name(), optionType, timeframe, download.candlesWritten(),
                download.tradingSymbol(), download.expiry(), download.strike(), download.outputPath());
        String windowSlug = shortSlug(window.name(), 12);
        String variantSlug = variant.slug();
        Path runRoot = suiteOutputDirectory.resolve("v-" + variantSlug).resolve("w-" + windowSlug);
        log.info("Backtest suite replay starting: suiteId={}, window={}, variant={}, optionType={}, timeframe={}, runRoot={}",
                suiteId, window.name(), variant.name(), optionType, timeframe, runRoot);
        BacktestRunResult result = backtestEngine.run(underlying, timeframe, optionType, from, to,
                new BacktestEngine.RunOptions(variantProperties, runRoot.toString(),
                        suiteId + "-" + variantSlug + "-" + windowSlug, variant.strategyType()));
        BacktestResultEntity saved = backtestResultRepository.save(toEntity(result));
        Path snapshotPath = snapshotInput(result, download);
        HistoricalDataDownloadService.ArchiveResult archive = from.equals(to)
                ? downloadService.archiveDownloadedOption(underlying, optionType, timeframe, download, from, to)
                : null;
        Path manifestPath = writeManifest(result, suiteId, variant, window, from, to, download, archive, snapshotPath);
        log.info("Backtest suite artifacts written: suiteId={}, backtestId={}, outputDirectory={}, snapshot={}, manifest={}",
                suiteId, saved.getId(), result.outputDirectory(), snapshotPath, manifestPath);
        return new SuiteRunResult(
                suiteId,
                "ok",
                null,
                window.name(),
                variant.name(),
                variantSlug,
                from,
                to,
                underlying,
                optionType,
                timeframe,
                saved.getId(),
                variantProperties.exit().stopLossPercent(),
                variantProperties.exit().targetPercent(),
                variantProperties.exit().trailingStopActivationPercent(),
                variantProperties.exit().trailingGapPercent(),
                variantProperties.entry().minSignalScorePercent(),
                variantProperties.entry().volumeSpikeMultiplier(),
                variantProperties.entry().breakoutBufferPercent(),
                variantProperties.entry().breakoutLookback(),
                variantProperties.entry().volumeLookback(),
                variantProperties.entry().minLiquidityVolume(),
                variantProperties.entry().maxIvPercent(),
                variantProperties.risk().totalCapital(),
                variantProperties.risk().maxRiskPerTradePercent(),
                variantProperties.backtest().lotSize(),
                variantProperties.entry().rsiFilterEnabled(),
                variantProperties.exit().maxHoldMinutes(),
                download.instrumentToken(),
                download.tradingSymbol(),
                download.expiry(),
                download.strike(),
                download.candlesWritten(),
                relativePath(download.outputPath()),
                relativePath(archivePath(archive, ArchivePath.RANGE)),
                relativePath(archivePath(archive, ArchivePath.CONTRACT_CUMULATIVE)),
                archiveCount(archive, false),
                relativePath(archivePath(archive, ArchivePath.SIDE_CUMULATIVE)),
                archiveCount(archive, true),
                relativePath(snapshotPath),
                relativePath(result.outputDirectory()),
                relativePath(result.reportHtml()),
                relativePath(manifestPath),
                result.metrics());
    }

    private SuiteRunResult failedRun(String suiteId, Path suiteOutputDirectory, UnderlyingSymbol underlying,
                                     OptionType optionType, Timeframe timeframe, SuiteWindow window, LocalDate from,
                                     LocalDate to, SuiteVariant variant, String message) {
        TradingProperties variantProperties = applyVariant(variant);
        return new SuiteRunResult(
                suiteId,
                "error",
                message,
                window.name(),
                variant.name(),
                variant.slug(),
                from,
                to,
                underlying,
                optionType,
                timeframe,
                null,
                variantProperties.exit().stopLossPercent(),
                variantProperties.exit().targetPercent(),
                variantProperties.exit().trailingStopActivationPercent(),
                variantProperties.exit().trailingGapPercent(),
                variantProperties.entry().minSignalScorePercent(),
                variantProperties.entry().volumeSpikeMultiplier(),
                variantProperties.entry().breakoutBufferPercent(),
                variantProperties.entry().breakoutLookback(),
                variantProperties.entry().volumeLookback(),
                variantProperties.entry().minLiquidityVolume(),
                variantProperties.entry().maxIvPercent(),
                variantProperties.risk().totalCapital(),
                variantProperties.risk().maxRiskPerTradePercent(),
                variantProperties.backtest().lotSize(),
                variantProperties.entry().rsiFilterEnabled(),
                variantProperties.exit().maxHoldMinutes(),
                0L,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                0,
                null,
                0,
                null,
                relativePath(suiteOutputDirectory),
                null,
                null,
                null);
    }

    private BacktestResultEntity toEntity(BacktestRunResult result) {
        return new BacktestResultEntity(result.id(), result.createdAt(), result.metrics().totalTrades(),
                result.metrics().winRatePercent(), result.metrics().expectancy(), result.metrics().maxDrawdown(),
                result.metrics().cumulativePnl(), relativePath(result.outputDirectory()));
    }

    private Path snapshotInput(BacktestRunResult result, HistoricalDataDownloadService.DownloadResult download)
            throws IOException {
        Path snapshotPath = result.outputDirectory().resolve("input-candles.csv");
        Files.copy(download.outputPath(), snapshotPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        return snapshotPath;
    }

    private Path writeManifest(BacktestRunResult result, String suiteId, SuiteVariant variant, SuiteWindow window,
                               LocalDate from, LocalDate to, HistoricalDataDownloadService.DownloadResult download,
                               HistoricalDataDownloadService.ArchiveResult archive, Path snapshotPath)
            throws IOException {
        Path manifestPath = result.outputDirectory().resolve("input-manifest.json");
        InputManifest manifest = new InputManifest(suiteId, variant.name(), variant.slug(), window.name(), from, to,
                download, archive, snapshotPath, result.metrics());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        return manifestPath;
    }

    private void writeSuiteRequest(Path suiteOutputDirectory, String suiteId, UnderlyingSymbol underlying, LocalDate to,
                                   List<SuiteWindow> windows, List<OptionType> optionTypes,
                                   List<Timeframe> timeframes, List<SuiteVariant> variants, SuiteRequest request)
            throws IOException {
        Path requestPath = suiteOutputDirectory.resolve("suite-request.json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suiteId", suiteId);
        payload.put("underlying", underlying);
        payload.put("to", to);
        payload.put("windows", windows);
        payload.put("optionTypes", optionTypes);
        payload.put("timeframes", timeframes);
        payload.put("variants", variants);
        payload.put("expiry", request.expiry());
        payload.put("strike", request.strike());
        payload.put("underlyingPrice", request.underlyingPrice());
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(requestPath.toFile(), payload);
    }

    private Path writeSummaryCsv(Path suiteOutputDirectory, List<SuiteRunResult> runs) throws IOException {
        Path path = suiteOutputDirectory.resolve("suite-summary.csv");
        StringBuilder csv = new StringBuilder();
        csv.append("suiteId,status,errorMessage,window,variant,variantSlug,from,to,underlying,optionType,timeframe,")
                .append("stopLossPercent,targetPercent,trailingStopActivationPercent,trailingGapPercent,")
                .append("minSignalScorePercent,volumeSpikeMultiplier,breakoutBufferPercent,breakoutLookback,")
                .append("volumeLookback,minLiquidityVolume,maxIvPercent,totalCapital,maxRiskPerTradePercent,lotSize,")
                .append("rsiFilterEnabled,maxHoldMinutes,")
                .append("totalTrades,winRatePercent,averageWin,averageLoss,expectancy,maxDrawdown,cumulativePnl,")
                .append("downloadedInputPath,outputDirectory,reportHtml,manifestPath")
                .append(System.lineSeparator());
        for (SuiteRunResult run : runs) {
            csv.append(csv(run.suiteId())).append(',')
                    .append(csv(run.status())).append(',')
                    .append(csv(run.errorMessage())).append(',')
                    .append(csv(run.window())).append(',')
                    .append(csv(run.variant())).append(',')
                    .append(csv(run.variantSlug())).append(',')
                    .append(csv(run.from())).append(',')
                    .append(csv(run.to())).append(',')
                    .append(csv(run.underlying())).append(',')
                    .append(csv(run.optionType())).append(',')
                    .append(csv(run.timeframe())).append(',')
                    .append(csv(run.stopLossPercent())).append(',')
                    .append(csv(run.targetPercent())).append(',')
                    .append(csv(run.trailingStopActivationPercent())).append(',')
                    .append(csv(run.trailingGapPercent())).append(',')
                    .append(csv(run.minSignalScorePercent())).append(',')
                    .append(csv(run.volumeSpikeMultiplier())).append(',')
                    .append(csv(run.breakoutBufferPercent())).append(',')
                    .append(csv(run.breakoutLookback())).append(',')
                    .append(csv(run.volumeLookback())).append(',')
                    .append(csv(run.minLiquidityVolume())).append(',')
                    .append(csv(run.maxIvPercent())).append(',')
                    .append(csv(run.totalCapital())).append(',')
                    .append(csv(run.maxRiskPerTradePercent())).append(',')
                    .append(csv(run.lotSize())).append(',')
                    .append(csv(run.rsiFilterEnabled())).append(',')
                    .append(csv(run.maxHoldMinutes())).append(',')
                    .append(csv(metric(run, MetricSelector.TOTAL_TRADES))).append(',')
                    .append(csv(metric(run, MetricSelector.WIN_RATE))).append(',')
                    .append(csv(metric(run, MetricSelector.AVERAGE_WIN))).append(',')
                    .append(csv(metric(run, MetricSelector.AVERAGE_LOSS))).append(',')
                    .append(csv(metric(run, MetricSelector.EXPECTANCY))).append(',')
                    .append(csv(metric(run, MetricSelector.MAX_DRAWDOWN))).append(',')
                    .append(csv(metric(run, MetricSelector.CUMULATIVE_PNL))).append(',')
                    .append(csv(run.downloadedInputPath())).append(',')
                    .append(csv(run.outputDirectory())).append(',')
                    .append(csv(run.reportHtml())).append(',')
                    .append(csv(run.manifestPath()))
                    .append(System.lineSeparator());
        }
        Files.writeString(path, csv.toString());
        return path;
    }

    private Path writeSummaryJson(Path suiteOutputDirectory, String suiteId, UnderlyingSymbol underlying,
                                  List<SuiteRunResult> runs) throws IOException {
        Path path = suiteOutputDirectory.resolve("suite-summary.json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suiteId", suiteId);
        payload.put("underlying", underlying);
        payload.put("generatedAt", Instant.now());
        payload.put("runs", runs);
        payload.put("variantRanking", rankingRows(runs));
        payload.put("periodPerformance", periodRows(runs));
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
        return path;
    }

    private Path writeRankingCsv(Path suiteOutputDirectory, List<SuiteRunResult> runs) throws IOException {
        Path path = suiteOutputDirectory.resolve("suite-variant-ranking.csv");
        StringBuilder csv = new StringBuilder();
        csv.append("variant,variantSlug,runCount,okRunCount,totalTrades,cumulativePnl,averageExpectancy,")
                .append("averageWinRatePercent,worstMaxDrawdown,bestRunOutputDirectory")
                .append(System.lineSeparator());
        for (VariantRankingRow row : rankingRows(runs)) {
            csv.append(csv(row.variant())).append(',')
                    .append(csv(row.variantSlug())).append(',')
                    .append(csv(row.runCount())).append(',')
                    .append(csv(row.okRunCount())).append(',')
                    .append(csv(row.totalTrades())).append(',')
                    .append(csv(row.cumulativePnl())).append(',')
                    .append(csv(row.averageExpectancy())).append(',')
                    .append(csv(row.averageWinRatePercent())).append(',')
                    .append(csv(row.worstMaxDrawdown())).append(',')
                    .append(csv(row.bestRunOutputDirectory()))
                    .append(System.lineSeparator());
        }
        Files.writeString(path, csv.toString());
        return path;
    }

    private Path writePeriodPerformanceCsv(Path suiteOutputDirectory, List<SuiteRunResult> runs) throws IOException {
        Path path = suiteOutputDirectory.resolve("suite-period-performance.csv");
        StringBuilder csv = new StringBuilder();
        csv.append("periodType,period,periodStart,periodEnd,window,variant,variantSlug,underlying,optionType,timeframe,")
                .append("tradeDays,totalPnl,bestDayPnl,worstDayPnl,averageDayPnl,runOutputDirectory")
                .append(System.lineSeparator());
        for (PeriodPerformanceRow row : periodRows(runs)) {
            csv.append(csv(row.periodType())).append(',')
                    .append(csv(row.period())).append(',')
                    .append(csv(row.periodStart())).append(',')
                    .append(csv(row.periodEnd())).append(',')
                    .append(csv(row.window())).append(',')
                    .append(csv(row.variant())).append(',')
                    .append(csv(row.variantSlug())).append(',')
                    .append(csv(row.underlying())).append(',')
                    .append(csv(row.optionType())).append(',')
                    .append(csv(row.timeframe())).append(',')
                    .append(csv(row.tradeDays())).append(',')
                    .append(csv(row.totalPnl())).append(',')
                    .append(csv(row.bestDayPnl())).append(',')
                    .append(csv(row.worstDayPnl())).append(',')
                    .append(csv(row.averageDayPnl())).append(',')
                    .append(csv(row.runOutputDirectory()))
                    .append(System.lineSeparator());
        }
        Files.writeString(path, csv.toString());
        return path;
    }

    private Path writePeriodPerformanceJson(Path suiteOutputDirectory, String suiteId, UnderlyingSymbol underlying,
                                            List<SuiteRunResult> runs) throws IOException {
        Path path = suiteOutputDirectory.resolve("suite-period-performance.json");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("suiteId", suiteId);
        payload.put("underlying", underlying);
        payload.put("generatedAt", Instant.now());
        payload.put("periodPerformance", periodRows(runs));
        OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), payload);
        return path;
    }

    private Path writeSuiteReportHtml(Path suiteOutputDirectory, String suiteId, UnderlyingSymbol underlying,
                                      List<SuiteRunResult> runs) throws IOException {
        Path path = suiteOutputDirectory.resolve("suite-report.html");
        List<VariantDecisionRow> decisions = variantDecisionRows(runs);
        List<PeriodPerformanceRow> periods = periodRows(runs);
        List<BucketDecisionRow> buckets = bucketDecisionRows(runs);
        StringBuilder shortlistRows = new StringBuilder();
        for (VariantDecisionRow row : decisions.stream()
                .filter(decision -> decision.cumulativePnl().signum() > 0)
                .filter(decision -> decision.pnlToDrawdown().compareTo(BigDecimal.valueOf(5)) >= 0)
                .filter(decision -> decision.positiveMonthPercent().compareTo(BigDecimal.valueOf(60)) >= 0)
                .filter(decision -> decision.drawdownCapitalPercent().compareTo(BigDecimal.valueOf(35)) < 0)
                .limit(8)
                .toList()) {
            shortlistRows.append("<tr><td>").append(htmlEscape(row.variant())).append("</td><td>")
                    .append(formatMoney(row.cumulativePnl())).append("</td><td>")
                    .append(formatMoney(row.worstMaxDrawdown())).append("</td><td>")
                    .append(formatMoney(row.pnlToDrawdown())).append("</td><td>")
                    .append(formatMoney(row.positiveMonthPercent())).append("%</td><td>")
                    .append(formatMoney(row.positiveWeekPercent())).append("%</td><td>")
                    .append(formatMoney(row.peFiveMinutePnl())).append("</td><td>")
                    .append(htmlEscape(row.recommendation())).append("</td></tr>");
        }
        StringBuilder bucketRows = new StringBuilder();
        for (BucketDecisionRow row : buckets) {
            String color = row.cumulativePnl().signum() >= 0 ? "#166534" : "#b91c1c";
            bucketRows.append("<tr><td>").append(htmlEscape(row.bucket())).append("</td><td>")
                    .append(row.runCount()).append("</td><td>")
                    .append(row.profitableRuns()).append("</td><td>")
                    .append(row.totalTrades()).append("</td><td style='color:").append(color).append("'>")
                    .append(formatMoney(row.cumulativePnl())).append("</td><td>")
                    .append(formatMoney(row.averageExpectancy())).append("</td><td>")
                    .append(formatMoney(row.averageWinRatePercent())).append("%</td></tr>");
        }
        StringBuilder decisionRows = new StringBuilder();
        for (VariantDecisionRow row : decisions) {
            String pnlColor = row.cumulativePnl().signum() >= 0 ? "#166534" : "#b91c1c";
            String peFiveColor = row.peFiveMinutePnl().signum() >= 0 ? "#166534" : "#b91c1c";
            decisionRows.append("<tr><td>").append(htmlEscape(row.variant())).append("</td><td>")
                    .append(row.okRunCount()).append('/').append(row.runCount()).append("</td><td>")
                    .append(row.totalTrades()).append("</td><td style='color:").append(pnlColor).append("'>")
                    .append(formatMoney(row.cumulativePnl())).append("</td><td>")
                    .append(formatMoney(row.worstMaxDrawdown())).append("</td><td>")
                    .append(formatMoney(row.drawdownCapitalPercent())).append("%</td><td>")
                    .append(formatMoney(row.pnlToDrawdown())).append("</td><td>")
                    .append(formatMoney(row.averageExpectancy())).append("</td><td>")
                    .append(formatMoney(row.averageWinRatePercent())).append("%</td><td>")
                    .append(formatMoney(row.positiveMonthPercent())).append("%</td><td>")
                    .append(formatMoney(row.worstMonthPnl())).append("</td><td>")
                    .append(formatMoney(row.positiveWeekPercent())).append("%</td><td>")
                    .append(formatMoney(row.oneMinutePnl())).append("</td><td>")
                    .append(formatMoney(row.fiveMinutePnl())).append("</td><td style='color:")
                    .append(peFiveColor).append("'>").append(formatMoney(row.peFiveMinutePnl())).append("</td><td>")
                    .append(htmlEscape(row.recommendation())).append("</td></tr>");
        }
        StringBuilder settingsRows = new StringBuilder();
        for (SuiteRunResult run : runs.stream()
                .collect(Collectors.toMap(SuiteRunResult::variantSlug, run -> run, (left, right) -> left,
                        LinkedHashMap::new))
                .values()) {
            settingsRows.append("<tr><td>").append(htmlEscape(run.variant())).append("</td><td>")
                    .append(formatMoney(run.totalCapital())).append("</td><td>")
                    .append(formatMoney(run.maxRiskPerTradePercent())).append("%</td><td>")
                    .append(formatMoney(run.stopLossPercent())).append("%</td><td>")
                    .append(formatMoney(run.targetPercent())).append("%</td><td>")
                    .append(formatMoney(run.trailingStopActivationPercent())).append("%</td><td>")
                    .append(formatMoney(run.trailingGapPercent())).append("%</td><td>")
                    .append(formatMoney(run.minSignalScorePercent())).append("%</td><td>")
                    .append(formatMoney(run.volumeSpikeMultiplier())).append("</td><td>")
                    .append(formatMoney(run.breakoutBufferPercent())).append("%</td><td>")
                    .append(run.rsiFilterEnabled()).append("</td><td>")
                    .append(run.maxHoldMinutes()).append("</td></tr>");
        }
        StringBuilder periodRows = new StringBuilder();
        for (PeriodPerformanceRow row : periods.stream()
                .sorted(Comparator.comparing(PeriodPerformanceRow::periodType)
                        .thenComparing(PeriodPerformanceRow::period)
                        .thenComparing(PeriodPerformanceRow::variantSlug))
                .toList()) {
            String color = row.totalPnl().signum() >= 0 ? "#166534" : "#b91c1c";
            periodRows.append("<tr><td>").append(row.periodType()).append("</td><td>")
                    .append(htmlEscape(row.period())).append("</td><td>")
                    .append(htmlEscape(row.window())).append("</td><td>")
                    .append(htmlEscape(row.variant())).append("</td><td>")
                    .append(row.optionType()).append("</td><td>")
                    .append(row.timeframe()).append("</td><td>")
                    .append(row.tradeDays()).append("</td><td style='color:").append(color).append("'>")
                    .append(formatMoney(row.totalPnl())).append("</td><td>")
                    .append(formatMoney(row.bestDayPnl())).append("</td><td>")
                    .append(formatMoney(row.worstDayPnl())).append("</td></tr>");
        }
        StringBuilder runRows = new StringBuilder();
        for (SuiteRunResult run : runs) {
            BacktestMetrics metrics = run.metrics();
            BigDecimal pnl = metrics == null ? BigDecimal.ZERO : metrics.cumulativePnl();
            String pnlColor = pnl.signum() >= 0 ? "#166534" : "#b91c1c";
            runRows.append("<tr><td>").append(htmlEscape(run.status())).append("</td><td>")
                    .append(htmlEscape(run.window())).append("</td><td>")
                    .append(htmlEscape(run.variant())).append("</td><td>")
                    .append(run.optionType()).append("</td><td>")
                    .append(run.timeframe()).append("</td><td>")
                    .append(run.from()).append("</td><td>")
                    .append(run.to()).append("</td><td>")
                    .append(metrics == null ? 0 : metrics.totalTrades()).append("</td><td>")
                    .append(formatMoney(metrics == null ? BigDecimal.ZERO : metrics.winRatePercent())).append("%</td><td>")
                    .append(formatMoney(metrics == null ? BigDecimal.ZERO : metrics.expectancy())).append("</td><td>")
                    .append(formatMoney(metrics == null ? BigDecimal.ZERO : metrics.maxDrawdown())).append("</td><td style='color:")
                    .append(pnlColor).append("'>").append(formatMoney(pnl)).append("</td><td>")
                    .append(formatMoney(run.totalCapital())).append("</td><td>")
                    .append(formatMoney(run.maxRiskPerTradePercent())).append("%</td><td>")
                    .append(formatMoney(run.stopLossPercent())).append("%</td><td>")
                    .append(formatMoney(run.targetPercent())).append("%</td><td>")
                    .append(run.candlesWritten()).append("</td><td>")
                    .append(htmlEscape(run.tradingSymbol())).append("</td><td>")
                    .append(htmlEscape(run.outputDirectory())).append("</td><td>")
                    .append(htmlEscape(run.reportHtml())).append("</td><td>")
                    .append(htmlEscape(run.errorMessage())).append("</td></tr>");
        }
        long okRuns = runs.stream().filter(run -> "ok".equals(run.status())).count();
        BigDecimal totalPnl = runs.stream().filter(run -> "ok".equals(run.status()))
                .map(run -> metric(run, MetricSelector.CUMULATIVE_PNL))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        int totalTrades = runs.stream().filter(run -> "ok".equals(run.status()))
                .map(run -> metric(run, MetricSelector.TOTAL_TRADES).intValue())
                .reduce(0, Integer::sum);
        VariantDecisionRow bestPnl = decisions.isEmpty() ? null : decisions.getFirst();
        VariantDecisionRow bestRiskAdjusted = decisions.stream()
                .filter(row -> row.cumulativePnl().signum() > 0)
                .max(Comparator.comparing(VariantDecisionRow::pnlToDrawdown)
                        .thenComparing(VariantDecisionRow::cumulativePnl))
                .orElse(null);
        String html = "<!DOCTYPE html><html lang='en'><head><meta charset='UTF-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Backtest Suite Report " + htmlEscape(suiteId) + "</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:0;padding:24px;background:#f8fafc;color:#1f2937}"
                + "h1{font-size:1.4rem;margin:0 0 4px}h2{font-size:1rem;margin:24px 0 8px;color:#4b5563}"
                + ".meta{font-size:.82rem;color:#6b7280;margin-bottom:20px}.cards{display:flex;flex-wrap:wrap;gap:12px}"
                + ".card{background:white;border-radius:8px;padding:14px 18px;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
                + ".label{font-size:.75rem;color:#6b7280}.value{font-size:1.2rem;font-weight:650}"
                + ".note{background:#fff7df;border:1px solid #e8c65b;border-radius:8px;padding:14px;margin:16px 0}"
                + ".table-tools{display:flex;gap:8px;align-items:center;margin:8px 0}.table-filter{border:1px solid #cbd5e1;border-radius:6px;padding:8px 10px;min-width:280px;background:white}"
                + ".row-count{font-size:.78rem;color:#6b7280}.table-wrap{overflow:auto;border-radius:8px}"
                + "table{width:100%;border-collapse:collapse;background:white;border-radius:8px;overflow:hidden;box-shadow:0 1px 3px rgba(0,0,0,.08)}"
                + "th{background:#eef2f7;padding:8px 10px;text-align:left;font-size:.78rem;color:#4b5563;white-space:nowrap;cursor:pointer;position:sticky;top:0}"
                + "th::after{content:' sort';font-size:.68rem;color:#94a3b8;font-weight:400}td{padding:7px 10px;font-size:.82rem;border-top:1px solid #edf2f7;white-space:nowrap}"
                + "a{color:#075985;text-decoration:none}</style></head><body>"
                + "<h1>Backtest Suite Report</h1><div class='meta'>ID: " + htmlEscape(suiteId)
                + " | Underlying: " + underlying + " | Generated: " + Instant.now() + "</div>"
                + "<div class='cards'><div class='card'><div class='label'>Runs</div><div class='value'>"
                + okRuns + "/" + runs.size() + "</div></div><div class='card'><div class='label'>Total PnL</div><div class='value'>Rs "
                + formatMoney(totalPnl) + "</div></div><div class='card'><div class='label'>Trades</div><div class='value'>"
                + totalTrades + "</div></div><div class='card'><div class='label'>Best PnL</div><div class='value'>"
                + htmlEscape(bestPnl == null ? "" : bestPnl.variant()) + "</div></div><div class='card'><div class='label'>Best PnL/DD</div><div class='value'>"
                + htmlEscape(bestRiskAdjusted == null ? "" : bestRiskAdjusted.variant()) + "</div></div></div>"
                + "<div class='note'><b>Recommendation rule:</b> choose from the shortlist first. It filters for positive PnL, positive expectancy, acceptable drawdown, stronger monthly stability, and a usable PnL-to-drawdown ratio. Treat PE FIVE_MINUTE as diagnostic because it was the weak bucket in the completed suite.</div>"
                + tableSection("Recommended Shortlist", "shortlist", "Variant,PnL,Worst DD,PnL/DD,Positive Months,Positive Weeks,PE 5m PnL,Notes", shortlistRows)
                + tableSection("Bucket Health", "buckets", "Bucket,Runs,Profitable Runs,Trades,PnL,Avg Expectancy,Avg Win Rate", bucketRows)
                + tableSection("All Variants", "variants", "Variant,OK Runs,Trades,PnL,Worst DD,DD % Capital,PnL/DD,Expectancy,Win Rate,Positive Months,Worst Month,Positive Weeks,1m PnL,5m PnL,PE 5m PnL,Notes", decisionRows)
                + tableSection("Variant Settings", "settings", "Variant,Capital,Risk,Stop,Target,Trail Act,Trail Gap,Score,Volume Spike,Breakout Buffer,RSI,Max Hold", settingsRows)
                + tableSection("Weekly and Monthly Performance", "periods", "Period Type,Period,Window,Variant,Side,Timeframe,Trade Days,PnL,Best Day,Worst Day", periodRows)
                + tableSection("All Runs", "runs", "Status,Window,Variant,Side,Timeframe,From,To,Trades,Win Rate,Expectancy,Max DD,PnL,Capital,Risk,Stop,Target,Candles,Trading Symbol,Output Directory,Report,Error", runRows)
                + "<script>"
                + "document.querySelectorAll('table.data-table').forEach(function(table){"
                + "var input=document.querySelector('[data-filter=\"'+table.id+'\"]');var count=document.querySelector('[data-count=\"'+table.id+'\"]');"
                + "function rows(){return Array.from(table.tBodies[0].rows)}"
                + "function update(){var q=(input&&input.value||'').toLowerCase();var shown=0;rows().forEach(function(r){var ok=r.textContent.toLowerCase().indexOf(q)>=0;r.style.display=ok?'':'none';if(ok)shown++});if(count)count.textContent=shown+' / '+rows().length+' rows'}"
                + "if(input)input.addEventListener('input',update);"
                + "Array.from(table.tHead.rows[0].cells).forEach(function(th,i){th.addEventListener('click',function(){var dir=th.dataset.dir==='asc'?'desc':'asc';Array.from(th.parentNode.cells).forEach(function(c){delete c.dataset.dir});th.dataset.dir=dir;var sorted=rows().sort(function(a,b){var av=a.cells[i].innerText.replace(/[%,$]/g,'').trim();var bv=b.cells[i].innerText.replace(/[%,$]/g,'').trim();var an=parseFloat(av);var bn=parseFloat(bv);var cmp=(!isNaN(an)&&!isNaN(bn))?an-bn:av.localeCompare(bv);return dir==='asc'?cmp:-cmp});sorted.forEach(function(r){table.tBodies[0].appendChild(r)});update()})});"
                + "update();"
                + "});"
                + "</script></body></html>";
        Files.writeString(path, html);
        return path;
    }

    private String tableSection(String title, String id, String headers, StringBuilder rows) {
        StringBuilder html = new StringBuilder();
        html.append("<h2>").append(htmlEscape(title)).append("</h2>")
                .append("<div class='table-tools'><input class='table-filter' data-filter='").append(id)
                .append("' placeholder='Filter ").append(htmlEscape(title)).append("'><span class='row-count' data-count='")
                .append(id).append("'></span></div><div class='table-wrap'><table class='data-table' id='")
                .append(id).append("'><thead><tr>");
        for (String header : headers.split(",")) {
            html.append("<th>").append(htmlEscape(header)).append("</th>");
        }
        html.append("</tr></thead><tbody>").append(rows).append("</tbody></table></div>");
        return html.toString();
    }

    private List<PeriodPerformanceRow> periodRows(List<SuiteRunResult> runs) {
        List<PeriodPerformanceRow> rows = new ArrayList<>();
        for (SuiteRunResult run : runs) {
            if (!"ok".equals(run.status()) || run.metrics() == null || run.metrics().dailyPnl().isEmpty()) {
                continue;
            }
            rows.addAll(periodRows(run, "WEEK"));
            rows.addAll(periodRows(run, "MONTH"));
        }
        return rows;
    }

    private List<PeriodPerformanceRow> periodRows(SuiteRunResult run, String periodType) {
        Map<String, List<Map.Entry<String, BigDecimal>>> grouped = run.metrics().dailyPnl().entrySet().stream()
                .collect(Collectors.groupingBy(entry -> periodKey(LocalDate.parse(entry.getKey()), periodType),
                        LinkedHashMap::new, Collectors.toList()));
        List<PeriodPerformanceRow> rows = new ArrayList<>();
        for (Map.Entry<String, List<Map.Entry<String, BigDecimal>>> entry : grouped.entrySet()) {
            List<BigDecimal> values = entry.getValue().stream().map(Map.Entry::getValue).toList();
            BigDecimal total = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal best = values.stream().max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            BigDecimal worst = values.stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
            LocalDate start = entry.getValue().stream().map(e -> LocalDate.parse(e.getKey()))
                    .min(Comparator.naturalOrder()).orElse(run.from());
            LocalDate end = entry.getValue().stream().map(e -> LocalDate.parse(e.getKey()))
                    .max(Comparator.naturalOrder()).orElse(run.to());
            rows.add(new PeriodPerformanceRow(periodType, entry.getKey(), start, end, run.window(), run.variant(),
                    run.variantSlug(), run.underlying(), run.optionType(), run.timeframe(), values.size(), total, best,
                    worst, average(values), run.outputDirectory()));
        }
        return rows;
    }

    private String periodKey(LocalDate date, String periodType) {
        if ("MONTH".equals(periodType)) {
            return date.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        }
        LocalDate weekStart = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate weekEnd = weekStart.plusDays(6);
        return weekStart + "_to_" + weekEnd;
    }

    private List<VariantDecisionRow> variantDecisionRows(List<SuiteRunResult> runs) {
        List<PeriodPerformanceRow> periods = periodRows(runs);
        return runs.stream()
                .collect(Collectors.groupingBy(SuiteRunResult::variantSlug, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(groupedRuns -> variantDecisionRow(groupedRuns, periods))
                .sorted(Comparator.comparing(VariantDecisionRow::cumulativePnl).reversed()
                        .thenComparing(Comparator.comparing(VariantDecisionRow::pnlToDrawdown).reversed()))
                .toList();
    }

    private VariantDecisionRow variantDecisionRow(List<SuiteRunResult> groupedRuns,
                                                  List<PeriodPerformanceRow> periods) {
        SuiteRunResult sample = groupedRuns.getFirst();
        VariantRankingRow ranking = rankingRow(groupedRuns);
        List<SuiteRunResult> okRuns = groupedRuns.stream().filter(run -> "ok".equals(run.status())).toList();
        BigDecimal capital = sample.totalCapital() == null ? BigDecimal.ZERO : sample.totalCapital();
        BigDecimal drawdownCapitalPercent = capital.signum() == 0
                ? BigDecimal.ZERO
                : ranking.worstMaxDrawdown().multiply(BigDecimal.valueOf(100), MathContext.DECIMAL64)
                .divide(capital, 4, RoundingMode.HALF_UP);
        BigDecimal pnlToDrawdown = ranking.worstMaxDrawdown().signum() == 0
                ? BigDecimal.ZERO
                : ranking.cumulativePnl().divide(ranking.worstMaxDrawdown(), 4, RoundingMode.HALF_UP);
        PeriodStability monthStability = stability(periods, sample.variantSlug(), "MONTH");
        PeriodStability weekStability = stability(periods, sample.variantSlug(), "WEEK");
        BigDecimal oneMinutePnl = okRuns.stream()
                .filter(run -> run.timeframe() == Timeframe.ONE_MINUTE)
                .map(run -> metric(run, MetricSelector.CUMULATIVE_PNL))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal fiveMinutePnl = okRuns.stream()
                .filter(run -> run.timeframe() == Timeframe.FIVE_MINUTE)
                .map(run -> metric(run, MetricSelector.CUMULATIVE_PNL))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal peFiveMinutePnl = okRuns.stream()
                .filter(run -> run.optionType() == OptionType.PE && run.timeframe() == Timeframe.FIVE_MINUTE)
                .map(run -> metric(run, MetricSelector.CUMULATIVE_PNL))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new VariantDecisionRow(ranking.variant(), ranking.variantSlug(), ranking.runCount(),
                ranking.okRunCount(), ranking.totalTrades(), ranking.cumulativePnl(), ranking.averageExpectancy(),
                ranking.averageWinRatePercent(), ranking.worstMaxDrawdown(), drawdownCapitalPercent, pnlToDrawdown,
                monthStability.positivePercent(), monthStability.worstPnl(), weekStability.positivePercent(),
                weekStability.worstPnl(), oneMinutePnl, fiveMinutePnl, peFiveMinutePnl,
                recommendation(ranking, drawdownCapitalPercent, pnlToDrawdown, monthStability, peFiveMinutePnl));
    }

    private PeriodStability stability(List<PeriodPerformanceRow> periods, String variantSlug, String periodType) {
        Map<String, BigDecimal> totals = new LinkedHashMap<>();
        for (PeriodPerformanceRow row : periods) {
            if (row.variantSlug().equals(variantSlug) && row.periodType().equals(periodType)) {
                totals.merge(row.period(), row.totalPnl(), BigDecimal::add);
            }
        }
        if (totals.isEmpty()) {
            return new PeriodStability(0, 0, BigDecimal.ZERO, BigDecimal.ZERO);
        }
        int positive = (int) totals.values().stream().filter(value -> value.signum() > 0).count();
        int negative = (int) totals.values().stream().filter(value -> value.signum() < 0).count();
        BigDecimal positivePercent = BigDecimal.valueOf(positive)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(totals.size()), 4, RoundingMode.HALF_UP);
        BigDecimal worst = totals.values().stream().min(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        return new PeriodStability(positive, negative, positivePercent, worst);
    }

    private String recommendation(VariantRankingRow ranking, BigDecimal drawdownCapitalPercent,
                                  BigDecimal pnlToDrawdown, PeriodStability monthStability,
                                  BigDecimal peFiveMinutePnl) {
        List<String> notes = new ArrayList<>();
        if (ranking.cumulativePnl().signum() <= 0) {
            notes.add("Reject: non-positive PnL");
        }
        if (ranking.averageExpectancy().signum() <= 0) {
            notes.add("Reject: non-positive expectancy");
        }
        if (drawdownCapitalPercent.compareTo(BigDecimal.valueOf(30)) >= 0) {
            notes.add("High risk: drawdown above 30% capital");
        } else if (drawdownCapitalPercent.compareTo(BigDecimal.valueOf(20)) >= 0) {
            notes.add("Risk watch: drawdown above 20% capital");
        }
        if (peFiveMinutePnl.signum() < 0) {
            notes.add("PE 5m drag");
        }
        if (ranking.cumulativePnl().signum() > 0
                && ranking.averageExpectancy().signum() > 0
                && pnlToDrawdown.compareTo(BigDecimal.valueOf(8)) >= 0
                && monthStability.positivePercent().compareTo(BigDecimal.valueOf(60)) >= 0) {
            notes.add("Shortlist: strong risk-adjusted result");
        } else if (ranking.cumulativePnl().signum() > 0 && pnlToDrawdown.compareTo(BigDecimal.valueOf(5)) >= 0) {
            notes.add("Candidate: review bucket mix");
        }
        return String.join("; ", notes);
    }

    private List<BucketDecisionRow> bucketDecisionRows(List<SuiteRunResult> runs) {
        return runs.stream()
                .filter(run -> "ok".equals(run.status()))
                .collect(Collectors.groupingBy(run -> run.optionType() + " " + run.timeframe(),
                        LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream()
                .map(entry -> {
                    List<SuiteRunResult> bucketRuns = entry.getValue();
                    int trades = bucketRuns.stream().map(run -> metric(run, MetricSelector.TOTAL_TRADES).intValue())
                            .reduce(0, Integer::sum);
                    BigDecimal pnl = bucketRuns.stream().map(run -> metric(run, MetricSelector.CUMULATIVE_PNL))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal expectancy = average(bucketRuns.stream()
                            .map(run -> metric(run, MetricSelector.EXPECTANCY)).toList());
                    BigDecimal winRate = average(bucketRuns.stream()
                            .map(run -> metric(run, MetricSelector.WIN_RATE)).toList());
                    int profitableRuns = (int) bucketRuns.stream()
                            .filter(run -> metric(run, MetricSelector.CUMULATIVE_PNL).signum() > 0)
                            .count();
                    return new BucketDecisionRow(entry.getKey(), bucketRuns.size(), profitableRuns, trades, pnl,
                            expectancy, winRate);
                })
                .sorted(Comparator.comparing(BucketDecisionRow::cumulativePnl).reversed())
                .toList();
    }

    private List<VariantRankingRow> rankingRows(List<SuiteRunResult> runs) {
        return runs.stream()
                .collect(Collectors.groupingBy(SuiteRunResult::variantSlug, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(this::rankingRow)
                .sorted(Comparator.comparing(VariantRankingRow::cumulativePnl).reversed()
                        .thenComparing(Comparator.comparing(VariantRankingRow::averageExpectancy).reversed()))
                .toList();
    }

    private VariantRankingRow rankingRow(List<SuiteRunResult> groupedRuns) {
        SuiteRunResult sample = groupedRuns.get(0);
        List<SuiteRunResult> okRuns = groupedRuns.stream().filter(run -> "ok".equals(run.status())).toList();
        BigDecimal cumulativePnl = okRuns.stream().map(run -> metric(run, MetricSelector.CUMULATIVE_PNL))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal averageExpectancy = average(okRuns.stream().map(run -> metric(run, MetricSelector.EXPECTANCY)).toList());
        BigDecimal averageWinRate = average(okRuns.stream().map(run -> metric(run, MetricSelector.WIN_RATE)).toList());
        BigDecimal worstDrawdown = okRuns.stream().map(run -> metric(run, MetricSelector.MAX_DRAWDOWN))
                .max(BigDecimal::compareTo).orElse(BigDecimal.ZERO);
        int totalTrades = okRuns.stream().map(run -> metric(run, MetricSelector.TOTAL_TRADES).intValue())
                .reduce(0, Integer::sum);
        String bestRunOutputDirectory = okRuns.stream()
                .max(Comparator.comparing((SuiteRunResult run) -> metric(run, MetricSelector.CUMULATIVE_PNL))
                        .thenComparing(run -> metric(run, MetricSelector.EXPECTANCY)))
                .map(SuiteRunResult::outputDirectory)
                .orElse("");
        return new VariantRankingRow(sample.variant(), sample.variantSlug(), groupedRuns.size(), okRuns.size(),
                totalTrades, cumulativePnl, averageExpectancy, averageWinRate, worstDrawdown, bestRunOutputDirectory);
    }

    private BigDecimal average(List<BigDecimal> values) {
        if (values.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(values.size()), 4, RoundingMode.HALF_UP);
    }

    private TradingProperties applyVariant(SuiteVariant variant) {
        TradingProperties.Entry baseEntry = properties.entry();
        TradingProperties.Exit baseExit = properties.exit();
        TradingProperties.Risk baseRisk = properties.risk();
        TradingProperties.Backtest baseBacktest = properties.backtest();
        return new TradingProperties(
                properties.mode(),
                properties.marketDataMode(),
                properties.executionMode(),
                properties.liveTradingEnabled(),
                properties.timezone(),
                properties.broker(),
                properties.symbols(),
                properties.strike(),
                new TradingProperties.Entry(
                        baseEntry.timeframe(),
                        baseEntry.trendTimeframe(),
                        baseEntry.enabledOptionTypes(),
                        baseEntry.vwapFilterEnabled(),
                        baseEntry.trendFilterEnabled(),
                        firstNonNull(variant.volumeSpikeMultiplier(), baseEntry.volumeSpikeMultiplier()),
                        firstNonNull(variant.breakoutBufferPercent(), baseEntry.breakoutBufferPercent()),
                        firstNonNull(variant.breakoutLookback(), baseEntry.breakoutLookback()),
                        firstNonNull(variant.volumeLookback(), baseEntry.volumeLookback()),
                        baseEntry.bullishImbalanceThreshold(),
                        baseEntry.bearishImbalanceThreshold(),
                        firstNonNull(variant.minLiquidityVolume(), baseEntry.minLiquidityVolume()),
                        firstNonNull(variant.maxIvPercent(), baseEntry.maxIvPercent()),
                        firstNonNull(variant.minSignalScorePercent(), baseEntry.minSignalScorePercent()),
                        firstNonNull(variant.ceOiSupportRequired(), baseEntry.ceOiSupportRequired()),
                        firstNonNull(variant.peOiSupportRequired(), baseEntry.peOiSupportRequired()),
                        firstNonNull(variant.ceOiDivergenceFilterEnabled(), baseEntry.ceOiDivergenceFilterEnabled()),
                        firstNonNull(variant.peOiDivergenceFilterEnabled(), baseEntry.peOiDivergenceFilterEnabled()),
                        firstNonNull(variant.oiDivergenceMultiplier(), baseEntry.oiDivergenceMultiplier()),
                        firstNonNull(variant.oiDivergenceMinChange(), baseEntry.oiDivergenceMinChange()),
                        firstNonNull(variant.ceBreakoutConfirmationCandles(), baseEntry.ceBreakoutConfirmationCandles()),
                        firstNonNull(variant.peBreakoutConfirmationCandles(), baseEntry.peBreakoutConfirmationCandles()),
                        baseEntry.entryStartTime(),
                        firstNonNull(variant.entryCutoffTime(), baseEntry.entryCutoffTime()),
                        baseEntry.allowFirstMinutesEntry(),
                        baseEntry.noEntryFirstMinutes(),
                        firstNonNull(variant.rsiFilterEnabled(), baseEntry.rsiFilterEnabled()),
                        firstNonNull(variant.rsiPeriod(), baseEntry.rsiPeriod()),
                        firstNonNull(variant.rsiCeBuyThreshold(), baseEntry.rsiCeBuyThreshold()),
                        firstNonNull(variant.rsiPeSellThreshold(), baseEntry.rsiPeSellThreshold())
                ),
                new TradingProperties.Exit(
                        firstNonNull(variant.stopLossPercent(), baseExit.stopLossPercent()),
                        firstNonNull(variant.targetPercent(), baseExit.targetPercent()),
                        firstNonNull(variant.trailingStopActivationPercent(), baseExit.trailingStopActivationPercent()),
                        firstNonNull(variant.trailingGapPercent(), baseExit.trailingGapPercent()),
                        baseExit.forcedExitTime(),
                        baseExit.partialProfitBookingEnabled(),
                        firstNonNull(variant.maxHoldMinutes(), baseExit.maxHoldMinutes())
                ),
                new TradingProperties.Risk(
                        firstNonNull(variant.totalCapital(), baseRisk.totalCapital()),
                        firstNonNull(variant.maxRiskPerTradePercent(), baseRisk.maxRiskPerTradePercent()),
                        baseRisk.maxDailyLossPercent(),
                        baseRisk.maxTradesPerDay(),
                        baseRisk.maxOrdersPerDay(),
                        baseRisk.maxConsecutiveLosses(),
                        baseRisk.maxOpenTrades(),
                        baseRisk.sameInstrumentReentryMinPriceMovePercent(),
                        baseRisk.cooldownMinutes(),
                        baseRisk.dailyProfitTarget()
                ),
                properties.paper(),
                properties.safety(),
                properties.telegram(),
                properties.algo(),
                new TradingProperties.Backtest(
                        baseBacktest.from(),
                        baseBacktest.to(),
                        baseBacktest.candleTimeframe(),
                        baseBacktest.csvImportPath(),
                        baseBacktest.outputDirectory(),
                        baseBacktest.mockInstrumentKey(),
                        baseBacktest.mockCandleCount(),
                        firstNonNull(variant.lotSize(), baseBacktest.lotSize())
                )
        );
    }

    private static String archivePath(HistoricalDataDownloadService.ArchiveResult archive, ArchivePath path) {
        if (archive == null) {
            return null;
        }
        return switch (path) {
            case RANGE -> archive.rangePath().toString();
            case CONTRACT_CUMULATIVE -> archive.cumulativePath().toString();
            case SIDE_CUMULATIVE -> archive.sideCumulativePath().toString();
        };
    }

    private static int archiveCount(HistoricalDataDownloadService.ArchiveResult archive, boolean side) {
        if (archive == null) {
            return 0;
        }
        return side ? archive.sideCumulativeCandles() : archive.cumulativeCandles();
    }

    private List<SuiteWindow> defaultWindows(LocalDate to) {
        return List.of(
                new SuiteWindow("1-day",   to,                  to),
                new SuiteWindow("1-week",  to.minusWeeks(1),    to),
                new SuiteWindow("1-month", to.minusMonths(1),   to),
                new SuiteWindow("3-month", to.minusMonths(3),   to),
                new SuiteWindow("6-month", to.minusMonths(6),   to)
        );
    }

    private List<SuiteVariant> defaultVariants() {
        long liq2x = properties.entry().minLiquidityVolume() * 2;
        return List.of(
                // --- Baseline ---
                variant("baseline", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null),

                // --- Capital variants ---
                variant("cap-30k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(30_000), BigDecimal.ONE, null, null),
                variant("cap-30k-risk-1-5", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(30_000), new BigDecimal("1.5"), null, null),
                variant("cap-50k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(50_000), BigDecimal.ONE, null, null),
                variant("cap-60k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("cap-60k-risk-2", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.valueOf(2), null, null),

                // --- Target variants ---
                variant("target-15", null, BigDecimal.valueOf(15), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                variant("target-16", null, BigDecimal.valueOf(16), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                variant("target-18", null, BigDecimal.valueOf(18), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                variant("target-25", null, BigDecimal.valueOf(25), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),

                // --- Stop variants ---
                variant("stop-6-target-12", BigDecimal.valueOf(6), BigDecimal.valueOf(12), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),
                variant("stop-8-target-16", BigDecimal.valueOf(8), BigDecimal.valueOf(16), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),
                variant("stop-12-target-24", BigDecimal.valueOf(12), BigDecimal.valueOf(24), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),

                // --- Trailing stop variants ---
                variant("trail-act-10-gap-5", null, null, BigDecimal.valueOf(10), BigDecimal.valueOf(5),
                        null, null, null, null, null, null, null, null, null, null, null),
                variant("trail-act-15-gap-7", null, null, BigDecimal.valueOf(15), BigDecimal.valueOf(7),
                        null, null, null, null, null, null, null, null, null, null, null),

                // --- Signal score variants ---
                variant("score-75", null, null, null, null, BigDecimal.valueOf(75), null, null, null, null,
                        null, null, null, null, null, null),
                variant("score-80", null, null, null, null, BigDecimal.valueOf(80), null, null, null, null,
                        null, null, null, null, null, null),

                // --- Breakout filter variants ---
                variant("breakout-strict", null, null, null, null, null, new BigDecimal("1.5"),
                        new BigDecimal("0.10"), 7, 7, null, null, null, null, null, null),
                variant("breakout-loose", null, null, null, null, null, new BigDecimal("1.1"),
                        new BigDecimal("0.03"), 3, 3, null, null, null, null, null, null),

                // --- Liquidity variants ---
                variant("liquidity-2x", null, null, null, null, null, null, null, null, null,
                        liq2x, null, null, null, null, null),

                // --- Entry cutoff variants ---
                variant("cutoff-1400", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, LocalTime.of(14, 0)),
                variant("cutoff-1330", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, LocalTime.of(13, 30)),

                // --- Best combos from suite analysis (cap-60k + tuned params) ---
                variant("cap-60k-target-18", null, BigDecimal.valueOf(18), null, null, null,
                        null, null, null, null, null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("cap-60k-target-20-score-75", null, BigDecimal.valueOf(20), null, null,
                        BigDecimal.valueOf(75), null, null, null, null, null, null,
                        BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("cap-60k-stop-8-target-16", BigDecimal.valueOf(8), BigDecimal.valueOf(16), null, null,
                        null, null, null, null, null, null, null,
                        BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("cap-60k-trail-act-10-gap-5", null, null, BigDecimal.valueOf(10), BigDecimal.valueOf(5),
                        null, null, null, null, null, null, null,
                        BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                new SuiteVariant("cap-60k-rsi-on", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null,
                        true, 14, BigDecimal.valueOf(55), BigDecimal.valueOf(45), null,
                        null, null, null, null, null, null, null, null),
                new SuiteVariant("cap-60k-hold-60", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null,
                        null, null, null, null, 60,
                        null, null, null, null, null, null, null, null),
                new SuiteVariant("cap-60k-rsi-hold-60", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null,
                        true, 14, BigDecimal.valueOf(55), BigDecimal.valueOf(45), 60,
                        null, null, null, null, null, null, null, null)
        );
    }

    /**
     * Generates variants for run-all: crosses each strategy (DIRECTIONAL_BUY, SCALPING,
     * VOLATILITY_BREAKOUT) with a curated set of parameter variants so we can compare
     * strategy × parameter performance in a single suite run.
     */
    public List<SuiteVariant> runAllVariants(List<String> strategies) {
        List<String> effectiveStrategies = strategies != null && !strategies.isEmpty()
                ? strategies
                : List.of("DIRECTIONAL_BUY", "SCALPING", "VOLATILITY_BREAKOUT");

        // Parameter variants to cross with each strategy
        List<SuiteVariant> paramVariants = List.of(
                variant("baseline", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                variant("stop-8-target-16", BigDecimal.valueOf(8), BigDecimal.valueOf(16), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),
                variant("stop-12-target-24", BigDecimal.valueOf(12), BigDecimal.valueOf(24), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),
                variant("target-18", null, BigDecimal.valueOf(18), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                variant("trail-act-10-gap-5", null, null, BigDecimal.valueOf(10), BigDecimal.valueOf(5),
                        null, null, null, null, null, null, null, null, null, null, null),
                variant("score-75", null, null, null, null, BigDecimal.valueOf(75), null, null, null, null,
                        null, null, null, null, null, null),
                variant("cap-60k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("cap-60k-target-18", null, BigDecimal.valueOf(18), null, null, null,
                        null, null, null, null, null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                // OI-focused variants
                new SuiteVariant("pe-oi-off", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null,
                        false, false, null, null, null, null, null, null),
                new SuiteVariant("both-oi-on", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null,
                        true, true, null, null, null, null, null, null),
                new SuiteVariant("oi-divergence-off", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null,
                        null, null, false, false, null, null, null, null),
                new SuiteVariant("oi-relaxed-imbalance", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null,
                        false, false, false, false, null, null, null, null)
        );

        List<SuiteVariant> result = new ArrayList<>();
        for (String strategy : effectiveStrategies) {
            String strategySlug = strategy.toLowerCase().replace('_', '-');
            for (SuiteVariant pv : paramVariants) {
                result.add(new SuiteVariant(
                        strategySlug + "-" + pv.name(),
                        pv.stopLossPercent(), pv.targetPercent(),
                        pv.trailingStopActivationPercent(), pv.trailingGapPercent(),
                        pv.minSignalScorePercent(), pv.volumeSpikeMultiplier(), pv.breakoutBufferPercent(),
                        pv.breakoutLookback(), pv.volumeLookback(), pv.minLiquidityVolume(), pv.maxIvPercent(),
                        pv.totalCapital(), pv.maxRiskPerTradePercent(), pv.lotSize(), pv.entryCutoffTime(),
                        pv.rsiFilterEnabled(), pv.rsiPeriod(), pv.rsiCeBuyThreshold(), pv.rsiPeSellThreshold(),
                        pv.maxHoldMinutes(), pv.ceOiSupportRequired(), pv.peOiSupportRequired(),
                        pv.ceOiDivergenceFilterEnabled(), pv.peOiDivergenceFilterEnabled(),
                        pv.oiDivergenceMultiplier(), pv.oiDivergenceMinChange(),
                        pv.ceBreakoutConfirmationCandles(), pv.peBreakoutConfirmationCandles(),
                        strategy
                ));
            }
        }
        return result;
    }

    public List<SuiteVariant> recommendedPrunedVariants() {
        return List.of(
                variant("stop-12-target-24", BigDecimal.valueOf(12), BigDecimal.valueOf(24), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),
                variant("cap-30k-risk-1-5", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(30_000), new BigDecimal("1.5"), null, null),
                variant("target-25", null, BigDecimal.valueOf(25), null, null, null, null, null, null, null,
                        null, null, null, null, null, null)
        );
    }

    public List<SuiteVariant> recommendedPruned120kVariants() {
        return List.of(
                variant("cap-120k-stop-12-target-24", BigDecimal.valueOf(12), BigDecimal.valueOf(24), null, null,
                        null, null, null, null, null, null, null, BigDecimal.valueOf(120_000), BigDecimal.ONE,
                        null, null),
                variant("cap-120k-target-25", null, BigDecimal.valueOf(25), null, null, null, null, null, null,
                        null, null, null, BigDecimal.valueOf(120_000), BigDecimal.ONE, null, null),
                variant("cap-120k-risk-1-5", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(120_000), new BigDecimal("1.5"), null, null)
        );
    }

    public SuiteRequest weekendIntensiveRequest(SuiteRequest request) {
        SuiteRequest base = request == null
                ? new SuiteRequest(null, null, null, null, null, null, null, null, null)
                : request;
        LocalDate to = base.to() != null ? base.to() : LocalDate.now(properties.timezone());
        return new SuiteRequest(
                base.underlying() != null ? base.underlying() : UnderlyingSymbol.NIFTY,
                to,
                base.windows() == null || base.windows().isEmpty() ? weekendIntensiveWindows(to) : base.windows(),
                base.optionTypes() == null || base.optionTypes().isEmpty()
                        ? List.of(OptionType.CE, OptionType.PE)
                        : base.optionTypes(),
                base.timeframes() == null || base.timeframes().isEmpty()
                        ? List.of(Timeframe.ONE_MINUTE, Timeframe.FIVE_MINUTE)
                        : base.timeframes(),
                base.variants() == null || base.variants().isEmpty() ? weekendIntensiveVariants() : base.variants(),
                base.expiry(),
                base.strike(),
                base.underlyingPrice()
        );
    }

    public SuiteRequest focusedValidationRequest(SuiteRequest request) {
        SuiteRequest base = request == null
                ? new SuiteRequest(null, null, null, null, null, null, null, null, null)
                : request;
        LocalDate to = base.to() != null ? base.to() : LocalDate.now(properties.timezone());
        return new SuiteRequest(
                base.underlying() != null ? base.underlying() : UnderlyingSymbol.NIFTY,
                to,
                base.windows() == null || base.windows().isEmpty() ? focusedValidationWindows(to) : base.windows(),
                base.optionTypes() == null || base.optionTypes().isEmpty()
                        ? List.of(OptionType.CE, OptionType.PE)
                        : base.optionTypes(),
                base.timeframes() == null || base.timeframes().isEmpty()
                        ? List.of(Timeframe.FIVE_MINUTE)
                        : base.timeframes(),
                base.variants() == null || base.variants().isEmpty() ? focusedValidationVariants() : base.variants(),
                base.expiry(),
                base.strike(),
                base.underlyingPrice()
        );
    }

    private List<SuiteWindow> focusedValidationWindows(LocalDate to) {
        return List.of(
                new SuiteWindow("validate-apr", LocalDate.of(to.getYear(), 4, 1), to),
                new SuiteWindow("1-month", to.minusMonths(1), to),
                new SuiteWindow("1-year", LocalDate.of(2025, 4, 15), to)
        );
    }

    public List<SuiteVariant> focusedValidationVariants() {
        return List.of(
                variant("cap-120k-risk-1-5", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(120_000), new BigDecimal("1.5"), null, null),
                variant("cap-50k-risk-2", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(50_000), BigDecimal.valueOf(2), null, null),
                variant("cap-100k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(100_000), BigDecimal.ONE, null, null),
                variant("trail-act-10-gap-5", null, null, BigDecimal.valueOf(10), BigDecimal.valueOf(5),
                        null, null, null, null, null, null, null, null, null, null, null),
                new SuiteVariant("hold-30", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, 30,
                        null, null, null, null, null, null, null, null),
                variant("breakout-balanced", null, null, null, null, null, new BigDecimal("1.5"),
                        new BigDecimal("0.10"), 7, 7, null, null, null, null, null, null)
        );
    }

    private List<SuiteWindow> weekendIntensiveWindows(LocalDate to) {
        return List.of(
                new SuiteWindow("last-day", to, to),
                new SuiteWindow("last-2-trading-days", to.minusDays(1), to),
                new SuiteWindow("1-week", to.minusWeeks(1), to),
                new SuiteWindow("1-month", to.minusMonths(1), to),
                new SuiteWindow("3-month", to.minusMonths(3), to),
                new SuiteWindow("6-month", to.minusMonths(6), to),
                new SuiteWindow("1-year", to.minusYears(1), to),
                new SuiteWindow("train-to-mar31", to.minusYears(1), LocalDate.of(to.getYear(), 3, 31)),
                new SuiteWindow("validate-apr", LocalDate.of(to.getYear(), 4, 1), to)
        );
    }

    public List<SuiteVariant> weekendIntensiveVariants() {
        List<SuiteVariant> variants = new ArrayList<>();
        variants.add(variant("baseline", null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null));

        List<BigDecimal> capitals = List.of(
                BigDecimal.valueOf(30_000),
                BigDecimal.valueOf(50_000),
                BigDecimal.valueOf(60_000),
                BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(120_000),
                BigDecimal.valueOf(300_000)
        );
        List<BigDecimal> risks = List.of(new BigDecimal("0.5"), BigDecimal.ONE, new BigDecimal("1.5"),
                BigDecimal.valueOf(2));
        for (BigDecimal capital : capitals) {
            for (BigDecimal risk : risks) {
                variants.add(variant("cap-" + shortAmount(capital) + "-risk-" + shortDecimal(risk),
                        null, null, null, null, null, null, null, null, null, null, null,
                        capital, risk, null, null));
            }
        }

        List<BigDecimal> stops = List.of(BigDecimal.valueOf(6), BigDecimal.valueOf(8), BigDecimal.TEN,
                BigDecimal.valueOf(12), BigDecimal.valueOf(15));
        List<BigDecimal> targets = List.of(BigDecimal.valueOf(12), BigDecimal.valueOf(15), BigDecimal.valueOf(18),
                BigDecimal.valueOf(20), BigDecimal.valueOf(25));
        for (BigDecimal stop : stops) {
            for (BigDecimal target : targets) {
                variants.add(variant("stop-" + shortDecimal(stop) + "-target-" + shortDecimal(target),
                        stop, target, null, null, null, null, null, null, null, null, null,
                        BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null));
            }
        }

        variants.addAll(List.of(
                variant("score-60", null, null, null, null, BigDecimal.valueOf(60), null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("score-70", null, null, null, null, BigDecimal.valueOf(70), null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("score-75", null, null, null, null, BigDecimal.valueOf(75), null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("score-80", null, null, null, null, BigDecimal.valueOf(80), null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("volume-1-2", null, null, null, null, null, new BigDecimal("1.2"), null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("volume-1-5", null, null, null, null, null, new BigDecimal("1.5"), null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("volume-2-0", null, null, null, null, null, new BigDecimal("2.0"), null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                variant("breakout-loose", null, null, null, null, null, new BigDecimal("1.1"),
                        new BigDecimal("0.03"), 3, 3, null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE,
                        null, null),
                variant("breakout-balanced", null, null, null, null, null, new BigDecimal("1.5"),
                        new BigDecimal("0.10"), 7, 7, null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE,
                        null, null),
                variant("breakout-strict", null, null, null, null, null, new BigDecimal("2.0"),
                        new BigDecimal("0.15"), 10, 10, null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE,
                        null, null),
                variant("cutoff-1330", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, LocalTime.of(13, 30)),
                variant("cutoff-1400", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, LocalTime.of(14, 0)),
                variant("cutoff-1430", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, LocalTime.of(14, 30)),
                variant("cutoff-1445", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, LocalTime.of(14, 45)),
                variant("trail-act-10-gap-5", null, null, BigDecimal.valueOf(10), BigDecimal.valueOf(5),
                        null, null, null, null, null, null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE,
                        null, null),
                variant("trail-act-15-gap-7", null, null, BigDecimal.valueOf(15), BigDecimal.valueOf(7),
                        null, null, null, null, null, null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE,
                        null, null),
                variant("lot-75-cap-60k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, 75, null),
                variant("lot-75-cap-120k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(120_000), BigDecimal.ONE, 75, null)
        ));

        variants.add(new SuiteVariant("rsi-on", null, null, null, null, null, null, null, null, null,
                null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null,
                true, 14, BigDecimal.valueOf(55), BigDecimal.valueOf(45), null,
                null, null, null, null, null, null, null, null));
        variants.add(new SuiteVariant("hold-30", null, null, null, null, null, null, null, null, null,
                null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null,
                null, null, null, null, 30,
                null, null, null, null, null, null, null, null));
        variants.add(new SuiteVariant("hold-60", null, null, null, null, null, null, null, null, null,
                null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null,
                null, null, null, null, 60,
                null, null, null, null, null, null, null, null));
        variants.add(new SuiteVariant("rsi-hold-60", null, null, null, null, null, null, null, null, null,
                null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null,
                true, 14, BigDecimal.valueOf(55), BigDecimal.valueOf(45), 60,
                null, null, null, null, null, null, null, null));
        variants.add(new SuiteVariant("ce-relaxed", null, null, null, null, null, null, null, null, null,
                null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null,
                null, null, null, null, null,
                false, true, null, null, null, null, 1, 2));
        return List.copyOf(variants);
    }

    private SuiteVariant variant(
            String name,
            BigDecimal stopLossPercent,
            BigDecimal targetPercent,
            BigDecimal trailingStopActivationPercent,
            BigDecimal trailingGapPercent,
            BigDecimal minSignalScorePercent,
            BigDecimal volumeSpikeMultiplier,
            BigDecimal breakoutBufferPercent,
            Integer breakoutLookback,
            Integer volumeLookback,
            Long minLiquidityVolume,
            BigDecimal maxIvPercent,
            BigDecimal totalCapital,
            BigDecimal maxRiskPerTradePercent,
            Integer lotSize,
            LocalTime entryCutoffTime
    ) {
        return new SuiteVariant(name, stopLossPercent, targetPercent, trailingStopActivationPercent,
                trailingGapPercent, minSignalScorePercent, volumeSpikeMultiplier, breakoutBufferPercent,
                breakoutLookback, volumeLookback, minLiquidityVolume, maxIvPercent, totalCapital,
                maxRiskPerTradePercent, lotSize, entryCutoffTime, null, null, null, null, null,
                null, null, null, null,
                null, null, null, null);
    }

    private String shortAmount(BigDecimal value) {
        BigDecimal thousands = value.divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP);
        return thousands.toPlainString() + "k";
    }

    private String shortDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString().replace(".", "-");
    }

    private String suiteId(UnderlyingSymbol underlying) {
        return "SUITE-" + underlying.name().toLowerCase() + "-"
                + SUITE_TIMESTAMP_FORMAT.withZone(properties.timezone()).format(Instant.now());
    }

    private String relativePath(Path path) {
        if (path == null) {
            return null;
        }
        try {
            return Path.of("").toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize()).toString();
        } catch (IllegalArgumentException ex) {
            return path.toString();
        }
    }

    private String relativePath(String path) {
        return path == null || path.isBlank() ? path : relativePath(Path.of(path));
    }

    private String shortSlug(String text, int maxLength) {
        String sanitized = sanitize(text);
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        String hash = Integer.toUnsignedString(Objects.hashCode(text), 36);
        int prefixLength = Math.max(4, maxLength - hash.length() - 1);
        String prefix = sanitized.substring(0, Math.min(prefixLength, sanitized.length())).replaceAll("-+$", "");
        return prefix + "-" + hash;
    }

    private String sanitize(String text) {
        return text == null ? "" : text.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private String csv(Object value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + String.valueOf(value).replace("\"", "\"\"") + "\"";
    }

    private String formatMoney(BigDecimal value) {
        return value == null ? "0.00" : value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private String htmlEscape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private BigDecimal metric(SuiteRunResult run, MetricSelector selector) {
        if (run.metrics() == null) {
            return BigDecimal.ZERO;
        }
        return switch (selector) {
            case TOTAL_TRADES -> BigDecimal.valueOf(run.metrics().totalTrades());
            case WIN_RATE -> run.metrics().winRatePercent();
            case AVERAGE_WIN -> run.metrics().averageWin();
            case AVERAGE_LOSS -> run.metrics().averageLoss();
            case EXPECTANCY -> run.metrics().expectancy();
            case MAX_DRAWDOWN -> run.metrics().maxDrawdown();
            case CUMULATIVE_PNL -> run.metrics().cumulativePnl();
        };
    }

    private DataPreparationKey dataPreparationKey(UnderlyingSymbol underlying, OptionType optionType,
                                                  Timeframe timeframe, LocalDate from, LocalDate to,
                                                  LocalDate expiry, BigDecimal strike,
                                                  TradingProperties activeProperties) {
        return new DataPreparationKey(
                underlying,
                optionType,
                timeframe,
                from,
                to,
                expiry,
                normalized(strike),
                normalized(activeProperties.risk().totalCapital()),
                normalized(activeProperties.risk().maxRiskPerTradePercent()),
                normalized(activeProperties.exit().stopLossPercent()),
                activeProperties.backtest().lotSize()
        );
    }

    private BigDecimal normalized(BigDecimal value) {
        return value == null ? null : value.stripTrailingZeros();
    }

    private enum ArchivePath {
        RANGE,
        CONTRACT_CUMULATIVE,
        SIDE_CUMULATIVE
    }

    private enum MetricSelector {
        TOTAL_TRADES,
        WIN_RATE,
        AVERAGE_WIN,
        AVERAGE_LOSS,
        EXPECTANCY,
        MAX_DRAWDOWN,
        CUMULATIVE_PNL
    }

    private record DataPreparationKey(
            UnderlyingSymbol underlying,
            OptionType optionType,
            Timeframe timeframe,
            LocalDate from,
            LocalDate to,
            LocalDate expiry,
            BigDecimal strike,
            BigDecimal totalCapital,
            BigDecimal maxRiskPerTradePercent,
            BigDecimal stopLossPercent,
            int lotSize
    ) {
        String cacheName() {
            return underlying + "-" + optionType + "-" + timeframe + "-" + from + "-" + to + "-"
                    + nullSafe(expiry) + "-" + nullSafe(strike) + "-cap-" + nullSafe(totalCapital)
                    + "-risk-" + nullSafe(maxRiskPerTradePercent) + "-stop-" + nullSafe(stopLossPercent)
                    + "-lot-" + lotSize;
        }

        private static String nullSafe(Object value) {
            return value == null ? "default" : String.valueOf(value);
        }
    }

    private record CachedDownload(HistoricalDataDownloadService.DownloadResult download, Path preparedPath) {}

    public record SuiteRequest(
            UnderlyingSymbol underlying,
            LocalDate to,
            List<SuiteWindow> windows,
            List<OptionType> optionTypes,
            List<Timeframe> timeframes,
            List<SuiteVariant> variants,
            LocalDate expiry,
            BigDecimal strike,
            BigDecimal underlyingPrice
    ) {}

    public record SuiteWindow(String name, LocalDate from, LocalDate to) {}

    public record SuiteVariant(
            String name,
            BigDecimal stopLossPercent,
            BigDecimal targetPercent,
            BigDecimal trailingStopActivationPercent,
            BigDecimal trailingGapPercent,
            BigDecimal minSignalScorePercent,
            BigDecimal volumeSpikeMultiplier,
            BigDecimal breakoutBufferPercent,
            Integer breakoutLookback,
            Integer volumeLookback,
            Long minLiquidityVolume,
            BigDecimal maxIvPercent,
            BigDecimal totalCapital,
            BigDecimal maxRiskPerTradePercent,
            Integer lotSize,
            LocalTime entryCutoffTime,
            Boolean rsiFilterEnabled,
            Integer rsiPeriod,
            BigDecimal rsiCeBuyThreshold,
            BigDecimal rsiPeSellThreshold,
            Integer maxHoldMinutes,
            Boolean ceOiSupportRequired,
            Boolean peOiSupportRequired,
            Boolean ceOiDivergenceFilterEnabled,
            Boolean peOiDivergenceFilterEnabled,
            BigDecimal oiDivergenceMultiplier,
            Long oiDivergenceMinChange,
            Integer ceBreakoutConfirmationCandles,
            Integer peBreakoutConfirmationCandles,
            String strategyType
    ) {
        /** Backward-compatible constructor (no strategyType). */
        public SuiteVariant(
                String name,
                BigDecimal stopLossPercent,
                BigDecimal targetPercent,
                BigDecimal trailingStopActivationPercent,
                BigDecimal trailingGapPercent,
                BigDecimal minSignalScorePercent,
                BigDecimal volumeSpikeMultiplier,
                BigDecimal breakoutBufferPercent,
                Integer breakoutLookback,
                Integer volumeLookback,
                Long minLiquidityVolume,
                BigDecimal maxIvPercent,
                BigDecimal totalCapital,
                BigDecimal maxRiskPerTradePercent,
                Integer lotSize,
                LocalTime entryCutoffTime,
                Boolean rsiFilterEnabled,
                Integer rsiPeriod,
                BigDecimal rsiCeBuyThreshold,
                BigDecimal rsiPeSellThreshold,
                Integer maxHoldMinutes,
                Boolean ceOiSupportRequired,
                Boolean peOiSupportRequired,
                Boolean ceOiDivergenceFilterEnabled,
                Boolean peOiDivergenceFilterEnabled,
                BigDecimal oiDivergenceMultiplier,
                Long oiDivergenceMinChange,
                Integer ceBreakoutConfirmationCandles,
                Integer peBreakoutConfirmationCandles
        ) {
            this(name, stopLossPercent, targetPercent, trailingStopActivationPercent, trailingGapPercent,
                    minSignalScorePercent, volumeSpikeMultiplier, breakoutBufferPercent, breakoutLookback,
                    volumeLookback, minLiquidityVolume, maxIvPercent, totalCapital, maxRiskPerTradePercent,
                    lotSize, entryCutoffTime, rsiFilterEnabled, rsiPeriod, rsiCeBuyThreshold, rsiPeSellThreshold,
                    maxHoldMinutes, ceOiSupportRequired, peOiSupportRequired, ceOiDivergenceFilterEnabled,
                    peOiDivergenceFilterEnabled, oiDivergenceMultiplier, oiDivergenceMinChange,
                    ceBreakoutConfirmationCandles, peBreakoutConfirmationCandles, null);
        }

        public String slug() {
            String value = name == null || name.isBlank() ? "variant" : name;
            String sanitized = value.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
            if (sanitized.length() <= 24) {
                return sanitized;
            }
            String hash = Integer.toUnsignedString(value.hashCode(), 36);
            String prefix = sanitized.substring(0, Math.min(18, sanitized.length())).replaceAll("-+$", "");
            return prefix + "-" + hash;
        }
    }

    public record SuiteResult(
            String suiteId,
            UnderlyingSymbol underlying,
            String outputDirectory,
            String summaryCsv,
            String summaryJson,
            String rankingCsv,
            String periodPerformanceCsv,
            String periodPerformanceJson,
            String reportHtml,
            List<SuiteRunResult> runs
    ) {}

    public record SuiteRunResult(
            String suiteId,
            String status,
            String errorMessage,
            String window,
            String variant,
            String variantSlug,
            LocalDate from,
            LocalDate to,
            UnderlyingSymbol underlying,
            OptionType optionType,
            Timeframe timeframe,
            String backtestId,
            BigDecimal stopLossPercent,
            BigDecimal targetPercent,
            BigDecimal trailingStopActivationPercent,
            BigDecimal trailingGapPercent,
            BigDecimal minSignalScorePercent,
            BigDecimal volumeSpikeMultiplier,
            BigDecimal breakoutBufferPercent,
            int breakoutLookback,
            int volumeLookback,
            long minLiquidityVolume,
            BigDecimal maxIvPercent,
            BigDecimal totalCapital,
            BigDecimal maxRiskPerTradePercent,
            int lotSize,
            boolean rsiFilterEnabled,
            int maxHoldMinutes,
            long instrumentToken,
            String tradingSymbol,
            LocalDate expiry,
            BigDecimal strike,
            int candlesWritten,
            String downloadedInputPath,
            String archivedRangePath,
            String archivedCumulativePath,
            int cumulativeCandles,
            String archivedSideCumulativePath,
            int sideCumulativeCandles,
            String snapshotInputPath,
            String outputDirectory,
            String reportHtml,
            String manifestPath,
            BacktestMetrics metrics
    ) {}

    public record VariantRankingRow(
            String variant,
            String variantSlug,
            int runCount,
            int okRunCount,
            int totalTrades,
            BigDecimal cumulativePnl,
            BigDecimal averageExpectancy,
            BigDecimal averageWinRatePercent,
            BigDecimal worstMaxDrawdown,
            String bestRunOutputDirectory
    ) {}

    public record VariantDecisionRow(
            String variant,
            String variantSlug,
            int runCount,
            int okRunCount,
            int totalTrades,
            BigDecimal cumulativePnl,
            BigDecimal averageExpectancy,
            BigDecimal averageWinRatePercent,
            BigDecimal worstMaxDrawdown,
            BigDecimal drawdownCapitalPercent,
            BigDecimal pnlToDrawdown,
            BigDecimal positiveMonthPercent,
            BigDecimal worstMonthPnl,
            BigDecimal positiveWeekPercent,
            BigDecimal worstWeekPnl,
            BigDecimal oneMinutePnl,
            BigDecimal fiveMinutePnl,
            BigDecimal peFiveMinutePnl,
            String recommendation
    ) {}

    private record PeriodStability(
            int positivePeriods,
            int negativePeriods,
            BigDecimal positivePercent,
            BigDecimal worstPnl
    ) {}

    private record BucketDecisionRow(
            String bucket,
            int runCount,
            int profitableRuns,
            int totalTrades,
            BigDecimal cumulativePnl,
            BigDecimal averageExpectancy,
            BigDecimal averageWinRatePercent
    ) {}

    public record PeriodPerformanceRow(
            String periodType,
            String period,
            LocalDate periodStart,
            LocalDate periodEnd,
            String window,
            String variant,
            String variantSlug,
            UnderlyingSymbol underlying,
            OptionType optionType,
            Timeframe timeframe,
            int tradeDays,
            BigDecimal totalPnl,
            BigDecimal bestDayPnl,
            BigDecimal worstDayPnl,
            BigDecimal averageDayPnl,
            String runOutputDirectory
    ) {}

    public record InputManifest(
            String suiteId,
            String variant,
            String variantSlug,
            String window,
            LocalDate from,
            LocalDate to,
            long instrumentToken,
            String instrumentKey,
            String tradingSymbol,
            LocalDate expiry,
            BigDecimal strike,
            OptionType optionType,
            Timeframe timeframe,
            int candlesWritten,
            String downloadedInputPath,
            String archivedRangePath,
            String archivedCumulativePath,
            String archivedSideCumulativePath,
            int sideCumulativeCandles,
            String snapshotInputPath,
            BacktestMetrics metrics
    ) {
        InputManifest(String suiteId, String variant, String variantSlug, String window, LocalDate from, LocalDate to,
                      HistoricalDataDownloadService.DownloadResult download,
                      HistoricalDataDownloadService.ArchiveResult archive, Path snapshotInputPath,
                      BacktestMetrics metrics) {
            this(suiteId, variant, variantSlug, window, from, to, download.instrumentToken(), download.instrumentKey(),
                    download.tradingSymbol(), download.expiry(), download.strike(), download.optionType(),
                    download.timeframe(), download.candlesWritten(), download.outputPath().toString(),
                    archivePath(archive, ArchivePath.RANGE), archivePath(archive, ArchivePath.CONTRACT_CUMULATIVE),
                    archivePath(archive, ArchivePath.SIDE_CUMULATIVE), archiveCount(archive, true),
                    snapshotInputPath.toString(), metrics);
        }
    }
}
