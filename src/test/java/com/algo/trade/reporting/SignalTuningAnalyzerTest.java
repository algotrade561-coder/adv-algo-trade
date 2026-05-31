package com.algo.trade.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SignalTuningAnalyzerTest {

    @Test
    void emptyDataReturnsEmptyReport() {
        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(
                new SignalTuningCsvLoader.Loaded(List.of(), List.of(), List.of(), Map.of(), Map.of()));
        assertThat(report.totalEvaluations()).isZero();
        assertThat(report.recommendations()).hasSize(1);
    }

    @Test
    void buyWithoutBreakoutConfirmationProducesCriticalRecommendation() {
        Instant ts = Instant.parse("2026-05-15T10:00:00+05:30");
        SignalTuningCsvLoader.SignalRow buy = signalRow(
                "key1", ts, "DIRECTIONAL_BUY", "BUY_CE",
                "Breakout condition passed; Breakout confirmation failed",
                true, false, false);
        SignalTuningCsvLoader.Loaded data = new SignalTuningCsvLoader.Loaded(
                List.of(buy), List.of(), List.of(), Map.of(), Map.of());

        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(data);

        assertThat(report.buySignals()).isOne();
        assertThat(report.buysWithoutBreakoutConfirmed()).isOne();
        assertThat(report.recommendations())
                .anyMatch(r -> r.severity() == SignalTuningAnalyzer.Severity.CRITICAL
                        && r.finding().contains("without breakout confirmation"));
    }

    @Test
    void swingFalseButConfirmationTrueIsInfoNotCritical() {
        Instant ts = Instant.parse("2026-05-15T10:00:00+05:30");
        SignalTuningCsvLoader.SignalRow buy = signalRow(
                "key1", ts, "DIRECTIONAL_BUY", "BUY_CE",
                "Trend condition passed; Breakout condition failed; Breakout confirmation passed; Volume spike confirmed",
                false, true, true);
        SignalTuningCsvLoader.Loaded data = new SignalTuningCsvLoader.Loaded(
                List.of(buy), List.of(), List.of(), Map.of(), Map.of());

        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(data);

        assertThat(report.swingBreakoutFalseConfirmTrue()).isOne();
        assertThat(report.buysWithoutBreakoutConfirmed()).isZero();
        assertThat(report.recommendations())
                .noneMatch(r -> r.severity() == SignalTuningAnalyzer.Severity.CRITICAL
                        && r.finding().contains("breakoutPassed=false"));
        assertThat(report.recommendations())
                .anyMatch(r -> r.severity() == SignalTuningAnalyzer.Severity.INFO
                        && r.finding().contains("swing breakoutPassed=false"));
    }

    @Test
    void oiSpikeBurstDuplicatesDetected() {
        Instant t0 = Instant.parse("2026-05-19T09:36:09+05:30");
        Instant t1 = Instant.parse("2026-05-19T09:36:10+05:30");
        Instant t2 = Instant.parse("2026-05-19T09:36:16+05:30");
        List<SignalTuningCsvLoader.SignalRow> rows = List.of(
                signalRow("k1", t0, "OI_MOMENTUM", "BUY_PE", "OI_MOMENTUM: SPIKE:EVENT_SPIKE", true, false, false),
                signalRow("k2", t1, "OI_MOMENTUM", "BUY_PE", "OI_MOMENTUM: SPIKE:EVENT_SPIKE", true, false, false),
                signalRow("k3", t2, "OI_MOMENTUM", "BUY_PE", "OI_MOMENTUM: SPIKE:EVENT_SPIKE", true, false, false));
        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(
                new SignalTuningCsvLoader.Loaded(rows, List.of(), List.of(), Map.of(), Map.of()));

        assertThat(report.oiSpikeBurstDuplicates()).isEqualTo(2);
        assertThat(report.recommendations())
                .anyMatch(r -> r.category().equals("oi_momentum") && r.finding().contains("duplicate"));
    }

    @Test
    void brokerErrorStageProducesCriticalRecommendation() {
        Instant ts = Instant.parse("2026-05-15T10:00:00+05:30");
        SignalTuningCsvLoader.SignalRow row = signalRow(
                "key1", ts, "DIRECTIONAL_BUY", "NO_TRADE", "score failed", true, true, true);
        SignalTuningCsvLoader.ExecutionRow entry = new SignalTuningCsvLoader.ExecutionRow(
                "key1", ts, "BROKER_ERROR", false, "DIRECTIONAL_BUY", "BUY_CE", "NIFTY", "IP not allowed", "403",
                0, 0, null);
        SignalTuningCsvLoader.Loaded data = new SignalTuningCsvLoader.Loaded(
                List.of(row), List.of(entry), List.of(), Map.of(), Map.of());

        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(data);

        assertThat(report.recommendations())
                .anyMatch(r -> r.severity() == SignalTuningAnalyzer.Severity.CRITICAL
                        && r.category().equals("broker"));
    }

    @Test
    void guardRejectionsCategorizedByReason() {
        Instant ts = Instant.parse("2026-05-15T10:00:00+05:30");
        SignalTuningCsvLoader.ExecutionRow guard = new SignalTuningCsvLoader.ExecutionRow(
                "key1", ts, "ORDER_GUARD_REJECTED", false, "DIRECTIONAL_BUY", "BUY_PE", "NIFTY", "",
                "Open trade already exists for instrument: NFO:NIFTY2651923400PE",
                0, 0, null);
        SignalTuningCsvLoader.SignalRow noop = signalRow(
                "noop", ts, "DIRECTIONAL_BUY", "NO_TRADE", "score failed", true, true, false);
        SignalTuningCsvLoader.Loaded data = new SignalTuningCsvLoader.Loaded(
                List.of(noop), List.of(guard, guard, guard), List.of(), Map.of(), Map.of());

        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(data);

        assertThat(report.recommendations())
                .anyMatch(r -> r.finding().contains("duplicateInstrument=3"));
    }

    private static SignalTuningCsvLoader.SignalRow signalRow(
            String key,
            Instant ts,
            String strategy,
            String signalType,
            String reasons,
            boolean breakoutPassed,
            boolean breakoutConfirmed,
            boolean volumeSpike) {
        return new SignalTuningCsvLoader.SignalRow(
                key, ts, strategy, signalType, "NIFTY", "CE",
                "NFO:NIFTY2651923700CE", new BigDecimal("100"), new BigDecimal("70"),
                new BigDecimal("75"), "signalScore", reasons,
                true, breakoutPassed, volumeSpike, true, true, true, true,
                breakoutConfirmed, "");
    }
}
