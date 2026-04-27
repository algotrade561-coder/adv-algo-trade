package com.algo.trade.strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

/**
 * Manages strategy configurations.
 * Auto-creates defaults for all strategy types on first access.
 * Enforces that selling strategies require explicit opt-in.
 */
@Service
public class StrategyConfigService {

    private static final Logger log = LoggerFactory.getLogger(StrategyConfigService.class);
    private final StrategyConfigRepository repository;

    public StrategyConfigService(StrategyConfigRepository repository) {
        this.repository = repository;
    }

    /** Get all strategy configs, creating defaults for any missing types. */
    public List<StrategyConfig> getAll() {
        List<StrategyConfig> existing = repository.findAll();
        for (StrategyType type : StrategyType.values()) {
            boolean exists = existing.stream().anyMatch(c -> c.getStrategyType() == type);
            if (!exists) {
                StrategyConfig config = new StrategyConfig(type);
                repository.save(config);
                existing.add(config);
            }
        }
        // Migrate null fields in old rows
        existing.forEach(this::migrateNullFields);
        return existing;
    }

    /** Get all enabled strategy configs, creating defaults for any missing types first. */
    public List<StrategyConfig> getEnabled() {
        ensureAllTypesExist();
        return repository.findByEnabledTrue();
    }

    private void ensureAllTypesExist() {
        List<StrategyConfig> existing = repository.findAll();
        for (StrategyType type : StrategyType.values()) {
            boolean exists = existing.stream().anyMatch(c -> c.getStrategyType() == type);
            if (!exists) {
                repository.save(new StrategyConfig(type));
                log.info("Auto-created default strategy config: type={}, enabled={}", type, type.isDefaultEnabled());
            }
        }
    }

    public StrategyConfig enable(StrategyType type) {
        StrategyConfig config = getOrCreate(type);
        if (type.isSellingStrategy()) {
            log.warn("Enabling SELLING strategy {} — ensure risk limits are configured", type);
        }
        config.setEnabled(true);
        return repository.save(config);
    }

    public StrategyConfig disable(StrategyType type) {
        StrategyConfig config = getOrCreate(type);
        config.setEnabled(false);
        return repository.save(config);
    }

    public StrategyConfig update(StrategyType type, StrategyConfig patch) {
        StrategyConfig config = getOrCreate(type);
        if (patch.getLots() > 0) config.setLots(patch.getLots());
        if (patch.getStopLossPercent() != null) config.setStopLossPercent(patch.getStopLossPercent());
        if (patch.getTargetPercent() != null) config.setTargetPercent(patch.getTargetPercent());
        if (patch.getMaxHoldMinutes() >= 0) config.setMaxHoldMinutes(patch.getMaxHoldMinutes());
        if (patch.getUnderlying() != null) config.setUnderlying(patch.getUnderlying());
        if (patch.getSpreadStrikes() > 0) config.setSpreadStrikes(patch.getSpreadStrikes());
        if (patch.getOtmStrikes() > 0) config.setOtmStrikes(patch.getOtmStrikes());
        if (patch.getMinCombinedPremium() != null) config.setMinCombinedPremium(patch.getMinCombinedPremium());
        if (patch.getMaxIvRankForBuying() != null) config.setMaxIvRankForBuying(patch.getMaxIvRankForBuying());
        if (patch.getTrailingStopActivationPercent() != null) config.setTrailingStopActivationPercent(patch.getTrailingStopActivationPercent());
        if (patch.getTrailingGapPercent() != null) config.setTrailingGapPercent(patch.getTrailingGapPercent());
        if (patch.getScanTimeframe() != null) config.setScanTimeframe(patch.getScanTimeframe());
        if (patch.getCandleTimeframe() != null) config.setCandleTimeframe(patch.getCandleTimeframe());
        if (patch.getTrendTimeframe() != null) config.setTrendTimeframe(patch.getTrendTimeframe());
        if (patch.getSquareoffHour() > 0) config.setSquareoffHour(patch.getSquareoffHour());
        config.setSquareoffMinute(patch.getSquareoffMinute());
        config.setPaperTrading(patch.isPaperTrading());
        if (patch.getItmDepth() > 0) config.setItmDepth(patch.getItmDepth());
        if (patch.getMinimumMove() != null) config.setMinimumMove(patch.getMinimumMove());
        if (patch.getMinimumStrengthGap() != null) config.setMinimumStrengthGap(patch.getMinimumStrengthGap());
        if (patch.getMinimumVolume() > 0) config.setMinimumVolume(patch.getMinimumVolume());
        return repository.save(config);
    }

    private StrategyConfig getOrCreate(StrategyType type) {
        return repository.findByStrategyType(type)
                .map(this::migrateNullFields)
                .orElseGet(() -> repository.save(new StrategyConfig(type)));
    }

    /**
     * Migrate null fields in old DB rows that were created before new columns were added.
     * Applies defaults from a fresh StrategyConfig for the same type.
     */
    private StrategyConfig migrateNullFields(StrategyConfig config) {
        boolean dirty = false;
        StrategyConfig defaults = new StrategyConfig(config.getStrategyType());
        if (config.getScanTimeframe() == null) {
            config.setScanTimeframe(defaults.getScanTimeframe());
            dirty = true;
        }
        if (config.getCandleTimeframe() == null) {
            config.setCandleTimeframe(defaults.getCandleTimeframe());
            dirty = true;
        }
        if (config.getTrendTimeframe() == null) {
            config.setTrendTimeframe(defaults.getTrendTimeframe());
            dirty = true;
        }
        // Migrate SCALPING trailing stop activation from old default 20% → 10%
        if (config.getStrategyType() == com.algo.trade.strategy.StrategyType.SCALPING
                && config.getTrailingStopActivationPercent() != null
                && config.getTrailingStopActivationPercent().compareTo(java.math.BigDecimal.valueOf(20)) == 0) {
            config.setTrailingStopActivationPercent(java.math.BigDecimal.valueOf(10));
            log.info("Migrated SCALPING trailingStopActivationPercent: 20% → 10%");
            dirty = true;
        }
        if (dirty) {
            log.info("Migrated config fields for strategy {}", config.getStrategyType());
            repository.save(config);
        }
        return config;
    }

    /** Returns the DB-backed config for a given type, creating a default if absent. */
    public StrategyConfig getConfig(StrategyType type) {
        return getOrCreate(type);
    }

    /** Convenience accessor for the directional buy config — used by RiskEngine, TrailingStopService, etc. */
    public StrategyConfig getDirectionalBuyConfig() {
        return getOrCreate(StrategyType.DIRECTIONAL_BUY);
    }
}
