package com.kiteapioptions.persistence;

import com.kiteapioptions.domain.TradeStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<TradeEntity, String> {

    List<TradeEntity> findByStatus(TradeStatus status);

    List<TradeEntity> findByEntryTimeBetween(Instant from, Instant to);
}
