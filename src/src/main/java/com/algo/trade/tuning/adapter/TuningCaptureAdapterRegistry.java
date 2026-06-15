package com.algo.trade.tuning.adapter;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.capture.CaptureToggleService;
import jakarta.annotation.PostConstruct;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Discovers every {@link TuningCaptureAdapter} Spring {@code @Component} on the
 * classpath and provides lookup by {@link StrategyType}.
 *
 * <p>On {@link PostConstruct}, the registry calls
 * {@link CaptureToggleService#ensureRowExists(StrategyType, int)} for each registered
 * adapter so its row appears in {@code tuning_capture_config} (defaulting to OFF)
 * with the adapter's preferred {@code episodeWindowSec}. The capture-toggle UI page
 * then renders the new strategy automatically — no UI code change required to add a
 * strategy.</p>
 *
 * <h2>Duplicate detection</h2>
 * If two adapters declare the same {@link StrategyType}, registration fails fast with
 * a clear error message. There must be exactly one adapter per strategy.
 */
@Component
public class TuningCaptureAdapterRegistry {

    private static final Logger log = LoggerFactory.getLogger(TuningCaptureAdapterRegistry.class);

    private final List<TuningCaptureAdapter> discovered;
    private final CaptureToggleService toggleService;
    private final Map<StrategyType, TuningCaptureAdapter> byStrategy =
            new EnumMap<>(StrategyType.class);

    public TuningCaptureAdapterRegistry(List<TuningCaptureAdapter> discovered,
                                         CaptureToggleService toggleService) {
        this.discovered = discovered;
        this.toggleService = toggleService;
    }

    @PostConstruct
    void init() {
        for (TuningCaptureAdapter adapter : discovered) {
            StrategyType strategy = adapter.strategy();
            TuningCaptureAdapter prior = byStrategy.put(strategy, adapter);
            if (prior != null) {
                throw new IllegalStateException(
                        "Two TuningCaptureAdapter beans claim StrategyType " + strategy
                                + ": " + prior.getClass().getName()
                                + " and " + adapter.getClass().getName()
                                + ". There must be exactly one adapter per strategy.");
            }
            toggleService.ensureRowExists(strategy, adapter.defaultEpisodeWindowSec());
            log.info("[TuningCaptureAdapterRegistry] registered {} → {} ({}, defaultWindow={}s, {} buckets, {} shadowGates)",
                    strategy, adapter.getClass().getSimpleName(),
                    adapter.cadence(), adapter.defaultEpisodeWindowSec(),
                    adapter.bucketDimensions().size(), adapter.shadowGates().size());
        }
        log.info("[TuningCaptureAdapterRegistry] {} adapter(s) registered", byStrategy.size());
    }

    /** Returns the adapter for {@code strategy}, or empty if none is registered. */
    public Optional<TuningCaptureAdapter> find(StrategyType strategy) {
        return Optional.ofNullable(byStrategy.get(strategy));
    }

    /** All registered adapters, in registration order. */
    public Collection<TuningCaptureAdapter> all() {
        return Collections.unmodifiableCollection(byStrategy.values());
    }

    /** True if a strategy has a registered adapter. */
    public boolean isRegistered(StrategyType strategy) {
        return byStrategy.containsKey(strategy);
    }

    public int size() {
        return byStrategy.size();
    }
}
