package com.kiteapioptions.persistence;

import com.kiteapioptions.domain.OrderStatus;
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
}
