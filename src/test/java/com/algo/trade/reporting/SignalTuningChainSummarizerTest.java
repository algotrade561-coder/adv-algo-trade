package com.algo.trade.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SignalTuningChainSummarizerTest {

    @Test
    void buyCeFlagsMismatchWhenPutOiChangeDominates() {
        Instant ts = Instant.parse("2026-05-15T10:00:00+05:30");
        SignalTuningCsvLoader.SignalRow signal = new SignalTuningCsvLoader.SignalRow(
                "k1", ts, "DIRECTIONAL_BUY", "BUY_CE", "NIFTY", "CE",
                "NFO:NIFTY2651923800CE", new BigDecimal("180"), new BigDecimal("70"),
                new BigDecimal("75"), "", "", true, true, true, true, true, true, true);

        List<SignalTuningCsvLoader.ChainLevelRow> levels = List.of(
                chainRow("k1", 23700, 23720, 1000, 2000, 500, 5000),
                chainRow("k1", 23800, 23720, 2000, 1500, 50, 8000));

        SignalTuningChainSummarizer.ChainSummary summary =
                SignalTuningChainSummarizer.summarize(signal, levels);

        assertThat(summary.present()).isTrue();
        assertThat(summary.oiDeltaPresent()).isTrue();
        assertThat(summary.oiMismatch()).isTrue();
        assertThat(summary.alignment()).isEqualTo("mismatch");
        assertThat(summary.maxPutChange()).isGreaterThan(summary.maxCallChange());
    }

    @Test
    void missingLevelsReturnsNotPresent() {
        SignalTuningCsvLoader.SignalRow signal = new SignalTuningCsvLoader.SignalRow(
                "k1", Instant.now(), "DIRECTIONAL_BUY", "NO_TRADE", "NIFTY", "CE",
                "", BigDecimal.ZERO, new BigDecimal("70"), BigDecimal.ZERO, "", "",
                false, false, false, false, false, false, false);

        assertThat(SignalTuningChainSummarizer.summarize(signal, List.of()).present()).isFalse();
    }

    private static SignalTuningCsvLoader.ChainLevelRow chainRow(
            String key, long strike, double spot, long callOi, long putOi, long callChg, long putChg) {
        return new SignalTuningCsvLoader.ChainLevelRow(
                key, Instant.now(), "NIFTY", "CE", strike, BigDecimal.valueOf(spot),
                callOi, putOi, callChg, putChg, BigDecimal.TEN, BigDecimal.TEN);
    }
}
