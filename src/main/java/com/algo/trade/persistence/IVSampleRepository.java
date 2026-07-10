package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IVSampleRepository extends JpaRepository<IVSampleEntity, Long> {
    List<IVSampleEntity> findByIndexTypeOrderBySampleDateAsc(String indexType);
    java.util.Optional<IVSampleEntity> findByIndexTypeAndSampleDate(String indexType, java.time.LocalDate sampleDate);
    @org.springframework.transaction.annotation.Transactional
    void deleteByIndexTypeAndSampleDateBefore(String indexType, java.time.LocalDate before);

    /** Purge ALL samples for an index — used by the reset+reseed step so the polluted VIX-seed-vs-biased-bot-IV
     *  series is fully rebuilt on one consistent measure (2026-07-06 IV-drift fix). */
    @org.springframework.transaction.annotation.Transactional
    long deleteByIndexType(String indexType);
}
