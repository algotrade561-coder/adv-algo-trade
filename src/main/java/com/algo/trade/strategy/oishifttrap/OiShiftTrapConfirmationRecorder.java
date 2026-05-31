package com.algo.trade.strategy.oishifttrap;

import org.springframework.stereotype.Service;

/**
 * Phase 6 stub — original legacy confirmation-shadow recorder retired; the unified
 * tuning pipeline's {@code ShadowGateEvent} now handles confirmation tracking
 * across all strategies. This stub keeps the legacy bean wiring intact.
 */
@Service
public class OiShiftTrapConfirmationRecorder {

    public void record(Object... args) { /* no-op */ }
}
