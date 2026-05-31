package com.algo.trade.tuning.analyzer;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.store.TuningEventStore;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * Query context handed to each {@link TuningAnalyzerPlugin#customSections}.
 * Carries the date range, strategy filter, and a reference to the
 * {@link TuningEventStore} so plugins can run SQL directly.
 *
 * <p>Constructed by the {@link TuningAnalyzerCoordinator} (Phase 6) per report
 * invocation. Phase 2 plugins are wired and tested but not yet called from a
 * coordinator — they're exercised via direct construction in tests.</p>
 */
public record TuningEventQuery(
        LocalDate fromDate,
        LocalDate toDate,
        Set<StrategyType> strategies,
        TuningEventStore store
) {

    public TuningEventQuery {
        Objects.requireNonNull(fromDate, "fromDate");
        Objects.requireNonNull(toDate, "toDate");
        Objects.requireNonNull(strategies, "strategies");
        Objects.requireNonNull(store, "store");
        if (fromDate.isAfter(toDate)) {
            throw new IllegalArgumentException("fromDate > toDate: " + fromDate + " → " + toDate);
        }
    }

    public boolean includesStrategy(StrategyType strategy) {
        return strategies.contains(strategy);
    }
}
