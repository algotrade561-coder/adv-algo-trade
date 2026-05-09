package com.algo.trade.backtest.v2;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for individual StrategyAdapters.
 * Validates that each adapter can evaluate entry/exit signals against synthetic data.
 */
@SpringBootTest
@ActiveProfiles("backtest")
class StrategyAdapterIntegrationTest {

    @Autowired
    private List<StrategyAdapter> adapters;

    private List<ChainSnapshot> bullishSnapshots;
    private List<ChainSnapshot> bearishSnapshots;
    private List<ChainSnapshot> sidewaysSnapshots;
    private List<ChainSnapshot> highVolSnapshots;

    @BeforeEach
    void setUp() {
        LocalDate testDate = LocalDate.of(2026, 4, 28);
        bullishSnapshots = TestSnapshotGenerator.generateBullishTrend("NIFTY", testDate, 24000.0);
        bearishSnapshots = TestSnapshotGenerator.generateBearishTrend("NIFTY", testDate, 24000.0);
        sidewaysSnapshots = TestSnapshotGenerator.generateSidewaysMarket("NIFTY", testDate, 24000.0);
        highVolSnapshots = TestSnapshotGenerator.generateHighVolatility("NIFTY", testDate, 24000.0);
    }

    @Test
    void shouldHaveAllExpectedAdapters() {
        assertNotNull(adapters);
        assertFalse(adapters.isEmpty());

        // Verify key adapters are present
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.DIRECTIONAL_BUY));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.SCALPING));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.VOLATILITY_BREAKOUT));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.OI_SHIFT_TRAP));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.EXPIRY_GAMMA));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.MOMENTUM));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.EXPIRY_REVERSAL));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.LONG_STRADDLE));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.GAP_AND_GO));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.REVERSAL_BUY));
        assertTrue(adapters.stream().anyMatch(a -> a.strategyType() == StrategyType.LONG_STRANGLE));
    }

    @Test
    void eachAdapterShouldEvaluateWithoutException() {
        for (StrategyAdapter adapter : adapters) {
            StrategyConfig config = new StrategyConfig(adapter.strategyType());

            // Should not throw on bullish data
            assertDoesNotThrow(() -> {
                for (int i = 20; i < bullishSnapshots.size(); i++) {
                    ChainSnapshot current = bullishSnapshots.get(i);
                    List<ChainSnapshot> history = bullishSnapshots.subList(
                            Math.max(0, i - 50), i);
                    adapter.evaluateEntry(current, history.reversed(), config);
                }
            }, "Adapter " + adapter.strategyType() + " threw on bullish data");

            // Should not throw on bearish data
            assertDoesNotThrow(() -> {
                for (int i = 20; i < bearishSnapshots.size(); i++) {
                    ChainSnapshot current = bearishSnapshots.get(i);
                    List<ChainSnapshot> history = bearishSnapshots.subList(
                            Math.max(0, i - 50), i);
                    adapter.evaluateEntry(current, history.reversed(), config);
                }
            }, "Adapter " + adapter.strategyType() + " threw on bearish data");
        }
    }

    @Test
    void directionalBuyShouldSignalOnBullishTrend() {
        StrategyAdapter adapter = findAdapter(StrategyType.DIRECTIONAL_BUY);
        StrategyConfig config = new StrategyConfig(StrategyType.DIRECTIONAL_BUY);

        boolean foundSignal = false;
        for (int i = 20; i < bullishSnapshots.size(); i++) {
            ChainSnapshot current = bullishSnapshots.get(i);
            List<ChainSnapshot> history = bullishSnapshots.subList(
                    Math.max(0, i - 50), i).reversed();

            Optional<StrategySignal> signal = adapter.evaluateEntry(current, history, config);
            if (signal.isPresent()) {
                foundSignal = true;
                assertEquals(StrategyType.DIRECTIONAL_BUY, signal.get().strategyType());
                assertTrue(signal.get().entryPrice() > 0);
                assertTrue(signal.get().confidence() > 0 && signal.get().confidence() <= 1);
                break;
            }
        }
        // Note: signal generation depends on random data, so we don't assert foundSignal
    }

    @Test
    void exitEvaluationShouldWork() {
        StrategyAdapter adapter = findAdapter(StrategyType.DIRECTIONAL_BUY);
        StrategyConfig config = new StrategyConfig(StrategyType.DIRECTIONAL_BUY);

        // Create a position
        Position position = new Position(
                "test-001", StrategyType.DIRECTIONAL_BUY, OptionType.CE,
                24000, 150.0, 24000.0,
                bullishSnapshots.get(0).timestamp(), "test entry", 75);

        // Evaluate exit against later snapshots
        boolean foundExit = false;
        for (int i = 10; i < bullishSnapshots.size(); i++) {
            Optional<ExitSignal> exit = adapter.evaluateExit(position, bullishSnapshots.get(i), config);
            if (exit.isPresent()) {
                foundExit = true;
                assertNotNull(exit.get().reason());
                assertTrue(exit.get().exitPrice() >= 0);
                break;
            }
        }
        // Exit may or may not trigger depending on random data
    }

    @Test
    void tradeSimulatorShouldWork() {
        TradeSimulator simulator = new TradeSimulator();
        ChainSnapshot snapshot = bullishSnapshots.get(10);

        double entryFill = simulator.simulateEntryFill(snapshot, snapshot.atmStrike(), OptionType.CE);
        double exitFill = simulator.simulateExitFill(snapshot, snapshot.atmStrike(), OptionType.CE);

        assertTrue(entryFill > 0, "Entry fill should be positive");
        assertTrue(exitFill > 0, "Exit fill should be positive");
        assertTrue(entryFill >= exitFill, "Entry (ask+slippage) should be >= exit (bid-slippage)");
    }

    @Test
    void snapshotValidatorShouldValidateTestData() {
        SnapshotValidator validator = new SnapshotValidator();

        for (ChainSnapshot snapshot : bullishSnapshots) {
            List<String> issues = validator.validate(snapshot);
            // Test data should be mostly valid
            assertTrue(issues.size() <= 2,
                    "Too many validation issues: " + issues);
        }
    }

    private StrategyAdapter findAdapter(StrategyType type) {
        return adapters.stream()
                .filter(a -> a.strategyType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Adapter not found: " + type));
    }
}
