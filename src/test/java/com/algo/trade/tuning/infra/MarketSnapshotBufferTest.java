package com.algo.trade.tuning.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.tuning.infra.MarketSnapshotBuffer.MarketSnapshot;
import com.algo.trade.tuning.infra.MarketSnapshotBuffer.StrikeOi;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Phase 1, Commit 5 — unified market snapshot buffer. Verifies ingest, retention
 * sliding window, nearest/floor/ceiling query semantics, parallel per-index storage,
 * and live capture from a mocked {@link LiveInstrumentCache}.
 */
class MarketSnapshotBufferTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:30:00Z");

    private LiveInstrumentCache liveCache;
    private MarketSnapshotBuffer buffer;

    @BeforeEach
    void setUp() {
        liveCache = Mockito.mock(LiveInstrumentCache.class);
        when(liveCache.allOptions()).thenReturn(List.of());
        buffer = new MarketSnapshotBuffer(liveCache);
        buffer.init();
    }

    @Test
    void ingestStoresSnapshotAndQueryRetrievesIt() {
        buffer.ingest(IndexType.NIFTY, T0, 23_500.0, 23_500, 125.0, 130.0, Map.of());

        Optional<MarketSnapshot> snap = buffer.floor(IndexType.NIFTY, T0);
        assertThat(snap).isPresent();
        assertThat(snap.get().spot()).isEqualTo(23_500.0);
        assertThat(snap.get().atmCeLast()).isEqualTo(125.0);
        assertThat(buffer.snapshotCount(IndexType.NIFTY)).isEqualTo(1);
    }

    @Test
    void invalidSpotIsRejected() {
        buffer.ingest(IndexType.NIFTY, T0, 0.0, 0, 0.0, 0.0, Map.of());
        buffer.ingest(IndexType.NIFTY, T0, -1.0, 0, 0.0, 0.0, Map.of());
        assertThat(buffer.snapshotCount(IndexType.NIFTY)).isZero();
    }

    @Test
    void retentionSlidingWindowDropsOldEntries() {
        // Ingest entries spanning 6 calendar days (one snapshot per hour). Retention is
        // 5 days — the oldest entries should get evicted.
        Instant t = T0;
        int totalIngested = 6 * 24;        // 6 days of hourly snapshots = 144 entries
        for (int i = 0; i < totalIngested; i++) {
            buffer.ingest(IndexType.NIFTY, t, 23_500.0, 23_500, 100.0, 100.0, Map.of());
            t = t.plus(Duration.ofHours(1));
        }
        // Latest is at T0 + 143h. Retention cutoff = latest - 5 days = T0 + 23h.
        // Entries strictly before T0 + 23h should be evicted (24 entries).
        int count = buffer.snapshotCount(IndexType.NIFTY);
        assertThat(count).isLessThanOrEqualTo(120).isGreaterThan(115);

        // Oldest entry remaining is within retention window relative to the latest.
        Optional<MarketSnapshot> earliest = buffer.ceiling(IndexType.NIFTY, T0);
        assertThat(earliest).isPresent();
        Duration gap = Duration.between(earliest.get().at(), t.minus(Duration.ofHours(1)));
        assertThat(gap).isLessThanOrEqualTo(MarketSnapshotBuffer.retention());
    }

    @Test
    void retentionAnchoredOnLatestEntry_oldDataIngestedFirstStays() {
        // Simulates SnapshotWarmupService: ingest oldest day first, then newer days.
        // The "latest" anchor means the old data stays as long as the newest entry's
        // window covers it.
        Instant fridayClose = T0;
        Instant mondayBoot = T0.plus(Duration.ofDays(3)).plus(Duration.ofHours(17));
        buffer.ingest(IndexType.NIFTY, fridayClose, 23_500.0, 23_500, 100.0, 100.0, Map.of());
        buffer.ingest(IndexType.NIFTY, mondayBoot, 23_550.0, 23_500, 102.0, 99.0, Map.of());

        assertThat(buffer.snapshotCount(IndexType.NIFTY)).isEqualTo(2);
        // Friday's snapshot remains queryable from Monday.
        assertThat(buffer.nearest(IndexType.NIFTY, fridayClose.plus(Duration.ofMinutes(30))).get().spot())
                .isEqualTo(23_500.0);
    }

    @Test
    void nearestPicksByAbsoluteTimeDelta() {
        buffer.ingest(IndexType.NIFTY, T0, 23_500.0, 23_500, 100.0, 100.0, Map.of());
        buffer.ingest(IndexType.NIFTY, T0.plusSeconds(60), 23_510.0, 23_500, 102.0, 99.0, Map.of());
        buffer.ingest(IndexType.NIFTY, T0.plusSeconds(120), 23_520.0, 23_500, 104.0, 98.0, Map.of());

        // T0 + 70s — closer to the 60s entry than the 120s entry.
        Optional<MarketSnapshot> n = buffer.nearest(IndexType.NIFTY, T0.plusSeconds(70));
        assertThat(n).isPresent();
        assertThat(n.get().spot()).isEqualTo(23_510.0);

        // T0 + 100s — closer to 120s entry.
        n = buffer.nearest(IndexType.NIFTY, T0.plusSeconds(100));
        assertThat(n.get().spot()).isEqualTo(23_520.0);

        // T0 - 10s — only floor would return nothing; nearest returns the earliest.
        n = buffer.nearest(IndexType.NIFTY, T0.minusSeconds(10));
        assertThat(n.get().spot()).isEqualTo(23_500.0);
    }

    @Test
    void floorReturnsAtOrBefore_ceilingReturnsAtOrAfter() {
        buffer.ingest(IndexType.NIFTY, T0, 100.0, 100, 1.0, 1.0, Map.of());
        buffer.ingest(IndexType.NIFTY, T0.plusSeconds(60), 200.0, 100, 2.0, 2.0, Map.of());

        // Floor at T0+30 → T0 entry.
        assertThat(buffer.floor(IndexType.NIFTY, T0.plusSeconds(30)).get().spot()).isEqualTo(100.0);
        // Ceiling at T0+30 → T0+60 entry.
        assertThat(buffer.ceiling(IndexType.NIFTY, T0.plusSeconds(30)).get().spot()).isEqualTo(200.0);
        // Floor at T0-10 → no earlier entry.
        assertThat(buffer.floor(IndexType.NIFTY, T0.minusSeconds(10))).isEmpty();
        // Ceiling at T0+120 → no later entry.
        assertThat(buffer.ceiling(IndexType.NIFTY, T0.plusSeconds(120))).isEmpty();
    }

    @Test
    void queryOnEmptyIndexReturnsEmpty() {
        assertThat(buffer.nearest(IndexType.SENSEX, T0)).isEmpty();
        assertThat(buffer.floor(IndexType.SENSEX, T0)).isEmpty();
        assertThat(buffer.ceiling(IndexType.SENSEX, T0)).isEmpty();
        assertThat(buffer.snapshotCount(IndexType.SENSEX)).isZero();
    }

    @Test
    void parallelIndicesAreIndependent() {
        buffer.ingest(IndexType.NIFTY, T0, 23_500.0, 23_500, 100.0, 100.0, Map.of());
        buffer.ingest(IndexType.SENSEX, T0, 75_000.0, 75_000, 200.0, 200.0, Map.of());
        buffer.ingest(IndexType.BANKNIFTY, T0, 53_000.0, 53_000, 300.0, 300.0, Map.of());

        assertThat(buffer.floor(IndexType.NIFTY, T0).get().spot()).isEqualTo(23_500.0);
        assertThat(buffer.floor(IndexType.SENSEX, T0).get().spot()).isEqualTo(75_000.0);
        assertThat(buffer.floor(IndexType.BANKNIFTY, T0).get().spot()).isEqualTo(53_000.0);
    }

    @Test
    void strikeOiMapIsPreservedAndQueryable() {
        Map<Integer, StrikeOi> strikes = Map.of(
                23_500, new StrikeOi(100_000L, 80_000L, 5_000L, 3_000L),
                23_550, new StrikeOi(50_000L, 60_000L, 2_000L, 1_000L)
        );
        buffer.ingest(IndexType.NIFTY, T0, 23_500.0, 23_500, 125.0, 130.0, strikes);

        MarketSnapshot snap = buffer.floor(IndexType.NIFTY, T0).get();
        assertThat(snap.strikes()).hasSize(2);
        assertThat(snap.strikeOi(23_500).oiCe()).isEqualTo(100_000L);
        assertThat(snap.strikeOi(23_500).oiPe()).isEqualTo(80_000L);
        assertThat(snap.strikeOi(23_550).oiPeChange()).isEqualTo(1_000L);
        // Unknown strike → empty (no NPE).
        assertThat(snap.strikeOi(99_999).oiCe()).isZero();
    }

    @Test
    void captureLive_pullsSpotAndAtmFromLiveCache() {
        when(liveCache.getFuturesPrice(IndexType.NIFTY)).thenReturn(23_512.0);
        // ATM strike for NIFTY at 23_512 = roundToATM → 23_500 (NIFTY strike interval = 50).
        OptionInstrument ce = mockOption(IndexType.NIFTY, 23_500, "CE", 145.0, 100_000L, 5_000L);
        OptionInstrument pe = mockOption(IndexType.NIFTY, 23_500, "PE", 132.0, 90_000L, 3_000L);
        OptionInstrument farCe = mockOption(IndexType.NIFTY, 24_500, "CE", 5.0, 1_000L, 100L);  // > radius
        when(liveCache.allOptions()).thenReturn(List.of(ce, pe, farCe));

        buffer.captureLive(IndexType.NIFTY, T0);

        MarketSnapshot snap = buffer.floor(IndexType.NIFTY, T0).get();
        assertThat(snap.spot()).isEqualTo(23_512.0);
        assertThat(snap.atm()).isEqualTo(23_500);
        assertThat(snap.atmCeLast()).isEqualTo(145.0);
        assertThat(snap.atmPeLast()).isEqualTo(132.0);
        assertThat(snap.strikes()).containsKey(23_500);
        // Out-of-radius strike should be excluded (24_500 is 20 strikes away from 23_500, > 5).
        assertThat(snap.strikes()).doesNotContainKey(24_500);
    }

    @Test
    void captureLive_skipsWhenSpotUnavailable() {
        when(liveCache.getFuturesPrice(IndexType.NIFTY)).thenReturn(0.0);
        buffer.captureLive(IndexType.NIFTY, T0);
        assertThat(buffer.snapshotCount(IndexType.NIFTY)).isZero();
    }

    @Test
    void captureMinuteSnapshots_doesNotThrowEvenWhenCacheFails() {
        when(liveCache.getFuturesPrice(Mockito.any(IndexType.class)))
                .thenThrow(new RuntimeException("cache offline"));
        buffer.captureMinuteSnapshots();   // should swallow per-index exceptions
        for (IndexType ix : IndexType.values()) {
            assertThat(buffer.snapshotCount(ix)).isZero();
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static OptionInstrument mockOption(IndexType ix, int strike, String type,
                                               double ltp, long oi, long oiChange) {
        OptionInstrument o = Mockito.mock(OptionInstrument.class);
        when(o.getIndexType()).thenReturn(ix);
        when(o.getStrikePrice()).thenReturn(strike);
        when(o.getOptionType()).thenReturn(type);
        when(o.getLastPrice()).thenReturn(ltp);
        when(o.getOpenInterest()).thenReturn(oi);
        when(o.getOiChange()).thenReturn(oiChange);
        return o;
    }
}
