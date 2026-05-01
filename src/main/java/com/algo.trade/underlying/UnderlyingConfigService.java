package com.algo.trade.underlying;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.UnderlyingSymbol;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages per-underlying index configuration.
 * Seeds default rows for all known underlyings on first startup.
 * On startup, syncs enabled state from GlobalConfig so UI toggles survive restarts.
 */
@Service
@DependsOn({"configSeedLoader", "globalConfigService"})
public class UnderlyingConfigService {

    private static final Logger log = LoggerFactory.getLogger(UnderlyingConfigService.class);

    private final UnderlyingConfigRepository repository;
    private final GlobalConfigService globalConfigService;

    public UnderlyingConfigService(UnderlyingConfigRepository repository,
                                   GlobalConfigService globalConfigService) {
        this.repository = repository;
        this.globalConfigService = globalConfigService;
    }

    @PostConstruct
    void init() {
        ensureDefaults();
        syncFromGlobalConfig();
    }

    public List<UnderlyingConfig> getAll() {
        return repository.findAll();
    }

    public List<UnderlyingConfig> getEnabled() {
        return repository.findByEnabledTrue();
    }

    public List<UnderlyingSymbol> getEnabledSymbols() {
        return getEnabled().stream().map(UnderlyingConfig::getUnderlying).toList();
    }

    public Optional<UnderlyingConfig> getConfig(UnderlyingSymbol underlying) {
        return repository.findById(underlying);
    }

    public UnderlyingConfig enable(UnderlyingSymbol underlying) {
        UnderlyingConfig config = getOrCreate(underlying);
        config.setEnabled(true);
        UnderlyingConfig saved = repository.save(config);
        log.info("Underlying enabled: {}", underlying);
        return saved;
    }

    public UnderlyingConfig disable(UnderlyingSymbol underlying) {
        UnderlyingConfig config = getOrCreate(underlying);
        config.setEnabled(false);
        UnderlyingConfig saved = repository.save(config);
        log.info("Underlying disabled: {}", underlying);
        return saved;
    }

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
        return new UnderlyingConfig(symbol, defaultEnabled, idx.underlyingSymbol());
    }
}
