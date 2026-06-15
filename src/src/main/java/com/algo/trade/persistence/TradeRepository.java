package com.algo.trade.persistence;

import com.algo.trade.domain.TradeStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TradeRepository extends JpaRepository<TradeEntity, String> {

    List<TradeEntity> findByStatus(TradeStatus status);

    List<TradeEntity> findByEntryTimeBetween(Instant from, Instant to);

    List<TradeEntity> findByInstrumentKeyAndStatus(String instrumentKey, TradeStatus status);

    List<TradeEntity> findByInstrumentKeyAndEntryTimeBetween(String instrumentKey, Instant from, Instant to);

    List<TradeEntity> findByUserId(Long userId);

    List<TradeEntity> findByUserIdAndStatus(Long userId, TradeStatus status);

    List<TradeEntity> findByUserIdAndEntryTimeBetween(Long userId, Instant from, Instant to);
}
