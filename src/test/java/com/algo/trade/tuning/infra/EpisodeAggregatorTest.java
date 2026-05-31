package com.algo.trade.tuning.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.tuning.infra.EpisodeAggregator.EpisodeRow;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Phase 1, Commit 4 — episode aggregator. Verifies the three transition rules
 * (same key within window → tick, key change → flush prior, window expiry → flush
 * prior), parallel streams, explicit flushes, and concurrent record correctness.
 */
class EpisodeAggregatorTest {

    private static final Instant T0 = Instant.parse("2026-06-01T09:30:00Z");

    @Test
    void singleTickStartsAnEpisodeButFlushesNothing() {
        EpisodeAggregator<IndexType, String, String> agg = new EpisodeAggregator<>(60);

        List<EpisodeRow<IndexType, String, String>> flushed =
                agg.record(IndexType.NIFTY, "low_bias", "diag-1", T0);

        assertThat(flushed).isEmpty();
        assertThat(agg.openEpisodeCount()).isEqualTo(1);
    }

    @Test
    void sameStreamSameKeyWithinWindow_collapsesIntoOneEpisode() {
        EpisodeAggregator<IndexType, String, String> agg = new EpisodeAggregator<>(60);

        for (int i = 0; i < 1000; i++) {
            agg.record(IndexType.NIFTY, "low_bias", "diag-" + i, T0.plusSeconds(i % 30));
        }
        List<EpisodeRow<IndexType, String, String>> rows = agg.flushAll();

        assertThat(rows).hasSize(1);
        EpisodeRow<IndexType, String, String> row = rows.get(0);
        assertThat(row.streamKey()).isEqualTo(IndexType.NIFTY);
        assertThat(row.dedupKey()).isEqualTo("low_bias");
        assertThat(row.tickCount()).isEqualTo(1000);
        assertThat(row.firstPayload()).isEqualTo("diag-0");           // first tick's payload
        assertThat(row.firstAt()).isEqualTo(T0);
        assertThat(row.lastAt()).isEqualTo(T0.plusSeconds(29));
    }

    @Test
    void keyChangeWithinStream_flushesPriorImmediately() {
        EpisodeAggregator<IndexType, String, String> agg = new EpisodeAggregator<>(60);
        agg.record(IndexType.NIFTY, "low_bias", "diag-A", T0);
        agg.record(IndexType.NIFTY, "low_bias", "diag-A2", T0.plusSeconds(5));

        // New key on same stream — must flush the "low_bias" episode.
        List<EpisodeRow<IndexType, String, String>> flushed =
                agg.record(IndexType.NIFTY, "midday_gate", "diag-B", T0.plusSeconds(10));

        assertThat(flushed).hasSize(1);
        EpisodeRow<IndexType, String, String> row = flushed.get(0);
        assertThat(row.dedupKey()).isEqualTo("low_bias");
        assertThat(row.tickCount()).isEqualTo(2);
        assertThat(row.firstAt()).isEqualTo(T0);
        assertThat(row.lastAt()).isEqualTo(T0.plusSeconds(5));

        // The new "midday_gate" episode is now open.
        assertThat(agg.openEpisodeCount()).isEqualTo(1);
    }

    @Test
    void parallelStreams_eachHaveOwnEpisode() {
        EpisodeAggregator<IndexType, String, String> agg = new EpisodeAggregator<>(60);

        agg.record(IndexType.NIFTY, "low_bias", "n", T0);
        agg.record(IndexType.SENSEX, "low_bias", "s", T0.plusSeconds(1));
        agg.record(IndexType.BANKNIFTY, "low_bias", "b", T0.plusSeconds(2));

        // Three parallel streams, none has flushed.
        assertThat(agg.openEpisodeCount()).isEqualTo(3);

        // Recording on NIFTY doesn't affect SENSEX or BANKNIFTY.
        agg.record(IndexType.NIFTY, "low_bias", "n2", T0.plusSeconds(3));
        assertThat(agg.openEpisodeCount()).isEqualTo(3);

        // Changing only NIFTY's key flushes only NIFTY.
        List<EpisodeRow<IndexType, String, String>> flushed =
                agg.record(IndexType.NIFTY, "different", "n3", T0.plusSeconds(4));
        assertThat(flushed).hasSize(1);
        assertThat(flushed.get(0).streamKey()).isEqualTo(IndexType.NIFTY);
        assertThat(agg.openEpisodeCount()).isEqualTo(3);   // NIFTY still has new episode
    }

    @Test
    void windowExpiryOnSameKey_flushesAndStartsNewEpisode() {
        EpisodeAggregator<IndexType, String, String> agg = new EpisodeAggregator<>(60);

        agg.record(IndexType.NIFTY, "low_bias", "diag-A", T0);
        agg.record(IndexType.NIFTY, "low_bias", "diag-A2", T0.plusSeconds(30));

        // Same key but 90s later — past the 60s window — must flush prior.
        List<EpisodeRow<IndexType, String, String>> flushed =
                agg.record(IndexType.NIFTY, "low_bias", "diag-A3", T0.plusSeconds(120));

        assertThat(flushed).hasSize(1);
        assertThat(flushed.get(0).tickCount()).isEqualTo(2);
        assertThat(flushed.get(0).lastAt()).isEqualTo(T0.plusSeconds(30));
        assertThat(agg.openEpisodeCount()).isEqualTo(1);   // new episode for the third tick
    }

    @Test
    void flushExpiredSweepsAllStaleStreams() {
        EpisodeAggregator<IndexType, String, String> agg = new EpisodeAggregator<>(60);

        agg.record(IndexType.NIFTY, "k", "n", T0);
        agg.record(IndexType.SENSEX, "k", "s", T0.plusSeconds(30));
        agg.record(IndexType.BANKNIFTY, "k", "b", T0.plusSeconds(50));

        // At T0 + 100s: NIFTY (lastAt=T0) and SENSEX (lastAt=T0+30) are stale; BANKNIFTY isn't.
        List<EpisodeRow<IndexType, String, String>> flushed =
                agg.flushExpired(T0.plusSeconds(100));

        assertThat(flushed)
                .extracting(EpisodeRow::streamKey)
                .containsExactlyInAnyOrder(IndexType.NIFTY, IndexType.SENSEX);
        assertThat(agg.openEpisodeCount()).isEqualTo(1);
    }

    @Test
    void flushAllEmptiesAggregator() {
        EpisodeAggregator<IndexType, String, String> agg = new EpisodeAggregator<>(60);
        agg.record(IndexType.NIFTY, "k", "n", T0);
        agg.record(IndexType.SENSEX, "k", "s", T0);

        List<EpisodeRow<IndexType, String, String>> rows = agg.flushAll();

        assertThat(rows).hasSize(2);
        assertThat(agg.openEpisodeCount()).isZero();
        // flushAll on empty aggregator returns empty.
        assertThat(agg.flushAll()).isEmpty();
    }

    @Test
    void durationConstructorWorks() {
        EpisodeAggregator<IndexType, String, String> agg =
                new EpisodeAggregator<>(Duration.ofMinutes(2));
        assertThat(agg.window()).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    void rejectsInvalidWindow() {
        assertThatThrownBy(() -> new EpisodeAggregator<IndexType, String, String>(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpisodeAggregator<IndexType, String, String>(-1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EpisodeAggregator<IndexType, String, String>(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentRecord_preservesTickCount() throws Exception {
        EpisodeAggregator<IndexType, String, String> agg = new EpisodeAggregator<>(60);

        int threads = 8;
        int ticksPerThread = 1_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger spuriousFlushes = new AtomicInteger();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < ticksPerThread; i++) {
                        List<EpisodeRow<IndexType, String, String>> f =
                                agg.record(IndexType.NIFTY, "same_key", "p", T0.plusMillis(i));
                        if (!f.isEmpty()) {
                            spuriousFlushes.incrementAndGet();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // All threads write same (stream, key) within the window — no episode should
        // have been flushed prematurely.
        assertThat(spuriousFlushes.get()).isZero();

        List<EpisodeRow<IndexType, String, String>> rows = agg.flushAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).tickCount()).isEqualTo(threads * ticksPerThread);
    }
}
