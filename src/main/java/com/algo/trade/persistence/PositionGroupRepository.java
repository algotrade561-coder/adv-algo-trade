package com.algo.trade.persistence;

import com.algo.trade.strategy.StrategyType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PositionGroupRepository extends JpaRepository<PositionGroupEntity, Long> {

    /** All currently open spread position groups (used by SpreadPositionExitMonitor). */
    List<PositionGroupEntity> findByOpenTrue();

    /** Open groups for a specific strategy (used on startup to rebuild in-memory cache). */
    List<PositionGroupEntity> findByStrategyTypeAndOpenTrue(StrategyType strategyType);

    /** Look up a specific group by its business key. */
    Optional<PositionGroupEntity> findByGroupId(String groupId);
}
