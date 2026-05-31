package com.algo.trade.strategy.oishifttrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import com.algo.trade.strategy.oimomentum.OiMarketSnapshotBuffer;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShiftTrapSnapshotWarmupServiceTest {

    @Test
    void ingestArchiveSnapshotPopulatesBothBuffers() {
        ShiftTrapChainSnapshotBuffer trapBuffer = new ShiftTrapChainSnapshotBuffer(null);
        OiMarketSnapshotBuffer oiBuffer = new OiMarketSnapshotBuffer(null);
        ShiftTrapSnapshotWarmupService svc = new ShiftTrapSnapshotWarmupService(
                new OiShiftTrapConfig(), null, trapBuffer, oiBuffer);

        Instant ts = Instant.parse("2026-05-29T05:00:00Z");
        ChainSnapshot arch = new ChainSnapshot(
                ts, "NIFTY", 25000, 15, "2026-05-29", 25000,
                List.of(new ChainSnapshot.StrikeData(
                        25000,
                        120, 500_000, 0, 0, 0, 0, 0, 0, 0, 0, 5000, 0, 0,
                        110, 400_000, 0, 0, 0, 0, 0, 0, 0, 0, 4000, 0, 0)));

        svc.ingest(arch);

        assertTrue(trapBuffer.nearest(IndexType.NIFTY, ts).isPresent());
        assertTrue(oiBuffer.nearest(IndexType.NIFTY, ts).isPresent());
    }
}
