package com.algo.trade.strategy.oishifttrap;

import org.springframework.data.jpa.repository.JpaRepository;

/** Single-row repository for {@link OiShiftTrapLadderConfig}. */
public interface OiShiftTrapLadderConfigRepository
        extends JpaRepository<OiShiftTrapLadderConfig, Long> {
}
