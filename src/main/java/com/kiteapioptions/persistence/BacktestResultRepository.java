package com.kiteapioptions.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BacktestResultRepository extends JpaRepository<BacktestResultEntity, String> {
}
