package com.algo.trade.backtest.v2;

import com.algo.trade.backtest.BacktestMetrics;
import com.algo.trade.backtest.BacktestRunResult;
import com.algo.trade.backtest.BacktestTrade;
import com.algo.trade.backtest.EquityCurvePoint;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Main orchestrator for V2 snapshot-based backtesting.
 * Coordinates snapshot loading, validation, strategy execution, and result aggregation.
 */
@Service
public class BacktestOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(BacktestOrchestrator.class);
    private static final DateTimeFormatter RUN_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int DEFAULT_LOT_SIZE_NIFTY = 65; // NSE circular Jan 2026 (was 75)
    private static final int DEFAULT_LOT_SIZE_BANKNIFTY = 30;

    private final SnapshotLoader snapshotLoader;
    private final SnapshotValidator snapshotValidator;
    private final StrategyConfigService strategyConfigService;
    private final TradingProperties tradingProperties;
    private final Map<StrategyType, StrategyAdapter> adapterMap;

    public BacktestOrchestrator(SnapshotLoader snapshotLoader,
                                 SnapshotValidator snapshotValidator,
                                 StrategyConfigService strategyConfigService,
                                 TradingProperties tradingProperties,
                                 List<StrategyAdapter> adapters) {
        this.snapshotLoader = snapshotLoader;
        this.snapshotValidator = snapshotValidator;
        this.strategyConfigService = strategyConfigService;
        this.tradingProperties = tradingProperties;
        this.adapterMap = adapters.stream()
                .collect(Collectors.toMap(StrategyAdapter::strategyType, a -> a));
        log.info("[BacktestOrchestrator] Initialized with {} strategy adapters: {}",
                adapterMap.size(), adapterMap.keySet());
    }

    /**
     * Run a single strategy backtest using option chain snapshots.
     */
    public BacktestRunResult runStrategy(BacktestRequest request) {
        validateRequest(request);

        String runId = generateRunId(request);
        Path outputDir = resolveOutputDir(runId);
        createOutputDir(outputDir);

        log.info("[BacktestV2] Starting: runId={}, strategy={}, underlying={}, from={}, to={}",
                runId, request.strategy(), request.underlying(), request.from(), request.to());

        // Load snapshots
        List<ChainSnapshot> snapshots = snapshotLoader.loadRange(
                request.from(), request.to(), request.underlying());

        if (snapshots.isEmpty()) {
            log.warn("[BacktestV2] No snapshot data found for {} from {} to {}",
                    request.underlying(), request.from(), request.to());
            return emptyResult(runId, outputDir);
        }

        // Validate data quality
        SnapshotValidator.ValidationResult validation = snapshotValidator.validateDay(snapshots);
        if (!validation.usable()) {
            log.warn("[BacktestV2] Data quality too low: {}/{} valid snapshots",
                    validation.validCount(), validation.validCount() + validation.invalidCount());
            return emptyResult(runId, outputDir);
        }

        // Get strategy adapter and config
        StrategyAdapter adapter = adapterMap.get(request.strategy());
        if (adapter == null) {
            throw new IllegalArgumentException("No adapter found for strategy: " + request.strategy());
        }

        StrategyConfig config = strategyConfigService.getConfig(
                request.strategy(), request.underlying());

        // Run backtest
        int lotSize = getLotSize(request.underlying());
        TradeSimulator simulator = new TradeSimulator();
        BacktestRunner runner = new BacktestRunner(adapter, simulator, snapshotValidator, lotSize);

        BacktestRunResult result = runner.run(snapshots, config, runId, outputDir);

        log.info("[BacktestV2] Completed: runId={}, trades={}, winRate={}%, pnl={}, maxDD={}",
                runId, result.metrics().totalTrades(), result.metrics().winRatePercent(),
                result.metrics().cumulativePnl(), result.metrics().maxDrawdown());

        return result;
    }

    /**
     * Run all available strategies and return aggregated results.
     */
    public Map<StrategyType, BacktestRunResult> runAllStrategies(String underlying,
                                                                   LocalDate from, LocalDate to) {
        Map<StrategyType, BacktestRunResult> results = new LinkedHashMap<>();

        for (StrategyType strategyType : adapterMap.keySet()) {
            try {
                BacktestRequest request = new BacktestRequest(underlying, from, to, strategyType);
                BacktestRunResult result = runStrategy(request);
                results.put(strategyType, result);
            } catch (Exception e) {
                log.error("[BacktestV2] Strategy {} failed: {}", strategyType, e.getMessage());
            }
        }

        log.info("[BacktestV2] All strategies completed: {}/{} succeeded",
                results.size(), adapterMap.size());
        return results;
    }

    /**
     * Get list of available strategy adapters.
     */
    public Set<StrategyType> availableStrategies() {
        return Collections.unmodifiableSet(adapterMap.keySet());
    }

    /**
     * Check data availability for a date range.
     */
    public DataAvailability checkDataAvailability(String underlying, LocalDate from, LocalDate to) {
        int totalDays = countWeekdays(from, to);
        int availableDays = snapshotLoader.countAvailableDays(from, to, underlying);
        double coverage = totalDays > 0 ? (double) availableDays / totalDays * 100 : 0;

        return new DataAvailability(underlying, from, to, totalDays, availableDays, coverage);
    }

    // ── Request / Response records ──────────────────────────────────────────────

    public record BacktestRequest(
            String underlying,
            LocalDate from,
            LocalDate to,
            StrategyType strategy
    ) {}

    public record DataAvailability(
            String underlying,
            LocalDate from,
            LocalDate to,
            int totalWeekdays,
            int daysWithData,
            double coveragePercent
    ) {}

    // ── Private helpers ─────────────────────────────────────────────────────────

    private void validateRequest(BacktestRequest request) {
        Objects.requireNonNull(request, "Request cannot be null");
        Objects.requireNonNull(request.underlying(), "Underlying is required");
        Objects.requireNonNull(request.from(), "From date is required");
        Objects.requireNonNull(request.to(), "To date is required");
        Objects.requireNonNull(request.strategy(), "Strategy is required");

        if (request.from().isAfter(request.to())) {
            throw new IllegalArgumentException("From date must be before to date");
        }
        if (!adapterMap.containsKey(request.strategy())) {
            throw new IllegalArgumentException("No adapter for strategy: " + request.strategy()
                    + ". Available: " + adapterMap.keySet());
        }
    }

    private String generateRunId(BacktestRequest request) {
        return "V2-" + request.strategy().name() + "-" +
                RUN_FMT.format(Instant.now().atZone(tradingProperties.timezone()));
    }

    private Path resolveOutputDir(String runId) {
        return Path.of(tradingProperties.backtest().outputDirectory(), "v2", runId);
    }

    private void createOutputDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception e) {
            log.warn("[BacktestV2] Could not create output dir {}: {}", dir, e.getMessage());
        }
    }

    private BacktestRunResult emptyResult(String runId, Path outputDir) {
        return new BacktestRunResult(runId, Instant.now(),
                new BacktestMetrics(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of()),
                List.of(), List.of(), outputDir,
                outputDir.resolve("trades.csv"), outputDir.resolve("metrics.csv"),
                outputDir.resolve("equity-curve.csv"), outputDir.resolve("report.html"));
    }

    private int getLotSize(String underlying) {
        return switch (underlying) {
            case "BANKNIFTY" -> DEFAULT_LOT_SIZE_BANKNIFTY;
            case "FINNIFTY" -> 25;
            case "MIDCPNIFTY" -> 50;
            case "SENSEX" -> 10;
            default -> DEFAULT_LOT_SIZE_NIFTY;
        };
    }

    private int countWeekdays(LocalDate from, LocalDate to) {
        int count = 0;
        LocalDate current = from;
        while (!current.isAfter(to)) {
            if (current.getDayOfWeek().getValue() <= 5) count++;
            current = current.plusDays(1);
        }
        return count;
    }
}
