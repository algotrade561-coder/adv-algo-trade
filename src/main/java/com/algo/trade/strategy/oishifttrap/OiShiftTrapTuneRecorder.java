package com.algo.trade.strategy.oishifttrap;

import org.springframework.stereotype.Service;

/**
 * Phase 6 stub — original legacy CSV recorder retired; the unified tuning pipeline
 * now handles OI Shift Trap signal capture via {@code OiShiftTrapCaptureAdapter}.
 * This stub keeps the legacy bean wiring intact so callers compile.
 */
@Service
public class OiShiftTrapTuneRecorder {

    public void recordScanBlocked(Object... args) { /* no-op */ }

    public void recordEvaluation(Object... args) { /* no-op */ }

    public void recordSignal(Object... args) { /* no-op */ }
}
