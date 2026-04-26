package com.algo.trade.config;

import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigRepository;
import com.algo.trade.strategy.StrategyType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * One-shot migration that applies live trading recommendations (₹75k capital, NIFTY-only)
 * to the DB on the first startup after they were defined.
 *
 * Uses configVersion on GlobalConfig as a guard: version 0 = never migrated, version 1 = done.
 * After the initial apply, all values remain editable at runtime via the UI / REST API.
 */
@Component
public class LiveConfigMigration {

    private static final Logger log = LoggerFactory.getLogger(LiveConfigMigration.class);
    private static final int TARGET_VERSION = 1;

    private final GlobalConfigService globalConfigService;
    private final StrategyConfigRepository strategyRepo;

    public LiveConfigMigration(GlobalConfigService globalConfigService,
                               StrategyConfigRepository strategyRepo) {
        this.globalConfigService = globalConfigService;
        this.strategyRepo = strategyRepo;
    }

    @PostConstruct
    void apply() {
        GlobalConfig cfg = globalConfigService.getCached();
        if (cfg.getConfigVersion() >= TARGET_VERSION) {
            log.debug("LiveConfigMigration already at version {} — skipping", cfg.getConfigVersion());
            return;
        }

        log.info("Applying live trading recommendations (v{} → v{})", cfg.getConfigVersion(), TARGET_VERSION);
        applyGlobalConfig(cfg);
        applyDirectionalBuy();
        applyVolatilityBreakout();
        applyScalping();
        log.info("LiveConfigMigration v{} complete — values are now editable via the UI", TARGET_VERSION);
    }

    // ── Global config ─────────────────────────────────────────────────────────

    private void applyGlobalConfig(GlobalConfig cfg) {
        cfg.setTotalCapital(BigDecimal.valueOf(75_000));
        cfg.setMaxRiskPerTradePercent(BigDecimal.valueOf(3));
        cfg.setMaxDailyLossPercent(BigDecimal.valueOf(2));
        cfg.setMaxTradesPerDay(3);
        cfg.setMaxOrdersPerDay(3);
        cfg.setMaxConsecutiveLosses(2);
        cfg.setMaxOpenTrades(1);
        cfg.setCooldownMinutes(15);
        cfg.setMaxLotsPerTrade(2);
        cfg.setEntryStartTime("09:30");
        cfg.setEntryCutoffTime("14:45");
        cfg.setForcedExitTime("15:00");
        cfg.setConfigVersion(TARGET_VERSION);

        globalConfigService.update(cfg);
        log.info("GlobalConfig: capital=75000, maxDailyLoss=2%, maxTrades=3, cooldown=15m, entry=09:30–14:45, exit=15:00");
    }

    // ── DIRECTIONAL_BUY ───────────────────────────────────────────────────────

    private void applyDirectionalBuy() {
        StrategyConfig cfg = strategyRepo.findByStrategyType(StrategyType.DIRECTIONAL_BUY)
                .orElse(new StrategyConfig(StrategyType.DIRECTIONAL_BUY));

        cfg.setStopLossPercent(BigDecimal.valueOf(30));
        cfg.setTargetPercent(BigDecimal.valueOf(60));
        cfg.setMaxHoldMinutes(20);
        cfg.setTrailingStopActivationPercent(BigDecimal.valueOf(25));
        cfg.setTrailingGapPercent(BigDecimal.valueOf(12));
        cfg.setMinCombinedPremium(BigDecimal.valueOf(60));
        cfg.setItmDepth(1);
        cfg.setSquareoffHour(15);
        cfg.setSquareoffMinute(0);

        strategyRepo.save(cfg);
        log.info("DIRECTIONAL_BUY: SL=30%, target=60%, hold=20m, trail@25%/gap12%, minPremium=60, squareoff=15:00");
    }

    // ── VOLATILITY_BREAKOUT ───────────────────────────────────────────────────

    private void applyVolatilityBreakout() {
        StrategyConfig cfg = strategyRepo.findByStrategyType(StrategyType.VOLATILITY_BREAKOUT)
                .orElse(new StrategyConfig(StrategyType.VOLATILITY_BREAKOUT));

        cfg.setStopLossPercent(BigDecimal.valueOf(30));
        cfg.setTargetPercent(BigDecimal.valueOf(80));
        cfg.setMaxIvRankForBuying(BigDecimal.valueOf(25));
        cfg.setTrailingStopActivationPercent(BigDecimal.valueOf(30));
        cfg.setTrailingGapPercent(BigDecimal.valueOf(15));
        cfg.setMinCombinedPremium(BigDecimal.valueOf(50));
        cfg.setSquareoffHour(15);
        cfg.setSquareoffMinute(0);

        strategyRepo.save(cfg);
        log.info("VOLATILITY_BREAKOUT: SL=30%, target=80%, ivRank≤25, trail@30%/gap15%, minPremium=50, squareoff=15:00");
    }

    // ── SCALPING ──────────────────────────────────────────────────────────────

    private void applyScalping() {
        StrategyConfig cfg = strategyRepo.findByStrategyType(StrategyType.SCALPING)
                .orElse(new StrategyConfig(StrategyType.SCALPING));

        cfg.setStopLossPercent(BigDecimal.valueOf(20));
        cfg.setTargetPercent(BigDecimal.valueOf(40));
        cfg.setMaxHoldMinutes(15);
        cfg.setTrailingStopActivationPercent(BigDecimal.valueOf(20));
        cfg.setTrailingGapPercent(BigDecimal.valueOf(10));
        cfg.setMinCombinedPremium(BigDecimal.valueOf(70));
        cfg.setSquareoffHour(14);
        cfg.setSquareoffMinute(30);

        strategyRepo.save(cfg);
        log.info("SCALPING: SL=20%, target=40%, hold=15m, trail@20%/gap10%, minPremium=70, squareoff=14:30");
    }
}
