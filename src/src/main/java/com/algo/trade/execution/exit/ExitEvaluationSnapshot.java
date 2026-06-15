package com.algo.trade.execution.exit;

import java.time.Instant;

/**
 * Last computed exit evaluation for dashboard / debugging.
 */
public record ExitEvaluationSnapshot(
        String positionId,
        String positionType,
        String strategyType,
        String underlying,
        Instant evaluatedAt,
        double profitPercent,
        double peakProfitPercent,
        double effectiveStopLossPercent,
        double effectiveTargetPercent,
        double effectiveTrailActivationPercent,
        double effectiveTrailGapPercent,
        boolean atrUsed,
        ExitMode exitMode,
        String decision,
        String detail
) {}
