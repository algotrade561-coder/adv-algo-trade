package com.algo.trade.strategy;

import org.springframework.stereotype.Service;

/**
 * Phase 6 stub — original legacy CSV recorder retired; the unified tuning pipeline
 * ({@code com.algo.trade.tuning}) now handles signal capture. This stub keeps the
 * legacy bean wiring intact so callers compile until they're migrated off.
 */
@Service
public class StrategySignalCsvRecorder {

    public void record(Object... args) { /* no-op */ }

    public void recordAdditionalNoTrade(Object... args) { /* no-op */ }

    public void recordUnified(Object ctx) { /* no-op */ }
}
