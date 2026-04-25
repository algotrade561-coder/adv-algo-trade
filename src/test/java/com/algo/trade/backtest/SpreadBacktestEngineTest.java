package com.algo.trade.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.regime.RegimeFilter;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.strategy.SpreadStrategyEvaluator;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.config.TradingProperties;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SpreadBacktestEngine} — exercises P&L computation,
 * exit condition evaluation, DTE SL scaling, and trailing stop logic
 * via reflection on private methods.
 *
 * Validates: Requirements 2.4, 4.4, 4.5, 4.6, 4.7, 4.10
 */
class SpreadBacktestEngineTest {

    private SpreadBacktestEngine engine;
    private ExecutionFlowTracker tracker;

    @BeforeEach
    void setUp() {
        engine = new SpreadBacktestEngine(
                mock(OptionChainBuilder.class),
                mock(SpreadStrategyEvaluator.class),
                mock(EmaIndicator.class),
                mock(MarketGuard.class),
                mock(RegimeFilter.class));
        tracker = new ExecutionFlowTracker();
    }

    // ── Helper: invoke private method via reflection ──────────────────────

    @SuppressWarnings("unchecked")
    private <T> T invoke(String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method m = SpreadBacktestEngine.class.getDeclaredMethod(methodName, paramTypes);
        m.setAccessible(true);
        return (T) m.invoke(engine, args);
    }

    // ── Helper: create OpenSpreadPosition via reflection ─────────────────

    private Object openPosition(String tradeId, StrategyType type, List<SpreadLeg> legs,
                                Map<String, BigDecimal> entryPrices, Instant entryTime,
                                Map<String, BigDecimal> currentPrices,
                                BigDecimal entryNetDebit, BigDecimal bestPnl) throws Exception {
        Class<?> clazz = Class.forName(
                "com.algo.trade.backtest.SpreadBacktestEngine$OpenSpreadPosition");
        Constructor<?> ctor = clazz.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        return ctor.newInstance(tradeId, type, legs, entryPrices, entryTime,
                currentPrices, entryNetDebit, bestPnl);
    }

    // ── Helpers for building test legs ────────────────────────────────────

    private SpreadLeg leg(int strike, OptionType optType, OrderSide side, int qty) {
        return new SpreadLeg(strike + "-" + optType.name(), strike, optType, side, qty,
                LocalDate.of(2026, 1, 29));
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. Iron Condor P&L Calculation (4-leg)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Iron Condor P&L Calculation")
    class IronCondorPnl {

        @Test
        @DisplayName("4-leg iron condor: BUY legs profit = exit-entry, SELL legs profit = entry-exit")
        void computesCombinedPnlForIronCondor() throws Exception {
            // Iron condor legs:
            //   SELL 24600 CE @ 55 → exit @ 15  → profit = (55-15) × 50 = 2000
            //   BUY  24800 CE @ 25 → exit @ 5   → profit = (5-25) × 50  = -1000
            //   SELL 24400 PE @ 60 → exit @ 20  → profit = (60-20) × 50 = 2000
            //   BUY  24200 PE @ 30 → exit @ 10  → profit = (10-30) × 50 = -1000
            // Total P&L = 2000 - 1000 + 2000 - 1000 = 2000

            List<SpreadLeg> legs = List.of(
                    leg(24600, OptionType.CE, OrderSide.SELL, 50),
                    leg(24800, OptionType.CE, OrderSide.BUY, 50),
                    leg(24400, OptionType.PE, OrderSide.SELL, 50),
                    leg(24200, OptionType.PE, OrderSide.BUY, 50));

            Map<String, BigDecimal> entryPrices = Map.of(
                    "24600-CE", new BigDecimal("55"),
                    "24800-CE", new BigDecimal("25"),
                    "24400-PE", new BigDecimal("60"),
                    "24200-PE", new BigDecimal("30"));

            Map<String, BigDecimal> exitPrices = Map.of(
                    "24600-CE", new BigDecimal("15"),
                    "24800-CE", new BigDecimal("5"),
                    "24400-PE", new BigDecimal("20"),
                    "24200-PE", new BigDecimal("10"));

            BigDecimal pnl = invoke("computeCombinedPnl",
                    new Class[]{List.class, Map.class, Map.class},
                    legs, entryPrices, exitPrices);

            assertThat(pnl).isEqualByComparingTo("2000");
        }

        @Test
        @DisplayName("iron condor losing trade: market moves through one side")
        void ironCondorLosingTrade() throws Exception {
            // Market rallied — CE side loses, PE side profits
            //   SELL 24600 CE @ 55 → exit @ 120 → profit = (55-120) × 50 = -3250
            //   BUY  24800 CE @ 25 → exit @ 60  → profit = (60-25) × 50  = 1750
            //   SELL 24400 PE @ 60 → exit @ 5   → profit = (60-5) × 50   = 2750
            //   BUY  24200 PE @ 30 → exit @ 2   → profit = (2-30) × 50   = -1400
            // Total = -3250 + 1750 + 2750 - 1400 = -150

            List<SpreadLeg> legs = List.of(
                    leg(24600, OptionType.CE, OrderSide.SELL, 50),
                    leg(24800, OptionType.CE, OrderSide.BUY, 50),
                    leg(24400, OptionType.PE, OrderSide.SELL, 50),
                    leg(24200, OptionType.PE, OrderSide.BUY, 50));

            Map<String, BigDecimal> entryPrices = Map.of(
                    "24600-CE", new BigDecimal("55"),
                    "24800-CE", new BigDecimal("25"),
                    "24400-PE", new BigDecimal("60"),
                    "24200-PE", new BigDecimal("30"));

            Map<String, BigDecimal> exitPrices = Map.of(
                    "24600-CE", new BigDecimal("120"),
                    "24800-CE", new BigDecimal("60"),
                    "24400-PE", new BigDecimal("5"),
                    "24200-PE", new BigDecimal("2"));

            BigDecimal pnl = invoke("computeCombinedPnl",
                    new Class[]{List.class, Map.class, Map.class},
                    legs, entryPrices, exitPrices);

            assertThat(pnl).isEqualByComparingTo("-150");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Bull Call Spread Entry and SL Exit
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Bull Call Spread SL Exit")
    class BullCallSpreadSlExit {

        @Test
        @DisplayName("debit spread SL triggers when loss exceeds SL percent")
        void stopLossTriggersForDebitSpread() throws Exception {
            // Bull call spread: BUY 24500 CE @ 120, SELL 24700 CE @ 85
            // Net debit = 120 - 85 = 35 per unit
            // SL = 50% → triggers when loss > 50% of entry net
            // Current: BUY leg worth 80, SELL leg worth 90
            // Net current = 80 - 90 = -10
            // Change = (35 - (-10)) / 35 × 100 = 128.6% → SL hit

            BigDecimal entryNet = new BigDecimal("35");
            BigDecimal currentNet = new BigDecimal("-10");
            BigDecimal slPercent = new BigDecimal("50");

            boolean hit = invoke("isStopLossHit",
                    new Class[]{BigDecimal.class, BigDecimal.class, BigDecimal.class, boolean.class},
                    entryNet, currentNet, slPercent, false);

            assertThat(hit).isTrue();
        }

        @Test
        @DisplayName("debit spread SL does NOT trigger when loss is within threshold")
        void stopLossDoesNotTriggerWithinThreshold() throws Exception {
            // Net debit = 35, current net = 25
            // Change = (35 - 25) / 35 × 100 = 28.6% → below 50% SL

            BigDecimal entryNet = new BigDecimal("35");
            BigDecimal currentNet = new BigDecimal("25");
            BigDecimal slPercent = new BigDecimal("50");

            boolean hit = invoke("isStopLossHit",
                    new Class[]{BigDecimal.class, BigDecimal.class, BigDecimal.class, boolean.class},
                    entryNet, currentNet, slPercent, false);

            assertThat(hit).isFalse();
        }

        @Test
        @DisplayName("credit spread SL triggers when cost to close exceeds threshold")
        void stopLossTriggersForCreditSpread() throws Exception {
            // Short strangle: entry net = -100 (received credit)
            // Current net = -180 (cost to close increased)
            // Change = |(-180) - (-100)| / |-100| × 100 = 80% → SL at 50% hit

            BigDecimal entryNet = new BigDecimal("-100");
            BigDecimal currentNet = new BigDecimal("-180");
            BigDecimal slPercent = new BigDecimal("50");

            boolean hit = invoke("isStopLossHit",
                    new Class[]{BigDecimal.class, BigDecimal.class, BigDecimal.class, boolean.class},
                    entryNet, currentNet, slPercent, true);

            assertThat(hit).isTrue();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Expiry-Day Forced Exit at 2 PM and 3 PM Danger Zone
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Expiry-Day Exits")
    class ExpiryDayExits {

        private TradingProperties testProperties() {
            return new TradingProperties(
                    null, false, null, null, null, null, null,
                    new TradingProperties.Exit(
                            BigDecimal.TEN, BigDecimal.valueOf(20), BigDecimal.valueOf(12),
                            BigDecimal.valueOf(6),
                            LocalTime.of(15, 15),  // forced exit at 3:15 PM
                            false, 0),
                    null, null, null, null, null);
        }

        private StrategyConfig ironCondorConfig() {
            StrategyConfig config = new StrategyConfig(StrategyType.IRON_CONDOR);
            config.setStopLossPercent(BigDecimal.valueOf(100));
            config.setTargetPercent(BigDecimal.valueOf(50));
            config.setTrailingStopActivationPercent(BigDecimal.valueOf(10));
            config.setTrailingGapPercent(BigDecimal.valueOf(5));
            config.setMaxHoldMinutes(0); // disabled
            return config;
        }

        @Test
        @DisplayName("expiry day 2 PM: triggers afternoon exit")
        void expiryAfternoonExitAt2pm() throws Exception {
            // On expiry day, after 2 PM → "Expiry afternoon exit"
            // Set up position with no SL/target hit, market time = 14:05

            List<SpreadLeg> legs = List.of(
                    leg(24600, OptionType.CE, OrderSide.SELL, 50),
                    leg(24800, OptionType.CE, OrderSide.BUY, 50));

            Map<String, BigDecimal> entryPrices = Map.of(
                    "24600-CE", new BigDecimal("55"),
                    "24800-CE", new BigDecimal("25"));

            // Current prices close to entry → no SL/target hit
            Map<String, BigDecimal> currentPrices = Map.of(
                    "24600-CE", new BigDecimal("50"),
                    "24800-CE", new BigDecimal("22"));

            Object position = openPosition("T-EXP-001", StrategyType.IRON_CONDOR, legs,
                    entryPrices, Instant.parse("2026-01-27T04:00:00Z"),
                    new HashMap<>(currentPrices), new BigDecimal("-1500"), BigDecimal.ZERO);

            String exitReason = invoke("evaluateExitConditions",
                    new Class[]{
                            Class.forName("com.algo.trade.backtest.SpreadBacktestEngine$OpenSpreadPosition"),
                            Map.class, StrategyConfig.class, TradingProperties.class,
                            LocalDate.class, boolean.class, long.class, LocalTime.class,
                            ExecutionFlowTracker.class},
                    position, currentPrices, ironCondorConfig(), testProperties(),
                    LocalDate.of(2026, 1, 27), true, 0L, LocalTime.of(14, 5), tracker);

            assertThat(exitReason).isEqualTo("Expiry afternoon exit");
            assertThat(tracker.getHitCount("exit.expiry-afternoon")).isGreaterThan(0);
        }

        @Test
        @DisplayName("non-expiry day at 2 PM: no expiry exit triggered")
        void nonExpiryDayNoAfternoonExit() throws Exception {
            List<SpreadLeg> legs = List.of(
                    leg(24600, OptionType.CE, OrderSide.SELL, 50),
                    leg(24800, OptionType.CE, OrderSide.BUY, 50));

            Map<String, BigDecimal> entryPrices = Map.of(
                    "24600-CE", new BigDecimal("55"),
                    "24800-CE", new BigDecimal("25"));

            Map<String, BigDecimal> currentPrices = Map.of(
                    "24600-CE", new BigDecimal("50"),
                    "24800-CE", new BigDecimal("22"));

            Object position = openPosition("T-EXP-002", StrategyType.IRON_CONDOR, legs,
                    entryPrices, Instant.parse("2026-01-26T04:00:00Z"),
                    new HashMap<>(currentPrices), new BigDecimal("-1500"), BigDecimal.ZERO);

            // Non-expiry day, market time 14:05, forced exit at 15:15 → no exit
            String exitReason = invoke("evaluateExitConditions",
                    new Class[]{
                            Class.forName("com.algo.trade.backtest.SpreadBacktestEngine$OpenSpreadPosition"),
                            Map.class, StrategyConfig.class, TradingProperties.class,
                            LocalDate.class, boolean.class, long.class, LocalTime.class,
                            ExecutionFlowTracker.class},
                    position, currentPrices, ironCondorConfig(), testProperties(),
                    LocalDate.of(2026, 1, 26), false, 3L, LocalTime.of(14, 5), tracker);

            assertThat(exitReason).isNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. DTE SL Scaling
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("DTE SL Scaling")
    class DteSlScaling {

        @Test
        @DisplayName("0 DTE: SL multiplied by 0.7")
        void zeroDteSLScaling() throws Exception {
            BigDecimal baseSL = new BigDecimal("100");

            BigDecimal scaled = invoke("scaleSLByDTE",
                    new Class[]{BigDecimal.class, long.class, boolean.class, ExecutionFlowTracker.class},
                    baseSL, 0L, true, tracker);

            assertThat(scaled).isEqualByComparingTo("70");
            assertThat(tracker.getHitCount("exit.dte-sl-scaling")).isGreaterThan(0);
        }

        @Test
        @DisplayName("1 DTE: SL multiplied by 0.7")
        void oneDteSLScaling() throws Exception {
            BigDecimal baseSL = new BigDecimal("100");

            BigDecimal scaled = invoke("scaleSLByDTE",
                    new Class[]{BigDecimal.class, long.class, boolean.class, ExecutionFlowTracker.class},
                    baseSL, 1L, false, tracker);

            assertThat(scaled).isEqualByComparingTo("70");
        }

        @Test
        @DisplayName("2 DTE: SL multiplied by 0.85")
        void twoDteSLScaling() throws Exception {
            BigDecimal baseSL = new BigDecimal("100");

            BigDecimal scaled = invoke("scaleSLByDTE",
                    new Class[]{BigDecimal.class, long.class, boolean.class, ExecutionFlowTracker.class},
                    baseSL, 2L, false, tracker);

            assertThat(scaled).isEqualByComparingTo("85");
        }

        @Test
        @DisplayName("3+ DTE: SL unchanged (1.0× multiplier)")
        void threePlusDteNoScaling() throws Exception {
            BigDecimal baseSL = new BigDecimal("100");

            BigDecimal scaled = invoke("scaleSLByDTE",
                    new Class[]{BigDecimal.class, long.class, boolean.class, ExecutionFlowTracker.class},
                    baseSL, 3L, false, tracker);

            assertThat(scaled).isEqualByComparingTo("100");
            // No DTE scaling branch hit for 3+ DTE
            assertThat(tracker.getHitCount("exit.dte-sl-scaling")).isZero();
        }

        @Test
        @DisplayName("5 DTE: SL unchanged")
        void fiveDteNoScaling() throws Exception {
            BigDecimal baseSL = new BigDecimal("50");

            BigDecimal scaled = invoke("scaleSLByDTE",
                    new Class[]{BigDecimal.class, long.class, boolean.class, ExecutionFlowTracker.class},
                    baseSL, 5L, false, tracker);

            assertThat(scaled).isEqualByComparingTo("50");
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 5. Trailing Stop Activation and Tightening
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Trailing Stop")
    class TrailingStop {

        @Test
        @DisplayName("trailing stop activates when profit exceeds threshold, triggers on drop")
        void trailingStopActivatesAndTriggers() throws Exception {
            // Entry net = 100 (debit spread)
            // Activation = 10% of 100 = 10
            // Best PnL = 15 (above activation)
            // Gap = 5% of 15 = 0.75
            // Current PnL = 14 → 15 - 14 = 1 > 0.75 → trailing stop hit

            BigDecimal entryNet = new BigDecimal("100");
            BigDecimal bestPnl = new BigDecimal("15");
            BigDecimal currentPnl = new BigDecimal("14");
            BigDecimal activationPercent = new BigDecimal("10");
            BigDecimal gapPercent = new BigDecimal("5");

            boolean hit = invoke("isTrailingStopHit",
                    new Class[]{BigDecimal.class, BigDecimal.class, BigDecimal.class,
                            BigDecimal.class, BigDecimal.class},
                    entryNet, bestPnl, currentPnl, activationPercent, gapPercent);

            assertThat(hit).isTrue();
        }

        @Test
        @DisplayName("trailing stop does NOT activate when profit below threshold")
        void trailingStopNotActivatedBelowThreshold() throws Exception {
            // Entry net = 100, activation = 10% = 10
            // Best PnL = 8 (below activation threshold of 10)

            BigDecimal entryNet = new BigDecimal("100");
            BigDecimal bestPnl = new BigDecimal("8");
            BigDecimal currentPnl = new BigDecimal("5");
            BigDecimal activationPercent = new BigDecimal("10");
            BigDecimal gapPercent = new BigDecimal("5");

            boolean hit = invoke("isTrailingStopHit",
                    new Class[]{BigDecimal.class, BigDecimal.class, BigDecimal.class,
                            BigDecimal.class, BigDecimal.class},
                    entryNet, bestPnl, currentPnl, activationPercent, gapPercent);

            assertThat(hit).isFalse();
        }

        @Test
        @DisplayName("trailing stop activated but profit drop within gap — no trigger")
        void trailingStopActivatedButWithinGap() throws Exception {
            // Entry net = 100, activation = 10% = 10
            // Best PnL = 20 (above activation)
            // Gap = 5% of 20 = 1.0
            // Current PnL = 19.5 → drop = 0.5 < 1.0 → no trigger

            BigDecimal entryNet = new BigDecimal("100");
            BigDecimal bestPnl = new BigDecimal("20");
            BigDecimal currentPnl = new BigDecimal("19.5");
            BigDecimal activationPercent = new BigDecimal("10");
            BigDecimal gapPercent = new BigDecimal("5");

            boolean hit = invoke("isTrailingStopHit",
                    new Class[]{BigDecimal.class, BigDecimal.class, BigDecimal.class,
                            BigDecimal.class, BigDecimal.class},
                    entryNet, bestPnl, currentPnl, activationPercent, gapPercent);

            assertThat(hit).isFalse();
        }

        @Test
        @DisplayName("trailing stop not triggered when bestPnl is zero or negative")
        void trailingStopNotTriggeredWithNoPnl() throws Exception {
            BigDecimal entryNet = new BigDecimal("100");
            BigDecimal bestPnl = BigDecimal.ZERO;
            BigDecimal currentPnl = new BigDecimal("-5");
            BigDecimal activationPercent = new BigDecimal("10");
            BigDecimal gapPercent = new BigDecimal("5");

            boolean hit = invoke("isTrailingStopHit",
                    new Class[]{BigDecimal.class, BigDecimal.class, BigDecimal.class,
                            BigDecimal.class, BigDecimal.class},
                    entryNet, bestPnl, currentPnl, activationPercent, gapPercent);

            assertThat(hit).isFalse();
        }
    }
}
