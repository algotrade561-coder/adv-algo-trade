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

    /** All signals (entries + rejections) since a given instant, oldest first — for trade journal CSV. */
    List<StrategyDecisionEntity> findByTimestampGreaterThanEqualOrderByTimestampAsc(java.time.Instant since);

    /** Count NO_TRADE signals grouped by firstFailedFilter — for the filter funnel UI panel. */
    @Query("SELECT s.firstFailedFilter, COUNT(s) FROM StrategyDecisionEntity s " +
           "WHERE s.signalType = 'NO_TRADE' AND s.timestamp >= :since " +
           "AND s.firstFailedFilter IS NOT NULL " +
           "GROUP BY s.firstFailedFilter ORDER BY COUNT(s) DESC")
    List<Object[]> countByFirstFailedFilterSince(java.time.Instant since);

    /**
     * Per-strategy NO_TRADE blockers grouped by firstFailedFilter — used by the EOD
     * Daily Blocker Summary to surface the dominant blocker per strategy. Returns rows
     * of (strategyType, firstFailedFilter, count) ordered so the top blocker for each
     * strategy comes first.
     */
    @Query("SELECT s.strategyType, s.firstFailedFilter, COUNT(s) FROM StrategyDecisionEntity s " +
           "WHERE s.signalType = 'NO_TRADE' AND s.timestamp >= :since " +
           "AND s.firstFailedFilter IS NOT NULL " +
           "GROUP BY s.strategyType, s.firstFailedFilter " +
           "ORDER BY s.strategyType ASC, COUNT(s) DESC")
    List<Object[]> countByStrategyAndFirstFailedFilterSince(java.time.Instant since);

    /** Per-strategy entry-signal counts (BUY_CE / BUY_PE) since a given instant. */
    @Query("SELECT s.strategyType, COUNT(s) FROM StrategyDecisionEntity s " +
           "WHERE s.signalType IN ('BUY_CE','BUY_PE') AND s.timestamp >= :since " +
           "GROUP BY s.strategyType")
    List<Object[]> countEntriesByStrategySince(java.time.Instant since);

    /** Entry signals since a given instant — for strategy scorecard computation. */
    @Query("SELECT s FROM StrategyDecisionEntity s WHERE " +
           "s.signalType IN ('BUY_CE','BUY_PE') AND s.timestamp >= :since " +
           "ORDER BY s.timestamp DESC")
    List<StrategyDecisionEntity> findEntrySignalsSince(java.time.Instant since);

    /** Bulk delete decisions older than a given instant — used by EOD cleanup job. */
    void deleteByTimestampBefore(java.time.Instant before);

    /** Find decisions by instrument key and time range — used by order audit trail. */
    List<StrategyDecisionEntity> findBySelectedInstrumentKeyAndTimestampBetween(
            String selectedInstrumentKey, java.time.Instant from, java.time.Instant to);

    /** Filtered paginated signals with optional criteria. */
    @Query("SELECT s FROM StrategyDecisionEntity s WHERE s.signalType IN :signalTypes " +
           "AND (:from IS NULL OR s.timestamp >= :from) " +
           "AND (:to IS NULL OR s.timestamp <= :to) " +
           "AND (COALESCE(:strategyType, '') = '' OR s.strategyType = :strategyType) " +
           "AND (COALESCE(:underlying, '') = '' OR s.underlying = :underlying) " +
           "AND (COALESCE(:optionType, '') = '' OR s.optionType = :optionType) " +
           "AND (COALESCE(:mode, '') = '' OR " +
           "     (:mode = 'PAPER' AND s.paperTrade = true) OR " +
           "     (:mode = 'LIVE' AND s.paperTrade = false)) " +
           "ORDER BY s.timestamp DESC")
    Page<StrategyDecisionEntity> findFilteredSignals(
            @org.springframework.data.repository.query.Param("signalTypes") List<String> signalTypes,
            @org.springframework.data.repository.query.Param("from") java.time.Instant from,
            @org.springframework.data.repository.query.Param("to") java.time.Instant to,
            @org.springframework.data.repository.query.Param("strategyType") String strategyType,
            @org.springframework.data.repository.query.Param("underlying") String underlying,
            @org.springframework.data.repository.query.Param("optionType") String optionType,
            @org.springframework.data.repository.query.Param("mode") String mode,
            Pageable pageable);
}
