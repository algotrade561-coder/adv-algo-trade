package com.algo.trade.tuning.adapter.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.strategy.oishifttrap.OiShiftTrapDiagnostics;
import com.algo.trade.strategy.oishifttrap.ShiftTrapConfirmationEvaluator;
import com.algo.trade.strategy.oishifttrap.ShiftTrapVelocityCalculator;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import com.algo.trade.tuning.ExitEvent;
import com.algo.trade.tuning.ShadowGateEvent;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import com.algo.trade.tuning.infra.MaeMfeTracker;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeEntity;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OiShiftTrapCaptureAdapterTest {

    private final OiShiftTrapCaptureAdapter adapter = new OiShiftTrapCaptureAdapter();

    @Test
    void declaresShiftTrapStrategyWithHighCadence() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.OI_SHIFT_TRAP);
        assertThat(adapter.cadence()).isEqualTo(com.algo.trade.tuning.adapter.CadenceHint.HIGH);
        assertThat(adapter.defaultEpisodeWindowSec()).isEqualTo(60);
        assertThat(adapter.bucketDimensions()).hasSize(4);
        assertThat(adapter.shadowGates()).hasSize(8);
    }

    @Test
    void normalizeBlocker_mapsGateTokens() {
        assertThat(adapter.normalizeBlocker("MIN_OI")).isEqualTo("gate:MIN_OI");
        assertThat(adapter.normalizeBlocker("SCORE")).isEqualTo("gate:SCORE");
    }

    @Test
    void buildSignalEvent_mapsTrapSideAndScore() {
        OiShiftTrapDiagnostics.CandidateSnapshot ce = new OiShiftTrapDiagnostics.CandidateSnapshot(
                BigDecimal.valueOf(23500), 120_000, 80_000, 5_000, 2.1, 0.4, 72, "");
        OiShiftTrapDiagnostics diag = new OiShiftTrapDiagnostics(
                "NIFTY", BigDecimal.valueOf(23510), 1, "NORMAL", 5000,
                "SIGNAL", "", 12, ce, OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                true, "CE", BigDecimal.valueOf(23500), 72);

        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T09:30:00Z"),
                UnderlyingSymbol.NIFTY,
                SignalType.BUY_CE,
                BigDecimal.valueOf(23510),
                Optional.of(BigDecimal.valueOf(100.5)),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of("NFO:NIFTY25JUN23500CE"),
                Optional.of(BigDecimal.valueOf(23500)),
                Optional.of(OptionType.CE),
                false,
                Optional.empty(),
                false,
                List.of("trap=CE"));

        SignalEvent event = adapter.buildSignalEvent(decision, diag, "trap-dk-1", List.of());

        assertThat(event.strategy()).isEqualTo(StrategyType.OI_SHIFT_TRAP);
        assertThat(event.optionType()).isEqualTo(OptionType.CE);
        assertThat(event.attributes().get("score")).isEqualTo(72);
        assertThat(event.attributes().get("trapSide")).isEqualTo("CE");
        assertThat(event.attributes().get("imbalance")).isEqualTo(2.1);
    }

    @Test
    void buildEvaluationEvent_episodeRowCarriesBlockerAndTickCount() {
        OiShiftTrapDiagnostics diag = OiShiftTrapDiagnostics.blocked(
                "NIFTY", "BLOCKED", "gate:SCORE", BigDecimal.valueOf(23500));
        Instant first = Instant.parse("2026-06-01T09:30:00Z");
        Instant last = first.plusSeconds(45);
        var row = new EpisodeAggregator.EpisodeRow<>(
                "NIFTY", "gate:SCORE", first, last, 12, diag);

        EvaluationEvent event = adapter.buildEvaluationEvent(row);

        assertThat(event.strategy()).isEqualTo(StrategyType.OI_SHIFT_TRAP);
        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("gate:SCORE");
        assertThat(event.episodeTickCount()).isEqualTo(12);
        assertThat(event.attributes().get("episodeFirstAt")).isEqualTo(first.toString());
    }

    @Test
    void buildImmediateEvaluationEvent_singleTick() {
        OiShiftTrapDiagnostics diag = OiShiftTrapDiagnostics.blocked(
                "BANKNIFTY", "SCAN_CONTEXT", "buildScanContext:unknown", BigDecimal.TEN);
        Instant at = Instant.parse("2026-06-01T10:00:00Z");

        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(diag, at);

        assertThat(event.episodeTickCount()).isEqualTo(1);
        assertThat(event.blocker()).isEqualTo("buildScanContext:unknown");
    }

    @Test
    void buildExitEvent_includesTrapContextAndMaeMfeSnapshot() {
        Instant entryAt = Instant.now().minusSeconds(900);
        TradeEntity trade = new TradeEntity(
                "TRD-TRAP", "NFO:NIFTY25JUN23500CE", "NIFTY", "CE",
                TradeStatus.OPEN, 65, new BigDecimal("100"), entryAt, "entry");
        java.util.Map<String, Object> trapAttrs = java.util.Map.of(
                "trapSide", "CE", "score", 72, "imbalance", 2.1, "proximityPct", 0.4);
        MaeMfeTracker.EntryContext entryCtx = new MaeMfeTracker.EntryContext(
                "TRD-TRAP", StrategyType.OI_SHIFT_TRAP, IndexType.NIFTY, "trap-dk-1",
                MaeMfeTracker.Direction.LONG, "NFO:NIFTY25JUN23500CE", 23500,
                OptionType.CE, new BigDecimal("100"), 23510.0, entryAt);
        MaeMfeTracker.Snapshot snapshot = new MaeMfeTracker.Snapshot(
                entryCtx, -3.5, entryAt.plusSeconds(300), 23490,
                8.0, entryAt.plusSeconds(600), 23520, 4, new BigDecimal("108"));

        ExitEvent event = adapter.buildExitEvent(
                IndexType.NIFTY, trade, snapshot, "trap-dk-1",
                new BigDecimal("110"), "TARGET", false, trapAttrs);

        assertThat(event.strategy()).isEqualTo(StrategyType.OI_SHIFT_TRAP);
        assertThat(event.correlationKey()).isEqualTo("trap-dk-1");
        assertThat(event.maePct()).isEqualTo(-3.5);
        assertThat(event.mfePct()).isEqualTo(8.0);
        assertThat(event.attributes().get("score")).isEqualTo(72);
        assertThat(event.attributes().get("trapSide")).isEqualTo("CE");
    }

    @Test
    void buildShadowGateEvents_emitsEightGatesMatchingAdapterDeclarations() {
        ShiftTrapConfirmationEvaluator.Confirmations confirmations =
                new ShiftTrapConfirmationEvaluator.Confirmations(
                        true, false, true, false, true, false, true, false, 4);
        ShiftTrapVelocityCalculator.Velocity velocity =
                new ShiftTrapVelocityCalculator.Velocity(0.5, 1.2, -0.3);

        List<ShadowGateEvent> events = adapter.buildShadowGateEvents(
                "trap-dk-1",
                Instant.parse("2026-06-01T09:30:00Z"),
                IndexType.NIFTY,
                confirmations,
                velocity,
                "CE",
                23500);

        assertThat(events).hasSize(8);
        assertThat(events.stream().map(ShadowGateEvent::gateName).toList()).containsExactly(
                "confirm_momentumDecelerating",
                "confirm_spotStalled",
                "confirm_oiStillBuilding",
                "confirm_oppositeOiFlushing",
                "confirm_priceRetraced",
                "confirm_proximityTightening",
                "confirm_volumeSpike",
                "confirm_pcrAligned");
        assertThat(events.stream().filter(ShadowGateEvent::passed).count()).isEqualTo(4);
        ShadowGateEvent momentum = events.get(0);
        assertThat(momentum.passed()).isTrue();
        assertThat(momentum.bandValue()).isEqualTo(-0.3);
        assertThat(momentum.correlationKey()).isEqualTo("trap-dk-1");
        assertThat(momentum.attributes().get("confirmationsPassedCount")).isEqualTo(4);
    }
}
