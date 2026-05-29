package com.algo.trade.controller;

import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.oimomentum.OIMomentumStrategy;
import com.algo.trade.strategy.oimomentum.runtime.OiMomentumRuntimeConfig;
import com.algo.trade.strategy.oimomentum.runtime.OiMomentumRuntimeConfigService;
import com.algo.trade.strategy.oimomentum.runtime.OiMomentumRuntimeConfigService.RuntimeConfigUpdate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.*;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * REST API for OI Momentum runtime settings.
 *
 * <p>OAuth-protected by the existing SecurityConfig — every request goes through
 * Google OIDC, so the {@link OidcUser} principal gives us the operator email for
 * audit trails.</p>
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>{@code GET  /oi-momentum/settings}      — current config</li>
 *   <li>{@code POST /oi-momentum/settings}      — partial update + reason</li>
 *   <li>{@code GET  /oi-momentum/settings/halts}       — per-index halt snapshot</li>
 *   <li>{@code POST /oi-momentum/settings/resume}      — clear halt fields</li>
 *   <li>{@code POST /oi-momentum/settings/halts/extend} — day-halt indices</li>
 *   <li>{@code POST /oi-momentum/settings/kill} — emergency kill switch</li>
 * </ul>
 */
@RestController
@RequestMapping("/oi-momentum/settings")
public class OiMomentumSettingsController {

    private static final Logger log = LoggerFactory.getLogger(OiMomentumSettingsController.class);

    private final OiMomentumRuntimeConfigService runtimeConfigService;

    @Autowired(required = false)
    private OIMomentumStrategy strategy;

    public OiMomentumSettingsController(OiMomentumRuntimeConfigService runtimeConfigService) {
        this.runtimeConfigService = runtimeConfigService;
    }

    @GetMapping
    public Map<String, Object> get() {
        return toDto(runtimeConfigService.getCached());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> update(@RequestBody UpdateRequest body,
                                                       @AuthenticationPrincipal OidcUser principal) {
        try {
            String operatorEmail = resolveOperator(principal);
            RuntimeConfigUpdate u = body.toUpdate();
            OiMomentumRuntimeConfig updated = runtimeConfigService.apply(u, operatorEmail, body.reason);
            return ResponseEntity.ok(toDto(updated));
        } catch (IllegalArgumentException ex) {
            log.warn("OiMomentum settings update rejected: {}", ex.getMessage());
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /** Halt status — what's blocked, what's not, per index. */
    @GetMapping("/halts")
    public ResponseEntity<Map<String, Object>> halts() {
        if (strategy == null) {
            return ResponseEntity.ok(Map.of("strategyEnabled", false,
                    "indices", Map.of(), "note", "strategy not wired"));
        }
        return ResponseEntity.ok(strategy.getHaltStatus());
    }

    /**
     * Resume from halt. Operator chooses which halt fields to clear and (optionally)
     * which indices. Empty indices list = all.
     */
    @PostMapping("/resume")
    public ResponseEntity<Map<String, Object>> resume(@RequestBody ResumeRequest body,
                                                       @AuthenticationPrincipal OidcUser principal) {
        if (strategy == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "strategy not wired"));
        }
        try {
            String operatorEmail = resolveOperator(principal);
            Set<IndexType> indices = parseIndices(body == null ? null : body.indices);
            boolean clearHalt = body == null || body.clearHaltedForDay == null
                    ? true : body.clearHaltedForDay;
            boolean clearLosses = body == null || body.clearConsecutiveLosses == null
                    ? true : body.clearConsecutiveLosses;
            boolean clearCooldown = body == null || body.clearSlCooldown == null
                    ? false : body.clearSlCooldown;
            boolean resetTrades = body == null || body.resetTradesToday == null
                    ? false : body.resetTradesToday;
            String reason = body == null ? null : body.reason;
            Map<String, Object> result = strategy.resumeHalts(
                    indices, clearHalt, clearLosses, clearCooldown, resetTrades,
                    operatorEmail, reason);
            return ResponseEntity.ok(Map.of("result", result,
                    "haltStatus", strategy.getHaltStatus()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    /**
     * Extend the halt — proactively halt selected indices (empty = all) for the rest
     * of the day. Use when something looks off but you want to keep the rest of the
     * portfolio trading.
     */
    @PostMapping("/halts/extend")
    public ResponseEntity<Map<String, Object>> extend(@RequestBody ResumeRequest body,
                                                       @AuthenticationPrincipal OidcUser principal) {
        if (strategy == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "strategy not wired"));
        }
        try {
            String operatorEmail = resolveOperator(principal);
            Set<IndexType> indices = parseIndices(body == null ? null : body.indices);
            String reason = body == null ? null : body.reason;
            Map<String, Object> result = strategy.extendHalts(indices, operatorEmail, reason);
            return ResponseEntity.ok(Map.of("result", result,
                    "haltStatus", strategy.getHaltStatus()));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }

    private Set<IndexType> parseIndices(List<String> names) {
        if (names == null || names.isEmpty()) return EnumSet.noneOf(IndexType.class);
        Set<IndexType> out = EnumSet.noneOf(IndexType.class);
        for (String n : names) {
            try { out.add(IndexType.valueOf(n.trim().toUpperCase())); }
            catch (IllegalArgumentException ignore) {}
        }
        return out;
    }

    @PostMapping("/kill")
    public ResponseEntity<Map<String, Object>> kill(@RequestBody KillRequest body,
                                                     @AuthenticationPrincipal OidcUser principal) {
        try {
            String operatorEmail = resolveOperator(principal);
            String reason = body == null || body.reason == null || body.reason.trim().length() < 5
                    ? "emergency kill switch invoked from UI"
                    : body.reason.trim();
            OiMomentumRuntimeConfig updated = runtimeConfigService.kill(operatorEmail, reason);
            log.warn("[OiMomentum] KILL SWITCH activated by={} reason='{}'", operatorEmail, reason);
            return ResponseEntity.ok(toDto(updated));
        } catch (Exception ex) {
            log.error("[OiMomentum] kill switch failed", ex);
            return ResponseEntity.internalServerError().body(Map.of("error", ex.getMessage()));
        }
    }

    private String resolveOperator(OidcUser principal) {
        if (principal == null) return "anonymous";
        Object email = principal.getClaim("email");
        return email == null ? principal.getName() : email.toString();
    }

    private Map<String, Object> toDto(OiMomentumRuntimeConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", c.isEnabled());
        m.put("paperTrading", c.isPaperTrading());
        m.put("v3Enabled", c.isV3Enabled());
        m.put("v3ShadowMode", c.isV3ShadowMode());
        m.put("antiPyramidEnabled", c.isAntiPyramidEnabled());
        m.put("antiPyramidCooldownMinutes", c.getAntiPyramidCooldownMinutes());
        m.put("expiryOtmCutoffEnabled", c.isExpiryOtmCutoffEnabled());
        m.put("expiryOtmCutoffTime", c.getExpiryOtmCutoffTime());
        m.put("dailyLossLimitRupees", c.getDailyLossLimitRupees());
        m.put("dailyLossMultiplierOfAvgLoser", c.getDailyLossMultiplierOfAvgLoser());
        m.put("consecutiveLossHaltCount", c.getConsecutiveLossHaltCount());
        m.put("breakEvenTriggerPercent", c.getBreakEvenTriggerPercent());
        m.put("maxTradesPerDay", c.getMaxTradesPerDay());
        // Legacy enhancements (29 May 2026 — data-validated)
        m.put("legacyTimeOfDayModeEnabled", c.isLegacyTimeOfDayModeEnabled());
        m.put("case0Enabled", c.isCase0Enabled());
        m.put("case0ShadowMode", c.isCase0ShadowMode());
        m.put("case0OpScoreThreshold", c.getCase0OpScoreThreshold());
        m.put("case0CoilMaxPct", c.getCase0CoilMaxPct());
        m.put("case0PcrSlopeMinAbs", c.getCase0PcrSlopeMinAbs());
        m.put("case4WatchlistBonusEnabled", c.isCase4WatchlistBonusEnabled());
        m.put("updatedAt", c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : "");
        m.put("updatedBy", c.getUpdatedBy());
        m.put("updatedReason", c.getUpdatedReason());
        return m;
    }

    /** Request body for updates. Any null field is unchanged. */
    public static class UpdateRequest {
        public Boolean enabled;
        public Boolean paperTrading;
        public Boolean v3Enabled;
        public Boolean v3ShadowMode;
        public Boolean antiPyramidEnabled;
        public Integer antiPyramidCooldownMinutes;
        public Boolean expiryOtmCutoffEnabled;
        public String  expiryOtmCutoffTime;
        public Double  dailyLossLimitRupees;
        public Double  dailyLossMultiplierOfAvgLoser;
        public Integer consecutiveLossHaltCount;
        public Double  breakEvenTriggerPercent;
        public Integer maxTradesPerDay;
        public String  reason;
        // Legacy enhancements (29 May 2026 — data-validated)
        public Boolean legacyTimeOfDayModeEnabled;
        public Boolean case0Enabled;
        public Boolean case0ShadowMode;
        public Integer case0OpScoreThreshold;
        public Double  case0CoilMaxPct;
        public Double  case0PcrSlopeMinAbs;
        public Boolean case4WatchlistBonusEnabled;

        RuntimeConfigUpdate toUpdate() {
            RuntimeConfigUpdate u = new RuntimeConfigUpdate();
            u.enabled = enabled;
            u.paperTrading = paperTrading;
            u.v3Enabled = v3Enabled;
            u.v3ShadowMode = v3ShadowMode;
            u.antiPyramidEnabled = antiPyramidEnabled;
            u.antiPyramidCooldownMinutes = antiPyramidCooldownMinutes;
            u.expiryOtmCutoffEnabled = expiryOtmCutoffEnabled;
            u.expiryOtmCutoffTime = expiryOtmCutoffTime;
            u.dailyLossLimitRupees = dailyLossLimitRupees;
            u.dailyLossMultiplierOfAvgLoser = dailyLossMultiplierOfAvgLoser;
            u.consecutiveLossHaltCount = consecutiveLossHaltCount;
            u.breakEvenTriggerPercent = breakEvenTriggerPercent;
            u.maxTradesPerDay = maxTradesPerDay;
            u.legacyTimeOfDayModeEnabled = legacyTimeOfDayModeEnabled;
            u.case0Enabled = case0Enabled;
            u.case0ShadowMode = case0ShadowMode;
            u.case0OpScoreThreshold = case0OpScoreThreshold;
            u.case0CoilMaxPct = case0CoilMaxPct;
            u.case0PcrSlopeMinAbs = case0PcrSlopeMinAbs;
            u.case4WatchlistBonusEnabled = case4WatchlistBonusEnabled;
            return u;
        }
    }

    public static class KillRequest {
        public String reason;
    }

    /** Body for {@code /resume} and {@code /halts/extend}. */
    public static class ResumeRequest {
        /** Index names to act on, e.g. ["NIFTY", "SENSEX"]. Empty/null = all. */
        public List<String> indices;
        /** When resuming: clear the trip-once haltedForDay flag. Default true. */
        public Boolean clearHaltedForDay;
        /** When resuming: reset consecutiveLosses counter to 0. Default true. */
        public Boolean clearConsecutiveLosses;
        /** When resuming: clear lastSlTime so SL cooldown is skipped. Default false. */
        public Boolean clearSlCooldown;
        /** When resuming: reset tradesToday to 0 (trade-cap recovery). Default false. */
        public Boolean resetTradesToday;
        /** Reason for audit. Required (min 5 chars). */
        public String reason;
    }
}
