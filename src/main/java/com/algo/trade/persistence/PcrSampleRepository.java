package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PcrSampleRepository extends JpaRepository<PcrSampleEntity, Long> {

    /** Today's PCR samples for one index, oldest first (for chart hydration on startup). */
    List<PcrSampleEntity> findByIndexTypeAndCapturedAtAfterOrderByCapturedAtAsc(String indexType, Instant after);

    /** Cleanup: delete samples older than a given timestamp (e.g., yesterday's data). */
    void deleteByIndexTypeAndCapturedAtBefore(String indexType, Instant before);
}
