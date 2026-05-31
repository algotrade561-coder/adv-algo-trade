package com.algo.trade.tuning.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.data.SnapshotFileWriter;
import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Phase 1, Commit 5 — JVM-restart warm-up. Verifies that today's archived chain
 * snapshots are read, filtered by retention window, converted, and ingested into
 * {@link MarketSnapshotBuffer}.
 */
class SnapshotWarmupServiceTest {

    private SnapshotFileWriter snapshotFileWriter;
    private MarketSnapshotBuffer buffer;
    private SnapshotWarmupService warmup;

    @BeforeEach
    void setUp() {
        snapshotFileWriter = Mockito.mock(SnapshotFileWriter.class);
        LiveInstrumentCache liveCache = Mockito.mock(LiveInstrumentCache.class);
        when(liveCache.allOptions()).thenReturn(List.of());
        buffer = new MarketSnapshotBuffer(liveCache);
        buffer.init();
        warmup = new SnapshotWarmupService(snapshotFileWriter, buffer);
    }

    @Test
    void noSnapshotsOnDisk_returnsZero() {
        when(snapshotFileWriter.listAllSnapshots(any())).thenReturn(List.of());

        int n = warmup.warmToday(Instant.parse("2026-06-01T10:00:00Z"));

        assertThat(n).isZero();
        assertThat(buffer.snapshotCount(IndexType.NIFTY)).isZero();
    }

    @Test
    void ingestsWithinRetentionWindow() {
        Path fake1 = Path.of("/tmp/NIFTY_0930.json.gz");
        Path fake2 = Path.of("/tmp/NIFTY_1000.json.gz");
        when(snapshotFileWriter.listAllSnapshots(any())).thenReturn(List.of(fake1, fake2));

        // Both snapshots inside the 3h retention window.
        ChainSnapshot s1 = makeChainSnapshot("NIFTY",
                Instant.parse("2026-06-01T09:30:00Z"), 23_500.0, 23_500);
        ChainSnapshot s2 = makeChainSnapshot("NIFTY",
                Instant.parse("2026-06-01T10:00:00Z"), 23_510.0, 23_500);
        when(snapshotFileWriter.read(fake1)).thenReturn(Optional.of(s1));
        when(snapshotFileWriter.read(fake2)).thenReturn(Optional.of(s2));

        int n = warmup.warmToday(Instant.parse("2026-06-01T10:30:00Z"));

        assertThat(n).isEqualTo(2);
        assertThat(buffer.snapshotCount(IndexType.NIFTY)).isEqualTo(2);
    }

    @Test
    void ingestsHistoricalSnapshotsWithinRetentionWindow() {
        // With 5-day retention, ingesting two snapshots 5h apart should both stay.
        Path early = Path.of("/tmp/NIFTY_0500.json.gz");
        Path late = Path.of("/tmp/NIFTY_1000.json.gz");
        when(snapshotFileWriter.listAllSnapshots(any())).thenReturn(List.of(early, late));

        ChainSnapshot first = makeChainSnapshot("NIFTY",
                Instant.parse("2026-06-01T05:00:00Z"), 23_400.0, 23_400);
        ChainSnapshot second = makeChainSnapshot("NIFTY",
                Instant.parse("2026-06-01T10:00:00Z"), 23_500.0, 23_500);
        when(snapshotFileWriter.read(early)).thenReturn(Optional.of(first));
        when(snapshotFileWriter.read(late)).thenReturn(Optional.of(second));

        int n = warmup.warmToday(Instant.parse("2026-06-01T10:30:00Z"));

        assertThat(n).isEqualTo(2);
        assertThat(buffer.snapshotCount(IndexType.NIFTY)).isEqualTo(2);
    }

    @Test
    void weekendRollover_warmsFridaySnapshotsOnMondayBoot() {
        // EC2 boots Monday 08:45 IST. SnapshotWarmupService should walk the last
        // 5 calendar days and pull Friday's snapshots (3 days ago) into the buffer.
        LocalDate monday = LocalDate.of(2026, 6, 8);
        LocalDate friday = LocalDate.of(2026, 6, 5);

        Path fridayFile = Path.of("/tmp/NIFTY_1525.json.gz");
        when(snapshotFileWriter.listAllSnapshots(friday)).thenReturn(List.of(fridayFile));
        when(snapshotFileWriter.listAllSnapshots(monday)).thenReturn(List.of());

        ChainSnapshot fridayLateClose = makeChainSnapshot("NIFTY",
                Instant.parse("2026-06-05T09:55:00Z"),    // 15:25 IST
                23_500.0, 23_500);
        when(snapshotFileWriter.read(fridayFile)).thenReturn(Optional.of(fridayLateClose));

        int n = warmup.warmRecent(Instant.parse("2026-06-08T03:15:00Z"));   // Mon 08:45 IST

        assertThat(n).isEqualTo(1);
        assertThat(buffer.snapshotCount(IndexType.NIFTY)).isEqualTo(1);
    }

    @Test
    void skipsSnapshotsFromFutureBeyondSkewTolerance() {
        Path future = Path.of("/tmp/NIFTY_1200.json.gz");
        when(snapshotFileWriter.listAllSnapshots(any())).thenReturn(List.of(future));

        // 2 hours in the future — well past the 60s skew tolerance.
        ChainSnapshot impossible = makeChainSnapshot("NIFTY",
                Instant.parse("2026-06-01T12:00:00Z"), 23_500.0, 23_500);
        when(snapshotFileWriter.read(future)).thenReturn(Optional.of(impossible));

        int n = warmup.warmToday(Instant.parse("2026-06-01T10:00:00Z"));

        assertThat(n).isZero();
    }

    @Test
    void skipsUnreadableFilesWithoutAborting() {
        Path bad = Path.of("/tmp/corrupt.json.gz");
        Path good = Path.of("/tmp/NIFTY_1000.json.gz");
        when(snapshotFileWriter.listAllSnapshots(any())).thenReturn(List.of(bad, good));
        when(snapshotFileWriter.read(bad)).thenReturn(Optional.empty());
        when(snapshotFileWriter.read(good)).thenReturn(Optional.of(makeChainSnapshot(
                "NIFTY", Instant.parse("2026-06-01T10:00:00Z"), 23_500.0, 23_500)));

        int n = warmup.warmToday(Instant.parse("2026-06-01T10:30:00Z"));

        assertThat(n).isEqualTo(1);
    }

    @Test
    void skipsSnapshotsWithUnknownUnderlying() {
        Path file = Path.of("/tmp/EXOTIC_1000.json.gz");
        when(snapshotFileWriter.listAllSnapshots(any())).thenReturn(List.of(file));
        ChainSnapshot exotic = makeChainSnapshot("DOWJONES",
                Instant.parse("2026-06-01T10:00:00Z"), 35_000.0, 35_000);
        when(snapshotFileWriter.read(file)).thenReturn(Optional.of(exotic));

        int n = warmup.warmToday(Instant.parse("2026-06-01T10:30:00Z"));

        assertThat(n).isZero();
    }

    @Test
    void preservesAtmStrikeOiFromSnapshot() {
        Path file = Path.of("/tmp/NIFTY_1000.json.gz");
        when(snapshotFileWriter.listAllSnapshots(any())).thenReturn(List.of(file));

        ChainSnapshot snap = new ChainSnapshot(
                Instant.parse("2026-06-01T10:00:00Z"),
                "NIFTY", 23_512.0, 14.5, "2026-06-12", 23_500,
                List.of(
                        strikeData(23_500, 145.0, 132.0, 100_000L, 90_000L, 5_000L, 3_000L),
                        strikeData(23_550, 120.0, 150.0, 50_000L, 60_000L, 2_000L, 1_000L)
                ));
        when(snapshotFileWriter.read(file)).thenReturn(Optional.of(snap));

        int n = warmup.warmToday(Instant.parse("2026-06-01T10:30:00Z"));

        assertThat(n).isEqualTo(1);
        var stored = buffer.floor(IndexType.NIFTY, Instant.parse("2026-06-01T10:00:00Z")).get();
        assertThat(stored.spot()).isEqualTo(23_512.0);
        assertThat(stored.atm()).isEqualTo(23_500);
        assertThat(stored.atmCeLast()).isEqualTo(145.0);    // taken from ATM strike's CE LTP
        assertThat(stored.atmPeLast()).isEqualTo(132.0);
        assertThat(stored.strikes()).hasSize(2);
        assertThat(stored.strikeOi(23_500).oiCe()).isEqualTo(100_000L);
        assertThat(stored.strikeOi(23_550).oiPe()).isEqualTo(60_000L);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ChainSnapshot makeChainSnapshot(String underlying, Instant t,
                                                    double spot, int atm) {
        return new ChainSnapshot(
                t, underlying, spot, 14.5, "2026-06-12", atm,
                List.of(strikeData(atm, 100.0, 100.0, 50_000L, 50_000L, 0L, 0L))
        );
    }

    private static ChainSnapshot.StrikeData strikeData(int strike,
                                                        double ceLTP, double peLTP,
                                                        long ceOI, long peOI,
                                                        long ceOiChange, long peOiChange) {
        return new ChainSnapshot.StrikeData(
                strike,
                ceLTP, ceOI, 0L, 18.0, 0.5, 0.001, -0.05, 0.1, ceLTP - 0.5, ceLTP + 0.5, ceOiChange, ceLTP, ceLTP,
                peLTP, peOI, 0L, 18.0, -0.5, 0.001, -0.05, 0.1, peLTP - 0.5, peLTP + 0.5, peOiChange, peLTP, peLTP
        );
    }
}
