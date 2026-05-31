package com.algo.trade.strategy.oishifttrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class ShiftTrapEvalEpisodeAggregatorTest {

    @Test
    void sameBlockerWithinWindowProducesOneEpisodeOnFlush() {
        ShiftTrapEvalEpisodeAggregator agg = new ShiftTrapEvalEpisodeAggregator(60);
        Instant t0 = Instant.parse("2026-05-29T04:00:00Z");
        OiShiftTrapDiagnostics diag = sample("NIFTY", "gate:SCORE");

        for (int i = 0; i < 100; i++) {
            agg.record(diag, t0.plusSeconds(i));
        }
        var flushed = agg.flushExpired(t0.plusSeconds(160));
        assertEquals(1, flushed.size());
        assertEquals(100, flushed.getFirst().tickCount());
    }

    @Test
    void blockerChangeFlushesPriorEpisode() {
        ShiftTrapEvalEpisodeAggregator agg = new ShiftTrapEvalEpisodeAggregator(60);
        Instant t0 = Instant.parse("2026-05-29T04:00:00Z");
        var flushed = agg.record(sample("NIFTY", "gate:SCORE"), t0);
        assertTrue(flushed.isEmpty());
        flushed = agg.record(sample("NIFTY", "gate:IMBALANCE"), t0.plusSeconds(5));
        assertEquals(1, flushed.size());
        assertEquals("gate:SCORE", flushed.getFirst().lastDiag().primaryBlocker());
    }

    private static OiShiftTrapDiagnostics sample(String underlying, String blocker) {
        return new OiShiftTrapDiagnostics(
                underlying, BigDecimal.valueOf(25000), 1, "NORMAL", 5000,
                "NO_TRAP_MATCH", blocker, 20,
                OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                false, "", BigDecimal.ZERO, 0);
    }
}
