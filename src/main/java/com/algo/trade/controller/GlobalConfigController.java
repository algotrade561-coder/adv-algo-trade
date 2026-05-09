package com.algo.trade.controller;

import com.algo.trade.config.GlobalConfig;
import com.algo.trade.config.GlobalConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * REST API for global trading configuration management.
 * Follows the StrategyController pattern for response shape and error handling.
 */
@RestController
@RequestMapping("/global-config")
public class GlobalConfigController {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigController.class);
    private final GlobalConfigService globalConfigService;

    public GlobalConfigController(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
    }

    /** Get current global config as flat DTO. */
    @GetMapping
    public Map<String, Object> get() {
        return toDto(globalConfigService.getCached());
    }

    /** Update global config. Returns updated DTO or 400 on validation failure. */
    @PutMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody GlobalConfig body) {
        try {
            GlobalConfig updated = globalConfigService.update(body);
            log.info("GlobalConfig updated via REST");
            return ResponseEntity.ok(toDto(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Reset global config to YAML defaults. */
    @PostMapping("/reset")
    public Map<String, Object> reset() {
        GlobalConfig reset = globalConfigService.resetToDefaults();
        log.info("GlobalConfig reset to defaults via REST");
        return toDto(reset);
    }

    private Map<String, Object> toDto(GlobalConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        // Entry fields
        m.put("timeframe", c.getTimeframe().name());
        m.put("trendTimeframe", c.getTrendTimeframe().name());
        m.put("enabledOptionTypes", c.getEnabledOptionTypes());
        m.put("enabledUnderlyings", c.getEnabledUnderlyings());
        m.put("vwapFilterEnabled", c.isVwapFilterEnabled());
        m.put("trendFilterEnabled", c.isTrendFilterEnabled());
        m.put("volumeSpikeMultiplier", c.getVolumeSpikeMultiplier());
        m.put("breakoutBufferPercent", c.getBreakoutBufferPercent());
        m.put("breakoutLookback", c.getBreakoutLookback());
        m.put("volumeLookback", c.getVolumeLookback());
        m.put("bullishImbalanceThreshold", c.getBullishImbalanceThreshold());
        m.put("bearishImbalanceThreshold", c.getBearishImbalanceThreshold());
        m.put("minLiquidityVolume", c.getMinLiquidityVolume());
        m.put("maxIvPercent", c.getMaxIvPercent());
        m.put("minSignalScorePercent", c.getMinSignalScorePercent());
        m.put("minEnvironmentScore", c.getMinEnvironmentScore());
        m.put("ceOiSupportRequired", c.isCeOiSupportRequired());
        m.put("peOiSupportRequired", c.isPeOiSupportRequired());
        m.put("ceOiDivergenceFilterEnabled", c.isCeOiDivergenceFilterEnabled());
        m.put("peOiDivergenceFilterEnabled", c.isPeOiDivergenceFilterEnabled());
        m.put("oiDivergenceMultiplier", c.getOiDivergenceMultiplier());
        m.put("oiDivergenceMinChange", c.getOiDivergenceMinChange());
        m.put("ceBreakoutConfirmationCandles", c.getCeBreakoutConfirmationCandles());
        m.put("peBreakoutConfirmationCandles", c.getPeBreakoutConfirmationCandles());
        m.put("entryStartTime", c.getEntryStartTime());
        m.put("entryCutoffTime", c.getEntryCutoffTime());
        m.put("allowFirstMinutesEntry", c.isAllowFirstMinutesEntry());
        m.put("noEntryFirstMinutes", c.getNoEntryFirstMinutes());
        m.put("rsiFilterEnabled", c.isRsiFilterEnabled());
        m.put("rsiPeriod", c.getRsiPeriod());
        m.put("rsiCeBuyThreshold", c.getRsiCeBuyThreshold());
        m.put("rsiPeSellThreshold", c.getRsiPeSellThreshold());
        // Exit fields
        m.put("stopLossPercent", c.getStopLossPercent());
        m.put("targetPercent", c.getTargetPercent());
        m.put("trailingStopActivationPercent", c.getTrailingStopActivationPercent());
        m.put("trailingGapPercent", c.getTrailingGapPercent());
        m.put("forcedExitTime", c.getForcedExitTime());
        m.put("partialProfitBookingEnabled", c.isPartialProfitBookingEnabled());
        m.put("maxHoldMinutes", c.getMaxHoldMinutes());
        // Risk fields
        m.put("totalCapital", c.getTotalCapital());
        m.put("maxRiskPerTradePercent", c.getMaxRiskPerTradePercent());
        m.put("maxDailyLossPercent", c.getMaxDailyLossPercent());
        m.put("maxTradesPerDay", c.getMaxTradesPerDay());
        m.put("maxConsecutiveLosses", c.getMaxConsecutiveLosses());
        m.put("maxOpenTrades", c.getMaxOpenTrades());
        m.put("cooldownMinutes", c.getCooldownMinutes());
        m.put("maxOpenPositionsPerStrategy", c.getMaxOpenPositionsPerStrategy());
        m.put("dailyProfitTarget", c.getDailyProfitTarget());
        m.put("maxLotsPerTrade", c.getMaxLotsPerTrade());
        m.put("mlVirtualTradeThreshold", c.getMlVirtualTradeThreshold());
        // Execution tuning
        m.put("limitOrderCancelMinutes", c.getLimitOrderCancelMinutes());
        m.put("failSafeSquareoffTime", c.getFailSafeSquareoffTime());
        m.put("maxPendingOrders", c.getMaxPendingOrders());
        m.put("ivCollapseExitThresholdPercent", c.getIvCollapseExitThresholdPercent());
        m.put("ivCollapseMaxProfitPercent", c.getIvCollapseMaxProfitPercent());
        m.put("maxEntriesPerScanPerUnderlying", c.getMaxEntriesPerScanPerUnderlying());
        m.put("maxEntriesPerScan", c.getMaxEntriesPerScan());
        return m;
    }
}
