package com.algo.trade.persistence;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SignalRepository extends JpaRepository<SignalEntity, Long> {

    Optional<SignalEntity> findTopByOrderByTimestampDesc();
}
