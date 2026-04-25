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
 * Uses the existing Kite auth flow — no separate auth needed.
 */
@RestController
@RequestMapping("/strategies")
public class StrategyController {

    private static final Logger log = LoggerFactory.getLogger(StrategyController.class);
    private final StrategyConfigService strategyConfigService;

    public StrategyController(StrategyConfigService strategyConfigService) {
        this.strategyConfigService = strategyConfigService;
    }

    /** Get all strategy configs with current state. */
    @GetMapping
    public List<Map<String, Object>> getAll() {
        return strategyConfigService.getAll().stream().map(this::toDto).toList();
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

    /** Enable a strategy. */
    @PostMapping("/{type}/enable")
    public ResponseEntity<Map<String, Object>> enable(@PathVariable String type) {
        try {
            StrategyType strategyType = StrategyType.valueOf(type);
            StrategyConfig config = strategyConfigService.enable(strategyType);
            log.info("Strategy enabled: {}", strategyType);
            return ResponseEntity.ok(Map.of(
                    "message", config.getStrategyType().displayName() + " enabled",
                    "config", toDto(config)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown strategy type: " + type));
        }
    }

    /** Disable a strategy. */
    @PostMapping("/{type}/disable")
    public ResponseEntity<Map<String, Object>> disable(@PathVariable String type) {
        try {
            StrategyType strategyType = StrategyType.valueOf(type);
            StrategyConfig config = strategyConfigService.disable(strategyType);
            log.info("Strategy disabled: {}", strategyType);
            return ResponseEntity.ok(Map.of(
                    "message", config.getStrategyType().displayName() + " disabled",
                    "config", toDto(config)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown strategy type: " + type));
        }
    }

    /** Update strategy parameters. */
    @PutMapping("/{type}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String type,
                                                       @RequestBody StrategyConfig patch) {
        try {
            StrategyType strategyType = StrategyType.valueOf(type);
            StrategyConfig config = strategyConfigService.update(strategyType, patch);
            log.info("Strategy updated: {}", strategyType);
            return ResponseEntity.ok(Map.of("message", "Updated", "config", toDto(config)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown strategy type: " + type));
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
        return m;
    }
}
