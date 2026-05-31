package com.algo.trade.tuning.infra;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Generic episode dedup. Collapses a stream of "same-thing-happening-repeatedly" ticks
 * into one episode row carrying {@code firstAt}, {@code lastAt}, {@code tickCount}, and
 * the first tick's payload.
 *
 * <h2>Three type parameters</h2>
 * <ul>
 *   <li>{@code S} — <b>stream key</b>. Only one episode is active per stream at any time.
 *       For OI Momentum reject dedup, this is {@code IndexType} — per-index streams.</li>
 *   <li>{@code K} — <b>dedup key</b>. Within a stream, ticks with the same {@code K} fold
 *       into the same episode; a different {@code K} forces a flush. For OI Momentum,
 *       this is the (index, reason) pair — when the reason changes, the prior episode
 *       closes.</li>
 *   <li>{@code T} — <b>payload</b>. The first tick's payload is preserved in the
 *       episode row (e.g. the diagnostics snapshot at the moment the episode started).</li>
 * </ul>
 *
 * <h2>Semantics</h2>
 * <ul>
 *   <li>{@link #record record(stream, key, payload, when)} on an empty stream → start
 *       a new episode (no flushes).</li>
 *   <li>{@code record} with the same {@code (stream, key)} within {@code window} →
 *       just tick (update {@code lastAt}, increment {@code tickCount}); no flush.</li>
 *   <li>{@code record} with the same {@code stream} but a different {@code key} → flush
 *       the prior episode immediately, start a new one.</li>
 *   <li>{@code record} with the same {@code (stream, key)} after the window elapsed →
 *       flush the prior episode, start a fresh one with the new tick.</li>
 *   <li>{@link #flushExpired flushExpired(now)} → emit and remove every episode whose
 *       {@code lastAt + window < now}.</li>
 *   <li>{@link #flushAll flushAll()} → emit and remove every active episode. Use at
 *       day rollover, JVM shutdown, etc.</li>
 * </ul>
 *
 * <h2>Thread safety</h2>
 * All mutating methods are {@code synchronized}. The critical section is small
 * (HashMap lookups + a few comparisons), so contention from one strategy's recorder
 * thread is negligible.
 *
 * <h2>History</h2>
 * Replaces {@code RejectEpisodeAggregator} (OI Momentum) and
 * {@code ShiftTrapEvalEpisodeAggregator} (OI Shift Trap). Both will be deleted in
 * Phase 6 once their strategies migrate to this unified version.
 */
public final class EpisodeAggregator<S, K, T> {

    /** Episode row emitted on flush. */
    public record EpisodeRow<S, K, T>(
            S streamKey,
            K dedupKey,
            Instant firstAt,
            Instant lastAt,
            int tickCount,
            T firstPayload
    ) {}

    private static final class OpenEpisode<K, T> {
        final K dedupKey;
        final Instant firstAt;
        final T firstPayload;
        Instant lastAt;
        int tickCount;

        OpenEpisode(K dedupKey, Instant firstAt, T firstPayload) {
            this.dedupKey = dedupKey;
            this.firstAt = firstAt;
            this.firstPayload = firstPayload;
            this.lastAt = firstAt;
            this.tickCount = 1;
        }

        void tick(Instant at) {
            lastAt = at;
            tickCount++;
        }
    }

    private final Duration window;
    private final Map<S, OpenEpisode<K, T>> active = new HashMap<>();

    public EpisodeAggregator(int windowSeconds) {
        if (windowSeconds < 1) {
            throw new IllegalArgumentException("windowSeconds must be >= 1, got " + windowSeconds);
        }
        this.window = Duration.ofSeconds(windowSeconds);
    }

    public EpisodeAggregator(Duration window) {
        Objects.requireNonNull(window, "window");
        if (window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("window must be positive, got " + window);
        }
        this.window = window;
    }

    /**
     * Records one tick. Returns any episodes that just flushed (typically 0 or 1
     * — the prior episode for this stream if its key changed or its window elapsed).
     */
    public synchronized List<EpisodeRow<S, K, T>> record(S streamKey, K dedupKey,
                                                          T payload, Instant when) {
        Objects.requireNonNull(streamKey, "streamKey");
        Objects.requireNonNull(dedupKey, "dedupKey");
        Objects.requireNonNull(when, "when");

        List<EpisodeRow<S, K, T>> flushed = new ArrayList<>();
        OpenEpisode<K, T> current = active.get(streamKey);

        if (current == null) {
            active.put(streamKey, new OpenEpisode<>(dedupKey, when, payload));
            return flushed;
        }

        boolean sameKey = Objects.equals(current.dedupKey, dedupKey);
        boolean withinWindow = Duration.between(current.lastAt, when).compareTo(window) <= 0;

        if (sameKey && withinWindow) {
            current.tick(when);
            return flushed;
        }

        // Either the dedup key changed or the prior episode's window has elapsed —
        // emit the prior episode and start a fresh one.
        flushed.add(toRow(streamKey, current));
        active.put(streamKey, new OpenEpisode<>(dedupKey, when, payload));
        return flushed;
    }

    /**
     * Emits and removes every active episode whose {@code lastAt + window < now}.
     * Intended for periodic background sweeps (e.g. every minute) so stale episodes
     * don't sit indefinitely waiting for a new tick that never comes.
     */
    public synchronized List<EpisodeRow<S, K, T>> flushExpired(Instant now) {
        Objects.requireNonNull(now, "now");
        List<EpisodeRow<S, K, T>> flushed = new ArrayList<>();
        active.entrySet().removeIf(entry -> {
            if (Duration.between(entry.getValue().lastAt, now).compareTo(window) > 0) {
                flushed.add(toRow(entry.getKey(), entry.getValue()));
                return true;
            }
            return false;
        });
        return flushed;
    }

    /**
     * Emits and removes every active episode regardless of window. Use at day
     * rollover and JVM shutdown.
     */
    public synchronized List<EpisodeRow<S, K, T>> flushAll() {
        List<EpisodeRow<S, K, T>> flushed = new ArrayList<>(active.size());
        active.forEach((s, ep) -> flushed.add(toRow(s, ep)));
        active.clear();
        return flushed;
    }

    /** Current count of in-flight (not yet flushed) episodes — useful for tests + dashboards. */
    public synchronized int openEpisodeCount() {
        return active.size();
    }

    public Duration window() {
        return window;
    }

    private EpisodeRow<S, K, T> toRow(S streamKey, OpenEpisode<K, T> ep) {
        return new EpisodeRow<>(streamKey, ep.dedupKey, ep.firstAt, ep.lastAt,
                ep.tickCount, ep.firstPayload);
    }
}
