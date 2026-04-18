package com.kiteapioptions.controller;

import com.kiteapioptions.backtest.BacktestEngine;
import com.kiteapioptions.backtest.BacktestRunResult;
import com.kiteapioptions.backtest.BacktestSuiteService;
import com.kiteapioptions.backtest.HistoricalDataDownloadService;
import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.Timeframe;
import com.kiteapioptions.domain.UnderlyingSymbol;
import com.kiteapioptions.persistence.BacktestResultEntity;
import com.kiteapioptions.persistence.BacktestResultRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
    private final TradingProperties properties;
    private final BacktestSuiteService suiteService;

    public BacktestController(BacktestResultRepository backtestResultRepository, BacktestEngine backtestEngine,
                               HistoricalDataDownloadService downloadService, TradingProperties properties,
                               BacktestSuiteService suiteService) {
        this.backtestResultRepository = backtestResultRepository;
        this.backtestEngine = backtestEngine;
        this.downloadService = downloadService;
        this.properties = properties;
        this.suiteService = suiteService;
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

    public record DownloadDataRequest(String instrumentToken, LocalDate from, LocalDate to, Timeframe timeframe) {}

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
                        null, null, null, null, null, null, null, null, null, null, null, null, null)),
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
