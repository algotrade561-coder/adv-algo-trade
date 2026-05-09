package com.algo.trade.backtest.v2;

import com.algo.trade.backtest.BacktestRunResult;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.data.SnapshotFileWriter;
import com.algo.trade.strategy.StrategyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration test for the backtest engine.
 * 
 * Tests the complete flow:
 * 1. Generate test snapshots
 * 2. Write to disk
 * 3. Load via SnapshotLoader
 * 4. Validate via SnapshotValidator
 * 5. Run backtest via BacktestOrchestrator
 * 6. Verify results
 */
@SpringBootTest
@TestPropertySource(properties = {
        "snapshot.base-dir=${java.io.tmpdir}/test-snapshots"
})
class BacktestOrchestratorIntegrationTest {

    @Autowired
    private BacktestOrchestrator orchestrator;

    @Autowired
    private SnapshotFileWriter snapshotFileWriter;

    @Autowired
    private TradingProperties properties;

    @TempDir
    Path tempDir;

    private LocalDate testDate;

    @BeforeEach
    void setUp() {
        testDate = LocalDate.of(2026, 4, 15);
    }

    @Test
    void testCompleteBacktest_DirectionalBuy() {
        // Given: Generate and write test snapshots
        List<ChainSnapshot> snapshots = TestSnapshotGenerator.generateBullishTrend(
                "NIFTY", testDate, 24000);
        
        snapshots.forEach(snapshotFileWriter::write);
        
        // When: Run backtest
        BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                "NIFTY",
                testDate,
                testDate,
                StrategyType.DIRECTIONAL_BUY,
                properties,
                "test-directional"
        );
        
        BacktestRunResult result = orchestrator.run(request);
        
        // Then: Verify result
        assertThat(result).isNotNull();
        assertThat(result.id()).isNotEmpty();
        assertThat(result.metrics()).isNotNull();
        assertThat(result.trades()).isNotNull();
        assertThat(result.equityCurve()).isNotNull();
        
        // Verify metrics
        assertThat(result.metrics().totalTrades()).isGreaterThanOrEqualTo(0);
        assertThat(result.metrics().totalSignals()).isGreaterThanOrEqualTo(0);
        
        System.out.println("Backtest completed: " + result.id());
        System.out.println("Total trades: " + result.metrics().totalTrades());
        System.out.println("Total signals: " + result.metrics().totalSignals());
        System.out.println("Win rate: " + result.metrics().winRatePercent() + "%");
        System.out.println("Cumulative P&L: " + result.metrics().cumulativePnl());
    }

    @Test
    void testCompleteBacktest_Scalping() {
        // Given: Generate and write test snapshots
        List<ChainSnapshot> snapshots = TestSnapshotGenerator.generateBullishTrend(
                "NIFTY", testDate, 24000);
        
        snapshots.forEach(snapshotFileWriter::write);
        
        // When: Run backtest with SCALPING strategy
        BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                "NIFTY",
                testDate,
                testDate,
                StrategyType.SCALPING,
                properties,
                "test-scalping"
        );
        
        BacktestRunResult result = orchestrator.run(request);
        
        // Then: Verify result
        assertThat(result).isNotNull();
        assertThat(result.metrics()).isNotNull();
        
        System.out.println("Scalping backtest completed: " + result.id());
        System.out.println("Total trades: " + result.metrics().totalTrades());
    }

    @Test
    void testCompleteBacktest_VolatilityBreakout() {
        // Given: Generate high volatility snapshots
        List<ChainSnapshot> snapshots = TestSnapshotGenerator.generateHighVolatility(
                "NIFTY", testDate, 24000);
        
        snapshots.forEach(snapshotFileWriter::write);
        
        // When: Run backtest with VOLATILITY_BREAKOUT strategy
        BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                "NIFTY",
                testDate,
                testDate,
                StrategyType.VOLATILITY_BREAKOUT,
                properties,
                "test-volbreakout"
        );
        
        BacktestRunResult result = orchestrator.run(request);
        
        // Then: Verify result
        assertThat(result).isNotNull();
        assertThat(result.metrics()).isNotNull();
        
        System.out.println("VolatilityBreakout backtest completed: " + result.id());
        System.out.println("Total trades: " + result.metrics().totalTrades());
    }

    @Test
    void testCompleteBacktest_OiShiftTrap() {
        // Given: Generate test snapshots
        List<ChainSnapshot> snapshots = TestSnapshotGenerator.generateBullishTrend(
                "NIFTY", testDate, 24000);
        
        snapshots.forEach(snapshotFileWriter::write);
        
        // When: Run backtest with OI_SHIFT_TRAP strategy
        BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                "NIFTY",
                testDate,
                testDate,
                StrategyType.OI_SHIFT_TRAP,
                properties,
                "test-oitrap"
        );
        
        BacktestRunResult result = orchestrator.run(request);
        
        // Then: Verify result
        assertThat(result).isNotNull();
        assertThat(result.metrics()).isNotNull();
        
        System.out.println("OiShiftTrap backtest completed: " + result.id());
        System.out.println("Total trades: " + result.metrics().totalTrades());
    }

    @Test
    void testBacktest_MultipleStrategies() {
        // Given: Generate test snapshots
        List<ChainSnapshot> snapshots = TestSnapshotGenerator.generateBullishTrend(
                "NIFTY", testDate, 24000);
        
        snapshots.forEach(snapshotFileWriter::write);
        
        // When: Run all strategies
        StrategyType[] strategies = {
                StrategyType.DIRECTIONAL_BUY,
                StrategyType.SCALPING,
                StrategyType.VOLATILITY_BREAKOUT,
                StrategyType.OI_SHIFT_TRAP
        };
        
        for (StrategyType strategy : strategies) {
            BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                    "NIFTY",
                    testDate,
                    testDate,
                    strategy,
                    properties,
                    "test-" + strategy.name().toLowerCase()
            );
            
            BacktestRunResult result = orchestrator.run(request);
            
            // Then: All strategies should complete successfully
            assertThat(result).isNotNull();
            assertThat(result.metrics()).isNotNull();
            
            System.out.println(strategy + " - Trades: " + result.metrics().totalTrades() +
                    ", Signals: " + result.metrics().totalSignals());
        }
    }

    @Test
    void testBacktest_BearishMarket() {
        // Given: Generate bearish trend snapshots
        List<ChainSnapshot> snapshots = TestSnapshotGenerator.generateBearishTrend(
                "NIFTY", testDate, 24000);
        
        snapshots.forEach(snapshotFileWriter::write);
        
        // When: Run backtest
        BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                "NIFTY",
                testDate,
                testDate,
                StrategyType.DIRECTIONAL_BUY,
                properties,
                "test-bearish"
        );
        
        BacktestRunResult result = orchestrator.run(request);
        
        // Then: Should handle bearish market
        assertThat(result).isNotNull();
        assertThat(result.metrics()).isNotNull();
        
        System.out.println("Bearish market backtest completed");
        System.out.println("Total trades: " + result.metrics().totalTrades());
    }

    @Test
    void testBacktest_SidewaysMarket() {
        // Given: Generate sideways market snapshots
        List<ChainSnapshot> snapshots = TestSnapshotGenerator.generateSidewaysMarket(
                "NIFTY", testDate, 24000);
        
        snapshots.forEach(snapshotFileWriter::write);
        
        // When: Run backtest
        BacktestOrchestrator.BacktestRequest request = new BacktestOrchestrator.BacktestRequest(
                "NIFTY",
                testDate,
                testDate,
                StrategyType.SCALPING,
                properties,
                "test-sideways"
        );
        
        BacktestRunResult result = orchestrator.run(request);
        
        // Then: Should handle sideways market
        assertThat(result).isNotNull();
        assertThat(result.metrics()).isNotNull();
        
        System.out.println("Sideways market backtest completed");
        System.out.println("Total trades: " + result.metrics().totalTrades());
    }
}
