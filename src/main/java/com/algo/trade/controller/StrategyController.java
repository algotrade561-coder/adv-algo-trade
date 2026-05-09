package com.algo.trade.controller;

import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * REST API for strategy configuration management.
 * All mutating endpoints accept an optional ?underlying=NIFTY param (defaults to NIFTY).
 */
@RestController
@RequestMapping("/strategies")
public class StrategyController {

    private static final Logger log = LoggerFactory.getLogger(StrategyController.class);
    private final StrategyConfigService strategyConfigService;

    public StrategyController(StrategyConfigService strategyConfigService) {
        this.strategyConfigService = strategyConfigService;
    }

    /** Get all strategy configs with current state (all underlyings). */
    @GetMapping
    public List<Map<String, Object>> getAll() {
        return strategyConfigService.getAll().stream().map(this::toDto).toList();
    }

    /** Get all strategy configs (enabled + disabled) for a specific underlying. Auto-seeds from NIFTY if new. */
    @GetMapping("/by-underlying/{underlying}")
    public List<Map<String, Object>> getByUnderlying(@PathVariable String underlying) {
        return strategyConfigService.getAllFor(underlying.toUpperCase())
                .stream().map(this::toDto).toList();
    }

    /** Get all strategy type metadata (for UI rendering). */
    @GetMapping("/types")
    public List<Map<String, Object>> getTypes() {
        return Arrays.stream(StrategyType.values()).map(t -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("type", t.name());
            m.put("displayName", t.displayName());
            m.put("description", t.description());
            m.put("sellingStrategy", t.isSellingStrategy());
            m.put("defaultEnabled", t.isDefaultEnabled());
            return m;
        }).toList();
    }

    /** Enable a strategy for the given underlying (default: NIFTY). */
    @PostMapping("/{type}/enable")
    public ResponseEntity<Map<String, Object>> enable(
            @PathVariable String type,
            @RequestParam(defaultValue = "NIFTY") String underlying) {
        try {
            StrategyType strategyType = StrategyType.valueOf(type.toUpperCase());
            StrategyConfig config = strategyConfigService.enable(strategyType, underlying.toUpperCase());
            log.info("Strategy enabled: type={} underlying={}", strategyType, underlying);
            return ResponseEntity.ok(Map.of(
                    "message", config.getStrategyType().displayName() + " enabled for " + underlying,
                    "config", toDto(config)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown strategy type or underlying: " + type));
        }
    }

    /** Disable a strategy for the given underlying (default: NIFTY). */
    @PostMapping("/{type}/disable")
    public ResponseEntity<Map<String, Object>> disable(
            @PathVariable String type,
            @RequestParam(defaultValue = "NIFTY") String underlying) {
        try {
            StrategyType strategyType = StrategyType.valueOf(type.toUpperCase());
            StrategyConfig config = strategyConfigService.disable(strategyType, underlying.toUpperCase());
            log.info("Strategy disabled: type={} underlying={}", strategyType, underlying);
            return ResponseEntity.ok(Map.of(
                    "message", config.getStrategyType().displayName() + " disabled for " + underlying,
                    "config", toDto(config)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown strategy type or underlying: " + type));
        }
    }

    /** Update strategy parameters for the given underlying (default: NIFTY). */
    @PutMapping("/{type}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String type,
            @RequestParam(defaultValue = "NIFTY") String underlying,
            @RequestBody StrategyConfig patch) {
        try {
            StrategyType strategyType = StrategyType.valueOf(type.toUpperCase());
            log.info("Strategy update request: type={} underlying={}", type, underlying);
            StrategyConfig config = strategyConfigService.update(strategyType, underlying.toUpperCase(), patch);
            log.info("Strategy updated: type={} underlying={} id={}", strategyType, config.getUnderlying(), config.getId());
            return ResponseEntity.ok(Map.of("message", "Updated", "config", toDto(config)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown strategy type or underlying: " + type));
        }
    }

    /**
     * Copy a strategy config from one underlying to another.
     * The copied config is created disabled — enable explicitly after reviewing params.
     * Example: POST /strategies/DIRECTIONAL_BUY/copy?from=NIFTY&to=BANKNIFTY
     */
    @PostMapping("/{type}/copy")
    public ResponseEntity<Map<String, Object>> copy(
            @PathVariable String type,
            @RequestParam(defaultValue = "NIFTY") String from,
            @RequestParam String to) {
        try {
            StrategyType strategyType = StrategyType.valueOf(type.toUpperCase());
            StrategyConfig config = strategyConfigService.copyTo(
                    strategyType, from.toUpperCase(), to.toUpperCase());
            log.info("Strategy config copied: type={} from={} to={}", strategyType, from, to);
            return ResponseEntity.ok(Map.of(
                    "message", "Copied " + strategyType.displayName() + " from " + from + " to " + to + " (disabled — enable explicitly)",
                    "config", toDto(config)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown strategy type or underlying: " + type));
        }
    }

    private Map<String, Object> toDto(StrategyConfig c) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("type", c.getStrategyType().name());
        m.put("displayName", c.getStrategyType().displayName());
        m.put("description", c.getStrategyType().description());
        m.put("sellingStrategy", c.getStrategyType().isSellingStrategy());
        m.put("enabled", c.isEnabled());
        m.put("underlying", c.getUnderlying());
        m.put("lots", c.getLots());
        m.put("stopLossPercent", c.getStopLossPercent());
        m.put("targetPercent", c.getTargetPercent());
        m.put("maxHoldMinutes", c.getMaxHoldMinutes());
        m.put("spreadStrikes", c.getSpreadStrikes());
        m.put("otmStrikes", c.getOtmStrikes());
        m.put("maxIvRankForBuying", c.getMaxIvRankForBuying());
        m.put("minCombinedPremium", c.getMinCombinedPremium());
        m.put("trailingStopActivationPercent", c.getTrailingStopActivationPercent());
        m.put("trailingGapPercent", c.getTrailingGapPercent());
        m.put("scanTimeframe", c.getScanTimeframe());
        m.put("candleTimeframe", c.getCandleTimeframe());
        m.put("trendTimeframe", c.getTrendTimeframe());
        m.put("paperTrading", c.isPaperTrading());
        m.put("itmDepth", c.getItmDepth());
        m.put("minimumMove", c.getMinimumMove());
        m.put("minimumStrengthGap", c.getMinimumStrengthGap());
        m.put("minimumVolume", c.getMinimumVolume());
        m.put("squareoffHour", c.getSquareoffHour());
        m.put("squareoffMinute", c.getSquareoffMinute());
        return m;
    }
}
