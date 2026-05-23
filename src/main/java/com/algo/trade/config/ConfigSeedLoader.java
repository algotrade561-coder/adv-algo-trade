package com.algo.trade.config;

import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigRepository;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.underlying.UnderlyingConfig;
import com.algo.trade.underlying.UnderlyingConfigRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Seeds GlobalConfig, StrategyConfig, and UnderlyingConfig tables from data/seed-config.yml
 * on first startup (when each table is empty). Skipped entirely when the seed file is absent
 * or when the target table already has rows — existing data is never overwritten.
 *
 * Workflow for a fresh environment:
 *   1. Delete the H2 DB files (data/adv-algo-trade.mv.db etc.)
 *   2. Edit data/seed-config.yml as needed
 *   3. Restart — this loader runs and populates all three tables
 *
 * GlobalConfigService and UnderlyingConfigService declare @DependsOn("configSeedLoader")
 * so they read the already-populated DB rather than falling back to Java defaults.
 */
@Component("configSeedLoader")
public class ConfigSeedLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigSeedLoader.class);
    private static final String SEED_FILE = "./data/seed-config.yml";

    private final GlobalConfigRepository globalConfigRepository;
    private final StrategyConfigRepository strategyConfigRepository;
    private final UnderlyingConfigRepository underlyingConfigRepository;
    private final com.algo.trade.auth.AppUserRepository appUserRepository;

    public ConfigSeedLoader(GlobalConfigRepository globalConfigRepository,
                            StrategyConfigRepository strategyConfigRepository,
                            UnderlyingConfigRepository underlyingConfigRepository,
                            com.algo.trade.auth.AppUserRepository appUserRepository) {
        this.globalConfigRepository = globalConfigRepository;
        this.strategyConfigRepository = strategyConfigRepository;
        this.underlyingConfigRepository = underlyingConfigRepository;
        this.appUserRepository = appUserRepository;
    }

    @PostConstruct
    void seed() {
        File seedFile = new File(SEED_FILE);
        if (!seedFile.exists()) {
            log.info("Seed file not found at {} — Java defaults will be used for first-run seeding", seedFile.getAbsolutePath());
            return;
        }
        log.info("Loading initial seed config from {}", seedFile.getAbsolutePath());
        try (InputStream in = new FileInputStream(seedFile)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            seedGlobalConfigIfEmpty(root);
            seedStrategyConfigsIfEmpty(root);
            seedUnderlyingConfigsIfEmpty(root);
            seedAppUsersIfEmpty(root);
        } catch (Exception e) {
            log.warn("Config seed from {} failed: {} — falling back to Java defaults", SEED_FILE, e.getMessage());
        }
    }

    // ── GlobalConfig ─────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void seedGlobalConfigIfEmpty(Map<String, Object> root) {
        if (globalConfigRepository.count() > 0) {
            log.debug("GlobalConfig already exists in DB — seed skipped");
            return;
        }
        Map<String, Object> map = (Map<String, Object>) root.get("globalConfig");
        if (map == null) {
            log.warn("seed-config.yml missing 'globalConfig' section");
            return;
        }
        GlobalConfig cfg = new GlobalConfig();
        cfg.setId(1L);
        // Entry
        cfg.setTimeframe(Timeframe.valueOf(str(map, "timeframe", "ONE_MINUTE")));
        cfg.setTrendTimeframe(Timeframe.valueOf(str(map, "trendTimeframe", "FIVE_MINUTE")));
        cfg.setEnabledOptionTypes(str(map, "enabledOptionTypes", "CE,PE"));
        cfg.setEnabledUnderlyings(str(map, "enabledUnderlyings", "NIFTY"));
        cfg.setVwapFilterEnabled(bool(map, "vwapFilterEnabled", true));
        cfg.setTrendFilterEnabled(bool(map, "trendFilterEnabled", true));
        cfg.setVolumeSpikeMultiplier(decimal(map, "volumeSpikeMultiplier", "1.2"));
        cfg.setBreakoutBufferPercent(decimal(map, "breakoutBufferPercent", "0.05"));
        cfg.setBreakoutLookback(integer(map, "breakoutLookback", 15));
        cfg.setVolumeLookback(integer(map, "volumeLookback", 5));
        cfg.setBullishImbalanceThreshold(decimal(map, "bullishImbalanceThreshold", "1.2"));
        cfg.setBearishImbalanceThreshold(decimal(map, "bearishImbalanceThreshold", "0.8"));
        cfg.setMinLiquidityVolume(longVal(map, "minLiquidityVolume", 5000));
        cfg.setMaxIvPercent(decimal(map, "maxIvPercent", "80"));
        cfg.setMinSignalScorePercent(decimal(map, "minSignalScorePercent", "70"));
        cfg.setCeOiSupportRequired(bool(map, "ceOiSupportRequired", false));
        cfg.setPeOiSupportRequired(bool(map, "peOiSupportRequired", false));
        cfg.setCeOiDivergenceFilterEnabled(bool(map, "ceOiDivergenceFilterEnabled", true));
        cfg.setPeOiDivergenceFilterEnabled(bool(map, "peOiDivergenceFilterEnabled", true));
        cfg.setOiDivergenceMultiplier(decimal(map, "oiDivergenceMultiplier", "2.0"));
        cfg.setOiDivergenceMinChange(longVal(map, "oiDivergenceMinChange", 100_000));
        cfg.setCeBreakoutConfirmationCandles(integer(map, "ceBreakoutConfirmationCandles", 1));
        cfg.setPeBreakoutConfirmationCandles(integer(map, "peBreakoutConfirmationCandles", 2));
        cfg.setEntryStartTime(str(map, "entryStartTime", "09:25"));
        cfg.setEntryCutoffTime(str(map, "entryCutoffTime", "15:10"));
        cfg.setAllowFirstMinutesEntry(bool(map, "allowFirstMinutesEntry", false));
        cfg.setNoEntryFirstMinutes(integer(map, "noEntryFirstMinutes", 10));
        cfg.setRsiFilterEnabled(bool(map, "rsiFilterEnabled", false));
        cfg.setRsiPeriod(integer(map, "rsiPeriod", 14));
        cfg.setRsiCeBuyThreshold(decimal(map, "rsiCeBuyThreshold", "55"));
        cfg.setRsiPeSellThreshold(decimal(map, "rsiPeSellThreshold", "45"));
        // Exit
        cfg.setStopLossPercent(decimal(map, "stopLossPercent", "12"));
        cfg.setTargetPercent(decimal(map, "targetPercent", "24"));
        cfg.setTrailingStopActivationPercent(decimal(map, "trailingStopActivationPercent", "10"));
        cfg.setTrailingGapPercent(decimal(map, "trailingGapPercent", "5"));
        cfg.setForcedExitTime(str(map, "forcedExitTime", "15:15"));
        cfg.setPartialProfitBookingEnabled(bool(map, "partialProfitBookingEnabled", false));
        cfg.setMaxHoldMinutes(integer(map, "maxHoldMinutes", 0));
        cfg.setVwapExitEnabled(bool(map, "vwapExitEnabled", false));
        cfg.setGlobalExitOverride(bool(map, "globalExitOverride", false));
        // Risk
        cfg.setTotalCapital(decimal(map, "totalCapital", "80000"));
        cfg.setMaxRiskPerTradePercent(decimal(map, "maxRiskPerTradePercent", "20"));
        cfg.setMaxDailyLossPercent(decimal(map, "maxDailyLossPercent", "60"));
        cfg.setMaxTradesPerDay(integer(map, "maxTradesPerDay", 10));
        cfg.setMaxConsecutiveLosses(integer(map, "maxConsecutiveLosses", 2));
        cfg.setMaxOpenTrades(integer(map, "maxOpenTrades", 1));
        cfg.setCooldownMinutes(integer(map, "cooldownMinutes", 0));
        cfg.setMaxOpenPositionsPerStrategy(integer(map, "maxOpenPositionsPerStrategy", 1));
        cfg.setDailyProfitTarget(decimal(map, "dailyProfitTarget", "0"));
        cfg.setMaxLotsPerTrade(integer(map, "maxLotsPerTrade", 1));
        cfg.setMinEnvironmentScore(integer(map, "minEnvironmentScore", 50));
        cfg.setMlVirtualTradeThreshold(decimal(map, "mlVirtualTradeThreshold", "45"));
        // Execution tuning
        cfg.setLimitOrderCancelMinutes(integer(map, "limitOrderCancelMinutes", 1));
        cfg.setFailSafeSquareoffTime(str(map, "failSafeSquareoffTime", "15:20"));
        cfg.setMaxPendingOrders(integer(map, "maxPendingOrders", 3));
        cfg.setIvCollapseExitThresholdPercent(decimal(map, "ivCollapseExitThresholdPercent", "15"));
        cfg.setIvCollapseMaxProfitPercent(decimal(map, "ivCollapseMaxProfitPercent", "15"));
        cfg.setMaxEntriesPerScanPerUnderlying(integer(map, "maxEntriesPerScanPerUnderlying", 1));
        cfg.setMaxEntriesPerScan(integer(map, "maxEntriesPerScan", 1));
        globalConfigRepository.save(cfg);
        log.info("GlobalConfig seeded from seed file");
    }

    // ── StrategyConfigs ───────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void seedStrategyConfigsIfEmpty(Map<String, Object> root) {
        if (strategyConfigRepository.count() > 0) {
            log.debug("StrategyConfigs already exist in DB — seed skipped");
            return;
        }
        List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("strategyConfigs");
        if (list == null || list.isEmpty()) {
            log.warn("seed-config.yml missing 'strategyConfigs' section");
            return;
        }
        List<StrategyConfig> configs = new ArrayList<>(list.size());
        for (Map<String, Object> map : list) {
            String typeStr = str(map, "strategyType", null);
            if (typeStr == null) continue;
            StrategyType type = StrategyType.valueOf(typeStr);
            StrategyConfig cfg = new StrategyConfig(type);
            cfg.setUnderlying(str(map, "underlying", "NIFTY"));
            cfg.setEnabled(bool(map, "enabled", type.isDefaultEnabled()));
            cfg.setLots(integer(map, "lots", 1));
            if (map.containsKey("stopLossPercent"))             cfg.setStopLossPercent(decimal(map, "stopLossPercent", null));
            if (map.containsKey("targetPercent"))               cfg.setTargetPercent(decimal(map, "targetPercent", null));
            if (map.containsKey("maxHoldMinutes"))              cfg.setMaxHoldMinutes(integer(map, "maxHoldMinutes", 0));
            if (map.containsKey("spreadStrikes"))               cfg.setSpreadStrikes(integer(map, "spreadStrikes", 2));
            if (map.containsKey("otmStrikes"))                  cfg.setOtmStrikes(integer(map, "otmStrikes", 2));
            if (map.containsKey("minCombinedPremium"))          cfg.setMinCombinedPremium(decimal(map, "minCombinedPremium", null));
            if (map.containsKey("maxIvRankForBuying"))          cfg.setMaxIvRankForBuying(decimal(map, "maxIvRankForBuying", null));
            if (map.containsKey("trailingStopActivationPercent")) cfg.setTrailingStopActivationPercent(decimal(map, "trailingStopActivationPercent", null));
            if (map.containsKey("trailingGapPercent"))          cfg.setTrailingGapPercent(decimal(map, "trailingGapPercent", null));
            if (map.containsKey("squareoffHour"))               cfg.setSquareoffHour(integer(map, "squareoffHour", 15));
            if (map.containsKey("squareoffMinute"))             cfg.setSquareoffMinute(integer(map, "squareoffMinute", 15));
            if (map.containsKey("scanTimeframe"))               cfg.setScanTimeframe(str(map, "scanTimeframe", null));
            if (map.containsKey("candleTimeframe"))             cfg.setCandleTimeframe(str(map, "candleTimeframe", null));
            if (map.containsKey("trendTimeframe"))              cfg.setTrendTimeframe(str(map, "trendTimeframe", null));
            cfg.setPaperTrading(bool(map, "paperTrading", false));
            if (map.containsKey("itmDepth"))                    cfg.setItmDepth(integer(map, "itmDepth", 1));
            if (map.containsKey("minimumMove"))                 cfg.setMinimumMove(decimal(map, "minimumMove", "5"));
            if (map.containsKey("minimumStrengthGap"))         cfg.setMinimumStrengthGap(decimal(map, "minimumStrengthGap", "2"));
            if (map.containsKey("minimumVolume"))               cfg.setMinimumVolume(longVal(map, "minimumVolume", 5000));
            configs.add(cfg);
        }
        strategyConfigRepository.saveAll(configs);
        log.info("StrategyConfigs seeded from seed file: {} rows", configs.size());
    }

    // ── UnderlyingConfigs ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void seedUnderlyingConfigsIfEmpty(Map<String, Object> root) {
        if (underlyingConfigRepository.count() > 0) {
            log.debug("UnderlyingConfigs already exist in DB — seed skipped");
            return;
        }
        List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("underlyingConfigs");
        if (list == null || list.isEmpty()) {
            log.warn("seed-config.yml missing 'underlyingConfigs' section");
            return;
        }
        List<UnderlyingConfig> configs = new ArrayList<>(list.size());
        for (Map<String, Object> map : list) {
            String symStr = str(map, "underlying", null);
            if (symStr == null) continue;
            UnderlyingSymbol symbol = UnderlyingSymbol.valueOf(symStr);
            boolean enabled = bool(map, "enabled", false);
            String displayName = str(map, "displayName", symbol.name());
            UnderlyingConfig cfg = new UnderlyingConfig(symbol, enabled, displayName);
            // New index-level fields
            cfg.setHasWeeklyExpiry(bool(map, "hasWeeklyExpiry", true));
            cfg.setExpiryPreference(str(map, "expiryPreference", "NEAREST"));
            cfg.setMaxDteForBuying(integer(map, "maxDteForBuying", 7));
            cfg.setBreakoutBufferPercent(decimal(map, "breakoutBufferPercent", "0"));
            cfg.setMinBreakoutPoints(decimal(map, "minBreakoutPoints", "0"));
            cfg.setVolumeSpikeMode(str(map, "volumeSpikeMode", "NORMAL"));
            cfg.setEntryCutoffTime(str(map, "entryCutoffTime", null));
            cfg.setMiddayChopStart(str(map, "middayChopStart", null));
            cfg.setMiddayChopEnd(str(map, "middayChopEnd", null));
            cfg.setNormalizeScoreForNoVolume(bool(map, "normalizeScoreForNoVolume", false));
            cfg.setMaxEntryPremium(decimal(map, "maxEntryPremium", "0"));
            configs.add(cfg);
        }
        underlyingConfigRepository.saveAll(configs);
        log.info("UnderlyingConfigs seeded from seed file: {} rows", configs.size());
    }

    // ── Parsing helpers ───────────────────────────────────────────────────────

    private static String str(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        return val != null ? val.toString() : def;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean def) {
        Object val = map.get(key);
        return val instanceof Boolean b ? b : def;
    }

    private static int integer(Map<String, Object> map, String key, int def) {
        Object val = map.get(key);
        return val instanceof Number n ? n.intValue() : def;
    }

    private static long longVal(Map<String, Object> map, String key, long def) {
        Object val = map.get(key);
        return val instanceof Number n ? n.longValue() : def;
    }

    private static BigDecimal decimal(Map<String, Object> map, String key, String def) {
        Object val = map.get(key);
        if (val == null) return def != null ? new BigDecimal(def) : null;
        return new BigDecimal(val.toString());
    }

    @SuppressWarnings("unchecked")
    private void seedAppUsersIfEmpty(Map<String, Object> root) {
        List<Map<String, Object>> users = (List<Map<String, Object>>) root.get("appUsers");
        if (users == null || users.isEmpty()) {
            log.debug("seed-config.yml missing 'appUsers' section — no users seeded");
            return;
        }
        for (Map<String, Object> u : users) {
            String email = str(u, "email", null);
            String role = str(u, "role", "USER");
            if (email == null || email.isBlank()) continue;
            String normalizedEmail = email.toLowerCase();
            // Always ensure seed users exist (idempotent — skip if already present)
            if (!appUserRepository.existsByEmail(normalizedEmail)) {
                com.algo.trade.auth.AppUser user = new com.algo.trade.auth.AppUser(normalizedEmail, role);
                appUserRepository.save(user);
                log.info("AppUser seeded: email={} role={}", normalizedEmail, role);
            }
        }
    }
}
