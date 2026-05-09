package com.algo.trade.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfig, Long> {
    /** Backward-compatible single-result lookup (returns first match — NIFTY row while only one underlying exists per type). */
    Optional<StrategyConfig> findByStrategyType(StrategyType type);

    /** Returns all configs for a given strategy type across all underlyings. */
    List<StrategyConfig> findAllByStrategyType(StrategyType type);

    /** Returns the config for a specific strategy type + underlying combination. */
    Optional<StrategyConfig> findByStrategyTypeAndUnderlying(StrategyType type, String underlying);

    /** Returns all enabled configs regardless of underlying. */
    List<StrategyConfig> findByEnabledTrue();

    /** Returns enabled configs for a specific underlying — used by the scheduler inner loop. */
    List<StrategyConfig> findByUnderlyingAndEnabledTrue(String underlying);

    /** Returns all configs (enabled + disabled) for a specific underlying. */
    List<StrategyConfig> findByUnderlying(String underlying);
}
