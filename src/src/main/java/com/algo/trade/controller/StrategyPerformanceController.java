package com.algo.trade.controller;

import com.algo.trade.monitoring.SlippageTracker;
import com.algo.trade.monitoring.StrategyAttributionService;
import com.algo.trade.risk.AdaptiveHaltManager;
import com.algo.trade.risk.CapitalAllocator;
import com.algo.trade.risk.ConsecutiveLossCircuitBreaker;
import com.algo.trade.risk.StrategyGovernor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Strategy Performance Controller — exposes per-strategy P&L, attribution,
 * governance status, slippage, and risk state for the Angular dashboard.
 */
@RestController
@RequestMapping("/performance")
public class StrategyPerformanceController {

    private final StrategyAttributionService attributionService;
    private final StrategyGovernor strategyGovernor;
    private final CapitalAllocator capitalAllocator;
    private final ConsecutiveLossCircuitBreaker circuitBreaker;
    private final AdaptiveHaltManager adaptiveHaltManager;
    private final SlippageTracker slippageTracker;

    public StrategyPerformanceController(StrategyAttributionService attributionService,
                                          StrategyGovernor strategyGovernor,
                                          CapitalAllocator capitalAllocator,
                                          ConsecutiveLossCircuitBreaker circuitBreaker,
                                          AdaptiveHaltManager adaptiveHaltManager,
                                          SlippageTracker slippageTracker) {
        this.attributionService = attributionService;
        this.strategyGovernor = strategyGovernor;
        this.capitalAllocator = capitalAllocator;
        this.circuitBreaker = circuitBreaker;
        this.adaptiveHaltManager = adaptiveHaltManager;
        this.slippageTracker = slippageTracker;
    }

    /**
     * GET /performance/attribution — per-strategy P&L and win rates
     */
    @GetMapping("/attribution")
    public ResponseEntity<?> getAttribution() {
        return ResponseEntity.ok(attributionService.getAllPerformance());
    }

    /**
     * GET /performance/today-pnl — today's P&L grouped by strategy
     */
    @GetMapping("/today-pnl")
    public ResponseEntity<?> getTodayPnl() {
        return ResponseEntity.ok(attributionService.getTodayPnlByStrategy());
    }

    /**
     * GET /performance/governance — strategy lot multipliers and ranks
     */
    @GetMapping("/governance")
    public ResponseEntity<?> getGovernance() {
        return ResponseEntity.ok(strategyGovernor.getAllGovernance());
    }

    /**
     * GET /performance/capital — capital allocation status
     */
    @GetMapping("/capital")
    public ResponseEntity<?> getCapitalStatus() {
        return ResponseEntity.ok(capitalAllocator.getStatus());
    }

    /**
     * GET /performance/circuit-breaker — global circuit breaker state
     */
    @GetMapping("/circuit-breaker")
    public ResponseEntity<?> getCircuitBreakerStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("globalCircuitOpen", circuitBreaker.isGlobalCircuitOpen());
        status.put("globalConsecutiveLosses", circuitBreaker.getGlobalConsecutiveLosses());
        return ResponseEntity.ok(status);
    }

    /**
     * POST /performance/circuit-breaker/force-close — manually close circuit breaker
     */
    @PostMapping("/circuit-breaker/force-close")
    public ResponseEntity<?> forceCloseCircuitBreaker() {
        circuitBreaker.forceClose();
        return ResponseEntity.ok(Map.of("status", "Circuit breaker force-closed"));
    }

    /**
     * GET /performance/adaptive-halt — adaptive halt status
     */
    @GetMapping("/adaptive-halt")
    public ResponseEntity<?> getAdaptiveHaltStatus() {
        return ResponseEntity.ok(adaptiveHaltManager.getStatus());
    }

    /**
     * POST /performance/adaptive-halt/resume — manually resume from adaptive halt
     */
    @PostMapping("/adaptive-halt/resume")
    public ResponseEntity<?> resumeAdaptiveHalt() {
        adaptiveHaltManager.manualResume();
        return ResponseEntity.ok(Map.of("status", "Adaptive halt manually resumed"));
    }

    /**
     * GET /performance/slippage — slippage tracking summary
     */
    @GetMapping("/slippage")
    public ResponseEntity<?> getSlippageSummary() {
        return ResponseEntity.ok(slippageTracker.getSlippageSummary());
    }
}
