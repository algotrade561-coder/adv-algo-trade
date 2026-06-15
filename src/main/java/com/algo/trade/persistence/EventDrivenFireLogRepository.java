package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface EventDrivenFireLogRepository extends JpaRepository<EventDrivenFireLogEntity, Long> {

    /** Returns today's fire record for a given underlying, if any. */
    Optional<EventDrivenFireLogEntity> findByUnderlyingAndFireDate(String underlying, LocalDate fireDate);

    /** Returns all fire records for a given day across underlyings. Used by
     *  {@code EventDrivenBuyStrategy.@PostConstruct} to bootstrap the in-memory map. */
    List<EventDrivenFireLogEntity> findByFireDate(LocalDate fireDate);
}
