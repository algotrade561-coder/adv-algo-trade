package com.algo.trade.strategy.oishifttrap;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Groups consecutive eval samples of the same (underlying, primaryBlocker) into one CSV episode row.
 */
final class ShiftTrapEvalEpisodeAggregator {

    record EpisodeKey(String underlying, String primaryBlocker) {}

    record EpisodeRow(
            Instant firstAt,
            Instant lastAt,
            int tickCount,
            OiShiftTrapDiagnostics firstDiag,
            OiShiftTrapDiagnostics lastDiag
    ) {}

    private static final class OpenEpisode {
        Instant firstAt;
        Instant lastAt;
        int tickCount;
        OiShiftTrapDiagnostics firstDiag;
        OiShiftTrapDiagnostics lastDiag;

        OpenEpisode(Instant at, OiShiftTrapDiagnostics diag) {
            this.firstAt = at;
            this.lastAt = at;
            this.tickCount = 1;
            this.firstDiag = diag;
            this.lastDiag = diag;
        }

        void tick(Instant at, OiShiftTrapDiagnostics diag) {
            lastAt = at;
            tickCount++;
            lastDiag = diag;
        }
    }

    private final Map<EpisodeKey, OpenEpisode> open = new ConcurrentHashMap<>();
    private final int windowSeconds;

    ShiftTrapEvalEpisodeAggregator(int windowSeconds) {
        this.windowSeconds = Math.max(1, windowSeconds);
    }

    synchronized List<EpisodeRow> record(OiShiftTrapDiagnostics diag, Instant now) {
        List<EpisodeRow> flushed = new ArrayList<>();
        if (diag == null || diag.underlying() == null || diag.underlying().isBlank()) {
            return flushed;
        }
        String blocker = ShiftTrapBlockerNormalizer.normalize(diag.primaryBlocker());
        EpisodeKey key = new EpisodeKey(diag.underlying(), blocker);

        open.entrySet().removeIf(e -> {
            EpisodeKey k = e.getKey();
            if (k.underlying().equals(diag.underlying()) && !k.equals(key)) {
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
            open.put(key, new OpenEpisode(now, diag));
            return flushed;
        }
        if (Duration.between(current.lastAt, now).getSeconds() > windowSeconds) {
            flushed.add(toRow(key, current));
            open.put(key, new OpenEpisode(now, diag));
            return flushed;
        }
        current.tick(now, diag);
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

    private static EpisodeRow toRow(EpisodeKey key, OpenEpisode ep) {
        return new EpisodeRow(ep.firstAt, ep.lastAt, ep.tickCount, ep.firstDiag, ep.lastDiag);
    }
}
