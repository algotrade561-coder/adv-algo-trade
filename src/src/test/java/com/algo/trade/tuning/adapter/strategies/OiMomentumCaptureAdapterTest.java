package com.algo.trade.tuning.adapter.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.strategy.oimomentum.OiMomentumEntryDiagnostics;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.adapter.BucketDimension;
import com.algo.trade.tuning.adapter.BucketDimension.BandStyle;
import com.algo.trade.tuning.adapter.CadenceHint;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Phase 2, Commit 2 — OI Momentum adapter. Verifies declarative metadata + the
 * attribute-mapping helper produces the expected SignalEvent sidecar.
 */
class OiMomentumCaptureAdapterTest {

    private final OiMomentumCaptureAdapter adapter = new OiMomentumCaptureAdapter();

    @Test
    void declaresOiMomentumWithHighCadence() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.OI_MOMENTUM);
        assertThat(adapter.cadence()).isEqualTo(CadenceHint.HIGH);
        assertThat(adapter.defaultEpisodeWindowSec()).isEqualTo(60);
    }

    @Test
    void bucketDimensionsCoverHotFields() {
        List<BucketDimension> dims = adapter.bucketDimensions();
        assertThat(dims).hasSize(5);
        assertThat(dims).extracting(BucketDimension::name)
                .containsExactly("entryCase", "biasScore", "matrixCase", "momentumType", "timeOfDayMode");
        // biasScore is numeric; the others are categorical.
        BucketDimension biasScore = dims.stream()
                .filter(d -> d.name().equals("biasScore")).findFirst().orElseThrow();
        assertThat(biasScore.style()).isEqualTo(BandStyle.NUMERIC_RANGE);
        assertThat(biasScore.bandFor(38)).isEqualTo("30–39");
        assertThat(biasScore.bandFor(80)).isEqualTo("80+");
    }

    @Test
    void normalizeBlocker_stripsPipeVariants() {
        assertThat(adapter.normalizeBlocker("matrix_skip:CASE5_SKIP|CASE5_PCR_VS_MOMENTUM"))
                .isEqualTo("matrix_skip:CASE5_SKIP");
        assertThat(adapter.normalizeBlocker("low_bias:38<40 [M(+30) BAL=1.20]"))
                .isEqualTo("low_bias:38<40 [M(+30) BAL=1.20]");   // no pipe → unchanged
    }

    @Test
    void normalizeBlocker_stripsTimingDetailsFromKnownPrefixes() {
        assertThat(adapter.normalizeBlocker("sl_cooldown:42s")).isEqualTo("sl_cooldown");
        assertThat(adapter.normalizeBlocker("market_guard")).isEqualTo("market_guard");
        // CASE-prefixed reasons keep their colon — they're already normalized.
        assertThat(adapter.normalizeBlocker("CASE5_PCR_VS_MOMENTUM:detail"))
                .isEqualTo("CASE5_PCR_VS_MOMENTUM:detail");
    }

    @Test
    void normalizeBlocker_handlesNullAndBlank() {
        assertThat(adapter.normalizeBlocker(null)).isEqualTo("unknown");
        assertThat(adapter.normalizeBlocker("")).isEqualTo("unknown");
        assertThat(adapter.normalizeBlocker("   ")).isEqualTo("unknown");
    }

    @Test
    void signalAttributesIncludeHotAndColdFields() {
        OiMomentumEntryDiagnostics diag = sampleDiagnostics();
        Map<String, Object> attrs = adapter.signalAttributes(diag);

        // Hot dimensions present.
        assertThat(attrs).containsKey("entryCase").containsKey("matrixCase")
                .containsKey("momentumType").containsKey("timeOfDayMode");
        assertThat(attrs.get("entryCase")).isEqualTo("CASE2_M+PCR");
        assertThat(attrs.get("matrixCase")).isEqualTo("CASE2");
        assertThat(attrs.get("momentumType")).isEqualTo("30M_HIGH_BREAK");
        assertThat(attrs.get("timeOfDayMode")).isEqualTo("MIDDAY_DISCIPLINE");

        // Cold diagnostics in the sidecar.
        assertThat(attrs.get("pcr")).isEqualTo(1.21);
        assertThat(attrs.get("ceOiChange")).isEqualTo(5_000L);
        assertThat(attrs.get("peOiChange")).isEqualTo(3_000L);
        assertThat(attrs.get("oiAvailable")).isEqualTo(true);
        assertThat(attrs.get("spot")).isEqualTo(23_500.0);
        assertThat(attrs.get("rangePct30m")).isEqualTo(0.42);
    }

    @Test
    void signalAttributesOnNullDiagnostics_returnsEmptyMap() {
        Map<String, Object> attrs = adapter.signalAttributes(null);
        assertThat(attrs).isEmpty();
    }

    @Test
    void signalAttributesSkipsBlankStringFields() {
        OiMomentumEntryDiagnostics diag = new OiMomentumEntryDiagnostics(
                IndexType.NIFTY, "",            // blank entryCase
                1, "30M_HIGH_BREAK", 0.45,
                1, 1, 1.21, 5_000L, 3_000L, true,
                23_500.0, 23_500, 23_540.0, 23_460.0, 0.10,
                "", 14.5, 7, false, false,
                "", false, 0.42, "", 125.0, 130.0,
                "", "", "", 0, 0
        );
        Map<String, Object> attrs = adapter.signalAttributes(diag);
        // Blank entryCase/matrixCase/etc. are skipped via putIfPresent.
        assertThat(attrs).doesNotContainKey("entryCase").doesNotContainKey("matrixCase");
        // Numeric fields are always populated.
        assertThat(attrs).containsKey("pcr");
    }

    @Test
    void buildExitEvent_includesMaeMfeFromSnapshot_andComputesRealizedPnlPct() {
        com.algo.trade.persistence.TradeEntity trade = sampleTrade();

        com.algo.trade.tuning.infra.MaeMfeTracker.EntryContext entry =
                new com.algo.trade.tuning.infra.MaeMfeTracker.EntryContext(
                        "TRD-1",
                        StrategyType.OI_MOMENTUM,
                        IndexType.NIFTY,
                        "decision-1",
                        com.algo.trade.tuning.infra.MaeMfeTracker.Direction.LONG,
                        "NFO:NIFTY25JUN23500CE",
                        23_500,
                        OptionType.CE,
                        new BigDecimal("125.50"),
                        23_500.0,
                        Instant.parse("2026-06-01T09:30:00Z"));

        com.algo.trade.tuning.infra.MaeMfeTracker.Snapshot snap =
                new com.algo.trade.tuning.infra.MaeMfeTracker.Snapshot(
                        entry,
                        -8.2,                              // maePct (adverse)
                        Instant.parse("2026-06-01T09:35:00Z"),
                        23_480.0,
                        22.4,                              // mfePct (favorable)
                        Instant.parse("2026-06-01T09:42:00Z"),
                        23_545.0,
                        47,
                        new BigDecimal("153.50"));

        com.algo.trade.tuning.ExitEvent event = adapter.buildExitEvent(
                IndexType.NIFTY, trade, snap, "decision-1",
                new BigDecimal("150.50"), "TARGET", false);

        assertThat(event.strategy()).isEqualTo(StrategyType.OI_MOMENTUM);
        assertThat(event.tradeId()).isEqualTo("TRD-1");
        assertThat(event.correlationKey()).isEqualTo("decision-1");
        assertThat(event.exitReason()).isEqualTo("TARGET");
        assertThat(event.reversal()).isFalse();
        assertThat(event.maePct()).isEqualTo(-8.2);
        assertThat(event.mfePct()).isEqualTo(22.4);
        // realizedPnlPct = (150.50 - 125.50) / 125.50 * 100 ≈ 19.92
        assertThat(event.realizedPnlPct()).isBetween(19.9, 19.95);
        assertThat(event.attributes()).containsEntry("tickCount", 47);
        assertThat(event.attributes()).containsEntry("instrumentKey", "NFO:NIFTY25JUN23500CE");
    }

    @Test
    void buildExitEvent_handlesNullSnapshot() {
        // Tracker.onExit returns empty when no entry was registered — exit should still work.
        com.algo.trade.persistence.TradeEntity trade = sampleTrade();
        com.algo.trade.tuning.ExitEvent event = adapter.buildExitEvent(
                IndexType.NIFTY, trade, null, "decision-1",
                new BigDecimal("150.50"), "TARGET", false);

        assertThat(event.maePct()).isEqualTo(0.0);
        assertThat(event.mfePct()).isEqualTo(0.0);
        assertThat(event.timeToMaeSec()).isZero();
        assertThat(event.timeToMfeSec()).isZero();
    }

    private static com.algo.trade.persistence.TradeEntity sampleTrade() {
        return new com.algo.trade.persistence.TradeEntity(
                "TRD-1", "NFO:NIFTY25JUN23500CE", "NIFTY", "CE",
                com.algo.trade.domain.TradeStatus.OPEN, 65,
                new BigDecimal("125.50"), Instant.parse("2026-06-01T09:30:00Z"), "entry");
    }

    @Test
    void buildEvaluationEventMarksOutcomeBlocked_andCarriesEpisodeMetadata() {
        OiMomentumEntryDiagnostics diag = sampleDiagnostics();
        Instant first = Instant.parse("2026-06-01T09:30:00Z");
        Instant last = first.plusSeconds(45);
        var row = new com.algo.trade.tuning.infra.EpisodeAggregator.EpisodeRow<>(
                IndexType.NIFTY, "low_bias", first, last, 47, diag);

        com.algo.trade.tuning.EvaluationEvent event = adapter.buildEvaluationEvent(row);

        assertThat(event.strategy()).isEqualTo(StrategyType.OI_MOMENTUM);
        assertThat(event.index()).isEqualTo(IndexType.NIFTY);
        assertThat(event.outcome())
                .isEqualTo(com.algo.trade.tuning.EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("low_bias");
        assertThat(event.episodeTickCount()).isEqualTo(47);
        assertThat(event.eventTime()).isEqualTo(first);
        assertThat(event.attributes())
                .containsEntry("episodeFirstAt", first.toString())
                .containsEntry("episodeLastAt", last.toString())
                .containsEntry("rawBlocker", "low_bias")
                .containsEntry("entryCase", "CASE2_M+PCR")
                .containsEntry("matrixCase", "CASE2");
        // Correlation key is deterministic from (index, blocker, first-at) so identical
        // episodes produce identical IDs (useful for joins).
        var row2 = new com.algo.trade.tuning.infra.EpisodeAggregator.EpisodeRow<>(
                IndexType.NIFTY, "low_bias", first, last, 47, diag);
        assertThat(adapter.buildEvaluationEvent(row2).correlationKey())
                .isEqualTo(event.correlationKey());
    }

    @Test
    void buildSignalEventWiresAllUniversalFields() {
        OiMomentumEntryDiagnostics diag = sampleDiagnostics();
        StrategyDecision decision = sampleDecision();

        SignalEvent event = adapter.buildSignalEvent(
                decision, diag, IndexType.NIFTY, new BigDecimal("125.50"), "key-1");

        assertThat(event.strategy()).isEqualTo(StrategyType.OI_MOMENTUM);
        assertThat(event.index()).isEqualTo(IndexType.NIFTY);
        assertThat(event.correlationKey()).isEqualTo("key-1");
        assertThat(event.instrumentKey()).isEqualTo("NFO:NIFTY25JUN23500CE");
        assertThat(event.strike()).isEqualTo(23_500);
        assertThat(event.optionType()).isEqualTo(OptionType.CE);
        assertThat(event.entryPremium()).isEqualByComparingTo("125.50");
        assertThat(event.attributes()).isNotEmpty();
        assertThat(event.attributes().get("entryCase")).isEqualTo("CASE2_M+PCR");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static OiMomentumEntryDiagnostics sampleDiagnostics() {
        return new OiMomentumEntryDiagnostics(
                IndexType.NIFTY, "CASE2_M+PCR",
                1, "30M_HIGH_BREAK", 0.45,
                1, 1, 1.21, 5_000L, 3_000L, true,
                23_500.0, 23_500, 23_540.0, 23_460.0, 0.10,
                "ep-1", 14.5, 7, false, false,
                "test signal", false, 0.42, "",
                125.0, 130.0,
                "MIDDAY_DISCIPLINE", "CASE2", "LEGACY", 0, 0
        );
    }

    private static StrategyDecision sampleDecision() {
        return new StrategyDecision(
                Instant.parse("2026-06-01T09:30:00Z"),
                UnderlyingSymbol.NIFTY,
                SignalType.BUY_CE,
                new BigDecimal("23500"),
                Optional.of(new BigDecimal("125.50")),
                Optional.empty(),
                Optional.of(65),
                Optional.of(new BigDecimal("8157.50")),
                Optional.of("NFO:NIFTY25JUN23500CE"),
                Optional.of(new BigDecimal("23500")),
                Optional.of(OptionType.CE),
                false, Optional.empty(), false,
                BigDecimal.valueOf(72),
                List.of("OI_MOMENTUM[NIFTY]: test")
        );
    }
}
