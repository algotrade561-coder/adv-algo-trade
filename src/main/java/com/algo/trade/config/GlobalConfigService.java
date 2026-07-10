package com.algo.trade.config;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;

/**
 * Manages the single-row {@link GlobalConfig} entity.
 * Seeds from {@link TradingProperties} on first startup; caches in memory for hot-path reads.
 */
@Service
@DependsOn("configSeedLoader")
public class GlobalConfigService {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigService.class);

    private final GlobalConfigRepository repository;
    private final TradingProperties tradingProperties;
    private final PositionSyncProperties positionSyncProperties;
    private volatile GlobalConfig cached;

    // ── Per-user layering (Settings redesign, 2026-06-25) ─────────────────────────────
    // When present + enabled, the per-user-relevant getters below resolve through the
    // TradingConfigResolver (user override → risk profile → this global baseline) so RiskEngine,
    // MarketGuard, exit monitors and strategies become per-user-aware with ZERO call-site changes.
    // Behavior-neutral: a user with no row / profile=BALANCED resolves to today's global values.
    // @Lazy breaks the cycle (resolver depends on this service); reads are fail-safe (fall back to
    // the cached entity on any error), and the resolver reads Layer-1 from getCached() — no recursion.
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.config.usersettings.TradingConfigResolver tradingConfigResolver;

    @org.springframework.beans.factory.annotation.Value("${config.per-user-settings.enabled:true}")
    private boolean perUserSettingsEnabled;

    /** Resolved per-user snapshot for the current thread's user, or null to use the global baseline. */
    private com.algo.trade.config.usersettings.EffectiveTradingConfig effOrNull() {
        if (!perUserSettingsEnabled || tradingConfigResolver == null) return null;
        try {
            return tradingConfigResolver.resolveForCurrentUser();
        } catch (Exception e) {
            return null;
        }
    }

    public GlobalConfigService(GlobalConfigRepository repository,
                                TradingProperties tradingProperties,
                                PositionSyncProperties positionSyncProperties) {
        this.repository = repository;
        this.tradingProperties = tradingProperties;
        this.positionSyncProperties = positionSyncProperties;
    }

    @PostConstruct
    void init() {
        var existing = repository.findById(1L);
        if (existing.isPresent()) {
            cached = existing.get();
            log.info("GlobalConfig loaded from database (id=1)");
        } else {
            GlobalConfig seeded = new GlobalConfig(tradingProperties);
            // Seed manage-synced-trades from position-sync.* YAML for first-boot installs.
            // Subsequent runtime changes via the Settings UI take precedence and persist.
            seeded.setManageSyncedTrades(positionSyncProperties.manageSyncedTrades());
            cached = repository.save(seeded);
            log.info("GlobalConfig seeded from TradingProperties (YAML defaults), manageSyncedTrades={}",
                    seeded.isManageSyncedTrades());
        }
        log.info("[Config] Effective runtime config: cooldown={}min, maxOpenTrades={}, maxTradesPerDay={}, entryCutoff={}, forcedExit={}, maxDailyLoss={}%, envScore={}",
            cached.getCooldownMinutes(), getMaxOpenTrades(), getMaxTradesPerDay(),
            cached.getEntryCutoffTimeAsLocalTime(), cached.getForcedExitTimeAsLocalTime(),
            getMaxDailyLossPercent(), getMinEnvironmentScore());
    }

    // ── Full entity accessor ──────────────────────────────────

    /** Returns the cached entity for consumers that need the whole object (e.g., ConfigSnapshot). */
    public GlobalConfig getCached() {
        return cached;
    }

    // ── Entry accessors ───────────────────────────────────────

    public Timeframe getTimeframe() { return cached.getTimeframe(); }

    public Timeframe getTrendTimeframe() { return cached.getTrendTimeframe(); }

    public List<OptionType> getEnabledOptionTypes() { return cached.getEnabledOptionTypesAsList(); }

    public List<UnderlyingSymbol> getEnabledUnderlyings() { return cached.getEnabledUnderlyingsAsList(); }

    public void persistEnabledUnderlyings(List<UnderlyingSymbol> underlyings) {
        cached.setEnabledUnderlyings(underlyings);
        repository.save(cached);
        log.info("GlobalConfig enabledUnderlyings persisted: {}", underlyings);
    }

    public boolean isVwapFilterEnabled() { return cached.isVwapFilterEnabled(); }

    public boolean isTrendFilterEnabled() { return cached.isTrendFilterEnabled(); }

    public BigDecimal getVolumeSpikeMultiplier() { return cached.getVolumeSpikeMultiplier(); }

    public BigDecimal getBreakoutBufferPercent() { return cached.getBreakoutBufferPercent(); }

    public int getBreakoutLookback() { return cached.getBreakoutLookback(); }

    public int getVolumeLookback() { return cached.getVolumeLookback(); }

    public BigDecimal getBullishImbalanceThreshold() { return cached.getBullishImbalanceThreshold(); }

    public BigDecimal getBearishImbalanceThreshold() { return cached.getBearishImbalanceThreshold(); }

    public long getMinLiquidityVolume() { return cached.getMinLiquidityVolume(); }

    public BigDecimal getMaxIvPercent() { return cached.getMaxIvPercent(); }

    public BigDecimal getVixMinForLongPremium() { return cached.getVixMinForLongPremium(); }
    public BigDecimal getVixMinForShortPremium() { return cached.getVixMinForShortPremium(); }
    public BigDecimal getVixMaxForShortPremium() { return cached.getVixMaxForShortPremium(); }

    public BigDecimal getMinSignalScorePercent() { var e = effOrNull(); return e != null ? e.getMinSignalScorePercent() : java.math.BigDecimal.valueOf(com.algo.trade.config.usersettings.RiskProfile.BALANCED.bundle().minSignalScorePercent()); }

    public int getMinEnvironmentScore() { var e = effOrNull(); return e != null ? e.getMinEnvironmentScore() : com.algo.trade.config.usersettings.RiskProfile.BALANCED.bundle().minEnvironmentScore(); }

    public boolean isCeOiSupportRequired() { return cached.isCeOiSupportRequired(); }

    public boolean isPeOiSupportRequired() { return cached.isPeOiSupportRequired(); }

    public boolean isCeOiDivergenceFilterEnabled() { return cached.isCeOiDivergenceFilterEnabled(); }

    public boolean isPeOiDivergenceFilterEnabled() { return cached.isPeOiDivergenceFilterEnabled(); }

    public BigDecimal getOiDivergenceMultiplier() { return cached.getOiDivergenceMultiplier(); }

    public long getOiDivergenceMinChange() { return cached.getOiDivergenceMinChange(); }

    public int getCeBreakoutConfirmationCandles() { return cached.getCeBreakoutConfirmationCandles(); }

    public int getPeBreakoutConfirmationCandles() { return cached.getPeBreakoutConfirmationCandles(); }

    public LocalTime getEntryStartTime() { var e = effOrNull(); return e != null ? LocalTime.parse(e.getEntryStartTime()) : cached.getEntryStartTimeAsLocalTime(); }

    public LocalTime getEntryCutoffTime() { var e = effOrNull(); return e != null ? LocalTime.parse(e.getEntryCutoffTime()) : cached.getEntryCutoffTimeAsLocalTime(); }

    public boolean isAllowFirstMinutesEntry() { return cached.isAllowFirstMinutesEntry(); }

    public int getNoEntryFirstMinutes() { return cached.getNoEntryFirstMinutes(); }

    public boolean isRsiFilterEnabled() { return cached.isRsiFilterEnabled(); }

    public int getRsiPeriod() { return cached.getRsiPeriod(); }

    public BigDecimal getRsiCeBuyThreshold() { return cached.getRsiCeBuyThreshold(); }

    public BigDecimal getRsiPeSellThreshold() { return cached.getRsiPeSellThreshold(); }

    // ── Exit accessors ────────────────────────────────────────

    public BigDecimal getStopLossPercent() { var e = effOrNull(); return e != null ? e.getStopLossPercent() : cached.getStopLossPercent(); }

    public BigDecimal getTargetPercent() { var e = effOrNull(); return e != null ? e.getTargetPercent() : cached.getTargetPercent(); }

    public BigDecimal getTrailingStopActivationPercent() { var e = effOrNull(); return e != null ? e.getTrailingStopActivationPercent() : cached.getTrailingStopActivationPercent(); }

    public BigDecimal getTrailingGapPercent() { var e = effOrNull(); return e != null ? e.getTrailingGapPercent() : cached.getTrailingGapPercent(); }

    public LocalTime getForcedExitTime() { var e = effOrNull(); return e != null ? LocalTime.parse(e.getForcedExitTime()) : cached.getForcedExitTimeAsLocalTime(); }

    public boolean isPartialProfitBookingEnabled() { var e = effOrNull(); return e != null ? e.isPartialProfitBookingEnabled() : cached.isPartialProfitBookingEnabled(); }
    public boolean isVwapExitEnabled() { var e = effOrNull(); return e != null ? e.isVwapExitEnabled() : cached.isVwapExitEnabled(); }

    public boolean isGlobalExitOverride() { return cached.isGlobalExitOverride(); }

    public int getMaxHoldMinutes() { var e = effOrNull(); return e != null ? e.getMaxHoldMinutes() : cached.getMaxHoldMinutes(); }

    /**
     * Hot-path accessor for the runtime "manage synced trades" toggle.
     * Authoritative source for ExecutionEngine / LivePositionExitMonitor /
     * GracefulShutdownHandler. Initial value comes from position-sync.* YAML
     * on first boot; thereafter editable from the Settings UI.
     */
    public boolean isManageSyncedTrades() { var e = effOrNull(); return e != null ? e.isManageSyncedTrades() : cached.isManageSyncedTrades(); }

    // ── Risk accessors ────────────────────────────────────────

    public BigDecimal getTotalCapital() { var e = effOrNull(); return e != null ? e.getTotalCapital() : cached.getTotalCapital(); }

    public BigDecimal getMaxRiskPerTradePercent() { var e = effOrNull(); return e != null ? e.getMaxRiskPerTradePercent() : java.math.BigDecimal.valueOf(com.algo.trade.config.usersettings.RiskProfile.BALANCED.bundle().maxRiskPerTradePercent()); }

    public BigDecimal getMaxDailyLossPercent() { var e = effOrNull(); return e != null ? e.getMaxDailyLossPercent() : java.math.BigDecimal.valueOf(com.algo.trade.config.usersettings.RiskProfile.BALANCED.bundle().maxDailyLossPercent()); }

    public int getMaxTradesPerDay() { var e = effOrNull(); return e != null ? e.getMaxTradesPerDay() : com.algo.trade.config.usersettings.RiskProfile.BALANCED.bundle().maxTradesPerDay(); }

    public int getMaxConsecutiveLosses() { var e = effOrNull(); return e != null ? e.getMaxConsecutiveLosses() : com.algo.trade.config.usersettings.RiskProfile.BALANCED.bundle().maxConsecutiveLosses(); }

    public int getMaxOpenTrades() { var e = effOrNull(); return e != null ? e.getMaxOpenTrades() : com.algo.trade.config.usersettings.RiskProfile.BALANCED.bundle().maxOpenTrades(); }

    public int getCooldownMinutes() { var e = effOrNull(); return e != null ? e.getCooldownMinutes() : cached.getCooldownMinutes(); }

    public int getDirectionFlipCooldownMinutes() { var e = effOrNull(); return e != null ? e.getDirectionFlipCooldownMinutes() : cached.getDirectionFlipCooldownMinutes(); }

    public int getMaxOpenPositionsPerStrategy() { var e = effOrNull(); return e != null ? e.getMaxOpenPositionsPerStrategy() : cached.getMaxOpenPositionsPerStrategy(); }

    /** V5 avalanche master switch (GLOBAL — the avalanche scanner runs as u:sys). NULL = enabled.
     *  Hot: reads the cached entity, refreshed on every UI save. */
    public boolean isAvalancheTradingEnabled() {
        Boolean v = cached != null ? cached.getAvalancheTradingEnabled() : null;
        return v == null || v;
    }

    public BigDecimal getDailyProfitTarget() { var e = effOrNull(); return e != null ? e.getDailyProfitTarget() : cached.getDailyProfitTarget(); }

    public int getMaxLotsPerTrade() { var e = effOrNull(); return e != null ? e.getMaxLotsPerTrade() : com.algo.trade.config.usersettings.RiskProfile.BALANCED.bundle().maxLotsPerTrade(); }

    public BigDecimal getMlVirtualTradeThreshold() { return cached.getMlVirtualTradeThreshold(); }

    /** V5 episode-suspension threshold override (system-wide — the memory engine is global, not
     *  per-user). NULL = use the engine's yml default; 0 = suspension disabled. Hot (DB-cached). */
    public Integer getMemorySuspensionAfterLosses() { return cached.getMemorySuspensionAfterLosses(); }

    public int getLimitOrderCancelMinutes() { return cached.getLimitOrderCancelMinutes(); }
    public LocalTime getFailSafeSquareoffTime() { var e = effOrNull(); return e != null ? LocalTime.parse(e.getFailSafeSquareoffTime()) : cached.getFailSafeSquareoffTimeAsLocalTime(); }
    public int getMaxPendingOrders() { return cached.getMaxPendingOrders(); }
    public BigDecimal getIvCollapseExitThresholdPercent() { return cached.getIvCollapseExitThresholdPercent(); }
    public BigDecimal getIvCollapseMaxProfitPercent() { var e = effOrNull(); return e != null ? e.getIvCollapseMaxProfitPercent() : cached.getIvCollapseMaxProfitPercent(); }
    public int getMaxEntriesPerScanPerUnderlying() { var e = effOrNull(); return e != null ? e.getMaxEntriesPerScanPerUnderlying() : cached.getMaxEntriesPerScanPerUnderlying(); }
    public int getMaxEntriesPerScan() { var e = effOrNull(); return e != null ? e.getMaxEntriesPerScan() : cached.getMaxEntriesPerScan(); }

    // ── Write operations ──────────────────────────────────────

    /**
     * Validates and persists the given config, then refreshes the in-memory cache.
     *
     * @param config the updated configuration
     * @return the persisted entity
     * @throws IllegalArgumentException if validation fails
     */
    public GlobalConfig update(GlobalConfig config) {
        validate(config);
        config.setId(1L);
        cached = repository.save(config);
        if (tradingConfigResolver != null) tradingConfigResolver.invalidateAll(); // baseline moved → drop resolved snapshots
        log.info("GlobalConfig updated and cache refreshed");
        return cached;
    }

    /**
     * Resets the config to YAML seed defaults from {@link TradingProperties}, persists, and refreshes cache.
     *
     * @return the reset entity
     */
    public GlobalConfig resetToDefaults() {
        GlobalConfig defaults = new GlobalConfig(tradingProperties);
        defaults.setManageSyncedTrades(positionSyncProperties.manageSyncedTrades());
        defaults.setId(1L);
        cached = repository.save(defaults);
        if (tradingConfigResolver != null) tradingConfigResolver.invalidateAll();
        log.info("GlobalConfig reset to TradingProperties defaults");
        return cached;
    }

    // ── Validation ────────────────────────────────────────────

    private void validate(GlobalConfig config) {
        if (config.getStopLossPercent() != null && config.getStopLossPercent().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("stopLossPercent must be >= 0");
        }
        if (config.getTargetPercent() != null && config.getTargetPercent().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("targetPercent must be >= 0");
        }
        if (config.getTotalCapital() != null && config.getTotalCapital().compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("totalCapital must be >= 0");
        }
        LocalTime start = config.getEntryStartTimeAsLocalTime();
        LocalTime cutoff = config.getEntryCutoffTimeAsLocalTime();
        if (cutoff != null && start != null && !cutoff.isAfter(start)) {
            throw new IllegalArgumentException("entryCutoffTime must be after entryStartTime");
        }
        LocalTime forcedExit = config.getForcedExitTimeAsLocalTime();
        if (forcedExit != null && cutoff != null && !forcedExit.isAfter(cutoff)) {
            throw new IllegalArgumentException("forcedExitTime must be after entryCutoffTime");
        }
    }
}
