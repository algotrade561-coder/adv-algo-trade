package com.algo.trade.backtest;

import com.algo.trade.backtest.ExecutionFlowTracker.ExecutionFlowCoverage;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Orchestrates a comprehensive verification run across all 16 strategy types.
 *
 * <p>For each strategy, loads its {@link StrategyConfig} from H2 (auto-creating
 * defaults if missing), delegates to the appropriate backtest engine, and collects
 * per-strategy results. A single {@link ExecutionFlowTracker} spans the entire run
 * so the coverage report reflects all branches hit across all strategies.</p>
 */
@Service
public class VerifyAllService {

    private static final Logger log = LoggerFactory.getLogger(VerifyAllService.class);
    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Single-leg buying strategies handled by the existing {@link BacktestEngine}. */
    private static final Set<StrategyType> SINGLE_LEG_STRATEGIES = Set.of(
            StrategyType.DIRECTIONAL_BUY,
            StrategyType.SCALPING,
            StrategyType.VOLATILITY_BREAKOUT,
            StrategyType.EVENT_DRIVEN_BUY,
            StrategyType.GAP_AND_GO,
            StrategyType.REVERSAL_BUY,
            StrategyType.OI_SHIFT_TRAP,
            StrategyType.EXPIRY_GAMMA,
            StrategyType.EXPIRY_REVERSAL,
            StrategyType.MOMENTUM,
            StrategyType.ITM_CONVICTION,
            StrategyType.OI_MOMENTUM
    );

    /** Strategies that require live data (ATP) and cannot be backtested. */
    private static final Set<StrategyType> LIVE_ONLY_STRATEGIES = Set.of(
            StrategyType.ITM_CONVICTION,
            StrategyType.OI_MOMENTUM
    );

    private final TradingProperties tradingProperties;
    private final GlobalConfigService globalConfigService;
    private final StrategyConfigService strategyConfigService;
    private final BacktestEngine backtestEngine;
    private final SpreadBacktestEngine spreadBacktestEngine;

    // Nullable — task 6 will create this. If absent, report generation is skipped.
    @Nullable
    private final VerifyAllReportGenerator reportGenerator;

    public VerifyAllService(TradingProperties tradingProperties,
                            GlobalConfigService globalConfigService,
                            StrategyConfigService strategyConfigService,
                            BacktestEngine backtestEngine,
                            SpreadBacktestEngine spreadBacktestEngine,
                            @Nullable VerifyAllReportGenerator reportGenerator) {
        this.tradingProperties = tradingProperties;
        this.globalConfigService = globalConfigService;
        this.strategyConfigService = strategyConfigService;
        this.backtestEngine = backtestEngine;
        this.spreadBacktestEngine = spreadBacktestEngine;
        this.reportGenerator = reportGenerator;
    }

    /**
     * Run a comprehensive verification across all 16 strategy types.
     *
     * @param request verification parameters (from, to, underlying) — nulls filled with defaults
     * @return aggregated result with per-strategy outcomes, flow coverage, and config snapshot
     */
    public VerifyAllResult verifyAll(VerifyAllRequest request) {
        VerifyAllRequest req = request != null ? request.withDefaults() : VerifyAllRequest.defaults();
        Instant startedAt = Instant.now();
        String verifyId = "VERIFY-ALL-" + TIMESTAMP_FMT.format(
                startedAt.atZone(tradingProperties.timezone()));

        log.info("VerifyAll started: id={}, underlying={}, from={}, to={}",
                verifyId, req.underlying(), req.from(), req.to());

        ExecutionFlowTracker tracker = new ExecutionFlowTracker();
        Map<StrategyType, StrategyConfig> configMap = new EnumMap<>(StrategyType.class);
        List<StrategyVerificationResult> strategyResults = new ArrayList<>();

        // ── Iterate all 16 strategy types ─────────────────────────────────
        for (StrategyType strategyType : StrategyType.values()) {
            StrategyConfig config = loadOrCreateConfig(strategyType);
            configMap.put(strategyType, config);

            log.info("VerifyAll: running strategy {} ({}/{})",
                    strategyType, strategyResults.size() + 1, StrategyType.values().length);

            try {
                StrategyVerificationResult result;
                if (LIVE_ONLY_STRATEGIES.contains(strategyType)) {
                    result = new StrategyVerificationResult(
                            strategyType, "skipped", null, List.of(),
                            0, 0, Map.of(), Map.of(),
                            "Live-only strategy — requires ATP data, not backtestable");
                } else if (SINGLE_LEG_STRATEGIES.contains(strategyType)) {
                    result = runSingleLegStrategy(strategyType, config, req, tracker);
                } else {
                    result = runSpreadStrategy(strategyType, config, req, tracker);
                }
                strategyResults.add(result);
                log.info("VerifyAll: {} completed — status={}, trades={}, pnl={}",
                        strategyType, result.status(),
                        result.metrics() != null ? result.metrics().totalTrades() : 0,
                        result.metrics() != null ? result.metrics().cumulativePnl() : "N/A");
            } catch (Exception ex) {
                log.error("VerifyAll: {} failed with exception", strategyType, ex);
                strategyResults.add(errorResult(strategyType, ex));
            }
        }

        // ── Build config snapshot ─────────────────────────────────────────
        ExecutionFlowCoverage flowCoverage = tracker.getCoverage();
        ConfigSnapshot configSnapshot = new ConfigSnapshot(globalConfigService.getCached(), tradingProperties, configMap, List.of());

        // ── Create output directory ───────────────────────────────────────
        Path outputDirectory = Path.of("suites", verifyId);
        createDirectoryQuietly(outputDirectory);

        // ── Delegate to report generator (if available) ───────────────────
        Instant completedAt = Instant.now();
        Path reportHtml = null;
        Path summaryJson = null;

        VerifyAllResult result = new VerifyAllResult(
                verifyId, startedAt, completedAt, req.underlying(),
                req.from(), req.to(), strategyResults, flowCoverage,
                configSnapshot, outputDirectory, reportHtml, summaryJson);

        if (reportGenerator != null) {
            try {
                // Generate HTML first
                reportHtml = reportGenerator.generateHtmlReport(result, outputDirectory);
                // Rebuild result with HTML path before generating JSON
                // so the JSON contains the correct report path
                result = new VerifyAllResult(
                        verifyId, startedAt, completedAt, req.underlying(),
                        req.from(), req.to(), strategyResults, flowCoverage,
                        configSnapshot, outputDirectory, reportHtml, null);
                // Now generate JSON with the updated result
                summaryJson = reportGenerator.generateJsonSummary(result, outputDirectory);
                // Final rebuild with both paths
                result = new VerifyAllResult(
                        verifyId, startedAt, completedAt, req.underlying(),
                        req.from(), req.to(), strategyResults, flowCoverage,
                        configSnapshot, outputDirectory, reportHtml, summaryJson);
            } catch (Exception ex) {
                log.error("VerifyAll: report generation failed", ex);
            }
        } else {
            log.info("VerifyAll: VerifyAllReportGenerator not available — skipping report generation");
        }

        log.info("VerifyAll completed: id={}, strategies={}, duration={}ms",
                verifyId, strategyResults.size(),
                completedAt.toEpochMilli() - startedAt.toEpochMilli());

        return result;
    }


    // ── Single-leg strategy execution ─────────────────────────────────────

    private StrategyVerificationResult runSingleLegStrategy(StrategyType strategyType,
                                                             StrategyConfig config,
                                                             VerifyAllRequest req,
                                                             ExecutionFlowTracker tracker) {
        String strategyTypeStr = switch (strategyType) {
            case SCALPING -> "SCALPING";
            case VOLATILITY_BREAKOUT -> "VOLATILITY_BREAKOUT";
            default -> null; // DIRECTIONAL_BUY uses default (null)
        };

        BacktestEngine.RunOptions options = new BacktestEngine.RunOptions(
                tradingProperties,
                "suites/VERIFY-ALL-tmp",
                "verify-" + strategyType.name().toLowerCase(),
                strategyTypeStr);

        // Run for both CE and PE option types
        List<BacktestTrade> allTrades = new ArrayList<>();
        BacktestMetrics latestMetrics = null;
        int totalSignals = 0;
        int rejectedSignals = 0;

        for (OptionType optionType : OptionType.values()) {
            try {
                BacktestRunResult runResult = backtestEngine.run(
                        req.underlying(),
                        tradingProperties.backtest().candleTimeframe(),
                        optionType,
                        req.from(),
                        req.to(),
                        options);

                allTrades.addAll(runResult.trades());
                if (runResult.metrics() != null) {
                    totalSignals += runResult.metrics().totalSignals();
                    rejectedSignals += runResult.metrics().rejectedSignals();
                    latestMetrics = runResult.metrics();
                }

                // Record flow hits for single-leg strategies
                if (!runResult.trades().isEmpty()) {
                    tracker.recordHit("entry.signal-to-trade",
                            strategyType + " " + optionType + ": " + runResult.trades().size() + " trades");
                }
            } catch (Exception ex) {
                log.warn("VerifyAll: {} {} failed: {}", strategyType, optionType, ex.getMessage());
                // Continue with other option type
            }
        }

        if (allTrades.isEmpty() && latestMetrics == null) {
            return noDataResult(strategyType);
        }

        // Recompute combined metrics from all trades (CE + PE)
        BacktestMetrics combinedMetrics;
        if (!allTrades.isEmpty()) {
            combinedMetrics = BacktestMetrics.compute(allTrades, totalSignals, rejectedSignals);
        } else {
            combinedMetrics = latestMetrics;
        }
        List<BacktestTrade> sampleTrades = allTrades.size() > 10
                ? allTrades.subList(0, 10) : allTrades;

        return new StrategyVerificationResult(
                strategyType, "ok", combinedMetrics, sampleTrades,
                totalSignals, rejectedSignals,
                Map.of(), Map.of(), null);
    }

    // ── Spread strategy execution ─────────────────────────────────────────

    private StrategyVerificationResult runSpreadStrategy(StrategyType strategyType,
                                                          StrategyConfig config,
                                                          VerifyAllRequest req,
                                                          ExecutionFlowTracker tracker) {
        SpreadBacktestResult spreadResult = spreadBacktestEngine.run(
                strategyType, config, req.underlying(),
                req.from(), req.to(), tradingProperties, tracker);

        if (spreadResult.trades().isEmpty()) {
            // Still return metrics/rejection data even with no trades — helps diagnose why
            String status = spreadResult.totalSignals() > 0 ? "no-trades" : "no-data";
            String errorMsg = spreadResult.totalSignals() > 0
                    ? "Signals generated but no trades completed — check exit conditions"
                    : "No entry signals generated — check evaluator and data availability";
            return new StrategyVerificationResult(
                    strategyType, status, spreadResult.metrics(), List.of(),
                    spreadResult.totalSignals(), spreadResult.rejectedSignals(),
                    spreadResult.rejectionReasons(), spreadResult.strategySpecificMetrics(),
                    errorMsg);
        }

        // Convert spread trades to BacktestTrade for sample display
        List<BacktestTrade> sampleTrades = spreadResult.trades().stream()
                .limit(10)
                .map(SpreadBacktestTrade::toBacktestTrade)
                .toList();

        return new StrategyVerificationResult(
                strategyType, "ok", spreadResult.metrics(), sampleTrades,
                spreadResult.totalSignals(), spreadResult.rejectedSignals(),
                spreadResult.rejectionReasons(), spreadResult.strategySpecificMetrics(),
                null);
    }

    // ── Helper methods ────────────────────────────────────────────────────

    private StrategyConfig loadOrCreateConfig(StrategyType strategyType) {
        // StrategyConfigService.getAll() auto-creates missing configs,
        // but we use the private getOrCreate pattern via getDirectionalBuyConfig-style access.
        // The simplest approach: call getAll() once would be wasteful per-strategy.
        // Instead, rely on the service's internal getOrCreate behavior.
        List<StrategyConfig> all = strategyConfigService.getAll();
        return all.stream()
                .filter(c -> c.getStrategyType() == strategyType)
                .findFirst()
                .orElseGet(() -> {
                    // Shouldn't happen since getAll() auto-creates, but just in case
                    log.warn("VerifyAll: config not found for {} after getAll(), creating default", strategyType);
                    return new StrategyConfig(strategyType);
                });
    }

    private static StrategyVerificationResult errorResult(StrategyType strategyType, Exception ex) {
        return new StrategyVerificationResult(
                strategyType, "error", null, List.of(),
                0, 0, Map.of(), Map.of(),
                ex.getClass().getSimpleName() + ": " + ex.getMessage());
    }

    private static StrategyVerificationResult noDataResult(StrategyType strategyType) {
        return new StrategyVerificationResult(
                strategyType, "no-data", null, List.of(),
                0, 0, Map.of(), Map.of(),
                "No trades produced — check data availability for date range");
    }

    private static void createDirectoryQuietly(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (Exception ex) {
            LoggerFactory.getLogger(VerifyAllService.class)
                    .warn("Failed to create output directory {}: {}", dir, ex.getMessage());
        }
    }
}
