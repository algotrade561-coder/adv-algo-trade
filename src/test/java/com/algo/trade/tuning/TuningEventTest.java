package com.algo.trade.tuning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 1, Commit 1 — sealed event model + per-type validation. Verifies every
 * subtype constructs cleanly, surfaces the correct {@link TuningEventType}, enforces
 * its invariants, and routes through the sealed-interface exhaustive switch the
 * recorder will use in Commit 3.
 */
class TuningEventTest {

    private static final Instant T = Instant.parse("2026-06-01T09:30:00Z");

    @Test
    void evaluationEvent_carriesTypeAndPreservesFields() {
        EvaluationEvent e = new EvaluationEvent(
                T, T.plusMillis(2), StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "episode-42",
                EvaluationOutcome.BLOCKED, "tod_gate:MIDDAY_DISCIPLINE",
                7,
                Map.of("biasScore", 38, "pcr", 1.21)
        );

        assertThat(e.type()).isEqualTo(TuningEventType.EVALUATION);
        assertThat(e.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(e.episodeTickCount()).isEqualTo(7);
        assertThat(e.attributes()).containsEntry("biasScore", 38);
        assertThat(routeToFileName(e)).isEqualTo("evaluation");
    }

    @Test
    void evaluationEvent_rejectsBlockerWithNonBlockedOutcome() {
        assertThatThrownBy(() -> new EvaluationEvent(
                T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                EvaluationOutcome.FIRED, "leaked_blocker", 1, Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("blocker must be null");
    }

    @Test
    void evaluationEvent_rejectsZeroTickCount() {
        assertThatThrownBy(() -> new EvaluationEvent(
                T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                EvaluationOutcome.FIRED, null, 0, Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("episodeTickCount must be >= 1");
    }

    @Test
    void signalEvent_carriesTypeAndUniversalFields() {
        SignalEvent e = new SignalEvent(
                T, T, StrategyType.OI_SHIFT_TRAP, IndexType.SENSEX,
                "decision-key-1",
                "BFO:SENSEX25JUN74900PE", 74_900, OptionType.PE,
                new BigDecimal("125.50"),
                Map.of("score", 72, "imbalance", 2.8)
        );

        assertThat(e.type()).isEqualTo(TuningEventType.SIGNAL);
        assertThat(e.optionType()).isEqualTo(OptionType.PE);
        assertThat(e.entryPremium()).isEqualByComparingTo("125.50");
        assertThat(routeToFileName(e)).isEqualTo("signal");
    }

    @Test
    void executionEvent_acceptsPartialFillsAndRejectsOverfill() {
        ExecutionEvent partial = new ExecutionEvent(
                T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                "ENTRY-uuid", "PARTIAL_FILL",
                65, 30,
                new BigDecimal("128.00"), 2.0, null,
                Map.of()
        );
        assertThat(partial.filledQty()).isEqualTo(30);
        assertThat(partial.slippagePct()).isEqualTo(2.0);

        assertThatThrownBy(() -> new ExecutionEvent(
                T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                "ENTRY-uuid", "OVERFILL",
                65, 100, null, null, null, Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("filledQty must be in [0, requestedQty]");
    }

    @Test
    void exitEvent_enforcesMaeMfeSignConventions() {
        ExitEvent ok = new ExitEvent(
                T, T, StrategyType.OI_SHIFT_TRAP, IndexType.NIFTY, "k",
                "TRD-abc", "TARGET",
                new BigDecimal("100.00"), new BigDecimal("120.00"),
                20.0, 600L,
                -4.8, 22.4, 300L, 1080L,
                false,
                Map.of()
        );
        assertThat(ok.maePct()).isLessThanOrEqualTo(0.0);
        assertThat(ok.mfePct()).isGreaterThanOrEqualTo(0.0);

        assertThatThrownBy(() -> new ExitEvent(
                T, T, StrategyType.OI_SHIFT_TRAP, IndexType.NIFTY, "k",
                "TRD-abc", "TARGET",
                new BigDecimal("100.00"), new BigDecimal("120.00"),
                20.0, 600L,
                4.8, 22.4, 300L, 1080L,    // maePct > 0 — wrong sign
                false, Map.of()
        )).isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("maePct must be <= 0");
    }

    @Test
    void forwardCheckpointEvent_completenessReportsPartialAndFull() {
        ForwardCheckpointEvent partial = new ForwardCheckpointEvent(
                T, T.plusSeconds(960), StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                23_500.0, 23_510.0, 23_520.0,
                null, null,                                  // 15m/30m not yet backfilled
                null, null,
                Map.of()
        );
        assertThat(partial.isComplete()).isFalse();

        ForwardCheckpointEvent full = new ForwardCheckpointEvent(
                T, T.plusSeconds(1860), StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                23_500.0, 23_510.0, 23_520.0, 23_540.0, 23_580.0,
                0.34, -0.12,
                Map.of()
        );
        assertThat(full.isComplete()).isTrue();
    }

    @Test
    void shadowGateEvent_supportsBinaryAndScoredGates() {
        ShadowGateEvent ruleGate = new ShadowGateEvent(
                T, T, StrategyType.OI_SHIFT_TRAP, IndexType.NIFTY, "k",
                "confirm_oiStillBuilding", true, null, Map.of()
        );
        assertThat(ruleGate.bandValue()).isNull();

        ShadowGateEvent modelGate = new ShadowGateEvent(
                T, T, StrategyType.OI_SHIFT_TRAP, IndexType.NIFTY, "k",
                "model:win_proba:v1", false, 0.42, Map.of()
        );
        assertThat(modelGate.bandValue()).isEqualTo(0.42);
        assertThat(modelGate.passed()).isFalse();
    }

    @Test
    void sealedDispatch_isExhaustive() {
        TuningEvent[] all = {
                new EvaluationEvent(T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                        EvaluationOutcome.FIRED, null, 1, Map.of()),
                new SignalEvent(T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                        "i", 23500, OptionType.CE, BigDecimal.ONE, Map.of()),
                new ExecutionEvent(T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                        "o", "FILLED", 1, 1, BigDecimal.ONE, 0.0, null, Map.of()),
                new ExitEvent(T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                        "t", "SL", BigDecimal.ONE, BigDecimal.ONE, -10.0, 1L, -10.0, 0.0, 1L, 0L, false, Map.of()),
                new ForwardCheckpointEvent(T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                        null, null, null, null, null, null, null, Map.of()),
                new ShadowGateEvent(T, T, StrategyType.OI_MOMENTUM, IndexType.NIFTY, "k",
                        "g", true, null, Map.of()),
        };
        for (TuningEvent e : all) {
            // Compile-time exhaustiveness check — if a new permits is added without a case here,
            // this switch will fail to compile, forcing the recorder to be updated too.
            String tag = switch (e) {
                case EvaluationEvent x -> "EVALUATION";
                case SignalEvent x -> "SIGNAL";
                case ExecutionEvent x -> "EXECUTION";
                case ExitEvent x -> "EXIT";
                case ForwardCheckpointEvent x -> "FORWARD_CHECKPOINT";
                case ShadowGateEvent x -> "SHADOW_GATE";
            };
            assertThat(tag).isEqualTo(e.type().name());
        }
    }

    /** Mirrors the routing logic the recorder uses to pick which CSV file an event lands in. */
    private static String routeToFileName(TuningEvent e) {
        return e.type().fileBaseName();
    }
}
