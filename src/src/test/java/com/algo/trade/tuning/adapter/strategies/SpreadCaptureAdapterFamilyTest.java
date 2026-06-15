package com.algo.trade.tuning.adapter.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.adapter.CadenceHint;
import com.algo.trade.tuning.adapter.TuningPipelineCapture;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Family-level checks for the 11 spread {@link AbstractSpreadCaptureAdapter}
 * subclasses not covered by their own dedicated test class.
 *
 * <p>Verifies that each adapter:
 * <ol>
 *   <li>Declares the expected {@link StrategyType}.</li>
 *   <li>Reports {@link CadenceHint#LOW} cadence (inherited from
 *       {@link AbstractSpreadCaptureAdapter}).</li>
 *   <li>Returns the documented bucket-dimension count.</li>
 *   <li>Produces a non-null {@link SignalEvent} for a FIRED context.</li>
 *   <li>Produces an {@link EvaluationEvent} with {@link EvaluationOutcome#BLOCKED}
 *       outcome and a normalised blocker label for a blocked context.</li>
 * </ol>
 *
 * <p>The single-adapter {@code BullCallSpreadCaptureAdapterTest} keeps richer
 * coverage for one spread; this class fills the family gap cheaply.
 */
class SpreadCaptureAdapterFamilyTest {

    static Stream<Arguments> adapters() {
        return Stream.of(
                args("BearPutSpread",      BearPutSpreadCaptureAdapter::new,      StrategyType.BEAR_PUT_SPREAD,      2),
                args("IronCondor",         IronCondorCaptureAdapter::new,         StrategyType.IRON_CONDOR,          3),
                args("LongStraddle",       LongStraddleCaptureAdapter::new,       StrategyType.LONG_STRADDLE,        2),
                args("ShortStraddle",      ShortStraddleCaptureAdapter::new,      StrategyType.SHORT_STRADDLE,       2),
                args("LongStrangle",       LongStrangleCaptureAdapter::new,       StrategyType.LONG_STRANGLE,        2),
                args("ShortStrangle",      ShortStrangleCaptureAdapter::new,      StrategyType.SHORT_STRANGLE,       3),
                args("CalendarSpread",     CalendarSpreadCaptureAdapter::new,     StrategyType.CALENDAR_SPREAD,      2),
                args("DiagonalSpread",     DiagonalSpreadCaptureAdapter::new,     StrategyType.DIAGONAL_SPREAD,      2),
                args("Butterfly",          ButterflyCaptureAdapter::new,          StrategyType.BUTTERFLY,            2),
                args("JadeLizard",         JadeLizardCaptureAdapter::new,         StrategyType.JADE_LIZARD,          3),
                args("SyntheticFutures",   SyntheticFuturesCaptureAdapter::new,   StrategyType.SYNTHETIC_FUTURES,    2)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("declares strategy type and LOW cadence")
    void declaresStrategyAndCadence(String label,
                                    Supplier<TuningPipelineCapture> factory,
                                    StrategyType expected,
                                    int dimCount) {
        TuningPipelineCapture adapter = factory.get();
        assertThat(adapter.strategy()).isEqualTo(expected);
        assertThat(adapter.cadence()).isEqualTo(CadenceHint.LOW);
        assertThat(adapter.defaultEpisodeWindowSec()).isEqualTo(60);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("reports the expected number of bucket dimensions")
    void declaresBucketDimensionCount(String label,
                                      Supplier<TuningPipelineCapture> factory,
                                      StrategyType expected,
                                      int dimCount) {
        TuningPipelineCapture adapter = factory.get();
        assertThat(adapter.bucketDimensions()).hasSize(dimCount);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("builds a SignalEvent for a FIRED context")
    void buildSignalEvent_emitsEvent(String label,
                                     Supplier<TuningPipelineCapture> factory,
                                     StrategyType expected,
                                     int dimCount) {
        TuningPipelineCapture adapter = factory.get();
        SignalRecordContext ctx = ctx(expected, SignalType.BUY_CE,
                List.of(label + " entry signal"), null);
        SignalEvent event = adapter.buildSignalEvent(ctx, label + "-grp-1");
        assertThat(event).isNotNull();
        assertThat(event.strategy()).isEqualTo(expected);
        assertThat(event.correlationKey()).isEqualTo(label + "-grp-1");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    @DisplayName("buildImmediateEvaluationEvent maps gate failure to BLOCKED")
    void buildImmediateEvaluationEvent_blocksOnGate(String label,
                                                    Supplier<TuningPipelineCapture> factory,
                                                    StrategyType expected,
                                                    int dimCount) {
        TuningPipelineCapture adapter = factory.get();
        SignalRecordContext ctx = ctx(expected, SignalType.NO_TRADE,
                List.of("spreadEntryBlocked:marketGuard(vix)"), "marketGuard(vix)");
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());
        assertThat(event.strategy()).isEqualTo(expected);
        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("marketGuard");
    }

    private static Arguments args(String label,
                                  Supplier<TuningPipelineCapture> factory,
                                  StrategyType expected,
                                  int dimCount) {
        return Arguments.of(label, factory, expected, dimCount);
    }

    private static SignalRecordContext ctx(StrategyType strategyType,
                                           SignalType signalType,
                                           List<String> reasons,
                                           String blocker) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T10:00:00Z"),
                UnderlyingSymbol.NIFTY,
                signalType,
                BigDecimal.valueOf(23500),
                signalType == SignalType.BUY_CE
                        ? Optional.of(BigDecimal.valueOf(100))
                        : Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(strategyType.name() + "-grp-1"),
                Optional.empty(),
                Optional.of(OptionType.CE),
                false,
                Optional.empty(),
                false,
                BigDecimal.valueOf(signalType == SignalType.NO_TRADE ? 0 : 60),
                reasons);
        var builder = SignalRecordContext.builder()
                .strategyType(strategyType.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .ivRank(45.0);
        if (blocker != null) {
            builder.firstFailedFilter(blocker);
        }
        return builder.build();
    }
}
