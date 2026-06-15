package com.algo.trade.execution;

import org.springframework.stereotype.Service;

/**
 * Phase 6 stub — original legacy CSV recorder retired; the unified tuning pipeline
 * ({@code com.algo.trade.tuning}) now handles execution outcome capture. This stub
 * keeps the legacy bean wiring intact so callers compile until they're migrated off.
 */
@Service
public class ExecutionOutcomeCsvRecorder {

    public void recordEntry(Object... args) { /* no-op */ }

    public void recordExit(Object... args) { /* no-op */ }
}
