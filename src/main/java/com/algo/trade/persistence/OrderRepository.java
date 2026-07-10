package com.algo.trade.persistence;

import com.algo.trade.domain.OrderStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<OrderEntity, String> {

    List<OrderEntity> findBySideAndUpdatedAtBetween(String side, Instant from, Instant to);

    List<OrderEntity> findByInstrumentKeyAndSideAndStatusIn(
            String instrumentKey,
            String side,
            Collection<OrderStatus> statuses
    );

    List<OrderEntity> findByStatusIn(Collection<OrderStatus> statuses);

    List<OrderEntity> findByUserId(Long userId);

    List<OrderEntity> findByUserIdAndUpdatedAtBetween(Long userId, Instant from, Instant to);

    List<OrderEntity> findByUpdatedAtBetween(Instant from, Instant to);

    java.util.Optional<OrderEntity> findByBrokerOrderId(String brokerOrderId);
}
