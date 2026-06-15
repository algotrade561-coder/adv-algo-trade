package com.algo.trade.tuning.capture;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TuningReportJobRepository
        extends JpaRepository<TuningReportJobEntity, String> {

    List<TuningReportJobEntity> findByStatus(TuningReportJobStatus status);

    List<TuningReportJobEntity> findAllByOrderByRequestedAtDesc(Pageable pageable);
}
