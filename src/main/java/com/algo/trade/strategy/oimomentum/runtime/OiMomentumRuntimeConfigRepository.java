package com.algo.trade.strategy.oimomentum.runtime;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Single-row repository for {@link OiMomentumRuntimeConfig} — only id=1 is ever used.
 */
public interface OiMomentumRuntimeConfigRepository
        extends JpaRepository<OiMomentumRuntimeConfig, Long> {
}
