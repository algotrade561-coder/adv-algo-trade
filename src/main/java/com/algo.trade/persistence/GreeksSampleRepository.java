package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.Instant;
import java.util.List;

public interface GreeksSampleRepository extends JpaRepository<GreeksSampleEntity, Long> {
    List<GreeksSampleEntity> findByIndexTypeAndOptionTypeOrderByCapturedAtDesc(String indexType, String optionType);
    List<GreeksSampleEntity> findByIndexTypeAndCapturedAtAfterOrderByCapturedAtAsc(String indexType, Instant after);
    void deleteByIndexTypeAndCapturedAtBefore(String indexType, Instant before);
}
