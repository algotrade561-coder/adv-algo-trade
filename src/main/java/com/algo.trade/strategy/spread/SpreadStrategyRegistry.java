package com.algo.trade.strategy.spread;

import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Central registry of all {@link AbstractSpreadStrategy} beans.
 * Collected automatically by Spring via constructor injection of
 * {@code List<AbstractSpreadStrategy>}. Both {@code AlgoTradingScheduler}
 * and {@code SpreadPositionExitMonitor} inject this instead of building
 * their own maps.
 */
@Component
public class SpreadStrategyRegistry {

    private final Map<StrategyType, AbstractSpreadStrategy> map;

    public SpreadStrategyRegistry(List<AbstractSpreadStrategy> strategies) {
        Map<StrategyType, AbstractSpreadStrategy> m = new EnumMap<>(StrategyType.class);
        strategies.forEach(s -> m.put(s.strategyType(), s));
        this.map = Collections.unmodifiableMap(m);
    }

    public Optional<AbstractSpreadStrategy> get(StrategyType type) {
        return Optional.ofNullable(map.get(type));
    }

    public Map<StrategyType, AbstractSpreadStrategy> all() {
        return map;
    }
}
