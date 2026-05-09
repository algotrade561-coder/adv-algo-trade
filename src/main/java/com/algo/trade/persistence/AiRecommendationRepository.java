package com.algo.trade.persistence;

import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiRecommendationRepository extends JpaRepository<AiRecommendationEntity, Long> {

    List<AiRecommendationEntity> findTop20ByOrderByGeneratedAtDesc();

    List<AiRecommendationEntity> findByGeneratedAtBetweenOrderByGeneratedAtDesc(Instant from, Instant to);
}
