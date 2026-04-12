package com.kiteapioptions.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StrategyDecisionRepository extends JpaRepository<StrategyDecisionEntity, Long> {

    Optional<StrategyDecisionEntity> findTopByOrderByTimestampDesc();
}
