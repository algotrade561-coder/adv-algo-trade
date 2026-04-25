package com.algo.trade.backtest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.config.GlobalConfig;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
/**
 * Unit tests for {@link VerifyAllService} — verifies orchestration across all 16
 * strategy types, correct engine delegation, error handling, and config loading.
 *
 * Validates: Requirements 2.1, 2.2
 */
class VerifyAllServiceTest {

    private TradingProperties tradingProperties;
    private GlobalConfigService globalConfigService;
    private StrategyConfigService strategyConfigService;
    private BacktestEngine backtestEngine;
    private SpreadBacktestEngine spreadBacktestEngine;
    private VerifyAllService service;

    /** The 3 single-leg strategies routed to BacktestEngine. */
    private static final Set<StrategyType> SINGLE_LEG = Set.of(
            StrategyType.DIRECTIONAL_BUY,
            StrategyType.SCALPING,
            StrategyType.VOLATILITY_BREAKOUT);

    @BeforeEach
    void setUp() {
        tradingProperties = testProperties();
        globalConfigService = mock(GlobalConfigService.class);
        when(globalConfigService.getCached()).thenReturn(new GlobalConfig(tradingProperties));
        strategyConfigService = mock(StrategyConfigService.class);
        backtestEngine = mock(BacktestEngine.class);
        spreadBacktestEngine = mock(SpreadBacktestEngine.class);

        // Return a config for every strategy type from getAll()
        List<StrategyConfig> allConfigs = Arrays.stream(StrategyType.values())
                .map(StrategyConfig::new)
                .toList();
        when(strategyConfigService.getAll()).thenReturn(allConfigs);

        // Default: BacktestEngine returns an empty result
        when(backtestEngine.run(
                any(UnderlyingSymbol.class), any(Timeframe.class), any(OptionType.class),
                any(LocalDate.class), any(LocalDate.class),
                any(BacktestEngine.RunOptions.class)))
                .thenReturn(emptyBacktestRunResult());

        // Default: SpreadBacktestEngine returns an empty result
        when(spreadBacktestEngine.run(
                any(StrategyType.class), any(StrategyConfig.class),
                any(UnderlyingSymbol.class), any(LocalDate.class), any(LocalDate.class),
                any(TradingProperties.class), any(ExecutionFlowTracker.class)))
                .thenReturn(emptySpreadResult());

        service = new VerifyAllService(
                tradingProperties, globalConfigService, strategyConfigService,
                backtestEngine, spreadBacktestEngine, null);
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 1. All 16 strategy types are iterated
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("All 16 strategies iterated")
    class AllStrategiesIterated {

        @Test
        @DisplayName("verifyAll produces exactly 16 StrategyVerificationResult entries")
        void verifyAllProduces16Results() {
            VerifyAllRequest request = new VerifyAllRequest(
                    LocalDate.of(2026, 1, 15), LocalDate.of(2026, 4, 15), UnderlyingSymbol.NIFTY);

            VerifyAllResult result = service.verifyAll(request);

            assertThat(result.strategyResults()).hasSize(16);
        }

        @Test
        @DisplayName("every StrategyType enum value appears in the results")
        void everyStrategyTypePresent() {
            VerifyAllResult result = service.verifyAll(VerifyAllRequest.defaults());

            Set<StrategyType> resultTypes = new java.util.HashSet<>();
            for (StrategyVerificationResult r : result.strategyResults()) {
                resultTypes.add(r.strategyType());
            }
            assertThat(resultTypes).containsExactlyInAnyOrderElementsOf(
                    Arrays.asList(StrategyType.values()));
        }

        @Test
        @DisplayName("result contains verifyId, timestamps, and underlying")
        void resultMetadataPopulated() {
            VerifyAllResult result = service.verifyAll(VerifyAllRequest.defaults());

            assertThat(result.verifyId()).startsWith("VERIFY-ALL-");
            assertThat(result.startedAt()).isNotNull();
            assertThat(result.completedAt()).isNotNull();
            assertThat(result.completedAt()).isAfterOrEqualTo(result.startedAt());
            assertThat(result.underlying()).isEqualTo(UnderlyingSymbol.NIFTY);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 2. Correct engine delegation
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Correct engine delegation")
    class EngineDelegation {

        @Test
        @DisplayName("3 single-leg strategies delegate to BacktestEngine")
        void singleLegStrategiesUseBacktestEngine() {
            service.verifyAll(VerifyAllRequest.defaults());

            // BacktestEngine.run() is called for CE and PE per single-leg strategy → 3 × 2 = 6 calls
            verify(backtestEngine, times(6)).run(
                    any(UnderlyingSymbol.class), any(Timeframe.class), any(OptionType.class),
                    any(LocalDate.class), any(LocalDate.class),
                    any(BacktestEngine.RunOptions.class));
        }

        @Test
        @DisplayName("13 spread strategies delegate to SpreadBacktestEngine")
        void spreadStrategiesUseSpreadBacktestEngine() {
            service.verifyAll(VerifyAllRequest.defaults());

            verify(spreadBacktestEngine, times(13)).run(
                    any(StrategyType.class), any(StrategyConfig.class),
                    any(UnderlyingSymbol.class), any(LocalDate.class), any(LocalDate.class),
                    any(TradingProperties.class), any(ExecutionFlowTracker.class));
        }

        @Test
        @DisplayName("DIRECTIONAL_BUY, SCALPING, VOLATILITY_BREAKOUT never go to SpreadBacktestEngine")
        void singleLegNeverDelegatedToSpread() {
            service.verifyAll(VerifyAllRequest.defaults());

            for (StrategyType singleLeg : SINGLE_LEG) {
                verify(spreadBacktestEngine, never()).run(
                        eq(singleLeg), any(StrategyConfig.class),
                        any(UnderlyingSymbol.class), any(LocalDate.class), any(LocalDate.class),
                        any(TradingProperties.class), any(ExecutionFlowTracker.class));
            }
        }

        @Test
        @DisplayName("spread strategies never go to BacktestEngine")
        void spreadNeverDelegatedToSingleLeg() {
            // BacktestEngine is only called for single-leg strategies.
            // We verify the total call count is exactly 6 (3 strategies × 2 option types).
            service.verifyAll(VerifyAllRequest.defaults());

            verify(backtestEngine, times(6)).run(
                    any(UnderlyingSymbol.class), any(Timeframe.class), any(OptionType.class),
                    any(LocalDate.class), any(LocalDate.class),
                    any(BacktestEngine.RunOptions.class));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 3. Error handling — one strategy throws, others still run
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("one spread strategy throws exception, result has status=error for that strategy")
        void oneSpreadStrategyThrows_othersStillRun() {
            // Make IRON_CONDOR throw
            when(spreadBacktestEngine.run(
                    eq(StrategyType.IRON_CONDOR), any(StrategyConfig.class),
                    any(UnderlyingSymbol.class), any(LocalDate.class), any(LocalDate.class),
                    any(TradingProperties.class), any(ExecutionFlowTracker.class)))
                    .thenThrow(new RuntimeException("Simulated IC failure"));

            VerifyAllResult result = service.verifyAll(VerifyAllRequest.defaults());

            // Still 16 results
            assertThat(result.strategyResults()).hasSize(16);

            // IRON_CONDOR has error status
            StrategyVerificationResult icResult = result.strategyResults().stream()
                    .filter(r -> r.strategyType() == StrategyType.IRON_CONDOR)
                    .findFirst().orElseThrow();
            assertThat(icResult.status()).isEqualTo("error");
            assertThat(icResult.errorMessage()).contains("Simulated IC failure");
            assertThat(icResult.metrics()).isNull();

            // Other strategies are not error (they return no-data since mocks return empty)
            long nonErrorCount = result.strategyResults().stream()
                    .filter(r -> r.strategyType() != StrategyType.IRON_CONDOR)
                    .filter(r -> !r.status().equals("error"))
                    .count();
            assertThat(nonErrorCount).isEqualTo(15);
        }

        @Test
        @DisplayName("one single-leg strategy throws exception, others still run")
        void oneSingleLegStrategyThrows_othersStillRun() {
            // Make BacktestEngine throw for ALL calls (both CE and PE) to simulate
            // DIRECTIONAL_BUY failing. Since the service catches per-optionType exceptions
            // internally, we need both to throw to get no-data or error.
            // Actually, looking at the code, the per-strategy try/catch is around the
            // entire runSingleLegStrategy call. Let's make the engine throw for all calls.
            when(backtestEngine.run(
                    any(UnderlyingSymbol.class), any(Timeframe.class), any(OptionType.class),
                    any(LocalDate.class), any(LocalDate.class),
                    any(BacktestEngine.RunOptions.class)))
                    .thenThrow(new RuntimeException("Simulated BT failure"));

            VerifyAllResult result = service.verifyAll(VerifyAllRequest.defaults());

            assertThat(result.strategyResults()).hasSize(16);

            // All 3 single-leg strategies should have no-data status (inner catch logs warning,
            // but both CE and PE fail → empty trades → noDataResult)
            for (StrategyType singleLeg : SINGLE_LEG) {
                StrategyVerificationResult r = result.strategyResults().stream()
                        .filter(sr -> sr.strategyType() == singleLeg)
                        .findFirst().orElseThrow();
                assertThat(r.status()).isIn("no-data", "error");
            }

            // Spread strategies still run fine
            long spreadOkCount = result.strategyResults().stream()
                    .filter(r -> !SINGLE_LEG.contains(r.strategyType()))
                    .filter(r -> !r.status().equals("error"))
                    .count();
            assertThat(spreadOkCount).isEqualTo(13);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // 4. StrategyConfig auto-creation for missing configs
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("StrategyConfig loading")
    class ConfigLoading {

        @Test
        @DisplayName("getAll() is called to load configs")
        void getAllCalledForConfigs() {
            service.verifyAll(VerifyAllRequest.defaults());

            // getAll() is called once per strategy type (16 times) since loadOrCreateConfig
            // calls it for each strategy
            verify(strategyConfigService, times(16)).getAll();
        }

        @Test
        @DisplayName("configSnapshot contains all strategy configs")
        void configSnapshotContainsAllConfigs() {
            VerifyAllResult result = service.verifyAll(VerifyAllRequest.defaults());

            assertThat(result.configSnapshot()).isNotNull();
            assertThat(result.configSnapshot().strategyConfigs()).hasSize(16);
            for (StrategyType type : StrategyType.values()) {
                assertThat(result.configSnapshot().strategyConfigs()).containsKey(type);
            }
        }

        @Test
        @DisplayName("configSnapshot captures tradingProperties")
        void configSnapshotCapturesTradingProperties() {
            VerifyAllResult result = service.verifyAll(VerifyAllRequest.defaults());

            assertThat(result.configSnapshot().tradingProperties()).isSameAs(tradingProperties);
            assertThat(result.configSnapshot().globalConfig()).isNotNull();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static TradingProperties testProperties() {
        return new TradingProperties(
                null, false, null, null, null, null, null,
                TradingProperties.Exit.defaults(),
                null, null, null, null,
                TradingProperties.Backtest.defaults());
    }

    private static BacktestRunResult emptyBacktestRunResult() {
        return new BacktestRunResult(
                "BT-EMPTY", Instant.now(),
                new BacktestMetrics(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of()),
                List.of(), List.of(),
                Path.of("test-output"), Path.of("trades.csv"),
                Path.of("metrics.csv"), Path.of("equity.csv"), Path.of("report.html"));
    }

    private static SpreadBacktestResult emptySpreadResult() {
        return new SpreadBacktestResult(
                List.of(),
                new BacktestMetrics(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of()),
                0, 0, Map.of(), Map.of());
    }
}
