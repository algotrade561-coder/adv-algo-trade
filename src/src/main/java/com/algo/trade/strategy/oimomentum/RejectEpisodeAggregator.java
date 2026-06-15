package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groups consecutive rejects of the same (index, reason) into one CSV episode row.
 */
final class RejectEpisodeAggregator {

    record EpisodeKey(IndexType indexType, String rejectReason) {}

    record EpisodeRow(
            Instant firstAt,
            Instant lastAt,
            int tickCount,
            IndexType indexType,
            String rejectReason,
            OiMomentumEntryDiagnostics diagnostics
    ) {}

    private static final class OpenEpisode {
        Instant firstAt;
        Instant lastAt;
        int tickCount;
        OiMomentumEntryDiagnostics diagnostics;

        OpenEpisode(Instant at, OiMomentumEntryDiagnostics diagnostics) {
            this.firstAt = at;
            this.lastAt = at;
            this.tickCount = 1;
            this.diagnostics = diagnostics;
        }

        void tick(Instant at) {
            lastAt = at;
            tickCount++;
        }
    }

    private final Map<EpisodeKey, OpenEpisode> open = new ConcurrentHashMap<>();
    private final int windowSeconds;

    RejectEpisodeAggregator(int windowSeconds) {
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    /**
     * Record a reject tick. Returns rows to flush when the prior episode closes.
     */
    synchronized List<EpisodeRow> record(IndexType indexType, String rejectReason,
                                         OiMomentumEntryDiagnostics diagnostics, Instant now) {
        List<EpisodeRow> flushed = new ArrayList<>();
        EpisodeKey key = new EpisodeKey(indexType, rejectReason);

        open.entrySet().removeIf(e -> {
            EpisodeKey k = e.getKey();
            if (k.indexType() == indexType && !k.equals(key)) {
                flushed.add(toRow(k, e.getValue()));
                return true;
            }
            return false;
        });

        for (var entry : new ArrayList<>(open.entrySet())) {
            EpisodeKey k = entry.getKey();
            OpenEpisode ep = entry.getValue();
            if (k.equals(key)) {
                continue;
            }
            if (Duration.between(ep.lastAt, now).getSeconds() > windowSeconds) {
                flushed.add(toRow(k, ep));
                open.remove(k);
            }
        }
        OpenEpisode current = open.get(key);
        if (current == null) {
            open.put(key, new OpenEpisode(now, diagnostics));
            return flushed;
        }
        if (Duration.between(current.lastAt, now).getSeconds() > windowSeconds) {
            flushed.add(toRow(key, current));
            open.put(key, new OpenEpisode(now, diagnostics));
            return flushed;
        }
        current.tick(now);
        return flushed;
    }

    synchronized List<EpisodeRow> flushExpired(Instant now) {
        List<EpisodeRow> flushed = new ArrayList<>();
        open.entrySet().removeIf(e -> {
            if (Duration.between(e.getValue().lastAt, now).getSeconds() > windowSeconds) {
                flushed.add(toRow(e.getKey(), e.getValue()));
                return true;
            }
            return false;
        });
        return flushed;
    }

    synchronized List<EpisodeRow> flushAll() {
        List<EpisodeRow> flushed = new ArrayList<>();
        for (var e : open.entrySet()) {
            flushed.add(toRow(e.getKey(), e.getValue()));
        }
        open.clear();
        return flushed;
    }

    private static EpisodeRow toRow(EpisodeKey key, OpenEpisode ep) {
        return new EpisodeRow(ep.firstAt, ep.lastAt, ep.tickCount, key.indexType(),
                key.rejectReason(), ep.diagnostics);
    }
}
