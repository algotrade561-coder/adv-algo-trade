package com.algo.trade.strategy.oimomentum;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.IndexType;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class RejectEpisodeAggregatorTest {

    @Test
    void thousandSameReasonCallsProduceOneRowAfterFlushAll() {
        RejectEpisodeAggregator agg = new RejectEpisodeAggregator(60);
        Instant base = Instant.parse("2026-05-15T10:00:00Z");
        OiMomentumEntryDiagnostics diagnostics = sampleDiagnostics();

        for (int i = 0; i < 1000; i++) {
            agg.record(IndexType.NIFTY, "low_bias", diagnostics, base.plusSeconds(i % 30));
        }

        List<RejectEpisodeAggregator.EpisodeRow> rows = agg.flushAll();

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tickCount()).isEqualTo(1000);
        assertThat(rows.get(0).rejectReason()).isEqualTo("low_bias");
        assertThat(rows.get(0).indexType()).isEqualTo(IndexType.NIFTY);
    }

    private static OiMomentumEntryDiagnostics sampleDiagnostics() {
        return new OiMomentumEntryDiagnostics(
                IndexType.NIFTY,
                "CASE1",
                1,
                "ROC",
                0.5,
                1,
                1,
                1.0,
                100,
                100,
                true,
                24000,
                24000,
                0,
                0,
                0,
                "",
                15,
                1,
                false,
                true,
                "",
                false,
                0,
                "",
                0,
                0,
                "",
                "CASE1",
                "LEGACY",
                0,
                25);
    }
}
