package com.algo.trade.backtest.v2;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.strategy.StrategyType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for strategy adapters.
 * 
 * Tests each adapter with synthetic snapshot data to verify:
 * - Signal generation works
 * - Data conversions are correct
 * - Strategies produce expected behavior
 */
@SpringBootTest
class StrategyAdapterIntegrationTest {

    @Autowired
    private DirectionalBuyAdapter directionalBuyAdapter;

    @Autowired
    private ScalpingAdapter scalpingAdapter;

    @Autowired
    private VolatilityBreakoutAdapter volatilityBreakoutAdapter;

    @Autowired
    private OiShiftTrapAdapter oiShiftTrapAdapter;

    @Autowired
    private TradingProperties properties;

    private List<ChainSnapshot> bullishSnapshots;
    private List<ChainSnapshot> bearishSnapshots;
    private List<ChainSnapshot> sidewaysSnapshots;

    @BeforeEach
    void setUp() {
        LocalDate testDate = LocalDate.of(2026, 4, 15);
        
        // Generate test data
        bullishSnapshots = TestSnapshotGenerator.generateBullishTrend("NIFTY", testDate, 24000);
        bearishSnapshots = TestSnapshotGenerator.generateBearishTrend("NIFTY", testDate, 24000);
        sidewaysSnapshots = TestSnapshotGenerator.generateSidewaysMarket("NIFTY", testDate, 24000);
    }

    @Test
    void testDirectionalBuyAdapter_BullishTrend() {
        // Given: Bullish trend snapshots
        ChainSnapshot currentSnapshot = bullishSnapshots.get(bullishSnapshots.size() - 1);
        
        // When: Evaluate entry
        Optional<StrategySignal> signal = directionalBuyAdapter.evaluateEntry(
                currentSnapshot,
                bullishSnapshots,
                properties
        );
        
        // Then: Should generate signal (may or may not fire depending on conditions)
        assertThat(directionalBuyAdapter.getStrategyType()).isEqualTo(StrategyType.DIRECTIONAL_BUY);
        // Signal may be empty if conditions not met, which is valid
    }

    @Test
    void testScalpingAdapter_BullishTrend() {
        // Given: Bullish trend snapshots (should trigger EMA crossover)
        ChainSnapshot currentSnapshot = bullishSnapshots.get(bullishSnapshots.size() - 1);
        
        // When: Evaluate entry
        Optional<StrategySignal> signal = scalpingAdapter.evaluateEntry(
                currentSnapshot,
                bullishSnapshots,
                properties
        );
        
        // Then: Adapter should work without errors
        assertThat(scalpingAdapter.getStrategyType()).isEqualTo(StrategyType.SCALPING);
        
        // If signal fired, verify it's valid
        signal.ifPresent(s -> {
            assertThat(s.optionType()).isNotNull();
            assertThat(s.strike()).isGreaterThan(0);
            assertThat(s.decision()).isNotNull();
        });
    }

    @Test
    void testScalpingAdapter_InsufficientHistory() {
        // Given: Only 5 snapshots (insufficient for EMA calculation)
        List<ChainSnapshot> shortHistory = bullishSnapshots.subList(0, 5);
        ChainSnapshot currentSnapshot = shortHistory.get(shortHistory.size() - 1);
        
        // When: Evaluate entry
        Optional<StrategySignal> signal = scalpingAdapter.evaluateEntry(
                currentSnapshot,
                shortHistory,
                properties
        );
        
        // Then: Should return empty (not enough history)
        assertThat(signal).isEmpty();
    }

    @Test
    void testVolatilityBreakoutAdapter_HighVolatility() {
        // Given: High volatility snapshots
        LocalDate testDate = LocalDate.of(2026, 4, 15);
        List<ChainSnapshot> volatileSnapshots = TestSnapshotGenerator.generateHighVolatility(
                "NIFTY", testDate, 24000);
        ChainSnapshot currentSnapshot = volatileSnapshots.get(volatileSnapshots.size() - 1);
        
        // When: Evaluate entry
        Optional<StrategySignal> signal = volatilityBreakoutAdapter.evaluateEntry(
                currentSnapshot,
                volatileSnapshots,
                properties
        );
        
        // Then: Adapter should work without errors
        assertThat(volatilityBreakoutAdapter.getStrategyType()).isEqualTo(StrategyType.VOLATILITY_BREAKOUT);
        
        // If signal fired, verify it's valid
        signal.ifPresent(s -> {
            assertThat(s.optionType()).isNotNull();
            assertThat(s.strike()).isGreaterThan(0);
            assertThat(s.decision()).isNotNull();
        });
    }

    @Test
    void testVolatilityBreakoutAdapter_InsufficientHistory() {
        // Given: Only 10 snapshots (insufficient for 15-min aggregation + BB)
        List<ChainSnapshot> shortHistory = bullishSnapshots.subList(0, 10);
        ChainSnapshot currentSnapshot = shortHistory.get(shortHistory.size() - 1);
        
        // When: Evaluate entry
        Optional<StrategySignal> signal = volatilityBreakoutAdapter.evaluateEntry(
                currentSnapshot,
                shortHistory,
                properties
        );
        
        // Then: Should return empty (not enough history)
        assertThat(signal).isEmpty();
    }

    @Test
    void testOiShiftTrapAdapter_BullishTrend() {
        // Given: Bullish trend snapshots
        ChainSnapshot currentSnapshot = bullishSnapshots.get(bullishSnapshots.size() - 1);
        
        // When: Evaluate entry
        Optional<StrategySignal> signal = oiShiftTrapAdapter.evaluateEntry(
                currentSnapshot,
                bullishSnapshots,
                properties
        );
        
        // Then: Adapter should work without errors
        assertThat(oiShiftTrapAdapter.getStrategyType()).isEqualTo(StrategyType.OI_SHIFT_TRAP);
        
        // If signal fired, verify it's valid
        signal.ifPresent(s -> {
            assertThat(s.optionType()).isNotNull();
            assertThat(s.strike()).isGreaterThan(0);
            assertThat(s.decision()).isNotNull();
        });
    }

    @Test
    void testOiShiftTrapAdapter_BearishTrend() {
        // Given: Bearish trend snapshots
        ChainSnapshot currentSnapshot = bearishSnapshots.get(bearishSnapshots.size() - 1);
        
        // When: Evaluate entry
        Optional<StrategySignal> signal = oiShiftTrapAdapter.evaluateEntry(
                currentSnapshot,
                bearishSnapshots,
                properties
        );
        
        // Then: Adapter should work without errors
        assertThat(oiShiftTrapAdapter.getStrategyType()).isEqualTo(StrategyType.OI_SHIFT_TRAP);
    }

    @Test
    void testAllAdapters_SidewaysMarket() {
        // Given: Sideways market snapshots
        ChainSnapshot currentSnapshot = sidewaysSnapshots.get(sidewaysSnapshots.size() - 1);
        
        // When: Evaluate all adapters
        Optional<StrategySignal> directionalSignal = directionalBuyAdapter.evaluateEntry(
                currentSnapshot, sidewaysSnapshots, properties);
        Optional<StrategySignal> scalpingSignal = scalpingAdapter.evaluateEntry(
                currentSnapshot, sidewaysSnapshots, properties);
        Optional<StrategySignal> volBreakoutSignal = volatilityBreakoutAdapter.evaluateEntry(
                currentSnapshot, sidewaysSnapshots, properties);
        Optional<StrategySignal> oiTrapSignal = oiShiftTrapAdapter.evaluateEntry(
                currentSnapshot, sidewaysSnapshots, properties);
        
        // Then: All adapters should execute without errors
        // Signals may or may not fire depending on market conditions
        assertThat(directionalBuyAdapter).isNotNull();
        assertThat(scalpingAdapter).isNotNull();
        assertThat(volatilityBreakoutAdapter).isNotNull();
        assertThat(oiShiftTrapAdapter).isNotNull();
    }

    @Test
    void testSnapshotDataQuality() {
        // Given: Generated snapshots
        ChainSnapshot snapshot = bullishSnapshots.get(0);
        
        // Then: Verify data quality
        assertThat(snapshot.underlying()).isEqualTo("NIFTY");
        assertThat(snapshot.spot()).isGreaterThan(0);
        assertThat(snapshot.vix()).isGreaterThan(0);
        assertThat(snapshot.atmStrike()).isGreaterThan(0);
        assertThat(snapshot.strikes()).hasSize(21); // ATM ± 10
        
        // Verify strike data
        ChainSnapshot.StrikeData atmStrike = snapshot.strikes().stream()
                .filter(s -> s.strike() == snapshot.atmStrike())
                .findFirst()
                .orElseThrow();
        
        assertThat(atmStrike.ceLTP()).isGreaterThan(0);
        assertThat(atmStrike.peLTP()).isGreaterThan(0);
        assertThat(atmStrike.ceOI()).isGreaterThan(0);
        assertThat(atmStrike.peOI()).isGreaterThan(0);
        assertThat(atmStrike.ceIV()).isGreaterThan(0);
        assertThat(atmStrike.peIV()).isGreaterThan(0);
    }
}
