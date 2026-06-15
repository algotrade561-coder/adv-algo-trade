package com.algo.trade.strategy;

import com.algo.trade.domain.UnderlyingSymbol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages strategy configurations.
 * Each strategy type can have one config per underlying (composite key: strategyType + underlying).
 * Auto-creates NIFTY defaults for all strategy types on first access.
 * Explicit calls with an underlying param create/update that underlying's config.
 */
@Service
public class StrategyConfigService {

    private static final Logger log = LoggerFactory.getLogger(StrategyConfigService.class);
    private final StrategyConfigRepository repository;

    public StrategyConfigService(StrategyConfigRepository repository) {
        this.repository = repository;
    }

    // ── Read ─────────────────────────────────────────────────────────────────

    /** All configs across all underlyings, creating NIFTY defaults for missing types. */
    public List<StrategyConfig> getAll() {
        List<StrategyConfig> existing = repository.findAll();
        for (StrategyType type : StrategyType.values()) {
            boolean niftyExists = existing.stream()
                    .anyMatch(c -> c.getStrategyType() == type && "NIFTY".equals(c.getUnderlying()));
            if (!niftyExists) {
                StrategyConfig config = new StrategyConfig(type);
                repository.save(config);
                existing.add(config);
            }
        }
        existing.forEach(this::migrateNullFields);
        return existing;
    }

    /** All enabled configs across all underlyings (used by reporting, backtest). */
    public List<StrategyConfig> getEnabled() {
        ensureNiftyDefaultsExist();
        return repository.findByEnabledTrue();
    }

    /** Enabled configs for a specific underlying — used by the scheduler inner loop. */
    public List<StrategyConfig> getEnabledFor(UnderlyingSymbol underlying) {
        return repository.findByUnderlyingAndEnabledTrue(underlying.name());
    }

    /**
     * All configs (enabled + disabled) for a specific underlying.
     * For NIFTY: always ensures every strategy type has a row (fills gaps).
     * For other underlyings: seeds all types from NIFTY if no rows exist yet.
     * Used by the Strategies UI when switching underlying tabs.
     */
    public List<StrategyConfig> getAllFor(String underlying) {
        ensureAllDefaultsExist(underlying);
        List<StrategyConfig> configs = repository.findByUnderlying(underlying);
        configs.forEach(this::migrateNullFields);
        return configs;
    }

    private void ensureAllDefaultsExist(String underlying) {
        if ("NIFTY".equals(underlying)) {
            ensureNiftyDefaultsExist();
            return;
        }
        ensureNiftyDefaultsExist();
        for (StrategyType type : StrategyType.values()) {
            if (repository.findByStrategyTypeAndUnderlying(type, underlying).isEmpty()) {
                copyTo(type, "NIFTY", underlying);
                log.info("Auto-seeded StrategyConfig: type={} underlying={}", type, underlying);
            }
        }
    }

    /** Copies all strategy type configs from NIFTY to the target underlying (disabled). */
    public void seedForUnderlying(String underlying) {
        ensureNiftyDefaultsExist();
        for (StrategyType type : StrategyType.values()) {
            if (repository.findByStrategyTypeAndUnderlying(type, underlying).isEmpty()) {
                copyTo(type, "NIFTY", underlying);
            }
        }
        log.info("Seeded all strategy configs for underlying={}", underlying);
    }

    /** Config for a specific strategy + underlying. Creates from defaults if absent. */
    public StrategyConfig getConfig(StrategyType type, String underlying) {
        return getOrCreate(type, underlying);
    }

    /** Backward-compatible: returns NIFTY config for the given type. */
    public StrategyConfig getConfig(StrategyType type) {
        return getOrCreate(type, "NIFTY");
    }

    /** Backward-compatible: returns NIFTY DIRECTIONAL_BUY config. */
    public StrategyConfig getDirectionalBuyConfig() {
        return getOrCreate(StrategyType.DIRECTIONAL_BUY, "NIFTY");
    }

    public StrategyConfig getDirectionalBuyConfig(String underlying) {
        return getOrCreate(StrategyType.DIRECTIONAL_BUY, underlying);
    }

    // ── Enable / Disable ─────────────────────────────────────────────────────

    /** Enable for a specific underlying. Creates config from defaults if absent. */
    public StrategyConfig enable(StrategyType type, String underlying) {
        StrategyConfig config = getOrCreate(type, underlying);
        if (type.isSellingStrategy()) {
            log.warn("Enabling SELLING strategy {} for {} — ensure risk limits are configured", type, underlying);
        }
        config.setEnabled(true);
        return repository.save(config);
    }

    /** Backward-compatible: enable for NIFTY. */
    public StrategyConfig enable(StrategyType type) {
        return enable(type, "NIFTY");
    }

    /** Disable for a specific underlying. */
    public StrategyConfig disable(StrategyType type, String underlying) {
        StrategyConfig config = getOrCreate(type, underlying);
        config.setEnabled(false);
        return repository.save(config);
    }

    /** Backward-compatible: disable for NIFTY. */
    public StrategyConfig disable(StrategyType type) {
        return disable(type, "NIFTY");
    }

    // ── Update ───────────────────────────────────────────────────────────────

    @Transactional
    public StrategyConfig update(StrategyType type, String underlying, StrategyConfig patch) {
        StrategyConfig config = getOrCreate(type, underlying);
        applyPatch(config, patch);
        return repository.save(config);
    }

    /** Backward-compatible: update NIFTY config. */
    @Transactional
    public StrategyConfig update(StrategyType type, StrategyConfig patch) {
        return update(type, "NIFTY", patch);
    }

    /**
     * Copy an existing underlying's config to a new underlying.
     * Useful for seeding BANKNIFTY or SENSEX configs from NIFTY defaults.
     */
    @Transactional
    public StrategyConfig copyTo(StrategyType type, String fromUnderlying, String toUnderlying) {
        StrategyConfig source = getOrCreate(type, fromUnderlying);
        StrategyConfig target = repository.findByStrategyTypeAndUnderlying(type, toUnderlying)
                .orElseGet(() -> {
                    StrategyConfig c = new StrategyConfig(type);
                    c.setUnderlying(toUnderlying);
                    return c;
                });
        applyPatch(target, source);
        target.setEnabled(false); // require explicit enable after copy
        if (!"NIFTY".equals(toUnderlying)) target.setPaperTrading(true); // non-NIFTY seeds start in paper mode
        repository.save(target);
        log.info("StrategyConfig copied: type={} from={} to={}", type, fromUnderlying, toUnderlying);
        return target;
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private StrategyConfig getOrCreate(StrategyType type, String underlying) {
        return repository.findByStrategyTypeAndUnderlying(type, underlying)
                .map(this::migrateNullFields)
                .orElseGet(() -> {
                    StrategyConfig config = new StrategyConfig(type);
                    config.setUnderlying(underlying);
                    log.info("Auto-created StrategyConfig: type={} underlying={} enabled={}", type, underlying, config.isEnabled());
                    return repository.save(config);
                });
    }

    private void ensureNiftyDefaultsExist() {
        List<StrategyConfig> existing = repository.findAll();
        for (StrategyType type : StrategyType.values()) {
            boolean niftyExists = existing.stream()
                    .anyMatch(c -> c.getStrategyType() == type && "NIFTY".equals(c.getUnderlying()));
            if (!niftyExists) {
                repository.save(new StrategyConfig(type));
                log.info("Auto-created NIFTY default: type={} enabled={}", type, type.isDefaultEnabled());
            }
        }
    }

    private void applyPatch(StrategyConfig config, StrategyConfig patch) {
        if (patch.getLots() > 0) config.setLots(patch.getLots());
        if (patch.getStopLossPercent() != null) config.setStopLossPercent(patch.getStopLossPercent());
        if (patch.getTargetPercent() != null) config.setTargetPercent(patch.getTargetPercent());
        if (patch.getMaxHoldMinutes() >= 0) config.setMaxHoldMinutes(patch.getMaxHoldMinutes());
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
    }

    private StrategyConfig migrateNullFields(StrategyConfig config) {
        boolean dirty = false;
        StrategyConfig defaults = new StrategyConfig(config.getStrategyType());
        if (config.getScanTimeframe() == null) { config.setScanTimeframe(defaults.getScanTimeframe()); dirty = true; }
        if (config.getCandleTimeframe() == null) { config.setCandleTimeframe(defaults.getCandleTimeframe()); dirty = true; }
        if (config.getTrendTimeframe() == null) { config.setTrendTimeframe(defaults.getTrendTimeframe()); dirty = true; }
        if (dirty) {
            log.info("Migrated config fields for strategy {} underlying {}", config.getStrategyType(), config.getUnderlying());
            repository.save(config);
        }
        return config;
    }
}
