package com.algo.trade.backtest;

import com.algo.trade.backtest.ExecutionFlowTracker.ExecutionFlowCoverage;
import com.algo.trade.domain.UnderlyingSymbol;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Comprehensive result of a verify-all run across all 16 strategies.
 */
public record VerifyAllResult(
        String verifyId,
        Instant startedAt,
        Instant completedAt,
        UnderlyingSymbol underlying,
        LocalDate from,
        LocalDate to,
        List<StrategyVerificationResult> strategyResults,
        ExecutionFlowCoverage flowCoverage,
        ConfigSnapshot configSnapshot,
        Path outputDirectory,
        Path reportHtml,
        Path summaryJson
) {

    public VerifyAllResult {
        strategyResults = strategyResults == null ? List.of() : List.copyOf(strategyResults);
    }
}
