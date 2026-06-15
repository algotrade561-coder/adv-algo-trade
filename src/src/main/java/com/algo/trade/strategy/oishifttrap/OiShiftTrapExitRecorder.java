package com.algo.trade.strategy.oishifttrap;

import org.springframework.stereotype.Service;

/**
 * Phase 6 stub — original legacy exit recorder retired; the unified
 * {@code ExitEvent} now handles trade exits across all strategies via the
 * adapter framework. This stub keeps the legacy bean wiring intact.
 */
@Service
public class OiShiftTrapExitRecorder {

    public void recordExit(Object... args) { /* no-op */ }
}
