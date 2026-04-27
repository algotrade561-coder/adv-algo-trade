package com.algo.trade.persistence;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StrategyDecisionRepository extends JpaRepository<StrategyDecisionEntity, Long> {

    Optional<StrategyDecisionEntity> findTopByOrderByTimestampDesc();

    List<StrategyDecisionEntity> findTop20ByOrderByTimestampDesc();

    List<StrategyDecisionEntity> findTop50ByOrderByTimestampDesc();

    List<StrategyDecisionEntity> findTop200BySignalTypeInOrderByTimestampDesc(List<String> signalTypes);

    List<StrategyDecisionEntity> findTop200BySignalTypeOrderByTimestampDesc(String signalType);

    /** Paginated entry signals. */
    Page<StrategyDecisionEntity> findBySignalTypeInOrderByTimestampDesc(List<String> signalTypes, Pageable pageable);

    /** Paginated entry signals filtered by date range. */
    Page<StrategyDecisionEntity> findBySignalTypeInAndTimestampBetweenOrderByTimestampDesc(
            List<String> signalTypes, java.time.Instant from, java.time.Instant to, Pageable pageable);

    /** Paginated rejected signals. */
    Page<StrategyDecisionEntity> findBySignalTypeOrderByTimestampDesc(String signalType, Pageable pageable);

    /** Paginated rejected signals filtered by date range. */
    Page<StrategyDecisionEntity> findBySignalTypeAndTimestampBetweenOrderByTimestampDesc(
            String signalType, java.time.Instant from, java.time.Instant to, Pageable pageable);

    /** All signals for a specific strategy type, most recent first. */
    List<StrategyDecisionEntity> findTop100ByStrategyTypeOrderByTimestampDesc(String strategyType);

    /** All entry signals (BUY_CE or BUY_PE) across all strategies. */
    @Query("SELECT s FROM StrategyDecisionEntity s WHERE s.signalType IN ('BUY_CE','BUY_PE') ORDER BY s.timestamp DESC")
    List<StrategyDecisionEntity> findTop200EntrySignals();

    /** Count signals per strategy type today — for monitoring dashboard. */
    @Query("SELECT s.strategyType, COUNT(s) FROM StrategyDecisionEntity s " +
           "WHERE s.timestamp >= :since GROUP BY s.strategyType ORDER BY COUNT(s) DESC")
    List<Object[]> countByStrategyTypeSince(java.time.Instant since);

    /** Count entry (BUY/SELL) signals since a given instant — for live dashboard counters. */
    @Query("SELECT COUNT(s) FROM StrategyDecisionEntity s WHERE " +
           "(s.signalType LIKE 'BUY_%' OR s.signalType LIKE 'SELL_%') AND s.timestamp >= :since")
    long countEntrySignalsSince(java.time.Instant since);

    /** Count NO_TRADE signals since a given instant — for live dashboard counters. */
    @Query("SELECT COUNT(s) FROM StrategyDecisionEntity s WHERE " +
           "s.signalType = 'NO_TRADE' AND s.timestamp >= :since")
    long countRejectedSignalsSince(java.time.Instant since);
}
