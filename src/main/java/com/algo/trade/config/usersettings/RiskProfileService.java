package com.algo.trade.config.usersettings;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Owns the persisted {@link RiskProfileDefinition} rows. Seeds CONSERVATIVE / BALANCED / AGGRESSIVE
 * from the {@link RiskProfile} enum defaults on first boot, then serves the (superuser-editable)
 * bundles to {@link TradingConfigResolver}.
 */
@Service
public class RiskProfileService {

    private static final Logger log = LoggerFactory.getLogger(RiskProfileService.class);

    private final RiskProfileDefinitionRepository repository;
    private final com.algo.trade.config.GlobalConfigService globalConfigService;

    public RiskProfileService(RiskProfileDefinitionRepository repository,
                              com.algo.trade.config.GlobalConfigService globalConfigService) {
        this.repository = repository;
        this.globalConfigService = globalConfigService;
    }

    @PostConstruct
    void seed() {
        for (RiskProfile p : RiskProfile.values()) {
            if (!p.hasBundle()) continue; // CUSTOM is not stored
            if (!repository.existsById(p.name())) {
                RiskProfileDefinition def = new RiskProfileDefinition(p.name(), p.bundle());
                // RISK-CAP fields come ONLY from the authoritative enum bundle (the user's table) — NEVER
                // from global config. (BALANCED used to be seeded from global, which made the profile caps
                // conflict with global; that coupling is removed.) The EXIT fields (SL/target/trail) are
                // not risk-profile caps, so BALANCED still seeds those from global for an exit-neutral
                // cutover; CONSERVATIVE/AGGRESSIVE keep the enum/exit defaults.
                if (p == RiskProfile.BALANCED) {
                    seedExitFromGlobal(def);
                }
                repository.save(def);
                log.info("[RiskProfile] seeded definition {}", p.name());
            }
        }
    }

    private void seedExitFromGlobal(RiskProfileDefinition def) {
        try {
            var g = globalConfigService.getCached();
            if (g == null) return;
            def.setStopLossPercent(g.getStopLossPercent().doubleValue());
            def.setTargetPercent(g.getTargetPercent().doubleValue());
            def.setTrailingStopActivationPercent(g.getTrailingStopActivationPercent().doubleValue());
            def.setTrailingGapPercent(g.getTrailingGapPercent().doubleValue());
            def.setMaxHoldMinutes(g.getMaxHoldMinutes());
            // Central monitor previously ran progressive booking whenever ATR was available (it ignored
            // the global flag). Now that it honors the profile flag, default BALANCED to ON so the
            // cutover is behavior-neutral; superuser can turn it off per profile.
            def.setPartialProfitBookingEnabled(true);
            def.setVwapExitEnabled(g.isVwapExitEnabled());
            def.setIvCollapseMaxProfitPercent(g.getIvCollapseMaxProfitPercent().doubleValue());
        } catch (Exception e) {
            log.warn("[RiskProfile] could not seed exit fields from global config: {}", e.getMessage());
        }
    }

    /** The effective bundle for a profile — DB definition if present, else the enum default; null for CUSTOM. */
    public RiskProfile.Bundle bundleFor(RiskProfile p) {
        if (p == null || !p.hasBundle()) return null;
        try {
            return repository.findById(p.name())
                    .map(RiskProfileDefinition::toBundle)
                    .orElse(p.bundle());
        } catch (Exception e) {
            return p.bundle();
        }
    }

    public List<RiskProfileDefinition> all() {
        return repository.findAll();
    }

    /** Persist a definition (used for the expanded entry/exit fields not covered by the bundle). */
    public RiskProfileDefinition save(RiskProfileDefinition def) {
        def.setUpdatedAt(Instant.now());
        return repository.save(def);
    }

    public RiskProfileDefinition get(String name) {
        return repository.findById(name).orElse(null);
    }

    /** SUPERUSER edit — overwrite a profile's numbers from the supplied bundle. */
    public RiskProfileDefinition update(String name, RiskProfile.Bundle bundle, String updatedBy) {
        RiskProfile p = RiskProfile.fromString(name);
        if (!p.hasBundle()) {
            throw new IllegalArgumentException("CUSTOM is not an editable profile definition");
        }
        RiskProfileDefinition def = repository.findById(p.name())
                .orElseGet(() -> new RiskProfileDefinition(p.name(), p.bundle()));
        def.applyBundle(bundle);
        def.setUpdatedAt(Instant.now());
        def.setUpdatedBy(updatedBy);
        return repository.save(def);
    }

    /**
     * SUPERUSER — reset all profile definitions to the current code defaults (enum bundles).
     * All profiles (CONSERVATIVE, BALANCED, AGGRESSIVE) are reset to their enum-defined values.
     */
    public void reseedFromDefaults(String updatedBy) {
        for (RiskProfile p : RiskProfile.values()) {
            if (!p.hasBundle()) continue;
            RiskProfileDefinition def = repository.findById(p.name())
                    .orElseGet(() -> new RiskProfileDefinition(p.name()));
            def.applyBundle(p.bundle());
            // Seed exit fields from GlobalConfig baseline for all profiles
            seedExitFromGlobal(def);
            def.setUpdatedAt(Instant.now());
            def.setUpdatedBy(updatedBy != null ? updatedBy : "system-reseed");
            repository.save(def);
            log.info("[RiskProfile] re-seeded definition {} from code defaults", p.name());
        }
    }
}
