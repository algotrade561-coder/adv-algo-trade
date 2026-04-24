package com.algo.trade.strategy;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface StrategyConfigRepository extends JpaRepository<StrategyConfig, Long> {
    Optional<StrategyConfig> findByStrategyType(StrategyType type);
    List<StrategyConfig> findByEnabledTrue();
}
