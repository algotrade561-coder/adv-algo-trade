package com.algo.trade.config;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the single-row {@link GlobalConfig} entity.
 * No custom query methods needed — {@code findById(1L)} is sufficient.
 */
public interface GlobalConfigRepository extends JpaRepository<GlobalConfig, Long> {
}
