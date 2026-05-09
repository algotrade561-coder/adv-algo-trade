package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface ErrorEventRepository extends JpaRepository<ErrorEventEntity, Long> {
    long countByTimestampAfter(Instant after);
    List<ErrorEventEntity> findByTimestampAfter(Instant after);
    List<ErrorEventEntity> findByTimestampAfterOrderByTimestampDesc(Instant after);
    List<ErrorEventEntity> findBySeverityAndTimestampAfterOrderByTimestampDesc(String severity, Instant after);
    long countBySeverityAndTimestampAfter(String severity, Instant after);
    List<ErrorEventEntity> findByComponentAndTimestampAfterOrderByTimestampDesc(String component, Instant after);
}
