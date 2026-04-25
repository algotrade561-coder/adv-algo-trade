package com.algo.trade.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SpreadBacktestResultTest {

    @Test
    void nullCollections_defaultToEmpty() {
        var metrics = new BacktestMetrics(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of());

        var result = new SpreadBacktestResult(null, metrics, 0, 0, null, null);

        assertThat(result.trades()).isEmpty();
        assertThat(result.rejectionReasons()).isEmpty();
        assertThat(result.strategySpecificMetrics()).isEmpty();
    }

    @Test
    void defensiveCopies_areImmutable() {
        var metrics = new BacktestMetrics(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of());

        var result = new SpreadBacktestResult(
                List.of(), metrics, 10, 3,
                Map.of("premium_too_high", 2, "risk_rejected", 1),
                Map.of("avgBBWidth", "12.5")
        );

        assertThat(result.totalSignals()).isEqualTo(10);
        assertThat(result.rejectedSignals()).isEqualTo(3);
        assertThat(result.rejectionReasons()).containsEntry("premium_too_high", 2);
        assertThat(result.strategySpecificMetrics()).containsEntry("avgBBWidth", "12.5");
    }
}
