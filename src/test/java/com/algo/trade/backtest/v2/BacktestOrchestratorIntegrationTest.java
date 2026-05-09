package com.algo.trade.backtest.v2;

import com.algo.trade.backtest.BacktestRunResult;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.data.SnapshotFileWriter;
import com.algo.trade.strategy.StrategyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for BacktestOrchestrator.
 * Uses TestSnapshotGenerator to create synthetic data and validates the full pipeline.
 */
@SpringBootTest
@ActiveProfiles("backtest")
class BacktestOrchestratorIntegrationTest {

    @Autowired
    private BacktestOrchestrator orchestrator;

    @Autowired
    private SnapshotFileWriter snapshotFileWriter;

    private static final String UNDERLYING = "NIFTY";
    private static final LocalDate TEST_DATE = LocalDate.of(2026, 4, 28);
    private static final double START_SPOT = 24000.0;

    @BeforeEach
    void setUp() {
        // Generate and write test snapshots
        List<ChainSnapshot> bullishDay = TestSnapshotGenerator.generateBullishTrend(
                UNDERLYING, TEST_DATE, START_SPOT);
        for (ChainSnapshot snapshot : bullishDay) {
            snapshotFileWriter.write(snapshot);
        }
    }

    @Test
    void shouldListAvailableStrategies() {
        Set<StrategyType> strategies = orchestrator.availableStrategies();
        assertNotNull(strategies);
        assertFalse(strategies.isEmpty());
        assertTrue(strategies.contains(StrategyType.DIRECTIONAL_BUY));
        assertTrue(strategies.contains(StrategyType.SCALPING));
        assertTrue(strategies.contains(StrategyType.VOLATILITY_BREAKOUT));
    }

    @Test
    void shouldRunSingleStrategy() {
        BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                UNDERLYING, TEST_DATE, TEST_DATE, StrategyType.DIRECTIONAL_BUY);

        BacktestRunResult result = orchestrator.runStrategy(request);

        assertNotNull(result);
        assertNotNull(result.id());
        assertTrue(result.id().startsWith("V2-DIRECTIONAL_BUY-"));
        assertNotNull(result.metrics());
        assertNotNull(result.trades());
    }

    @Test
    void shouldRunAllStrategies() {
        Map<StrategyType, BacktestRunResult> results =
                orchestrator.runAllStrategies(UNDERLYING, TEST_DATE, TEST_DATE);

        assertNotNull(results);
        assertFalse(results.isEmpty());

        for (var entry : results.entrySet()) {
            assertNotNull(entry.getValue().id());
            assertNotNull(entry.getValue().metrics());
        }
    }

    @Test
    void shouldCheckDataAvailability() {
        BacktestOrchestrator.DataAvailability availability =
                orchestrator.checkDataAvailability(UNDERLYING, TEST_DATE, TEST_DATE);

        assertNotNull(availability);
        assertEquals(UNDERLYING, availability.underlying());
        assertTrue(availability.daysWithData() >= 0);
    }

    @Test
    void shouldRejectInvalidRequest() {
        assertThrows(IllegalArgumentException.class, () -> {
            BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                    UNDERLYING, TEST_DATE, TEST_DATE.minusDays(5), StrategyType.DIRECTIONAL_BUY);
            orchestrator.runStrategy(request);
        });
    }

    @Test
    void shouldHandleMissingData() {
        BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                UNDERLYING, LocalDate.of(2020, 1, 1), LocalDate.of(2020, 1, 5),
                StrategyType.DIRECTIONAL_BUY);

        BacktestRunResult result = orchestrator.runStrategy(request);

        assertNotNull(result);
        assertEquals(0, result.metrics().totalTrades());
    }
}
