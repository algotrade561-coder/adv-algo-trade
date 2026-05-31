package com.algo.trade.tuning;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.recorder.TuningEventCsvWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LegEventCsvWriterTest {

    private final TuningEventCsvWriter writer = new TuningEventCsvWriter();

    @Test
    void headerAndFormatIncludeLegFields() {
        assertThat(writer.headerFor(TuningEventType.LEG)).contains("legNumber", "legSide", "stage");

        LegEvent event = new LegEvent(
                Instant.parse("2026-06-01T10:00:00Z"),
                Instant.parse("2026-06-01T10:00:00.100Z"),
                StrategyType.BULL_CALL_SPREAD,
                IndexType.NIFTY,
                "bcs-abc123",
                1,
                "BUY",
                OptionType.CE,
                23500,
                "NIFTY2560623500CE",
                "FILLED",
                50,
                50,
                BigDecimal.valueOf(120.5),
                Map.of("phase", "BUY"));

        String row = writer.format(event);
        assertThat(row).doesNotContain("BULL_CALL_SPREAD");
        assertThat(row).contains("NIFTY", "bcs-abc123", "BUY", "CE", "23500", "FILLED", "120.5");
    }
}
