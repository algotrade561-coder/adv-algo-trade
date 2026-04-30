package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorEventRepository extends JpaRepository<ErrorEventEntity, Long> {
    long countByTimestampAfter(java.time.Instant after);
    java.util.List<ErrorEventEntity> findByTimestampAfter(java.time.Instant after);
}
