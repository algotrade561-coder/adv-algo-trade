package com.algo.trade.strategy;

import com.algo.trade.domain.StrategyDecision;
import java.util.Optional;

/**
 * Indicator snapshot captured at NO_TRADE evaluation time.
 * Strategies that compute indicators before rejecting populate this so the
 * CSV recorder can write per-filter pass/fail data on every row — not just on BUY signals.
 */
public record StrategyDiagnostics(
        String firstFailedFilter,
        Double ema9,
        Double ema21,
        String emaCrossType,
        Integer emaCrossConfirmCount,
        Double bbUpper,
        Double bbLower,
        Double bbBandwidth,
        Boolean bbSqueeze
) {
    /** Sentinel for strategies that don't provide diagnostics. */
    public static final StrategyDiagnostics NONE =
            new StrategyDiagnostics(null, null, null, null, null, null, null, null, null);

    /** Carries both the optional signal and the diagnostics from a single evaluate call. */
    public record WithSignal(Optional<StrategyDecision> signal, StrategyDiagnostics diagnostics) {}
}
