package com.algo.trade.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/** Analog-days library (design §14.5, §17). */
public interface DayFingerprintRepository extends JpaRepository<DayFingerprintEntity, String> {
}
