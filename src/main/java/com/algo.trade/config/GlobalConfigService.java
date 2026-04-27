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
@DependsOn("databaseSchemaMigration")
public class GlobalConfigService {

    private static final Logger log = LoggerFactory.getLogger(GlobalConfigService.class);

    private final GlobalConfigRepository repository;
    private final TradingProperties tradingProperties;
    private volatile GlobalConfig cached;

    public GlobalConfigService(GlobalConfigRepository repository, TradingProperties tradingProperties) {
        this.repository = repository;
        this.tradingProperties = tradingProperties;
    }

    @PostConstruct
    void init() {
        var existing = repository.findById(1L);
        if (existing.isPresent()) {
            cached = existing.get();
            log.info("GlobalConfig loaded from database (id=1)");
        } else {
            GlobalConfig seeded = new GlobalConfig(tradingProperties);
            cached = repository.save(seeded);
            log.info("GlobalConfig seeded from TradingProperties (YAML defaults)");
        }
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

    public BigDecimal getMinSignalScorePercent() { return cached.getMinSignalScorePercent(); }

    public boolean isCeOiSupportRequired() { return cached.isCeOiSupportRequired(); }

    public boolean isPeOiSupportRequired() { return cached.isPeOiSupportRequired(); }

    public boolean isCeOiDivergenceFilterEnabled() { return cached.isCeOiDivergenceFilterEnabled(); }

    public boolean isPeOiDivergenceFilterEnabled() { return cached.isPeOiDivergenceFilterEnabled(); }

    public BigDecimal getOiDivergenceMultiplier() { return cached.getOiDivergenceMultiplier(); }

    public long getOiDivergenceMinChange() { return cached.getOiDivergenceMinChange(); }

    public int getCeBreakoutConfirmationCandles() { return cached.getCeBreakoutConfirmationCandles(); }

    public int getPeBreakoutConfirmationCandles() { return cached.getPeBreakoutConfirmationCandles(); }

    public LocalTime getEntryStartTime() { return cached.getEntryStartTimeAsLocalTime(); }

    public LocalTime getEntryCutoffTime() { return cached.getEntryCutoffTimeAsLocalTime(); }

    public boolean isAllowFirstMinutesEntry() { return cached.isAllowFirstMinutesEntry(); }

    public int getNoEntryFirstMinutes() { return cached.getNoEntryFirstMinutes(); }

    public boolean isRsiFilterEnabled() { return cached.isRsiFilterEnabled(); }

    public int getRsiPeriod() { return cached.getRsiPeriod(); }

    public BigDecimal getRsiCeBuyThreshold() { return cached.getRsiCeBuyThreshold(); }

    public BigDecimal getRsiPeSellThreshold() { return cached.getRsiPeSellThreshold(); }

    // ── Exit accessors ────────────────────────────────────────

    public BigDecimal getStopLossPercent() { return cached.getStopLossPercent(); }

    public BigDecimal getTargetPercent() { return cached.getTargetPercent(); }

    public BigDecimal getTrailingStopActivationPercent() { return cached.getTrailingStopActivationPercent(); }

    public BigDecimal getTrailingGapPercent() { return cached.getTrailingGapPercent(); }

    public LocalTime getForcedExitTime() { return cached.getForcedExitTimeAsLocalTime(); }

    public boolean isPartialProfitBookingEnabled() { return cached.isPartialProfitBookingEnabled(); }

    public int getMaxHoldMinutes() { return cached.getMaxHoldMinutes(); }

    // ── Risk accessors ────────────────────────────────────────

    public BigDecimal getTotalCapital() { return cached.getTotalCapital(); }

    public BigDecimal getMaxRiskPerTradePercent() { return cached.getMaxRiskPerTradePercent(); }

    public BigDecimal getMaxDailyLossPercent() { return cached.getMaxDailyLossPercent(); }

    public int getMaxTradesPerDay() { return cached.getMaxTradesPerDay(); }

    public int getMaxOrdersPerDay() { return cached.getMaxOrdersPerDay(); }

    public int getMaxConsecutiveLosses() { return cached.getMaxConsecutiveLosses(); }

    public int getMaxOpenTrades() { return cached.getMaxOpenTrades(); }

    public BigDecimal getSameInstrumentReentryMinPriceMovePercent() { return cached.getSameInstrumentReentryMinPriceMovePercent(); }

    public int getCooldownMinutes() { return cached.getCooldownMinutes(); }

    public BigDecimal getDailyProfitTarget() { return cached.getDailyProfitTarget(); }

    public int getMaxLotsPerTrade() { return cached.getMaxLotsPerTrade(); }

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
        defaults.setId(1L);
        cached = repository.save(defaults);
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
        if (config.getMaxTradesPerDay() < 1) {
            throw new IllegalArgumentException("maxTradesPerDay must be >= 1");
        }
        if (config.getMaxOrdersPerDay() < 1) {
            throw new IllegalArgumentException("maxOrdersPerDay must be >= 1");
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
