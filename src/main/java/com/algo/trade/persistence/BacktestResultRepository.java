package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BacktestResultRepository extends JpaRepository<BacktestResultEntity, String> {
}
