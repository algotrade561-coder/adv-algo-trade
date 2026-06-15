package com.algo.trade.tuning.capture;

import com.algo.trade.strategy.StrategyType;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TuningCaptureAuditRepository
        extends JpaRepository<TuningCaptureAuditEntity, Long> {

    List<TuningCaptureAuditEntity> findByStrategyOrderByChangedAtDesc(StrategyType strategy);

    List<TuningCaptureAuditEntity> findByChangedAtAfterOrderByChangedAtDesc(Instant cutoff);
}
