package com.algo.trade.controller;

import com.algo.trade.config.usersettings.EffectiveTradingConfig;
import com.algo.trade.config.usersettings.RiskProfile;
import com.algo.trade.config.usersettings.RiskProfileDefinition;
import com.algo.trade.config.usersettings.RiskProfileService;
import com.algo.trade.config.usersettings.TradingConfigResolver;
import com.algo.trade.config.usersettings.UserTradingSettings;
import com.algo.trade.multiuser.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-user Trading Settings API backing the redesigned settings page.
 *
 * <p>Layer-2 (per-user) only; the global admin baseline stays on {@code /global-config}. Every value
 * the user sees is resolved through {@link TradingConfigResolver} (user → profile → global), and the
 * response carries provenance so the UI can label "auto / global / your value" and offer reset-to-auto.
 */
@RestController
@RequestMapping("/trading-settings")
public class TradingSettingsController {

    private static final Logger log = LoggerFactory.getLogger(TradingSettingsController.class);

    private final TradingConfigResolver resolver;
    private final RiskProfileService riskProfileService;

    public TradingSettingsController(TradingConfigResolver resolver, RiskProfileService riskProfileService) {
        this.resolver = resolver;
        this.riskProfileService = riskProfileService;
    }

    /** Resolved effective config + the raw stored Layer-2 row for the current user. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        Long uid = UserContext.getUserId();
        EffectiveTradingConfig eff = resolver.resolve(uid);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("resolved", toDto(eff));
        out.put("raw", rawDto(resolver.loadSettings(uid).orElse(null)));
        return out;
    }

    /** Upsert the current user's sparse settings; returns the freshly resolved config. */
    @PutMapping("/me")
    public ResponseEntity<Map<String, Object>> update(@RequestBody Map<String, Object> body) {
        Long uid = UserContext.getUserId();
        try {
            UserTradingSettings s = resolver.loadSettings(uid).orElseGet(() -> new UserTradingSettings(uid));
            applyBody(s, body);
            validate(s);
            s.setUpdatedBy(UserContext.getUserEmail());
            resolver.save(s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("resolved", toDto(resolver.resolve(uid)));
            out.put("raw", rawDto(s));
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** Live preview of a hypothetical profile/capital/daily-loss without saving. */
    @GetMapping("/preview")
    public Map<String, Object> preview(@RequestParam(required = false) String profile,
                                       @RequestParam(required = false) BigDecimal capital,
                                       @RequestParam(required = false) BigDecimal dailyLoss) {
        Long uid = UserContext.getUserId();
        return toDto(resolver.preview(uid, profile, capital, dailyLoss));
    }

    /** The persisted (superuser-editable) profile definitions, for the editor + assignment dropdown. */
    @GetMapping("/profiles")
    @PreAuthorize("hasRole('SUPERUSER')")
    public List<Map<String, Object>> profiles() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (RiskProfile p : RiskProfile.values()) {
            if (!p.hasBundle()) continue; // skip CUSTOM
            RiskProfileDefinition d = riskProfileService.get(p.name());
            if (d == null) d = new RiskProfileDefinition(p.name(), p.bundle());
            list.add(profileDto(d));
        }
        return list;
    }

    /** SUPERUSER — redefine what a profile means (risk %, trades/day, lots, …). */
    @PutMapping("/profiles/{name}")
    @PreAuthorize("hasRole('SUPERUSER')")
    public ResponseEntity<Map<String, Object>> updateProfile(@PathVariable String name,
                                                             @RequestBody Map<String, Object> body) {
        try {
            RiskProfile p = RiskProfile.fromString(name);
            RiskProfileDefinition cur = riskProfileService.get(p.name());
            RiskProfile.Bundle base = cur != null ? cur.toBundle() : p.bundle();
            // Strict parsing: a field that's present but non-numeric is a 400, not a silent fall-back
            // to the old value (which would mask a typo). Absent fields keep the current value.
            RiskProfile.Bundle merged = new RiskProfile.Bundle(
                    reqDbl(body, "maxRiskPerTradePercent", base.maxRiskPerTradePercent()),
                    reqDbl(body, "maxDailyLossPercent", base.maxDailyLossPercent()),
                    reqInt(body, "maxTradesPerDay", base.maxTradesPerDay()),
                    reqInt(body, "maxConsecutiveLosses", base.maxConsecutiveLosses()),
                    reqInt(body, "maxOpenTrades", base.maxOpenTrades()),
                    reqInt(body, "maxLotsPerTrade", base.maxLotsPerTrade()),
                    reqInt(body, "maxOpenPositionsPerStrategy", base.maxOpenPositionsPerStrategy()),
                    reqDbl(body, "minSignalScorePercent", base.minSignalScorePercent()),
                    reqInt(body, "minEnvironmentScore", base.minEnvironmentScore()),
                    reqInt(body, "cooldownMinutes", base.cooldownMinutes()),
                    reqInt(body, "directionFlipCooldownMinutes", base.directionFlipCooldownMinutes()),
                    reqInt(body, "maxEntriesPerScan", base.maxEntriesPerScan()),
                    reqInt(body, "maxEntriesPerScanPerUnderlying", base.maxEntriesPerScanPerUnderlying()));
            validateBundle(merged); // range-check BEFORE persisting — rejects typos (99999 lots, negatives)
            RiskProfileDefinition saved = riskProfileService.update(p.name(), merged, UserContext.getUserEmail());
            // Exit-style fields (PROFILE-static) — not part of the 13-field bundle; set directly.
            saved.setStopLossPercent(reqDbl(body, "stopLossPercent", saved.getStopLossPercent()));
            saved.setTargetPercent(reqDbl(body, "targetPercent", saved.getTargetPercent()));
            saved.setTrailingStopActivationPercent(reqDbl(body, "trailingStopActivationPercent", saved.getTrailingStopActivationPercent()));
            saved.setTrailingGapPercent(reqDbl(body, "trailingGapPercent", saved.getTrailingGapPercent()));
            saved.setMaxHoldMinutes(reqInt(body, "maxHoldMinutes", saved.getMaxHoldMinutes()));
            saved.setPartialProfitBookingEnabled(bool(body, "partialProfitBookingEnabled", saved.isPartialProfitBookingEnabled()));
            saved.setVwapExitEnabled(bool(body, "vwapExitEnabled", saved.isVwapExitEnabled()));
            saved.setIvCollapseMaxProfitPercent(reqDbl(body, "ivCollapseMaxProfitPercent", saved.getIvCollapseMaxProfitPercent()));
            validateExitFields(saved); // range-check exit-style fields before the final save
            saved = riskProfileService.save(saved);
            resolver.invalidateAll(); // every assigned user's snapshot just changed
            log.info("[TradingSettings] profile {} redefined by {}", p.name(), UserContext.getUserEmail());
            return ResponseEntity.ok(profileDto(saved));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** SUPERUSER — reset all profiles to the current code defaults. Fixes stale DB values. */
    @PostMapping("/profiles/reseed")
    @PreAuthorize("hasRole('SUPERUSER')")
    public ResponseEntity<Map<String, Object>> reseedProfiles() {
        riskProfileService.reseedFromDefaults(UserContext.getUserEmail());
        resolver.invalidateAll();
        log.info("[TradingSettings] All profiles re-seeded from code defaults by {}", UserContext.getUserEmail());
        return ResponseEntity.ok(Map.of("success", true, "message", "All profiles reset to code defaults"));
    }

    /** SUPERUSER — assign a risk profile to a user (their settings page does NOT let them pick it). */
    @PutMapping("/admin/{userId}/profile")
    @PreAuthorize("hasRole('SUPERUSER')")
    public ResponseEntity<Map<String, Object>> assignProfile(@PathVariable Long userId,
                                                             @RequestBody Map<String, Object> body) {
        RiskProfile p = RiskProfile.fromString(str(body.get("riskProfile")));
        UserTradingSettings s = resolver.loadSettings(userId).orElseGet(() -> new UserTradingSettings(userId));
        s.setRiskProfile(p.name());
        s.setUpdatedBy(UserContext.getUserEmail());
        resolver.save(s);
        log.info("[TradingSettings] user {} assigned profile {} by {}", userId, p.name(), UserContext.getUserEmail());
        return ResponseEntity.ok(Map.of("userId", userId, "riskProfile", p.name()));
    }

    private Map<String, Object> profileDto(RiskProfileDefinition d) {
        RiskProfile.Bundle bd = d.toBundle();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", d.getName());
        m.put("maxRiskPerTradePercent", bd.maxRiskPerTradePercent());
        m.put("maxDailyLossPercent", bd.maxDailyLossPercent());
        m.put("maxTradesPerDay", bd.maxTradesPerDay());
        m.put("maxConsecutiveLosses", bd.maxConsecutiveLosses());
        m.put("maxOpenTrades", bd.maxOpenTrades());
        m.put("maxLotsPerTrade", bd.maxLotsPerTrade());
        m.put("maxOpenPositionsPerStrategy", bd.maxOpenPositionsPerStrategy());
        m.put("minSignalScorePercent", bd.minSignalScorePercent());
        m.put("minEnvironmentScore", bd.minEnvironmentScore());
        m.put("cooldownMinutes", bd.cooldownMinutes());
        m.put("directionFlipCooldownMinutes", bd.directionFlipCooldownMinutes());
        m.put("maxEntriesPerScan", bd.maxEntriesPerScan());
        m.put("maxEntriesPerScanPerUnderlying", bd.maxEntriesPerScanPerUnderlying());
        // Exit-style
        m.put("stopLossPercent", d.getStopLossPercent());
        m.put("targetPercent", d.getTargetPercent());
        m.put("trailingStopActivationPercent", d.getTrailingStopActivationPercent());
        m.put("trailingGapPercent", d.getTrailingGapPercent());
        m.put("maxHoldMinutes", d.getMaxHoldMinutes());
        m.put("partialProfitBookingEnabled", d.isPartialProfitBookingEnabled());
        m.put("vwapExitEnabled", d.isVwapExitEnabled());
        m.put("ivCollapseMaxProfitPercent", d.getIvCollapseMaxProfitPercent());
        return m;
    }

    private static boolean bool(Map<String, Object> b, String k, boolean dflt) {
        Object v = b.get(k);
        if (v == null) return dflt;
        if (v instanceof Boolean bn) return bn;
        return Boolean.parseBoolean(String.valueOf(v));
    }

    /** Strict double: absent → default; present-but-non-numeric → 400 (never silently keep old value). */
    private static double reqDbl(Map<String, Object> b, String k, double dflt) {
        Object v = b.get(k);
        if (v == null) return dflt;
        try { return Double.parseDouble(String.valueOf(v)); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(k + " must be a number, got: " + v); }
    }

    /** Strict int: absent → default; present-but-non-numeric → 400. */
    private static int reqInt(Map<String, Object> b, String k, int dflt) {
        Object v = b.get(k);
        if (v == null) return dflt;
        try { return (int) Math.round(Double.parseDouble(String.valueOf(v))); }
        catch (NumberFormatException e) { throw new IllegalArgumentException(k + " must be a number, got: " + v); }
    }

    private static void range(String k, double v, double min, double max) {
        if (Double.isNaN(v) || v < min || v > max)
            throw new IllegalArgumentException(k + " must be between " + min + " and " + max + " (got " + v + ")");
    }

    private static void rangeI(String k, int v, int min, int max) {
        if (v < min || v > max)
            throw new IllegalArgumentException(k + " must be between " + min + " and " + max + " (got " + v + ")");
    }

    /** Range-check a profile bundle before it reaches live trading. Generous bounds — catches typos
     *  (e.g. {@code maxLotsPerTrade=99999}, negatives) without rejecting legitimate configurations. */
    private static void validateBundle(RiskProfile.Bundle b) {
        range("maxRiskPerTradePercent", b.maxRiskPerTradePercent(), 0, 100);
        range("maxDailyLossPercent", b.maxDailyLossPercent(), 0, 100);
        rangeI("maxTradesPerDay", b.maxTradesPerDay(), 0, 1000);
        rangeI("maxConsecutiveLosses", b.maxConsecutiveLosses(), 0, 100);
        rangeI("maxOpenTrades", b.maxOpenTrades(), 0, 100);
        rangeI("maxLotsPerTrade", b.maxLotsPerTrade(), 1, 1000);
        rangeI("maxOpenPositionsPerStrategy", b.maxOpenPositionsPerStrategy(), 0, 100);
        range("minSignalScorePercent", b.minSignalScorePercent(), 0, 100);
        rangeI("minEnvironmentScore", b.minEnvironmentScore(), 0, 100);
        rangeI("cooldownMinutes", b.cooldownMinutes(), 0, 1440);
        rangeI("directionFlipCooldownMinutes", b.directionFlipCooldownMinutes(), 0, 1440);
        rangeI("maxEntriesPerScan", b.maxEntriesPerScan(), 0, 100);
        rangeI("maxEntriesPerScanPerUnderlying", b.maxEntriesPerScanPerUnderlying(), 0, 100);
    }

    /** Range-check the exit-style profile fields before persisting. These getters are nullable
     *  (a null field means "inherit" and is left unchecked). */
    private static void validateExitFields(RiskProfileDefinition d) {
        if (d.getStopLossPercent() != null) range("stopLossPercent", d.getStopLossPercent(), 0, 100);
        if (d.getTargetPercent() != null) range("targetPercent", d.getTargetPercent(), 0, 1000);
        if (d.getTrailingStopActivationPercent() != null) range("trailingStopActivationPercent", d.getTrailingStopActivationPercent(), 0, 1000);
        if (d.getTrailingGapPercent() != null) range("trailingGapPercent", d.getTrailingGapPercent(), 0, 100);
        if (d.getMaxHoldMinutes() != null) rangeI("maxHoldMinutes", d.getMaxHoldMinutes(), 0, 1440);
        if (d.getIvCollapseMaxProfitPercent() != null) range("ivCollapseMaxProfitPercent", d.getIvCollapseMaxProfitPercent(), 0, 1000);
    }

    // ── mapping ──────────────────────────────────────────────────────────────────
    private void applyBody(UserTradingSettings s, Map<String, Object> b) {
        // NOTE: riskProfile is intentionally NOT settable here — profile assignment is SUPERUSER-only
        // via PUT /trading-settings/admin/{userId}/profile. Regular users cannot self-assign.
        if (b.containsKey("totalCapital")) s.setTotalCapital(decOrNull(b.get("totalCapital")));
        if (b.containsKey("dailyLossPercent")) s.setDailyLossPercent(decOrNull(b.get("dailyLossPercent")));
        if (b.containsKey("dailyProfitTarget")) s.setDailyProfitTarget(decOrNull(b.get("dailyProfitTarget")));
        if (b.containsKey("sessionPreset")) s.setSessionPreset("CUSTOM".equalsIgnoreCase(str(b.get("sessionPreset"))) ? "CUSTOM" : "STANDARD");
        if (b.containsKey("entryStartTime")) s.setEntryStartTime(str(b.get("entryStartTime")));
        if (b.containsKey("entryCutoffTime")) s.setEntryCutoffTime(str(b.get("entryCutoffTime")));
        if (b.containsKey("forcedExitTime")) s.setForcedExitTime(str(b.get("forcedExitTime")));
        if (b.containsKey("failSafeSquareoffTime")) s.setFailSafeSquareoffTime(str(b.get("failSafeSquareoffTime")));
        if (b.containsKey("manageSyncedTrades")) s.setManageSyncedTrades(boolOrNull(b.get("manageSyncedTrades")));
        if (b.containsKey("autoExits")) s.setAutoExits(Boolean.TRUE.equals(boolOrNull(b.get("autoExits"))));
        if (b.containsKey("autoVixGate")) s.setAutoVixGate(Boolean.TRUE.equals(boolOrNull(b.get("autoVixGate"))));
        if (b.containsKey("autoIvCap")) s.setAutoIvCap(Boolean.TRUE.equals(boolOrNull(b.get("autoIvCap"))));
        if (b.containsKey("advancedOverrides")) {
            Object v = b.get("advancedOverrides");
            s.setAdvancedOverrides(v == null ? null : String.valueOf(v));
        }
    }

    private void validate(UserTradingSettings s) {
        if (s.getTotalCapital() != null && s.getTotalCapital().compareTo(BigDecimal.ZERO) < 0)
            throw new IllegalArgumentException("totalCapital must be >= 0");
        if (s.getDailyLossPercent() != null
                && (s.getDailyLossPercent().compareTo(BigDecimal.ZERO) < 0
                    || s.getDailyLossPercent().compareTo(BigDecimal.valueOf(100)) > 0))
            throw new IllegalArgumentException("dailyLossPercent must be between 0 and 100");
        if ("CUSTOM".equalsIgnoreCase(s.getSessionPreset())) {
            requireTime(s.getEntryStartTime(), "entryStartTime");
            requireTime(s.getEntryCutoffTime(), "entryCutoffTime");
        }
    }

    private void requireTime(String v, String field) {
        if (v == null || !v.matches("^([01]?\\d|2[0-3]):[0-5]\\d$"))
            throw new IllegalArgumentException(field + " must be HH:mm when session preset is CUSTOM");
    }

    private Map<String, Object> toDto(EffectiveTradingConfig c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", c.getUserId());
        m.put("riskProfile", c.getRiskProfile().name());
        m.put("totalCapital", c.getTotalCapital());
        m.put("maxRiskPerTradePercent", c.getMaxRiskPerTradePercent());
        m.put("maxDailyLossPercent", c.getMaxDailyLossPercent());
        m.put("dailyProfitTarget", c.getDailyProfitTarget());
        m.put("maxTradesPerDay", c.getMaxTradesPerDay());
        m.put("maxConsecutiveLosses", c.getMaxConsecutiveLosses());
        m.put("maxOpenTrades", c.getMaxOpenTrades());
        m.put("maxLotsPerTrade", c.getMaxLotsPerTrade());
        m.put("maxOpenPositionsPerStrategy", c.getMaxOpenPositionsPerStrategy());
        m.put("cooldownMinutes", c.getCooldownMinutes());
        m.put("directionFlipCooldownMinutes", c.getDirectionFlipCooldownMinutes());
        m.put("maxEntriesPerScan", c.getMaxEntriesPerScan());
        m.put("maxEntriesPerScanPerUnderlying", c.getMaxEntriesPerScanPerUnderlying());
        m.put("minSignalScorePercent", c.getMinSignalScorePercent());
        m.put("minEnvironmentScore", c.getMinEnvironmentScore());
        m.put("sessionPreset", c.getSessionPreset());
        m.put("entryStartTime", c.getEntryStartTime());
        m.put("entryCutoffTime", c.getEntryCutoffTime());
        m.put("forcedExitTime", c.getForcedExitTime());
        m.put("failSafeSquareoffTime", c.getFailSafeSquareoffTime());
        m.put("manageSyncedTrades", c.isManageSyncedTrades());
        m.put("autoExits", c.isAutoExits());
        m.put("autoVixGate", c.isAutoVixGate());
        m.put("autoIvCap", c.isAutoIvCap());
        Map<String, String> prov = new LinkedHashMap<>();
        c.getProvenance().forEach((k, v) -> prov.put(k, v.name()));
        m.put("provenance", prov);
        return m;
    }

    private Map<String, Object> rawDto(UserTradingSettings s) {
        if (s == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", s.getUserId());
        m.put("riskProfile", s.getRiskProfile());
        m.put("totalCapital", s.getTotalCapital());
        m.put("dailyLossPercent", s.getDailyLossPercent());
        m.put("dailyProfitTarget", s.getDailyProfitTarget());
        m.put("sessionPreset", s.getSessionPreset());
        m.put("entryStartTime", s.getEntryStartTime());
        m.put("entryCutoffTime", s.getEntryCutoffTime());
        m.put("forcedExitTime", s.getForcedExitTime());
        m.put("failSafeSquareoffTime", s.getFailSafeSquareoffTime());
        m.put("manageSyncedTrades", s.getManageSyncedTrades());
        m.put("autoExits", s.isAutoExits());
        m.put("autoVixGate", s.isAutoVixGate());
        m.put("autoIvCap", s.isAutoIvCap());
        m.put("advancedOverrides", s.getAdvancedOverrides());
        return m;
    }

    private static String str(Object o) { return o == null ? null : String.valueOf(o); }

    private static BigDecimal decOrNull(Object o) {
        if (o == null) return null;
        String v = String.valueOf(o).trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return null;
        try { return new BigDecimal(v); } catch (NumberFormatException e) { return null; }
    }

    private static Boolean boolOrNull(Object o) {
        if (o == null) return null;
        if (o instanceof Boolean bn) return bn;
        String v = String.valueOf(o).trim();
        if (v.isEmpty() || "null".equalsIgnoreCase(v)) return null;
        return Boolean.parseBoolean(v);
    }
}
