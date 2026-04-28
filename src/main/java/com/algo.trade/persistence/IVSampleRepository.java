package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface IVSampleRepository extends JpaRepository<IVSampleEntity, Long> {
    List<IVSampleEntity> findByIndexTypeOrderBySampleDateAsc(String indexType);
    java.util.Optional<IVSampleEntity> findByIndexTypeAndSampleDate(String indexType, java.time.LocalDate sampleDate);
    void deleteByIndexTypeAndSampleDateBefore(String indexType, java.time.LocalDate before);
}
