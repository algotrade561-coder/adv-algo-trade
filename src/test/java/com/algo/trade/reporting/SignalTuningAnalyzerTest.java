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
    void buyWithoutBreakoutProducesCriticalRecommendation() {
        Instant ts = Instant.parse("2026-05-15T10:00:00+05:30");
        SignalTuningCsvLoader.SignalRow buy = new SignalTuningCsvLoader.SignalRow(
                "key1", ts, "DIRECTIONAL_BUY", "BUY_CE", "NIFTY", "CE",
                "NFO:NIFTY2651923700CE", new BigDecimal("100"), new BigDecimal("70"),
                new BigDecimal("75"), "signalScore", "Breakout condition failed",
                true, false, false, true, true, true, true);
        SignalTuningCsvLoader.Loaded data = new SignalTuningCsvLoader.Loaded(
                List.of(buy), List.of(), List.of(), Map.of(), Map.of());

        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(data);

        assertThat(report.buySignals()).isOne();
        assertThat(report.buysWithoutBreakoutFlag()).isOne();
        assertThat(report.recommendations())
                .anyMatch(r -> r.severity() == SignalTuningAnalyzer.Severity.CRITICAL
                        && r.finding().contains("breakoutPassed=false"));
    }

    @Test
    void brokerErrorStageProducesCriticalRecommendation() {
        Instant ts = Instant.parse("2026-05-15T10:00:00+05:30");
        SignalTuningCsvLoader.SignalRow row = new SignalTuningCsvLoader.SignalRow(
                "key1", ts, "DIRECTIONAL_BUY", "NO_TRADE", "NIFTY", "CE",
                "NFO:NIFTY2651923700CE", new BigDecimal("100"), new BigDecimal("70"),
                new BigDecimal("50"), "signalScore", "score failed",
                true, true, false, true, true, true, true);
        SignalTuningCsvLoader.ExecutionRow entry = new SignalTuningCsvLoader.ExecutionRow(
                "key1", ts, "BROKER_ERROR", false, "BUY_CE", "NIFTY", "IP not allowed", "403");
        SignalTuningCsvLoader.Loaded data = new SignalTuningCsvLoader.Loaded(
                List.of(row), List.of(entry), List.of(), Map.of(), Map.of());

        SignalTuningAnalyzer.Report report = SignalTuningAnalyzer.analyze(data);

        assertThat(report.recommendations())
                .anyMatch(r -> r.severity() == SignalTuningAnalyzer.Severity.CRITICAL
                        && r.category().equals("broker"));
    }
}
