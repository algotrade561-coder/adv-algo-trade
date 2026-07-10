package com.algo.trade.tuning.change;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ConfigChangeLogRepository extends JpaRepository<ConfigChangeLog, Long> {

    List<ConfigChangeLog> findAllByOrderByChangedAtDesc(Pageable pageable);

    List<ConfigChangeLog> findByChangedAtBetweenOrderByChangedAtAsc(Instant from, Instant to);
}
