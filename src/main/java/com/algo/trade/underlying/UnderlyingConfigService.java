package com.algo.trade.underlying;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.UnderlyingSymbol;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Manages per-underlying index configuration.
 * Seeds default rows for all known underlyings on first startup.
 * Caches in memory for hot-path reads (strategy evaluation runs every second).
 */
@Service
@DependsOn({"configSeedLoader", "globalConfigService"})
public class UnderlyingConfigService {

    private static final Logger log = LoggerFactory.getLogger(UnderlyingConfigService.class);

    private final UnderlyingConfigRepository repository;
    private final GlobalConfigService globalConfigService;
    private final Map<UnderlyingSymbol, UnderlyingConfig> cache = new ConcurrentHashMap<>();

    public UnderlyingConfigService(UnderlyingConfigRepository repository,
                                   GlobalConfigService globalConfigService) {
        this.repository = repository;
        this.globalConfigService = globalConfigService;
    }

    @PostConstruct
    void init() {
        ensureDefaults();
        migrateIndexVolumeSpikeMode();
        syncFromGlobalConfig();
        refreshCache();
    }

    /**
     * One-time idempotent migration: indices have no traded underlying volume,
     * so the OI Shift Trap / volume-spike gates run on a permanent zero in NORMAL mode.
     * Flip any index row still on the legacy NORMAL default to OI_PROXY.
     * Operators who deliberately set DISABLED or another mode are untouched.
     */
    private void migrateIndexVolumeSpikeMode() {
        for (UnderlyingSymbol symbol : UnderlyingSymbol.values()) {
            repository.findById(symbol).ifPresent(config -> {
                if ("NORMAL".equals(config.getVolumeSpikeMode())) {
                    config.setVolumeSpikeMode("OI_PROXY");
                    repository.save(config);
                    log.info("[Migration] {} volumeSpikeMode NORMAL\u2192OI_PROXY "
                            + "(indices have no traded volume)", symbol);
                }
            });
        }
    }

    // ── Cache management ──────────────────────────────────────

    private void refreshCache() {
        cache.clear();
        repository.findAll().forEach(cfg -> cache.put(cfg.getUnderlying(), cfg));
        log.info("UnderlyingConfig cache refreshed: {} entries", cache.size());
    }

    // ── Read operations (from cache) ──────────────────────────

    public List<UnderlyingConfig> getAll() {
        return List.copyOf(cache.values());
    }

    public List<UnderlyingConfig> getEnabled() {
        return cache.values().stream().filter(UnderlyingConfig::isEnabled).toList();
    }

    public List<UnderlyingSymbol> getEnabledSymbols() {
        return getEnabled().stream().map(UnderlyingConfig::getUnderlying).toList();
    }

    public Optional<UnderlyingConfig> getConfig(UnderlyingSymbol underlying) {
        return Optional.ofNullable(cache.get(underlying));
    }

    /** Get config or a default instance (never null — safe for hot-path). */
    public UnderlyingConfig getOrDefault(UnderlyingSymbol underlying) {
        return cache.getOrDefault(underlying, buildDefault(underlying));
    }

    // ── Convenience accessors for hot-path reads ──────────────

    public String getExpiryPreference(UnderlyingSymbol underlying) {
        return getOrDefault(underlying).getExpiryPreference();
    }

    public int getMaxDteForBuying(UnderlyingSymbol underlying) {
        return getOrDefault(underlying).getMaxDteForBuying();
    }

    public boolean hasWeeklyExpiry(UnderlyingSymbol underlying) {
        return getOrDefault(underlying).isHasWeeklyExpiry();
    }

    public String getVolumeSpikeMode(UnderlyingSymbol underlying) {
        return getOrDefault(underlying).getVolumeSpikeMode();
    }

    public boolean isNormalizeScoreForNoVolume(UnderlyingSymbol underlying) {
        return getOrDefault(underlying).isNormalizeScoreForNoVolume();
    }

    /**
     * Get max entry premium for this underlying. Returns 0 if no cap (use global/strategy config).
     */
    public BigDecimal getMaxEntryPremium(UnderlyingSymbol underlying) {
        BigDecimal cap = getOrDefault(underlying).getMaxEntryPremium();
        return cap != null ? cap : BigDecimal.ZERO;
    }

    /**
     * Get effective breakout buffer for this underlying.
     * Returns the underlying-specific override if set (> 0), otherwise the global default.
     */
    public BigDecimal getEffectiveBreakoutBuffer(UnderlyingSymbol underlying, BigDecimal globalDefault) {
        BigDecimal override = getOrDefault(underlying).getEffectiveBreakoutBuffer();
        return override != null ? override : globalDefault;
    }

    /**
     * Get effective entry cutoff time for this underlying.
     * Returns the underlying-specific override if set, otherwise null (caller uses global).
     */
    public LocalTime getEntryCutoffTime(UnderlyingSymbol underlying) {
        return getOrDefault(underlying).getEntryCutoffTimeAsLocalTime();
    }

    public LocalTime getMiddayChopStart(UnderlyingSymbol underlying) {
        return getOrDefault(underlying).getMiddayChopStartAsLocalTime();
    }

    public LocalTime getMiddayChopEnd(UnderlyingSymbol underlying) {
        return getOrDefault(underlying).getMiddayChopEndAsLocalTime();
    }

    // ── Write operations ──────────────────────────────────────

    public UnderlyingConfig enable(UnderlyingSymbol underlying) {
        UnderlyingConfig config = getOrCreate(underlying);
        config.setEnabled(true);
        UnderlyingConfig saved = repository.save(config);
        cache.put(underlying, saved);
        log.info("Underlying enabled: {}", underlying);
        return saved;
    }

    public UnderlyingConfig disable(UnderlyingSymbol underlying) {
        UnderlyingConfig config = getOrCreate(underlying);
        config.setEnabled(false);
        UnderlyingConfig saved = repository.save(config);
        cache.put(underlying, saved);
        log.info("Underlying disabled: {}", underlying);
        return saved;
    }

    /**
     * Update a full UnderlyingConfig from REST API.
     * Validates and persists, then refreshes cache.
     */
    public UnderlyingConfig update(UnderlyingSymbol underlying, UnderlyingConfig incoming) {
        UnderlyingConfig existing = getOrCreate(underlying);

        // Apply all mutable fields
        existing.setEnabled(incoming.isEnabled());
        existing.setDisplayName(incoming.getDisplayName());
        existing.setHasWeeklyExpiry(incoming.isHasWeeklyExpiry());
        existing.setExpiryPreference(validateExpiryPreference(incoming.getExpiryPreference()));
        existing.setMaxDteForBuying(Math.max(1, incoming.getMaxDteForBuying()));
        existing.setBreakoutBufferPercent(incoming.getBreakoutBufferPercent() != null
                ? incoming.getBreakoutBufferPercent() : BigDecimal.ZERO);
        existing.setMinBreakoutPoints(incoming.getMinBreakoutPoints() != null
                ? incoming.getMinBreakoutPoints() : BigDecimal.ZERO);
        existing.setVolumeSpikeMode(validateVolumeSpikeMode(incoming.getVolumeSpikeMode()));
        existing.setEntryCutoffTime(incoming.getEntryCutoffTime());
        existing.setMiddayChopStart(incoming.getMiddayChopStart());
        existing.setMiddayChopEnd(incoming.getMiddayChopEnd());
        existing.setNormalizeScoreForNoVolume(incoming.isNormalizeScoreForNoVolume());
        existing.setMaxEntryPremium(incoming.getMaxEntryPremium() != null
                ? incoming.getMaxEntryPremium() : BigDecimal.ZERO);

        UnderlyingConfig saved = repository.save(existing);
        cache.put(underlying, saved);
        log.info("UnderlyingConfig updated: underlying={}", underlying);
        return saved;
    }

    // ── Internal ──────────────────────────────────────────────

    private UnderlyingConfig getOrCreate(UnderlyingSymbol underlying) {
        return repository.findById(underlying)
                .orElseGet(() -> repository.save(buildDefault(underlying)));
    }

    private void syncFromGlobalConfig() {
        List<UnderlyingSymbol> persisted = globalConfigService.getEnabledUnderlyings();
        if (persisted.isEmpty()) return;
        Set<UnderlyingSymbol> enabledSet = persisted.stream().collect(Collectors.toSet());
        for (UnderlyingSymbol symbol : UnderlyingSymbol.values()) {
            repository.findById(symbol).ifPresent(config -> {
                config.setEnabled(enabledSet.contains(symbol));
                repository.save(config);
            });
        }
        log.info("UnderlyingConfig synced from GlobalConfig: enabled={}", enabledSet);
    }

    private void ensureDefaults() {
        for (UnderlyingSymbol symbol : UnderlyingSymbol.values()) {
            if (repository.findById(symbol).isEmpty()) {
                repository.save(buildDefault(symbol));
                log.info("Auto-created UnderlyingConfig: symbol={}", symbol);
            }
        }
    }

    private UnderlyingConfig buildDefault(UnderlyingSymbol symbol) {
        IndexType idx = IndexType.from(symbol);
        boolean defaultEnabled = symbol == UnderlyingSymbol.NIFTY || symbol == UnderlyingSymbol.BANKNIFTY;
        UnderlyingConfig cfg = new UnderlyingConfig(symbol, defaultEnabled, idx.underlyingSymbol());

        // Set sensible defaults based on index characteristics
        switch (symbol) {
            case BANKNIFTY -> {
                cfg.setHasWeeklyExpiry(false);
                cfg.setExpiryPreference("NEAREST_MONTHLY");
                cfg.setMaxDteForBuying(7);
                cfg.setBreakoutBufferPercent(new BigDecimal("0.15"));
                cfg.setMinBreakoutPoints(new BigDecimal("80"));
                cfg.setVolumeSpikeMode("OI_PROXY");
                cfg.setNormalizeScoreForNoVolume(true);
                cfg.setMaxEntryPremium(new BigDecimal("250"));
            }
            case FINNIFTY -> {
                cfg.setHasWeeklyExpiry(false);
                cfg.setExpiryPreference("NEAREST_MONTHLY");
                cfg.setMaxDteForBuying(7);
                cfg.setBreakoutBufferPercent(new BigDecimal("0.10"));
                cfg.setMinBreakoutPoints(new BigDecimal("40"));
                cfg.setVolumeSpikeMode("OI_PROXY");
                cfg.setNormalizeScoreForNoVolume(true);
            }
            case MIDCPNIFTY -> {
                cfg.setHasWeeklyExpiry(false);
                cfg.setExpiryPreference("NEAREST_MONTHLY");
                cfg.setMaxDteForBuying(7);
                cfg.setBreakoutBufferPercent(new BigDecimal("0.08"));
                cfg.setMinBreakoutPoints(new BigDecimal("20"));
                cfg.setVolumeSpikeMode("OI_PROXY");
                cfg.setNormalizeScoreForNoVolume(true);
            }
            case NIFTY -> {
                cfg.setHasWeeklyExpiry(true);
                cfg.setExpiryPreference("NEAREST");
                cfg.setMaxDteForBuying(5);
                // NIFTY is an index — underlying carries no traded volume.
                // OI_PROXY substitutes option OI change for the volume gate.
                cfg.setVolumeSpikeMode("OI_PROXY");
                cfg.setNormalizeScoreForNoVolume(true);
            }
            case SENSEX -> {
                cfg.setHasWeeklyExpiry(true);
                cfg.setExpiryPreference("NEAREST");
                cfg.setMaxDteForBuying(5);
                // SENSEX is an index — underlying carries no traded volume.
                // OI_PROXY substitutes option OI change for the volume gate.
                cfg.setVolumeSpikeMode("OI_PROXY");
                cfg.setNormalizeScoreForNoVolume(true);
            }
        }
        return cfg;
    }

    private String validateExpiryPreference(String pref) {
        if (pref == null || pref.isBlank()) return "NEAREST";
        String upper = pref.trim().toUpperCase();
        return switch (upper) {
            case "NEAREST", "NEAREST_WEEKLY", "NEAREST_MONTHLY" -> upper;
            default -> "NEAREST";
        };
    }

    private String validateVolumeSpikeMode(String mode) {
        if (mode == null || mode.isBlank()) return "NORMAL";
        String upper = mode.trim().toUpperCase();
        return switch (upper) {
            case "NORMAL", "OI_PROXY", "DISABLED" -> upper;
            default -> "NORMAL";
        };
    }
}
