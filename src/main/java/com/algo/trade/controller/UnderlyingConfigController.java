package com.algo.trade.controller;

import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.underlying.UnderlyingConfig;
import com.algo.trade.underlying.UnderlyingConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for per-underlying index configuration.
 * Manages index-level settings (expiry preference, breakout buffer, volume mode, etc.)
 * that apply across all strategies for a given underlying.
 */
@RestController
@RequestMapping("/underlying-config")
public class UnderlyingConfigController {

    private static final Logger log = LoggerFactory.getLogger(UnderlyingConfigController.class);
    private final UnderlyingConfigService underlyingConfigService;

    public UnderlyingConfigController(UnderlyingConfigService underlyingConfigService) {
        this.underlyingConfigService = underlyingConfigService;
    }

    /** Get all underlying configs. */
    @GetMapping
    public List<Map<String, Object>> getAll() {
        return underlyingConfigService.getAll().stream().map(this::toDto).toList();
    }

    /** Get config for a specific underlying. */
    @GetMapping("/{underlying}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String underlying) {
        try {
            UnderlyingSymbol symbol = UnderlyingSymbol.valueOf(underlying.toUpperCase());
            return underlyingConfigService.getConfig(symbol)
                    .map(cfg -> ResponseEntity.ok(toDto(cfg)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown underlying: " + underlying));
        }
    }

    /** Update config for a specific underlying. */
    @PutMapping("/{underlying}")
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPERUSER')")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String underlying,
                                                       @RequestBody UnderlyingConfig body) {
        try {
            UnderlyingSymbol symbol = UnderlyingSymbol.valueOf(underlying.toUpperCase());
            UnderlyingConfig updated = underlyingConfigService.update(symbol, body);
            log.info("UnderlyingConfig updated via REST: {}", symbol);
            return ResponseEntity.ok(toDto(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> toDto(UnderlyingConfig cfg) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("underlying", cfg.getUnderlying().name());
        dto.put("enabled", cfg.isEnabled());
        dto.put("displayName", cfg.getDisplayName());
        // Expiry & DTE
        dto.put("hasWeeklyExpiry", cfg.isHasWeeklyExpiry());
        dto.put("expiryPreference", cfg.getExpiryPreference());
        dto.put("maxDteForBuying", cfg.getMaxDteForBuying());
        // Breakout
        dto.put("breakoutBufferPercent", cfg.getBreakoutBufferPercent());
        dto.put("minBreakoutPoints", cfg.getMinBreakoutPoints());
        // Volume
        dto.put("volumeSpikeMode", cfg.getVolumeSpikeMode());
        // Session overrides
        dto.put("entryCutoffTime", cfg.getEntryCutoffTime());
        dto.put("middayChopStart", cfg.getMiddayChopStart());
        dto.put("middayChopEnd", cfg.getMiddayChopEnd());
        // Score
        dto.put("normalizeScoreForNoVolume", cfg.isNormalizeScoreForNoVolume());
        // Premium band (both enforced in ExecutionEngine's entry gates; min floor blocks
        // charge-uneconomical cheap options — Rs50/rt on a Rs16x20 SENSEX lot is 15% of notional)
        dto.put("maxEntryPremium", cfg.getMaxEntryPremium());
        dto.put("minEntryPremium", cfg.getMinEntryPremium());
        return dto;
    }
}
