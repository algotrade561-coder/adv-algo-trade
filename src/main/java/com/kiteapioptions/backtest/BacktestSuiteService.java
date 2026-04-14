package com.kiteapioptions.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.UnderlyingSymbol;
import com.kiteapioptions.persistence.BacktestResultEntity;
import com.kiteapioptions.persistence.BacktestResultRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class BacktestSuiteService {

    private static final DateTimeFormatter SUITE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final HistoricalDataDownloadService downloadService;
    private final BacktestEngine backtestEngine;
    private final BacktestResultRepository backtestResultRepository;
    private final TradingProperties properties;

    public BacktestSuiteService(HistoricalDataDownloadService downloadService, BacktestEngine backtestEngine,
                                BacktestResultRepository backtestResultRepository, TradingProperties properties) {
        this.downloadService = downloadService;
        this.backtestEngine = backtestEngine;
        this.backtestResultRepository = backtestResultRepository;
        this.properties = properties;
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
        writeSuiteRequest(suiteOutputDirectory, suiteId, underlying, to, windows, optionTypes, timeframes, variants,
                request);

        List<SuiteRunResult> runs = new ArrayList<>();
        for (SuiteWindow window : windows) {
            if (window.from() == null) {
                throw new IllegalArgumentException("suite window from is required: " + window.name());
            }
            LocalDate from = window.from();
            LocalDate windowTo = window.to() != null ? window.to() : to;
            if (from.isAfter(windowTo)) {
                throw new IllegalArgumentException("suite window from must be on or before to: " + window.name());
            }
            for (SuiteVariant variant : variants) {
                for (OptionType optionType : optionTypes) {
                    for (Timeframe timeframe : timeframes) {
                        try {
                            runs.add(runOne(suiteId, suiteOutputDirectory, underlying, optionType, timeframe, window,
                                    from, windowTo, request.expiry(), request.strike(), request.underlyingPrice(),
                                    variant));
                        } catch (RuntimeException | IOException ex) {
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
        return new SuiteResult(suiteId, underlying, suiteOutputDirectory.toString(), summaryCsv.toString(),
                summaryJson.toString(), rankingCsv.toString(), runs);
    }

    private SuiteRunResult runOne(String suiteId, Path suiteOutputDirectory, UnderlyingSymbol underlying,
                                  OptionType optionType, Timeframe timeframe, SuiteWindow window, LocalDate from,
                                  LocalDate to, LocalDate expiry, BigDecimal strike, BigDecimal underlyingPrice,
                                  SuiteVariant variant) throws IOException {
        TradingProperties variantProperties = applyVariant(variant);
        HistoricalDataDownloadService.DownloadResult download = downloadService.downloadSelectedOption(
                underlying, optionType, from, to, timeframe, expiry, strike, underlyingPrice, variantProperties);
        String windowSlug = shortSlug(window.name(), 12);
        String variantSlug = variant.slug();
        Path runRoot = suiteOutputDirectory.resolve("v-" + variantSlug).resolve("w-" + windowSlug);
        BacktestRunResult result = backtestEngine.run(underlying, timeframe, optionType, from, to,
                new BacktestEngine.RunOptions(variantProperties, runRoot.toString(),
                        suiteId + "-" + variantSlug + "-" + windowSlug));
        BacktestResultEntity saved = backtestResultRepository.save(toEntity(result));
        Path snapshotPath = snapshotInput(result, download);
        HistoricalDataDownloadService.ArchiveResult archive = from.equals(to)
                ? downloadService.archiveDownloadedOption(underlying, optionType, timeframe, download, from, to)
                : null;
        Path manifestPath = writeManifest(result, suiteId, variant, window, from, to, download, archive, snapshotPath);
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

    private List<VariantRankingRow> rankingRows(List<SuiteRunResult> runs) {
        return runs.stream()
                .collect(Collectors.groupingBy(SuiteRunResult::variantSlug, LinkedHashMap::new, Collectors.toList()))
                .values().stream()
                .map(this::rankingRow)
                .sorted(Comparator.comparing(VariantRankingRow::cumulativePnl).reversed()
                        .thenComparing(VariantRankingRow::averageExpectancy).reversed())
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
                        baseEntry.entryStartTime(),
                        firstNonNull(variant.entryCutoffTime(), baseEntry.entryCutoffTime()),
                        baseEntry.allowFirstMinutesEntry(),
                        baseEntry.noEntryFirstMinutes()
                ),
                new TradingProperties.Exit(
                        firstNonNull(variant.stopLossPercent(), baseExit.stopLossPercent()),
                        firstNonNull(variant.targetPercent(), baseExit.targetPercent()),
                        firstNonNull(variant.trailingStopActivationPercent(), baseExit.trailingStopActivationPercent()),
                        firstNonNull(variant.trailingGapPercent(), baseExit.trailingGapPercent()),
                        baseExit.forcedExitTime(),
                        baseExit.partialProfitBookingEnabled()
                ),
                new TradingProperties.Risk(
                        firstNonNull(variant.totalCapital(), baseRisk.totalCapital()),
                        firstNonNull(variant.maxRiskPerTradePercent(), baseRisk.maxRiskPerTradePercent()),
                        baseRisk.maxDailyLossPercent(),
                        baseRisk.maxTradesPerDay(),
                        baseRisk.maxOrdersPerDay(),
                        baseRisk.maxConsecutiveLosses(),
                        baseRisk.oneOpenTradeAtATime(),
                        baseRisk.sameInstrumentReentryMinPriceMovePercent(),
                        baseRisk.cooldownMinutes()
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
                new SuiteVariant("baseline", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null),

                // --- Capital variants ---
                new SuiteVariant("cap-30k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(30_000), BigDecimal.ONE, null, null),
                new SuiteVariant("cap-30k-risk-1-5", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(30_000), new BigDecimal("1.5"), null, null),
                new SuiteVariant("cap-50k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(50_000), BigDecimal.ONE, null, null),
                new SuiteVariant("cap-60k-risk-1", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                new SuiteVariant("cap-60k-risk-2", null, null, null, null, null, null, null, null, null,
                        null, null, BigDecimal.valueOf(60_000), BigDecimal.valueOf(2), null, null),

                // --- Target variants ---
                new SuiteVariant("target-15", null, BigDecimal.valueOf(15), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                new SuiteVariant("target-16", null, BigDecimal.valueOf(16), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                new SuiteVariant("target-18", null, BigDecimal.valueOf(18), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),
                new SuiteVariant("target-25", null, BigDecimal.valueOf(25), null, null, null, null, null, null, null,
                        null, null, null, null, null, null),

                // --- Stop variants ---
                new SuiteVariant("stop-6-target-12", BigDecimal.valueOf(6), BigDecimal.valueOf(12), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),
                new SuiteVariant("stop-8-target-16", BigDecimal.valueOf(8), BigDecimal.valueOf(16), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),
                new SuiteVariant("stop-12-target-24", BigDecimal.valueOf(12), BigDecimal.valueOf(24), null, null, null,
                        null, null, null, null, null, null, null, null, null, null),

                // --- Trailing stop variants ---
                new SuiteVariant("trail-act-10-gap-5", null, null, BigDecimal.valueOf(10), BigDecimal.valueOf(5),
                        null, null, null, null, null, null, null, null, null, null, null),
                new SuiteVariant("trail-act-15-gap-7", null, null, BigDecimal.valueOf(15), BigDecimal.valueOf(7),
                        null, null, null, null, null, null, null, null, null, null, null),

                // --- Signal score variants ---
                new SuiteVariant("score-75", null, null, null, null, BigDecimal.valueOf(75), null, null, null, null,
                        null, null, null, null, null, null),
                new SuiteVariant("score-80", null, null, null, null, BigDecimal.valueOf(80), null, null, null, null,
                        null, null, null, null, null, null),

                // --- Breakout filter variants ---
                new SuiteVariant("breakout-strict", null, null, null, null, null, new BigDecimal("1.5"),
                        new BigDecimal("0.10"), 7, 7, null, null, null, null, null, null),
                new SuiteVariant("breakout-loose", null, null, null, null, null, new BigDecimal("1.1"),
                        new BigDecimal("0.03"), 3, 3, null, null, null, null, null, null),

                // --- Liquidity variants ---
                new SuiteVariant("liquidity-2x", null, null, null, null, null, null, null, null, null,
                        liq2x, null, null, null, null, null),

                // --- Entry cutoff variants ---
                new SuiteVariant("cutoff-1400", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, LocalTime.of(14, 0)),
                new SuiteVariant("cutoff-1330", null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, LocalTime.of(13, 30)),

                // --- Best combos from suite analysis (cap-60k + tuned params) ---
                new SuiteVariant("cap-60k-target-18", null, BigDecimal.valueOf(18), null, null, null,
                        null, null, null, null, null, null, BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                new SuiteVariant("cap-60k-target-20-score-75", null, BigDecimal.valueOf(20), null, null,
                        BigDecimal.valueOf(75), null, null, null, null, null, null,
                        BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                new SuiteVariant("cap-60k-stop-8-target-16", BigDecimal.valueOf(8), BigDecimal.valueOf(16), null, null,
                        null, null, null, null, null, null, null,
                        BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null),
                new SuiteVariant("cap-60k-trail-act-10-gap-5", null, null, BigDecimal.valueOf(10), BigDecimal.valueOf(5),
                        null, null, null, null, null, null, null,
                        BigDecimal.valueOf(60_000), BigDecimal.ONE, null, null)
        );
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
            LocalTime entryCutoffTime
    ) {
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
