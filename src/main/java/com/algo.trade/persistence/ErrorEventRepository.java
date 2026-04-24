package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ErrorEventRepository extends JpaRepository<ErrorEventEntity, Long> {
}
