package com.algo.trade.strategy.oimomentum;

import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.List;

public interface AnchorBiasHistoryRepository extends JpaRepository<AnchorBiasHistory, Long> {

    List<AnchorBiasHistory> findByIndexTypeAndTradeDateAfter(String indexType, LocalDate after);

    List<AnchorBiasHistory> findByTradeDateAfter(LocalDate after);
}
