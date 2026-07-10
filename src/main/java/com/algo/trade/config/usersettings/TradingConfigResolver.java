package com.algo.trade.config.usersettings;

import com.algo.trade.config.GlobalConfig;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.multiuser.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves effective trading configuration for a user using layered precedence:
 * <ol>
 *   <li>User's own per-field overrides (UserTradingSettings)</li>
 *   <li>Assigned risk profile defaults (RiskProfileDefinition)</li>
 *   <li>Global baseline (GlobalConfig)</li>
 * </ol>
 *
 * If per-user settings are disabled (config.per-user-settings.enabled=false),
 * returns global values directly.
 */
@Service
public class TradingConfigResolver {

    private static final Logger log = LoggerFactory.getLogger(TradingConfigResolver.class);

    private final UserTradingSettingsRepository userSettingsRepo;
    private final RiskProfileDefinitionRepository profileRepo;
    private final GlobalConfigService globalConfigService;
    private final ConcurrentHashMap<Long, EffectiveTradingConfig> cache = new ConcurrentHashMap<>();

    @Value("${config.per-user-settings.enabled:true}")
    private boolean perUserEnabled;

    public TradingConfigResolver(UserTradingSettingsRepository userSettingsRepo,
                                 RiskProfileDefinitionRepository profileRepo,
                                 GlobalConfigService globalConfigService) {
        this.userSettingsRepo = userSettingsRepo;
        this.profileRepo = profileRepo;
        this.globalConfigService = globalConfigService;
    }

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Resolve the effective trading config for a user by ID.
     * Results are cached per userId; call {@link #invalidateCache(Long)} on writes.
     */
    public EffectiveTradingConfig resolveForUser(Long userId) {
        return cache.computeIfAbsent(userId, this::computeConfig);
    }

    /** Alias used by TradingSettingsController. */
    public EffectiveTradingConfig resolve(Long userId) {
        return resolveForUser(userId);
    }

    /**
     * Resolve for the current thread's user (via UserContext).
     * Used by GlobalConfigService's effOrNull() delegate.
     */
    public EffectiveTradingConfig resolveForCurrentUser() {
        Long userId = UserContext.getUserId();
        return resolveForUser(userId);
    }

    /** Load the raw persisted settings for a user (sparse row). */
    public Optional<UserTradingSettings> loadSettings(Long userId) {
        return userSettingsRepo.findByUserId(userId);
    }

    /** Persist a user's settings and invalidate their cache. */
    public UserTradingSettings save(UserTradingSettings settings) {
        settings.setUpdatedAt(java.time.Instant.now());
        UserTradingSettings saved = userSettingsRepo.save(settings);
        invalidateCache(saved.getUserId());
        return saved;
    }

    /**
     * Preview what the effective config would look like with hypothetical overrides
     * (without persisting). Used by the Settings UI preview endpoint.
     */
    public EffectiveTradingConfig preview(Long userId, String profileName,
                                          BigDecimal capital, BigDecimal dailyLoss) {
        EffectiveTradingConfig base = computeConfig(userId);
        // Apply hypothetical overrides
        if (profileName != null && !profileName.isBlank()) {
            RiskProfile rp = RiskProfile.fromString(profileName);
            base.setRiskProfile(rp);
            // Re-resolve with the hypothetical profile
            RiskProfileDefinition profDef = profileRepo.findById(rp.name()).orElse(null);
            if (profDef != null) {
                applyProfileDefaults(base, profDef);
            }
        }
        if (capital != null) {
            base.setTotalCapital(capital);
            base.putProvenance("totalCapital", EffectiveTradingConfig.Source.USER);
        }
        if (dailyLoss != null) {
            base.setMaxDailyLossPercent(dailyLoss);
            base.putProvenance("maxDailyLossPercent", EffectiveTradingConfig.Source.USER);
        }
        return base;
    }

    /** Evict cached config for a specific user. Call after any write to that user's settings. */
    public void invalidateCache(Long userId) {
        cache.remove(userId);
        log.debug("Evicted effectiveTradingConfig cache for userId={}", userId);
    }

    /** Evict all cached configs. Call after a profile definition update (affects all users). */
    public void invalidateAll() {
        cache.clear();
        log.debug("Evicted all effectiveTradingConfig cache entries");
    }

    // ── Private resolution ───────────────────────────────────────────

    private EffectiveTradingConfig computeConfig(Long userId) {
        GlobalConfig gc = globalConfigService.getCached();

        if (!perUserEnabled) {
            return buildFromGlobal(gc, userId);
        }

        Optional<UserTradingSettings> userOpt = userSettingsRepo.findByUserId(userId);
        UserTradingSettings user = userOpt.orElse(null);

        RiskProfile riskProfile = (user != null && user.getRiskProfile() != null)
                ? RiskProfile.fromString(user.getRiskProfile())
                : RiskProfile.BALANCED;
        // Load the profile definition for the RESOLVED profile even when the user has NO settings row.
        // Before this, profDef was only loaded inside `if (user != null ...)`, so a user with no
        // user_trading_settings row (e.g. the secondary copy user u=8) left profDef=null and the 8
        // risk-profile fields below fell through to global_config (open=50/consec=8/lots=10) instead of
        // their Balanced profile (5/5/2). A default-profile user must still be governed by that profile.
        RiskProfileDefinition profDef = riskProfile.hasBundle()
                ? profileRepo.findById(riskProfile.name()).orElse(null)
                : null;
        // Non-global default for the 8 risk-profile fields: the in-code bundle for the resolved profile.
        // These fields NEVER fall back to global_config (per user directive 2026-06-29) — when a DB profile
        // value is missing we use the profile's bundle default. CUSTOM (no bundle) uses the BALANCED bundle
        // as the safe non-global default rather than reading global.
        RiskProfile.Bundle bundle = riskProfile.hasBundle() ? riskProfile.bundle() : RiskProfile.BALANCED.bundle();

        EffectiveTradingConfig cfg = new EffectiveTradingConfig();
        cfg.setUserId(userId);
        cfg.setRiskProfile(riskProfile);

        // ── Session preset & times ──
        String sessionPreset = user != null ? user.getSessionPreset() : "STANDARD";
        cfg.setSessionPreset(sessionPreset);

        // Times: user CUSTOM overrides → global
        cfg.setEntryStartTime(resolveString(
                "CUSTOM".equalsIgnoreCase(sessionPreset) && user != null ? user.getEntryStartTime() : null,
                null, gc.getEntryStartTime(), "entryStartTime", cfg));
        cfg.setEntryCutoffTime(resolveString(
                "CUSTOM".equalsIgnoreCase(sessionPreset) && user != null ? user.getEntryCutoffTime() : null,
                null, gc.getEntryCutoffTime(), "entryCutoffTime", cfg));
        cfg.setForcedExitTime(resolveString(
                "CUSTOM".equalsIgnoreCase(sessionPreset) && user != null ? user.getForcedExitTime() : null,
                null, gc.getForcedExitTime(), "forcedExitTime", cfg));
        cfg.setFailSafeSquareoffTime(resolveString(
                "CUSTOM".equalsIgnoreCase(sessionPreset) && user != null ? user.getFailSafeSquareoffTime() : null,
                null, gc.getFailSafeSquareoffTime(), "failSafeSquareoffTime", cfg));

        // ── Capital / daily targets (user → global) ──
        cfg.setTotalCapital(resolveBD(
                user != null ? user.getTotalCapital() : null, null, gc.getTotalCapital(), "totalCapital", cfg));
        cfg.setDailyProfitTarget(resolveBD(
                user != null ? user.getDailyProfitTarget() : null, null, gc.getDailyProfitTarget(), "dailyProfitTarget", cfg));
        // ── The 8 risk-profile fields: user-override → DB profile → profile BUNDLE default. NEVER global. ──
        cfg.setMaxDailyLossPercent(resolveBD(
                user != null ? user.getDailyLossPercent() : null,
                profDef != null ? dblToBD(profDef.getMaxDailyLossPercent()) : null,
                dblToBD(bundle.maxDailyLossPercent()), "maxDailyLossPercent", cfg));

        // ── Risk / selectivity (profile → bundle) ──
        cfg.setMaxRiskPerTradePercent(resolveBD(null,
                profDef != null ? dblToBD(profDef.getMaxRiskPerTradePercent()) : null,
                dblToBD(bundle.maxRiskPerTradePercent()), "maxRiskPerTradePercent", cfg));
        cfg.setMinSignalScorePercent(resolveBD(null,
                profDef != null ? dblToBD(profDef.getMinSignalScorePercent()) : null,
                dblToBD(bundle.minSignalScorePercent()), "minSignalScorePercent", cfg));
        cfg.setMinEnvironmentScore(resolveInt(null,
                profDef != null ? profDef.getMinEnvironmentScore() : null,
                bundle.minEnvironmentScore(), "minEnvironmentScore", cfg));

        // ── Concurrency (profile → bundle) ──
        cfg.setMaxTradesPerDay(resolveInt(null,
                profDef != null ? profDef.getMaxTradesPerDay() : null,
                bundle.maxTradesPerDay(), "maxTradesPerDay", cfg));
        cfg.setMaxConsecutiveLosses(resolveInt(null,
                profDef != null ? profDef.getMaxConsecutiveLosses() : null,
                bundle.maxConsecutiveLosses(), "maxConsecutiveLosses", cfg));
        cfg.setMaxOpenTrades(resolveInt(null,
                profDef != null ? profDef.getMaxOpenTrades() : null,
                bundle.maxOpenTrades(), "maxOpenTrades", cfg));
        cfg.setMaxLotsPerTrade(resolveInt(null,
                profDef != null ? profDef.getMaxLotsPerTrade() : null,
                bundle.maxLotsPerTrade(), "maxLotsPerTrade", cfg));
        cfg.setMaxOpenPositionsPerStrategy(resolveInt(null,
                profDef != null ? profDef.getMaxOpenPositionsPerStrategy() : null,
                gc.getMaxOpenPositionsPerStrategy(), "maxOpenPositionsPerStrategy", cfg));
        cfg.setCooldownMinutes(resolveInt(null,
                profDef != null ? profDef.getCooldownMinutes() : null,
                gc.getCooldownMinutes(), "cooldownMinutes", cfg));
        cfg.setDirectionFlipCooldownMinutes(resolveInt(null,
                profDef != null ? profDef.getDirectionFlipCooldownMinutes() : null,
                gc.getDirectionFlipCooldownMinutes(), "directionFlipCooldownMinutes", cfg));
        cfg.setMaxEntriesPerScan(resolveInt(null,
                profDef != null ? profDef.getMaxEntriesPerScan() : null,
                gc.getMaxEntriesPerScan(), "maxEntriesPerScan", cfg));
        cfg.setMaxEntriesPerScanPerUnderlying(resolveInt(null,
                profDef != null ? profDef.getMaxEntriesPerScanPerUnderlying() : null,
                gc.getMaxEntriesPerScanPerUnderlying(), "maxEntriesPerScanPerUnderlying", cfg));

        // ── Exits (profile → global) ──
        cfg.setStopLossPercent(resolveBD(null,
                profDef != null ? dblToBD(profDef.getStopLossPercent()) : null,
                gc.getStopLossPercent(), "stopLossPercent", cfg));
        cfg.setTargetPercent(resolveBD(null,
                profDef != null ? dblToBD(profDef.getTargetPercent()) : null,
                gc.getTargetPercent(), "targetPercent", cfg));
        cfg.setTrailingStopActivationPercent(resolveBD(null,
                profDef != null ? dblToBD(profDef.getTrailingStopActivationPercent()) : null,
                gc.getTrailingStopActivationPercent(), "trailingStopActivationPercent", cfg));
        cfg.setTrailingGapPercent(resolveBD(null,
                profDef != null ? dblToBD(profDef.getTrailingGapPercent()) : null,
                gc.getTrailingGapPercent(), "trailingGapPercent", cfg));
        cfg.setMaxHoldMinutes(resolveInt(null,
                profDef != null ? profDef.getMaxHoldMinutes() : null,
                gc.getMaxHoldMinutes(), "maxHoldMinutes", cfg));
        cfg.setPartialProfitBookingEnabled(resolveBool(null,
                profDef != null ? profDef.getPartialProfitBookingEnabled() : null,
                gc.isPartialProfitBookingEnabled(), "partialProfitBookingEnabled", cfg));
        cfg.setVwapExitEnabled(resolveBool(null,
                profDef != null ? profDef.getVwapExitEnabled() : null,
                gc.isVwapExitEnabled(), "vwapExitEnabled", cfg));
        cfg.setIvCollapseMaxProfitPercent(resolveBD(null,
                profDef != null ? dblToBD(profDef.getIvCollapseMaxProfitPercent()) : null,
                gc.getIvCollapseMaxProfitPercent(), "ivCollapseMaxProfitPercent", cfg));

        // ── Toggles (user → global) ──
        cfg.setManageSyncedTrades(resolveBool(
                user != null ? user.getManageSyncedTrades() : null,
                null, gc.isManageSyncedTrades(), "manageSyncedTrades", cfg));
        cfg.setAutoExits(user != null ? user.isAutoExits() : true);
        cfg.setAutoVixGate(user != null ? user.isAutoVixGate() : true);
        cfg.setAutoIvCap(user != null ? user.isAutoIvCap() : true);

        return cfg;
    }

    private EffectiveTradingConfig buildFromGlobal(GlobalConfig gc, Long userId) {
        EffectiveTradingConfig cfg = new EffectiveTradingConfig();
        cfg.setUserId(userId);
        cfg.setRiskProfile(RiskProfile.BALANCED);
        cfg.setSessionPreset("STANDARD");

        cfg.setTotalCapital(gc.getTotalCapital());
        cfg.setDailyProfitTarget(gc.getDailyProfitTarget());
        // The 8 risk-profile cap fields were removed from GlobalConfig — they now live ONLY in the
        // per-user risk profile. When falling back to "global" (no user/profile resolved), source these
        // caps from the BALANCED bundle so the cutover stays behavior-neutral.
        var balanced = RiskProfile.BALANCED.bundle();
        cfg.setMaxDailyLossPercent(java.math.BigDecimal.valueOf(balanced.maxDailyLossPercent()));
        cfg.setMaxRiskPerTradePercent(java.math.BigDecimal.valueOf(balanced.maxRiskPerTradePercent()));
        cfg.setMinSignalScorePercent(java.math.BigDecimal.valueOf(balanced.minSignalScorePercent()));
        cfg.setMinEnvironmentScore(balanced.minEnvironmentScore());
        cfg.setMaxTradesPerDay(balanced.maxTradesPerDay());
        cfg.setMaxConsecutiveLosses(balanced.maxConsecutiveLosses());
        cfg.setMaxOpenTrades(balanced.maxOpenTrades());
        cfg.setMaxLotsPerTrade(balanced.maxLotsPerTrade());
        cfg.setMaxOpenPositionsPerStrategy(gc.getMaxOpenPositionsPerStrategy());
        cfg.setCooldownMinutes(gc.getCooldownMinutes());
        cfg.setDirectionFlipCooldownMinutes(gc.getDirectionFlipCooldownMinutes());
        cfg.setMaxEntriesPerScan(gc.getMaxEntriesPerScan());
        cfg.setMaxEntriesPerScanPerUnderlying(gc.getMaxEntriesPerScanPerUnderlying());
        cfg.setEntryStartTime(gc.getEntryStartTime());
        cfg.setEntryCutoffTime(gc.getEntryCutoffTime());
        cfg.setForcedExitTime(gc.getForcedExitTime());
        cfg.setFailSafeSquareoffTime(gc.getFailSafeSquareoffTime());
        cfg.setStopLossPercent(gc.getStopLossPercent());
        cfg.setTargetPercent(gc.getTargetPercent());
        cfg.setTrailingStopActivationPercent(gc.getTrailingStopActivationPercent());
        cfg.setTrailingGapPercent(gc.getTrailingGapPercent());
        cfg.setMaxHoldMinutes(gc.getMaxHoldMinutes());
        cfg.setPartialProfitBookingEnabled(gc.isPartialProfitBookingEnabled());
        cfg.setVwapExitEnabled(gc.isVwapExitEnabled());
        cfg.setIvCollapseMaxProfitPercent(gc.getIvCollapseMaxProfitPercent());
        cfg.setManageSyncedTrades(gc.isManageSyncedTrades());

        // All provenance is GLOBAL
        for (String f : new String[]{"totalCapital", "dailyProfitTarget", "maxDailyLossPercent",
                "maxRiskPerTradePercent", "minSignalScorePercent", "minEnvironmentScore",
                "maxTradesPerDay", "maxConsecutiveLosses", "maxOpenTrades", "maxLotsPerTrade",
                "maxOpenPositionsPerStrategy", "cooldownMinutes", "directionFlipCooldownMinutes",
                "maxEntriesPerScan", "maxEntriesPerScanPerUnderlying",
                "entryStartTime", "entryCutoffTime", "forcedExitTime", "failSafeSquareoffTime",
                "stopLossPercent", "targetPercent", "trailingStopActivationPercent", "trailingGapPercent",
                "maxHoldMinutes", "partialProfitBookingEnabled", "vwapExitEnabled",
                "ivCollapseMaxProfitPercent", "manageSyncedTrades"}) {
            cfg.putProvenance(f, EffectiveTradingConfig.Source.GLOBAL);
        }
        return cfg;
    }

    /** Apply profile-level defaults to an existing config (for preview). */
    private void applyProfileDefaults(EffectiveTradingConfig cfg, RiskProfileDefinition profDef) {
        if (profDef.getMaxRiskPerTradePercent() != null) cfg.setMaxRiskPerTradePercent(BigDecimal.valueOf(profDef.getMaxRiskPerTradePercent()));
        if (profDef.getMaxDailyLossPercent() != null) cfg.setMaxDailyLossPercent(BigDecimal.valueOf(profDef.getMaxDailyLossPercent()));
        if (profDef.getMaxTradesPerDay() != null) cfg.setMaxTradesPerDay(profDef.getMaxTradesPerDay());
        if (profDef.getMaxConsecutiveLosses() != null) cfg.setMaxConsecutiveLosses(profDef.getMaxConsecutiveLosses());
        if (profDef.getMaxOpenTrades() != null) cfg.setMaxOpenTrades(profDef.getMaxOpenTrades());
        if (profDef.getMaxLotsPerTrade() != null) cfg.setMaxLotsPerTrade(profDef.getMaxLotsPerTrade());
        if (profDef.getMinSignalScorePercent() != null) cfg.setMinSignalScorePercent(BigDecimal.valueOf(profDef.getMinSignalScorePercent()));
    }

    // ── Resolution helpers ───────────────────────────────────────────

    private BigDecimal resolveBD(BigDecimal userVal, BigDecimal profileVal, BigDecimal globalVal,
                                 String field, EffectiveTradingConfig cfg) {
        if (userVal != null) { cfg.putProvenance(field, EffectiveTradingConfig.Source.USER); return userVal; }
        if (profileVal != null) { cfg.putProvenance(field, EffectiveTradingConfig.Source.PROFILE); return profileVal; }
        cfg.putProvenance(field, EffectiveTradingConfig.Source.GLOBAL);
        return globalVal;
    }

    private int resolveInt(Integer userVal, Integer profileVal, int globalVal,
                           String field, EffectiveTradingConfig cfg) {
        if (userVal != null) { cfg.putProvenance(field, EffectiveTradingConfig.Source.USER); return userVal; }
        if (profileVal != null) { cfg.putProvenance(field, EffectiveTradingConfig.Source.PROFILE); return profileVal; }
        cfg.putProvenance(field, EffectiveTradingConfig.Source.GLOBAL);
        return globalVal;
    }

    private String resolveString(String userVal, String profileVal, String globalVal,
                                 String field, EffectiveTradingConfig cfg) {
        if (userVal != null && !userVal.isBlank()) { cfg.putProvenance(field, EffectiveTradingConfig.Source.USER); return userVal; }
        if (profileVal != null && !profileVal.isBlank()) { cfg.putProvenance(field, EffectiveTradingConfig.Source.PROFILE); return profileVal; }
        cfg.putProvenance(field, EffectiveTradingConfig.Source.GLOBAL);
        return globalVal;
    }

    private boolean resolveBool(Boolean userVal, Boolean profileVal, boolean globalVal,
                                String field, EffectiveTradingConfig cfg) {
        if (userVal != null) { cfg.putProvenance(field, EffectiveTradingConfig.Source.USER); return userVal; }
        if (profileVal != null) { cfg.putProvenance(field, EffectiveTradingConfig.Source.PROFILE); return profileVal; }
        cfg.putProvenance(field, EffectiveTradingConfig.Source.GLOBAL);
        return globalVal;
    }

    private static BigDecimal dblToBD(Double v) {
        return v != null ? BigDecimal.valueOf(v) : null;
    }
}
