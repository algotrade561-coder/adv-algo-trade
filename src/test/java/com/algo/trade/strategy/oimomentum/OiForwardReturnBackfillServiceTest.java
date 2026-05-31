package com.algo.trade.strategy.oimomentum;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OiForwardReturnBackfillServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void backfillIsIdempotentOnTempCsv() throws Exception {
        Path csv = tempDir.resolve("oi-momentum-rejects.csv");
        Files.writeString(csv, "timestamp,indexType,fwdSpot60m\n"
                + "2026-01-01T04:30:00Z,NIFTY,\n");

        List<String> fwdCols = List.of("fwdSpot15m", "fwdSpot30m", "fwdSpot60m");
        OiForwardReturnBackfillService.RowBackfill backfill = (row, ts, indexName) -> Map.of(
                "fwdSpot15m", "24100.00",
                "fwdSpot30m", "24150.00",
                "fwdSpot60m", "24200.00");

        OiForwardReturnBackfillService.backfillCsv(csv, "timestamp", "indexType", fwdCols, backfill);
        String afterFirst = Files.readString(csv);

        OiForwardReturnBackfillService.backfillCsv(csv, "timestamp", "indexType", fwdCols, backfill);
        String afterSecond = Files.readString(csv);

        assertThat(afterFirst).contains("24200.00");
        assertThat(afterSecond).isEqualTo(afterFirst);
    }
}
