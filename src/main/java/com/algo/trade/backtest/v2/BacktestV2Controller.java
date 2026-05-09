package com.algo.trade.backtest.v2;

import com.algo.trade.backtest.BacktestRunResult;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * REST controller for Backtest V2 — strategy-level backtesting using option chain snapshots.
 */
@RestController
@RequestMapping("/backtest/v2")
public class BacktestV2Controller {

    private static final Logger log = LoggerFactory.getLogger(BacktestV2Controller.class);

    private final BacktestOrchestrator orchestrator;

    public BacktestV2Controller(BacktestOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * Run a single strategy backtest using option chain snapshots.
     */
    @PostMapping("/run-strategy")
    public ResponseEntity<?> runStrategy(@RequestBody RunStrategyRequest request) {
        log.info("[BacktestV2] /run-strategy called: strategy={}, underlying={}, from={}, to={}",
                request.strategy(), request.underlying(), request.from(), request.to());
        try {
            BacktestOrchestrator.BacktestRequest backtestRequest = new BacktestOrchestrator.BacktestRequest(
                    request.underlying() != null ? request.underlying() : "NIFTY",
                    request.from() != null ? request.from() : LocalDate.now().minusMonths(1),
                    request.to() != null ? request.to() : LocalDate.now(),
                    request.strategy()
            );

            BacktestRunResult result = orchestrator.runStrategy(backtestRequest);
            return ResponseEntity.ok(toResponse(result));
        } catch (IllegalArgumentException e) {
            log.warn("[BacktestV2] Bad request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[BacktestV2] Strategy run failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Run all available strategies and return comparative results.
     */
    @PostMapping("/run-all-strategies")
    public ResponseEntity<?> runAllStrategies(@RequestBody(required = false) RunAllRequest request) {
        String underlying = request != null && request.underlying() != null ? request.underlying() : "NIFTY";
        LocalDate from = request != null && request.from() != null ? request.from() : LocalDate.now().minusMonths(1);
        LocalDate to = request != null && request.to() != null ? request.to() : LocalDate.now();

        log.info("[BacktestV2] /run-all-strategies called: underlying={}, from={}, to={}",
                underlying, from, to);
        try {
            Map<StrategyType, BacktestRunResult> results = orchestrator.runAllStrategies(underlying, from, to);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("underlying", underlying);
            response.put("from", from.toString());
            response.put("to", to.toString());
            response.put("strategiesRun", results.size());

            Map<String, Object> strategyResults = new LinkedHashMap<>();
            for (var entry : results.entrySet()) {
                strategyResults.put(entry.getKey().name(), toResponse(entry.getValue()));
            }
            response.put("results", strategyResults);

            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("[BacktestV2] Run all failed: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * List available strategy adapters.
     */
    @GetMapping("/strategies")
    public ResponseEntity<?> listStrategies() {
        Set<StrategyType> available = orchestrator.availableStrategies();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("count", available.size());
        response.put("strategies", available.stream()
                .map(s -> Map.of(
                        "type", s.name(),
                        "displayName", s.displayName(),
                        "description", s.description()
                ))
                .toList());
        return ResponseEntity.ok(response);
    }

    /**
     * Check data availability for a date range.
     */
    @GetMapping("/data-availability")
    public ResponseEntity<?> checkDataAvailability(
            @RequestParam(defaultValue = "NIFTY") String underlying,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        BacktestOrchestrator.DataAvailability availability =
                orchestrator.checkDataAvailability(underlying, from, to);
        return ResponseEntity.ok(availability);
    }

    // ── Request/Response DTOs ───────────────────────────────────────────────────

    public record RunStrategyRequest(
            StrategyType strategy,
            String underlying,
            LocalDate from,
            LocalDate to
    ) {}

    public record RunAllRequest(
            String underlying,
            LocalDate from,
            LocalDate to
    ) {}

    private Map<String, Object> toResponse(BacktestRunResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", result.id());
        map.put("createdAt", result.createdAt().toString());
        map.put("totalTrades", result.metrics().totalTrades());
        map.put("winRatePercent", result.metrics().winRatePercent());
        map.put("expectancy", result.metrics().expectancy());
        map.put("maxDrawdown", result.metrics().maxDrawdown());
        map.put("cumulativePnl", result.metrics().cumulativePnl());
        map.put("profitFactor", result.metrics().profitFactor());
        map.put("avgHoldMinutes", result.metrics().avgHoldMinutes());
        map.put("maxConsecutiveWins", result.metrics().maxConsecutiveWins());
        map.put("maxConsecutiveLosses", result.metrics().maxConsecutiveLosses());
        map.put("totalSignals", result.metrics().totalSignals());
        map.put("rejectedSignals", result.metrics().rejectedSignals());
        map.put("outputPath", result.outputDirectory().toString());
        return map;
    }
}
