package com.algo.trade.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ExitEvaluationRepository extends JpaRepository<ExitEvaluationEntity, Long> {

    List<ExitEvaluationEntity> findByPositionId(String positionId);

    List<ExitEvaluationEntity> findByEvaluatedAtAfter(Instant after);

    void deleteByEvaluatedAtBefore(Instant before);
}
