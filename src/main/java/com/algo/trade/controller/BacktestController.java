package com.algo.trade.controller;

import com.algo.trade.backtest.BacktestDataFileResolver;
import com.algo.trade.backtest.BacktestEngine;
import com.algo.trade.backtest.BacktestMetrics;
import com.algo.trade.backtest.GlobalDataFeedsOptionConverter;
import com.algo.trade.backtest.BacktestMonthDiagnosticsService;
import com.algo.trade.backtest.BacktestRunResult;
import com.algo.trade.backtest.BacktestSuiteService;
import com.algo.trade.backtest.HistoricalDataDownloadService;
import com.algo.trade.backtest.StrategyVerificationResult;
import com.algo.trade.backtest.VerifyAllRequest;
import com.algo.trade.backtest.VerifyAllResult;
import com.algo.trade.backtest.VerifyAllService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.persistence.BacktestResultEntity;
import com.algo.trade.persistence.BacktestResultRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BacktestController {

    private static final Logger log = LoggerFactory.getLogger(BacktestController.class);

    private final BacktestResultRepository backtestResultRepository;
    private final BacktestEngine backtestEngine;
    private final HistoricalDataDownloadService downloadService;
    private final BacktestMonthDiagnosticsService diagnosticsService;
    private final TradingProperties properties;
    private final BacktestSuiteService suiteService;
    private final InstrumentCache instrumentCache;
    private final VerifyAllService verifyAllService;

    public BacktestController(BacktestResultRepository backtestResultRepository, BacktestEngine backtestEngine,
                               HistoricalDataDownloadService downloadService, BacktestMonthDiagnosticsService diagnosticsService, TradingProperties properties,
                               BacktestSuiteService suiteService, InstrumentCache instrumentCache,
                               VerifyAllService verifyAllService) {
        this.backtestResultRepository = backtestResultRepository;
        this.backtestEngine = backtestEngine;
        this.downloadService = downloadService;
        this.diagnosticsService = diagnosticsService;
        this.properties = properties;
        this.suiteService = suiteService;
        this.instrumentCache = instrumentCache;
        this.verifyAllService = verifyAllService;
    }

    @PostMapping("/backtest/run")
    public ResponseEntity<?> run(@RequestBody(required = false) RunBacktestRequest request) {
        Timeframe timeframe = request == null ? null : request.timeframe();
        OptionType optionType = request == null || request.optionType() == null ? OptionType.CE : request.optionType();
        UnderlyingSymbol underlying = request == null || request.underlying() == null ? UnderlyingSymbol.NIFTY
                : request.underlying();
        LocalDate from = request == null || request.from() == null ? null : request.from();
        LocalDate to = request == null || request.to() == null ? null : request.to();
        log.info("Backtest run endpoint called: underlying={}, timeframe={}, optionType={}, from={}, to={}",
                underlying, timeframe, optionType, from, to);
        try {
            BacktestRunResult result = from == null || to == null
                    ? (timeframe == null
                    ? backtestEngine.run(underlying, optionType)
                    : backtestEngine.run(underlying, timeframe, optionType))
                    : backtestEngine.run(underlying,
                    timeframe == null ? properties.backtest().candleTimeframe() : timeframe,
                    optionType, from, to);
            BacktestResultEntity entity = new BacktestResultEntity(result.id(), result.createdAt(),
                    result.metrics().totalTrades(), result.metrics().winRatePercent(), result.metrics().expectancy(),
                    result.metrics().maxDrawdown(), result.metrics().cumulativePnl(), result.outputDirectory().toString());
            BacktestResultEntity saved = backtestResultRepository.save(entity);
            log.info("Backtest run endpoint completed: id={}, totalTrades={}, cumulativePnl={}, outputDirectory={}",
                    saved.getId(), saved.getTotalTrades(), saved.getCumulativePnl(), saved.getOutputPath());
            return ResponseEntity.ok(saved);
        } catch (IllegalStateException ex) {
            log.warn("Backtest run rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    /**
     * Comprehensive backtest: runs all strategies (DIRECTIONAL_BUY, SCALPING, VOLATILITY_BREAKOUT)
     * crossed with parameter variants using the full suite infrastructure. Data is sourced from
     * global-datafeeds imports (with Zerodha download fallback). Results are grouped under
     * {@code suites/RUNALL-{underlying}-{timestamp}/}.
     */
    @PostMapping("/backtest/run-all")
    public ResponseEntity<?> runAll(@RequestBody(required = false) RunAllRequest request) {
        RunAllRequest req = request != null ? request : new RunAllRequest(null, null, null, null, null);
        UnderlyingSymbol underlying = req.underlying() != null ? req.underlying() : UnderlyingSymbol.NIFTY;
        Timeframe timeframe = req.timeframe() != null ? req.timeframe() : Timeframe.FIVE_MINUTE;
        LocalDate from = req.from() != null ? req.from() : properties.backtest().from();
        LocalDate to = req.to() != null ? req.to() : properties.backtest().to();
        List<String> strategies = req.strategies() != null && !req.strategies().isEmpty()
                ? req.strategies()
                : List.of("DIRECTIONAL_BUY", "SCALPING", "VOLATILITY_BREAKOUT");

        log.info("Backtest run-all called: underlying={}, timeframe={}, from={}, to={}, strategies={}",
                underlying, timeframe, from, to, strategies);

        List<BacktestSuiteService.SuiteVariant> variants = suiteService.runAllVariants(strategies);
        BacktestSuiteService.SuiteRequest suiteRequest = new BacktestSuiteService.SuiteRequest(
                underlying,
                to,
                List.of(new BacktestSuiteService.SuiteWindow("full-range", from, to)),
                List.of(OptionType.CE, OptionType.PE),
                List.of(timeframe),
                variants,
                null, null, null
        );

        try {
            BacktestSuiteService.SuiteResult result = suiteService.run(suiteRequest);
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("status", "ok");
            summary.put("suiteId", result.suiteId());
            summary.put("suiteDirectory", result.outputDirectory());
            summary.put("underlying", underlying);
            summary.put("timeframe", timeframe);
            summary.put("from", from);
            summary.put("to", to);
            summary.put("strategies", strategies);
            summary.put("totalVariants", variants.size());
            summary.put("totalRuns", result.runs().size());
            summary.put("reportHtml", result.reportHtml());
            summary.put("summaryCsv", result.summaryCsv());
            summary.put("rankingCsv", result.rankingCsv());

            int totalTrades = 0;
            BigDecimal totalPnl = BigDecimal.ZERO;
            List<Map<String, Object>> runs = new ArrayList<>();
            for (BacktestSuiteService.SuiteRunResult run : result.runs()) {
                Map<String, Object> runMap = new LinkedHashMap<>();
                runMap.put("variant", run.variant());
                runMap.put("window", run.window());
                runMap.put("optionType", run.optionType());
                runMap.put("timeframe", run.timeframe());
                runMap.put("status", run.status());
                runMap.put("backtestId", run.backtestId());
                if (run.metrics() != null) {
                    runMap.put("totalTrades", run.metrics().totalTrades());
                    runMap.put("winRatePercent", run.metrics().winRatePercent());
                    runMap.put("expectancy", run.metrics().expectancy());
                    runMap.put("maxDrawdown", run.metrics().maxDrawdown());
                    runMap.put("cumulativePnl", run.metrics().cumulativePnl());
                    totalTrades += run.metrics().totalTrades();
                    totalPnl = totalPnl.add(run.metrics().cumulativePnl());
                }
                if (run.reportHtml() != null) {
                    runMap.put("reportUrl", "/backtest/results/" + run.backtestId() + "/report");
                }
                if (run.errorMessage() != null) {
                    runMap.put("error", run.errorMessage());
                }
                runs.add(runMap);
            }
            summary.put("totalTrades", totalTrades);
            summary.put("totalPnl", totalPnl);
            summary.put("runs", runs);

            log.info("Backtest run-all completed: suiteId={}, strategies={}, variants={}, runs={}, totalTrades={}, totalPnl={}",
                    result.suiteId(), strategies, variants.size(), result.runs().size(), totalTrades, totalPnl);
            return ResponseEntity.ok(summary);
        } catch (IllegalStateException | IllegalArgumentException ex) {
            log.warn("Backtest run-all rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest run-all failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest run-all failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    public record RunAllRequest(
            UnderlyingSymbol underlying,
            Timeframe timeframe,
            LocalDate from,
            LocalDate to,
            List<String> strategies  // null = all three: DIRECTIONAL_BUY, SCALPING, VOLATILITY_BREAKOUT
    ) {}

    @PostMapping("/backtest/replay-month")
    public ResponseEntity<?> replayMonth(@RequestBody(required = false) ReplayMonthRequest request) {
        ReplayMonthRequest effectiveRequest = request == null
                ? new ReplayMonthRequest(null, null, null, null, null)
                : request;
        try {
            ReplayMonthContext context = replayMonthContext(effectiveRequest);
            log.info("Backtest replay-month endpoint called: underlying={}, optionTypes={}, timeframe={}, month={}, from={}, to={}",
                    context.underlying(), context.optionTypes(), context.timeframe(), context.month(), context.from(), context.to());
            return ResponseEntity.ok(runReplayMonth(context));
        } catch (IllegalStateException ex) {
            log.warn("Backtest replay-month rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/backtest/prepare-and-replay-month")
    public ResponseEntity<?> prepareAndReplayMonth(@RequestBody(required = false) ReplayMonthRequest request) {
        ReplayMonthRequest effectiveRequest = request == null
                ? new ReplayMonthRequest(null, null, null, null, null)
                : request;
        try {
            ReplayMonthContext context = replayMonthContext(effectiveRequest);
            log.info("Backtest prepare-and-replay-month called: underlying={}, optionTypes={}, timeframe={}, month={}, from={}, to={}",
                    context.underlying(), context.optionTypes(), context.timeframe(), context.month(), context.from(), context.to());
            Map<String, Object> preparation = prepareReplayMonthInputs(context);
            Map<String, Object> replay = runReplayMonth(context);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("prepared", preparation);
            body.put("replay", replay);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException ex) {
            log.warn("Backtest prepare-and-replay-month rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest prepare-and-replay-month failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest prepare-and-replay-month failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/backtest/convert-and-replay-month")
    public ResponseEntity<?> convertAndReplayMonth(@RequestBody(required = false) ConvertAndReplayMonthRequest request) {
        ConvertAndReplayMonthRequest effectiveRequest = request == null
                ? new ConvertAndReplayMonthRequest(null, null, null, null, null, null)
                : request;
        try {
            ReplayMonthContext context = replayMonthContext(new ReplayMonthRequest(
                    effectiveRequest.underlying(),
                    null,
                    effectiveRequest.optionTypes(),
                    effectiveRequest.timeframe(),
                    effectiveRequest.month()
            ));
            Path workspaceRoot = workspaceRoot(effectiveRequest, context);
            Path importsRoot = workspaceRoot.resolve("imports");
            Path globalDatafeedsRoot = importsRoot.resolve("global-datafeeds");
            Path resultsRoot = workspaceRoot.resolve("results");
            Path csvImportPath = importsRoot.resolve("input.csv");
            Files.createDirectories(importsRoot);
            Files.createDirectories(resultsRoot);

            Path sourceRoot = effectiveRequest.sourceRoot() == null || effectiveRequest.sourceRoot().isBlank()
                    ? Path.of("C:\\data\\Nifty _Option_15.04.2025_to_15.04.2026_1 _Min_data\\2026")
                    : Path.of(effectiveRequest.sourceRoot());
            log.info("Backtest convert-and-replay-month called: sourceRoot={}, workspaceRoot={}, underlying={}, optionTypes={}, timeframe={}, month={}",
                    sourceRoot, workspaceRoot, context.underlying(), context.optionTypes(), context.timeframe(), context.month());

            GlobalDataFeedsOptionConverter.convert(new GlobalDataFeedsOptionConverter.Config(
                    sourceRoot,
                    globalDatafeedsRoot,
                    context.underlying().name(),
                    Timeframe.ONE_MINUTE
            ));

            TradingProperties workspaceProperties = overrideBacktestPaths(csvImportPath.toString(), resultsRoot.toString());
            Map<String, Object> preparation = prepareReplayMonthInputs(context, workspaceProperties);
            Map<String, Object> replay = runReplayMonth(context, workspaceProperties);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("sourceRoot", sourceRoot.toString());
            body.put("workspaceRoot", workspaceRoot.toString());
            body.put("importsRoot", importsRoot.toString());
            body.put("resultsRoot", resultsRoot.toString());
            body.put("prepared", preparation);
            body.put("replay", replay);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException ex) {
            log.warn("Backtest convert-and-replay-month rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest convert-and-replay-month failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest convert-and-replay-month failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/backtest/replay-month-preflight")
    public ResponseEntity<?> replayMonthPreflight(@RequestBody(required = false) ReplayMonthRequest request) {
        ReplayMonthRequest effectiveRequest = request == null
                ? new ReplayMonthRequest(null, null, null, null, null)
                : request;
        UnderlyingSymbol underlying = effectiveRequest.underlying() == null ? UnderlyingSymbol.NIFTY : effectiveRequest.underlying();
        List<OptionType> optionTypes = replayMonthOptionTypes(effectiveRequest);
        Timeframe timeframe = effectiveRequest.timeframe() == null ? Timeframe.ONE_MINUTE : effectiveRequest.timeframe();
        YearMonth month = effectiveRequest.month() == null ? YearMonth.now(properties.timezone()) : effectiveRequest.month();
        List<Map<String, Object>> requiredFiles = new ArrayList<>();
        requiredFiles.add(requiredFile("underlying", null,
                BacktestDataFileResolver.forTimeframe(properties.backtest().csvImportPath(), timeframe)));
        for (OptionType optionType : optionTypes) {
            requiredFiles.add(requiredFile("option", optionType,
                    BacktestDataFileResolver.forSelection(properties.backtest().csvImportPath(), underlying,
                            optionType, timeframe)));
        }
        boolean ready = requiredFiles.stream().allMatch(file -> Boolean.TRUE.equals(file.get("exists")));
        List<String> missingPaths = requiredFiles.stream()
                .filter(file -> !Boolean.TRUE.equals(file.get("exists")))
                .map(file -> String.valueOf(file.get("path")))
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", ready ? "ok" : "error");
        body.put("ready", ready);
        body.put("underlying", underlying);
        body.put("optionTypes", optionTypes);
        body.put("timeframe", timeframe);
        body.put("month", month);
        body.put("requiredFiles", requiredFiles);
        body.put("missingPaths", missingPaths);
        body.put("message", ready
                ? "Replay month prerequisites are present."
                : "Replay month is missing required backtest files. Download underlying spot data and matching option data first.");
        return ready ? ResponseEntity.ok(body) : ResponseEntity.badRequest().body(body);
    }

    @PostMapping("/backtest/replay-month-diagnostics")
    public ResponseEntity<?> replayMonthDiagnostics(@RequestBody(required = false) ReplayMonthRequest request) {
        ReplayMonthRequest effectiveRequest = request == null
                ? new ReplayMonthRequest(null, null, null, null, null)
                : request;
        try {
            ReplayMonthContext context = replayMonthContext(effectiveRequest);
            return ResponseEntity.ok(diagnosticsService.diagnose(context.underlying(), context.optionTypes(),
                    context.timeframe(), context.from(), context.to()));
        } catch (IllegalStateException ex) {
            log.warn("Backtest replay-month-diagnostics rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    /**
     * Downloads Zerodha historical candles and writes them to the backtest CSV input file.
     * Requires a valid Kite access token (LIVE or PAPER+ZERODHA market-data mode).
     *
     * Example:
     * POST /backtest/download-data
     * { "instrumentToken": "256265", "from": "2024-01-01", "to": "2025-01-01", "timeframe": "ONE_MINUTE" }
     *
     * Common NIFTY tokens: 256265 (NSE:NIFTY 50 index), 11924994 (NIFTY futures front-month)
     */
    @PostMapping("/backtest/download-data")
    public ResponseEntity<Map<String, Object>> downloadData(@RequestBody DownloadDataRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "request body is required"));
        }
        log.info("Backtest download-data requested: token={}, from={}, to={}, timeframe={}",
                request.instrumentToken(), request.from(), request.to(), request.timeframe());
        try {
            Timeframe tf = request.timeframe() != null ? request.timeframe() : Timeframe.ONE_MINUTE;
            int count = downloadService.download(request.instrumentToken(), request.from(), request.to(), tf);
            Path outputPath = downloadService.outputPath(request.instrumentToken(), tf);
            log.info("Backtest download-data completed: token={}, candles={}", request.instrumentToken(), count);
            return ResponseEntity.ok(Map.of("status", "ok", "candlesWritten", count,
                    "outputPath", outputPath.toString(),
                    "message", "Data written to backtest CSV. Call POST /backtest/run to execute."));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest download-data rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest download-data failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest download-data failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    public record RunBacktestRequest(
            UnderlyingSymbol underlying,
            Timeframe timeframe,
            OptionType optionType,
            LocalDate from,
            LocalDate to
    ) {}

    public record ReplayMonthRequest(
            UnderlyingSymbol underlying,
            OptionType optionType,
            List<OptionType> optionTypes,
            Timeframe timeframe,
            YearMonth month
    ) {}

    private List<OptionType> replayMonthOptionTypes(ReplayMonthRequest request) {
        if (request.optionTypes() != null && !request.optionTypes().isEmpty()) {
            return request.optionTypes();
        }
        if (request.optionType() != null) {
            return List.of(request.optionType());
        }
        return List.of(OptionType.CE, OptionType.PE);
    }

    private Map<String, Object> replayMonthRun(BacktestResultEntity saved, OptionType optionType,
                                               Timeframe timeframe, BacktestRunResult result) {
        Map<String, Object> run = new LinkedHashMap<>();
        run.put("id", saved.getId());
        run.put("optionType", optionType);
        run.put("timeframe", timeframe);
        run.put("totalTrades", result.metrics().totalTrades());
        run.put("winRatePercent", result.metrics().winRatePercent());
        run.put("expectancy", result.metrics().expectancy());
        run.put("maxDrawdown", result.metrics().maxDrawdown());
        run.put("cumulativePnl", result.metrics().cumulativePnl());
        run.put("outputPath", result.outputDirectory().toString());
        run.put("reportPath", result.reportHtml().toString());
        run.put("reportUrl", "/backtest/results/" + saved.getId() + "/report");
        return run;
    }

    private ReplayMonthContext replayMonthContext(ReplayMonthRequest request) {
        UnderlyingSymbol underlying = request.underlying() == null ? UnderlyingSymbol.NIFTY : request.underlying();
        List<OptionType> optionTypes = replayMonthOptionTypes(request);
        Timeframe timeframe = request.timeframe() == null ? Timeframe.ONE_MINUTE : request.timeframe();
        YearMonth month = request.month() == null ? YearMonth.now(properties.timezone()) : request.month();
        return new ReplayMonthContext(underlying, optionTypes, timeframe, month, month.atDay(1), month.atEndOfMonth());
    }

    private Map<String, Object> runReplayMonth(ReplayMonthContext context) {
        return runReplayMonth(context, properties);
    }

    private Map<String, Object> runReplayMonth(ReplayMonthContext context, TradingProperties activeProperties) {
        List<Map<String, Object>> runs = new ArrayList<>();
        int totalTrades = 0;
        BigDecimal cumulativePnl = BigDecimal.ZERO;
        BigDecimal weightedWinRate = BigDecimal.ZERO;
        BigDecimal weightedExpectancy = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (OptionType optionType : context.optionTypes()) {
            BacktestRunResult result = backtestEngine.run(context.underlying(), context.timeframe(), optionType,
                    context.from(), context.to(), new BacktestEngine.RunOptions(activeProperties,
                            activeProperties.backtest().outputDirectory(), "BT"));
            BacktestResultEntity entity = new BacktestResultEntity(result.id(), result.createdAt(),
                    result.metrics().totalTrades(), result.metrics().winRatePercent(), result.metrics().expectancy(),
                    result.metrics().maxDrawdown(), result.metrics().cumulativePnl(), result.outputDirectory().toString());
            BacktestResultEntity saved = backtestResultRepository.save(entity);
            runs.add(replayMonthRun(saved, optionType, context.timeframe(), result));
            int runTrades = result.metrics().totalTrades();
            BigDecimal tradeCount = BigDecimal.valueOf(runTrades);
            totalTrades += runTrades;
            cumulativePnl = cumulativePnl.add(result.metrics().cumulativePnl());
            weightedWinRate = weightedWinRate.add(result.metrics().winRatePercent().multiply(tradeCount));
            weightedExpectancy = weightedExpectancy.add(result.metrics().expectancy().multiply(tradeCount));
            if (result.metrics().maxDrawdown().compareTo(maxDrawdown) > 0) {
                maxDrawdown = result.metrics().maxDrawdown();
            }
        }
        BigDecimal totalTradesDecimal = BigDecimal.valueOf(totalTrades);
        BigDecimal winRatePercent = totalTrades == 0
                ? BigDecimal.ZERO
                : weightedWinRate.divide(totalTradesDecimal, 4, RoundingMode.HALF_UP);
        BigDecimal expectancy = totalTrades == 0
                ? BigDecimal.ZERO
                : weightedExpectancy.divide(totalTradesDecimal, 4, RoundingMode.HALF_UP);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "ok");
        body.put("underlying", context.underlying());
        body.put("optionTypes", context.optionTypes());
        body.put("timeframe", context.timeframe());
        body.put("month", context.month());
        body.put("from", context.from());
        body.put("to", context.to());
        body.put("totalTrades", totalTrades);
        body.put("winRatePercent", winRatePercent);
        body.put("expectancy", expectancy);
        body.put("maxDrawdown", maxDrawdown);
        body.put("cumulativePnl", cumulativePnl);
        body.put("runs", runs);
        log.info("Backtest replay-month completed: underlying={}, optionTypes={}, totalTrades={}, cumulativePnl={}, runs={}",
                context.underlying(), context.optionTypes(), totalTrades, cumulativePnl, runs.size());
        return body;
    }

    private Map<String, Object> prepareReplayMonthInputs(ReplayMonthContext context) throws IOException {
        return prepareReplayMonthInputs(context, properties);
    }

    private Map<String, Object> prepareReplayMonthInputs(ReplayMonthContext context,
                                                         TradingProperties activeProperties) throws IOException {
        Map<String, Object> preparation = new LinkedHashMap<>();
        List<Map<String, Object>> steps = new ArrayList<>();

        String underlyingToken = underlyingInstrumentToken(context.underlying());
        int underlyingCandles = downloadService.download(underlyingToken, context.from(), context.to(),
                context.timeframe(), activeProperties);
        Map<String, Object> underlyingStep = new LinkedHashMap<>();
        underlyingStep.put("kind", "underlying");
        underlyingStep.put("underlying", context.underlying());
        underlyingStep.put("instrumentToken", underlyingToken);
        underlyingStep.put("candlesWritten", underlyingCandles);
        underlyingStep.put("outputPath", downloadService.outputPath(activeProperties, context.timeframe()).toString());
        steps.add(underlyingStep);

        for (OptionType optionType : context.optionTypes()) {
            HistoricalDataDownloadService.DownloadResult result = downloadService
                    .prepareImportedOption(context.underlying(), optionType, context.from(), context.to(),
                            context.timeframe(), null, null, activeProperties)
                    .orElseGet(() -> {
                        try {
                            return downloadService.downloadSelectedOption(context.underlying(), optionType,
                                    context.from(), context.to(), context.timeframe(), null, null, null, activeProperties);
                        } catch (IOException ex) {
                            throw new RuntimeException(ex);
                        }
                    });
            Map<String, Object> optionStep = new LinkedHashMap<>();
            optionStep.put("kind", "option");
            optionStep.put("optionType", optionType);
            optionStep.put("instrumentKey", result.instrumentKey());
            optionStep.put("tradingSymbol", result.tradingSymbol());
            optionStep.put("candlesWritten", result.candlesWritten());
            optionStep.put("outputPath", result.outputPath().toString());
            steps.add(optionStep);
        }

        preparation.put("underlying", context.underlying());
        preparation.put("optionTypes", context.optionTypes());
        preparation.put("timeframe", context.timeframe());
        preparation.put("month", context.month());
        preparation.put("from", context.from());
        preparation.put("to", context.to());
        preparation.put("steps", steps);
        return preparation;
    }

    private record ReplayMonthContext(
            UnderlyingSymbol underlying,
            List<OptionType> optionTypes,
            Timeframe timeframe,
            YearMonth month,
            LocalDate from,
            LocalDate to
    ) {
    }

    private Map<String, Object> requiredFile(String kind, OptionType optionType, Path path) {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("kind", kind);
        if (optionType != null) {
            file.put("optionType", optionType);
        }
        file.put("path", path.toString());
        file.put("exists", Files.exists(path));
        return file;
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

    private Path workspaceRoot(ConvertAndReplayMonthRequest request, ReplayMonthContext context) {
        if (request.workspaceRoot() != null && !request.workspaceRoot().isBlank()) {
            return Path.of(request.workspaceRoot());
        }
        String monthToken = context.month().toString().replace("-", "");
        String timeframeToken = context.timeframe().name().toLowerCase().replace('_', '-');
        String runToken = String.valueOf(System.currentTimeMillis());
        return Path.of("C:\\data\\backtest\\workspaces",
                context.underlying().name().toLowerCase() + "-" + monthToken + "-" + timeframeToken + "-" + runToken);
    }

    private TradingProperties overrideBacktestPaths(String csvImportPath, String outputDirectory) {
        TradingProperties.Backtest base = properties.backtest();
        TradingProperties.Backtest overriddenBacktest = new TradingProperties.Backtest(
                base.from(),
                base.to(),
                base.candleTimeframe(),
                csvImportPath,
                outputDirectory,
                base.mockInstrumentKey(),
                base.mockCandleCount(),
                base.lotSize()
        );
        return new TradingProperties(
                properties.mode(),
                properties.marketDataMode(),
                properties.executionMode(),
                properties.liveTradingEnabled(),
                properties.timezone(),
                properties.broker(),
                properties.symbols(),
                properties.strike(),
                properties.entry(),
                properties.exit(),
                properties.risk(),
                properties.paper(),
                properties.safety(),
                properties.telegram(),
                properties.algo(),
                overriddenBacktest
        );
    }

    public record ConvertAndReplayMonthRequest(
            UnderlyingSymbol underlying,
            List<OptionType> optionTypes,
            Timeframe timeframe,
            YearMonth month,
            String sourceRoot,
            String workspaceRoot
    ) {
    }

    public record DownloadDataRequest(String instrumentToken, LocalDate from, LocalDate to, Timeframe timeframe) {}

    @PostMapping("/backtest/download-underlying-data")
    public ResponseEntity<Map<String, Object>> downloadUnderlyingData(@RequestBody DownloadUnderlyingDataRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "request body is required"));
        }
        log.info("Backtest download-underlying-data requested: underlying={}, from={}, to={}, timeframe={}",
                request.underlying(), request.from(), request.to(), request.timeframe());
        try {
            UnderlyingSymbol underlying = request.underlying() != null ? request.underlying() : UnderlyingSymbol.NIFTY;
            Timeframe tf = request.timeframe() != null ? request.timeframe() : Timeframe.ONE_MINUTE;
            String instrumentToken = underlyingInstrumentToken(underlying);
            int count = downloadService.download(instrumentToken, request.from(), request.to(), tf);
            Path outputPath = downloadService.outputPath(tf);
            return ResponseEntity.ok(Map.of(
                    "status", "ok",
                    "underlying", underlying,
                    "instrumentToken", instrumentToken,
                    "candlesWritten", count,
                    "outputPath", outputPath.toString(),
                    "message", "Underlying spot candle data written. Replay month can now use the correct spot series."
            ));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest download-underlying-data rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest download-underlying-data failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest download-underlying-data failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    public record DownloadUnderlyingDataRequest(
            UnderlyingSymbol underlying,
            LocalDate from,
            LocalDate to,
            Timeframe timeframe
    ) {}

    @PostMapping("/backtest/download-option-data")
    public ResponseEntity<Map<String, Object>> downloadOptionData(@RequestBody DownloadOptionDataRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", "request body is required"));
        }
        log.info("Backtest download-option-data requested: underlying={}, optionType={}, from={}, to={}, timeframe={}, expiry={}, strike={}, underlyingPrice={}",
                request.underlying(), request.optionType(), request.from(), request.to(), request.timeframe(),
                request.expiry(), request.strike(), request.underlyingPrice());
        try {
            Timeframe tf = request.timeframe() != null ? request.timeframe() : Timeframe.ONE_MINUTE;
            UnderlyingSymbol underlying = request.underlying() != null ? request.underlying() : UnderlyingSymbol.NIFTY;
            OptionType optionType = request.optionType() != null ? request.optionType() : OptionType.CE;
            HistoricalDataDownloadService.DownloadResult result = downloadService.downloadSelectedOption(
                    underlying, optionType, request.from(), request.to(), tf, request.expiry(), request.strike(),
                    request.underlyingPrice());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", "ok");
            body.put("candlesWritten", result.candlesWritten());
            body.put("instrumentToken", result.instrumentToken());
            body.put("instrumentKey", result.instrumentKey());
            body.put("tradingSymbol", result.tradingSymbol());
            body.put("expiry", result.expiry());
            body.put("strike", result.strike());
            body.put("optionType", result.optionType());
            body.put("timeframe", result.timeframe());
            body.put("outputPath", result.outputPath().toString());
            body.put("message", "Option candle data written. Call POST /backtest/run with the same timeframe.");
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest download-option-data rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest download-option-data failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest download-option-data failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    public record DownloadOptionDataRequest(
            UnderlyingSymbol underlying,
            OptionType optionType,
            LocalDate from,
            LocalDate to,
            Timeframe timeframe,
            LocalDate expiry,
            BigDecimal strike,
            BigDecimal underlyingPrice
    ) {}

    @PostMapping("/backtest/run-suite")
    public ResponseEntity<?> runSuite(@RequestBody(required = false) BacktestSuiteService.SuiteRequest request) {
        BacktestSuiteService.SuiteRequest effectiveRequest = request == null
                ? new BacktestSuiteService.SuiteRequest(null, null, null, null, null, null, null, null, null)
                : request;
        log.info("Backtest suite endpoint called: underlying={}, to={}, windows={}, optionTypes={}, timeframes={}",
                effectiveRequest.underlying(), effectiveRequest.to(), effectiveRequest.windows(),
                effectiveRequest.optionTypes(), effectiveRequest.timeframes());
        try {
            BacktestSuiteService.SuiteResult result = suiteService.run(effectiveRequest);
            log.info("Backtest suite endpoint completed: runs={}", result.runs().size());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            log.warn("Backtest suite rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest suite rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest suite failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest suite failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    /**
     * Single endpoint: prepares option candle data for all requested option types and timeframes,
     * then immediately runs the full variant suite. Local Global Datafeeds imports are used first,
     * with Zerodha download as a fallback. No separate trigger is needed.
     *
     * Minimal example (uses all defaults — NIFTY, CE+PE, 1-min+5-min, today's ATM, all default variants):
     * POST /backtest/analyze-variants
     * {}
     *
     * Full example with explicit option selection:
     * POST /backtest/analyze-variants
     * {
     *   "underlying": "NIFTY",
     *   "optionTypes": ["CE", "PE"],
     *   "timeframes": ["ONE_MINUTE", "FIVE_MINUTE"],
     *   "to": "2026-04-13",
     *   "windows": [
     *     { "name": "1-week",  "from": "2026-04-06", "to": "2026-04-13" },
     *     { "name": "1-month", "from": "2026-03-13", "to": "2026-04-13" }
     *   ],
     *   "expiry": "2026-04-24",
     *   "strike": 23800,
     *   "underlyingPrice": 23850
     * }
     */
    @PostMapping({"/backtest/analyze-variants", "/backtest/download-and-run-suite"})
    public ResponseEntity<?> downloadAndRunSuite(
            @RequestBody(required = false) BacktestSuiteService.SuiteRequest request) {
        BacktestSuiteService.SuiteRequest effectiveRequest = request == null
                ? new BacktestSuiteService.SuiteRequest(null, null, null, null, null, null, null, null, null)
                : request;
        log.info("Backtest analyze-variants called: underlying={}, to={}, optionTypes={}, timeframes={}",
                effectiveRequest.underlying(), effectiveRequest.to(),
                effectiveRequest.optionTypes(), effectiveRequest.timeframes());
        try {
            BacktestSuiteService.SuiteResult result = suiteService.run(effectiveRequest);
            log.info("Backtest analyze-variants completed: suiteId={}, runs={}",
                    result.suiteId(), result.runs().size());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            log.warn("Backtest analyze-variants rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest analyze-variants rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest analyze-variants failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest analyze-variants failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/backtest/analyze-quick")
    public ResponseEntity<?> analyzeQuick(
            @RequestBody(required = false) BacktestSuiteService.SuiteRequest request) {
        BacktestSuiteService.SuiteRequest baseRequest = request == null
                ? new BacktestSuiteService.SuiteRequest(null, null, null, null, null, null, null, null, null)
                : request;
        BacktestSuiteService.SuiteRequest effectiveRequest = new BacktestSuiteService.SuiteRequest(
                baseRequest.underlying(),
                baseRequest.to(),
                baseRequest.windows(),
                baseRequest.optionTypes(),
                baseRequest.timeframes(),
                List.of(new BacktestSuiteService.SuiteVariant("baseline", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                        null, null, null, null, null, null)),
                baseRequest.expiry(),
                baseRequest.strike(),
                baseRequest.underlyingPrice()
        );
        log.info("Backtest analyze-quick called: underlying={}, to={}, windows={}, optionTypes={}, timeframes={}",
                effectiveRequest.underlying(), effectiveRequest.to(), effectiveRequest.windows(),
                effectiveRequest.optionTypes(), effectiveRequest.timeframes());
        try {
            BacktestSuiteService.SuiteResult result = suiteService.run(effectiveRequest);
            log.info("Backtest analyze-quick completed: suiteId={}, runs={}",
                    result.suiteId(), result.runs().size());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            log.warn("Backtest analyze-quick rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest analyze-quick rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest analyze-quick failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest analyze-quick failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/backtest/analyze-pruned")
    public ResponseEntity<?> analyzePruned(
            @RequestBody(required = false) BacktestSuiteService.SuiteRequest request) {
        BacktestSuiteService.SuiteRequest baseRequest = request == null
                ? new BacktestSuiteService.SuiteRequest(null, null, null, null, null, null, null, null, null)
                : request;
        BacktestSuiteService.SuiteRequest effectiveRequest = new BacktestSuiteService.SuiteRequest(
                baseRequest.underlying(),
                baseRequest.to(),
                baseRequest.windows(),
                baseRequest.optionTypes(),
                baseRequest.timeframes(),
                suiteService.recommendedPrunedVariants(),
                baseRequest.expiry(),
                baseRequest.strike(),
                baseRequest.underlyingPrice()
        );
        log.info("Backtest analyze-pruned called: underlying={}, to={}, windows={}, optionTypes={}, timeframes={}, variants={}",
                effectiveRequest.underlying(), effectiveRequest.to(), effectiveRequest.windows(),
                effectiveRequest.optionTypes(), effectiveRequest.timeframes(), effectiveRequest.variants().size());
        try {
            BacktestSuiteService.SuiteResult result = suiteService.run(effectiveRequest);
            log.info("Backtest analyze-pruned completed: suiteId={}, runs={}",
                    result.suiteId(), result.runs().size());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            log.warn("Backtest analyze-pruned rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest analyze-pruned rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest analyze-pruned failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest analyze-pruned failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/backtest/analyze-pruned-120k")
    public ResponseEntity<?> analyzePruned120k(
            @RequestBody(required = false) BacktestSuiteService.SuiteRequest request) {
        BacktestSuiteService.SuiteRequest baseRequest = request == null
                ? new BacktestSuiteService.SuiteRequest(null, null, null, null, null, null, null, null, null)
                : request;
        BacktestSuiteService.SuiteRequest effectiveRequest = new BacktestSuiteService.SuiteRequest(
                baseRequest.underlying(),
                baseRequest.to(),
                baseRequest.windows(),
                baseRequest.optionTypes(),
                baseRequest.timeframes(),
                suiteService.recommendedPruned120kVariants(),
                baseRequest.expiry(),
                baseRequest.strike(),
                baseRequest.underlyingPrice()
        );
        log.info("Backtest analyze-pruned-120k called: underlying={}, to={}, windows={}, optionTypes={}, timeframes={}, variants={}",
                effectiveRequest.underlying(), effectiveRequest.to(), effectiveRequest.windows(),
                effectiveRequest.optionTypes(), effectiveRequest.timeframes(), effectiveRequest.variants().size());
        try {
            BacktestSuiteService.SuiteResult result = suiteService.run(effectiveRequest);
            log.info("Backtest analyze-pruned-120k completed: suiteId={}, runs={}",
                    result.suiteId(), result.runs().size());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            log.warn("Backtest analyze-pruned-120k rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest analyze-pruned-120k rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest analyze-pruned-120k failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest analyze-pruned-120k failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping({"/backtest/analyze-weekend-intensive", "/backtest/analyze-everything"})
    public ResponseEntity<?> analyzeWeekendIntensive(
            @RequestBody(required = false) BacktestSuiteService.SuiteRequest request) {
        BacktestSuiteService.SuiteRequest effectiveRequest = suiteService.weekendIntensiveRequest(request);
        int totalRuns = effectiveRequest.windows().size()
                * effectiveRequest.variants().size()
                * effectiveRequest.optionTypes().size()
                * effectiveRequest.timeframes().size();
        log.info("Backtest analyze-weekend-intensive called: underlying={}, to={}, windows={}, variants={}, optionTypes={}, timeframes={}, totalRuns={}",
                effectiveRequest.underlying(), effectiveRequest.to(), effectiveRequest.windows().size(),
                effectiveRequest.variants().size(), effectiveRequest.optionTypes(), effectiveRequest.timeframes(),
                totalRuns);
        try {
            BacktestSuiteService.SuiteResult result = suiteService.run(effectiveRequest);
            log.info("Backtest analyze-weekend-intensive completed: suiteId={}, runs={}",
                    result.suiteId(), result.runs().size());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            log.warn("Backtest analyze-weekend-intensive rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest analyze-weekend-intensive rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest analyze-weekend-intensive failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest analyze-weekend-intensive failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    @PostMapping("/backtest/analyze-focused-validation")
    public ResponseEntity<?> analyzeFocusedValidation(
            @RequestBody(required = false) BacktestSuiteService.SuiteRequest request) {
        BacktestSuiteService.SuiteRequest effectiveRequest = suiteService.focusedValidationRequest(request);
        int totalRuns = effectiveRequest.windows().size()
                * effectiveRequest.variants().size()
                * effectiveRequest.optionTypes().size()
                * effectiveRequest.timeframes().size();
        log.info("Backtest analyze-focused-validation called: underlying={}, to={}, windows={}, variants={}, optionTypes={}, timeframes={}, totalRuns={}",
                effectiveRequest.underlying(), effectiveRequest.to(), effectiveRequest.windows().size(),
                effectiveRequest.variants().size(), effectiveRequest.optionTypes(), effectiveRequest.timeframes(),
                totalRuns);
        try {
            BacktestSuiteService.SuiteResult result = suiteService.run(effectiveRequest);
            log.info("Backtest analyze-focused-validation completed: suiteId={}, runs={}",
                    result.suiteId(), result.runs().size());
            return ResponseEntity.ok(result);
        } catch (IllegalStateException ex) {
            log.warn("Backtest analyze-focused-validation rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IllegalArgumentException ex) {
            log.warn("Backtest analyze-focused-validation rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (IOException ex) {
            log.warn("Backtest analyze-focused-validation failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        } catch (RuntimeException ex) {
            log.warn("Backtest analyze-focused-validation failed: {}", ex.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("status", "error", "message", ex.getMessage()));
        }
    }

    // ── Verify-All Endpoint ──────────────────────────────────────────────

    @PostMapping("/backtest/verify-all")
    public ResponseEntity<?> verifyAll(@RequestBody(required = false) VerifyAllRequest request) {
        VerifyAllRequest req = request != null ? request.withDefaults() : VerifyAllRequest.defaults();
        log.info("Verify-all called: underlying={}, from={}, to={}", req.underlying(), req.from(), req.to());

        // Validate global-datafeeds directory exists
        Path globalDatafeedsDir = resolveGlobalDatafeedsByDayDir();
        if (!Files.isDirectory(globalDatafeedsDir)) {
            log.warn("Verify-all rejected: global-datafeeds directory not found at {}", globalDatafeedsDir);
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "Global-datafeeds directory not found: " + globalDatafeedsDir
                            + ". Run GlobalDataFeedsOptionConverter first to populate by-day CSVs."));
        }

        // Validate date range has at least one trading day (weekday)
        if (!hasTradingDays(req.from(), req.to())) {
            log.warn("Verify-all rejected: no trading days in range {} to {}", req.from(), req.to());
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "No trading days in date range " + req.from() + " to " + req.to()));
        }

        try {
            long startMs = System.currentTimeMillis();
            VerifyAllResult result = verifyAllService.verifyAll(req);
            long timeTakenMs = System.currentTimeMillis() - startMs;

            // Build a clean JSON response (no Path fields)
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("verifyId", result.verifyId());
            response.put("underlying", result.underlying());
            response.put("from", result.from());
            response.put("to", result.to());
            response.put("reportPath", result.reportHtml() != null ? result.reportHtml().toString() : null);
            response.put("summaryPath", result.summaryJson() != null ? result.summaryJson().toString() : null);

            // Inline summary stats
            int totalStrategies = result.strategyResults().size();
            int totalTrades = 0;
            BigDecimal totalPnl = BigDecimal.ZERO;
            int totalWins = 0;
            int strategiesOk = 0;
            int strategiesError = 0;
            int strategiesNoData = 0;

            for (StrategyVerificationResult sr : result.strategyResults()) {
                BacktestMetrics m = sr.metrics();
                if (m != null) {
                    totalTrades += m.totalTrades();
                    totalPnl = totalPnl.add(m.cumulativePnl());
                    // Approximate wins from winRate and totalTrades
                    if (m.totalTrades() > 0 && m.winRatePercent() != null) {
                        totalWins += m.winRatePercent().multiply(BigDecimal.valueOf(m.totalTrades()))
                                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP).intValue();
                    }
                }
                switch (sr.status()) {
                    case "ok" -> strategiesOk++;
                    case "error" -> strategiesError++;
                    case "no-data" -> strategiesNoData++;
                }
            }

            BigDecimal overallWinRate = totalTrades > 0
                    ? BigDecimal.valueOf(totalWins).multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalTrades), 2, RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;

            response.put("totalStrategies", totalStrategies);
            response.put("strategiesOk", strategiesOk);
            response.put("strategiesError", strategiesError);
            response.put("strategiesNoData", strategiesNoData);
            response.put("totalTrades", totalTrades);
            response.put("totalPnl", totalPnl);
            response.put("overallWinRate", overallWinRate);
            response.put("timeTakenMs", timeTakenMs);

            log.info("Verify-all completed: id={}, strategies={}, trades={}, pnl={}, time={}ms",
                    result.verifyId(), totalStrategies, totalTrades, totalPnl, timeTakenMs);
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            log.error("Verify-all failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    /** Resolve the global-datafeeds by-day directory from backtest config. */
    private Path resolveGlobalDatafeedsByDayDir() {
        Path importDir = Path.of(properties.backtest().csvImportPath()).getParent();
        if (importDir == null) {
            importDir = Path.of("C:/data/backtest/imports");
        }
        return importDir.resolve("global-datafeeds").resolve("by-day");
    }

    /** Check if the date range contains at least one weekday (trading day). */
    private static boolean hasTradingDays(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            return false;
        }
        LocalDate date = from;
        while (!date.isAfter(to)) {
            DayOfWeek dow = date.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY) {
                return true;
            }
            date = date.plusDays(1);
        }
        return false;
    }

    // ── Verify-Today Endpoint ─────────────────────────────────────────────

    /**
     * Runs all 16 strategies for a single day (today or the most recent trading day).
     * Shows what each strategy would have signaled — useful for forward-testing before going live.
     */
    @PostMapping("/backtest/verify-today")
    public ResponseEntity<?> verifyToday(@RequestBody(required = false) VerifyTodayRequest request) {
        UnderlyingSymbol underlying = request != null && request.underlying() != null
                ? request.underlying() : UnderlyingSymbol.NIFTY;
        LocalDate targetDate = request != null && request.date() != null
                ? request.date() : mostRecentTradingDay(LocalDate.now());

        log.info("Verify-today called: date={}, underlying={}", targetDate, underlying);

        // Validate data exists for the target date
        Path byDayDir = resolveGlobalDatafeedsByDayDir();
        Path dayFile = byDayDir.resolve(String.valueOf(targetDate.getYear()))
                .resolve(targetDate + ".csv");
        if (!Files.exists(dayFile)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "status", "error",
                    "message", "No by-day CSV found for " + targetDate + " at " + dayFile
                            + ". Run GlobalDataFeedsOptionConverter for this date first."));
        }

        try {
            // Run verify-all for just this single day
            VerifyAllRequest verifyRequest = new VerifyAllRequest(targetDate, targetDate, underlying);
            long startMs = System.currentTimeMillis();
            VerifyAllResult result = verifyAllService.verifyAll(verifyRequest);
            long timeTakenMs = System.currentTimeMillis() - startMs;

            // Build compact response focused on signals
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", "ok");
            response.put("date", targetDate.toString());
            response.put("dayOfWeek", targetDate.getDayOfWeek().toString());
            response.put("underlying", underlying);
            response.put("verifyId", result.verifyId());
            response.put("timeTakenMs", timeTakenMs);

            // Per-strategy signal summary
            List<Map<String, Object>> strategies = new ArrayList<>();
            for (StrategyVerificationResult sr : result.strategyResults()) {
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("strategy", sr.strategyType().displayName());
                s.put("strategyType", sr.strategyType().name());
                s.put("status", sr.status());
                s.put("signals", sr.entrySignals());
                s.put("rejected", sr.rejectedSignals());
                s.put("trades", sr.metrics() != null ? sr.metrics().totalTrades() : 0);
                s.put("pnl", sr.metrics() != null ? sr.metrics().cumulativePnl() : BigDecimal.ZERO);
                if (!sr.rejectionReasons().isEmpty()) {
                    s.put("rejectionReasons", sr.rejectionReasons());
                }
                if (!sr.strategySpecificMetrics().isEmpty()) {
                    s.put("metrics", sr.strategySpecificMetrics());
                }
                if (sr.sampleTrades() != null && !sr.sampleTrades().isEmpty()) {
                    s.put("sampleTrade", Map.of(
                            "entry", sr.sampleTrades().getFirst().entryPrice(),
                            "exit", sr.sampleTrades().getFirst().exitPrice(),
                            "pnl", sr.sampleTrades().getFirst().pnl(),
                            "exitReason", sr.sampleTrades().getFirst().exitReason()));
                }
                strategies.add(s);
            }
            response.put("strategies", strategies);

            // Quick totals
            int totalSignals = result.strategyResults().stream().mapToInt(StrategyVerificationResult::entrySignals).sum();
            int totalTrades = result.strategyResults().stream()
                    .filter(r -> r.metrics() != null).mapToInt(r -> r.metrics().totalTrades()).sum();
            BigDecimal totalPnl = result.strategyResults().stream()
                    .filter(r -> r.metrics() != null).map(r -> r.metrics().cumulativePnl())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            response.put("totalSignals", totalSignals);
            response.put("totalTrades", totalTrades);
            response.put("totalPnl", totalPnl);

            if (result.reportHtml() != null) {
                response.put("reportPath", result.reportHtml().toString());
            }

            log.info("Verify-today completed: date={}, signals={}, trades={}, pnl={}, time={}ms",
                    targetDate, totalSignals, totalTrades, totalPnl, timeTakenMs);
            return ResponseEntity.ok(response);
        } catch (Exception ex) {
            log.error("Verify-today failed: {}", ex.getMessage(), ex);
            return ResponseEntity.internalServerError().body(Map.of(
                    "status", "error",
                    "message", ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
    }

    /** Find the most recent trading day (weekday) on or before the given date. */
    private static LocalDate mostRecentTradingDay(LocalDate date) {
        LocalDate d = date;
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) {
            d = d.minusDays(1);
        }
        return d;
    }

    record VerifyTodayRequest(LocalDate date, UnderlyingSymbol underlying) {}

    @GetMapping("/backtest/results/{id}")
    public ResponseEntity<BacktestResultEntity> result(@PathVariable String id) {
        log.info("Backtest result requested: id={}", id);
        var result = backtestResultRepository.findById(id);
        if (result.isPresent()) {
            log.info("Backtest result found: id={}, totalTrades={}, cumulativePnl={}",
                    result.get().getId(), result.get().getTotalTrades(), result.get().getCumulativePnl());
            return ResponseEntity.ok(result.get());
        }
        log.warn("Backtest result not found: id={}", id);
        return ResponseEntity.notFound().build();
    }

    @GetMapping(value = "/backtest/results/{id}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(@PathVariable String id) {
        log.info("Backtest HTML report requested: id={}", id);
        var result = backtestResultRepository.findById(id);
        if (result.isEmpty()) {
            log.warn("Backtest result not found for report: id={}", id);
            return ResponseEntity.<String>notFound().build();
        }

        Path reportPath = Path.of(result.get().getOutputPath(), "report.html");
        if (!Files.exists(reportPath)) {
            log.warn("Backtest HTML report file not found: id={}, path={}", id, reportPath);
            return ResponseEntity.<String>notFound().build();
        }

        try {
            String html = Files.readString(reportPath);
            log.info("Backtest HTML report served: id={}, path={}", id, reportPath);
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_HTML)
                    .body(html);
        } catch (IOException ex) {
            log.warn("Backtest HTML report read failed: id={}, message={}", id, ex.getMessage());
            return ResponseEntity.<String>internalServerError().build();
        }
    }
}
