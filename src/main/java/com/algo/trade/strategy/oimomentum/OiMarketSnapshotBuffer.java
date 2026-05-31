package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-memory ring buffer of spot + ATM option LTP per index for forward-return backfill.
 */
@Component
public class OiMarketSnapshotBuffer {

    private static final Logger log = LoggerFactory.getLogger(OiMarketSnapshotBuffer.class);
    private static final Duration RETENTION = Duration.ofHours(3);

    public record Snapshot(Instant at, double spot, int atm, double atmCeLast, double atmPeLast) {}

    private final LiveInstrumentCache liveInstrumentCache;
    private final Map<IndexType, NavigableMap<Instant, Snapshot>> byIndex = new ConcurrentHashMap<>();

    public OiMarketSnapshotBuffer(LiveInstrumentCache liveInstrumentCache) {
        this.liveInstrumentCache = liveInstrumentCache;
    }

    @PostConstruct
    void init() {
        for (IndexType ix : IndexType.values()) {
            byIndex.put(ix, new TreeMap<>());
        }
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 15_000)
    public void captureMinuteSnapshots() {
        Instant now = Instant.now();
        for (IndexType ix : IndexType.values()) {
            try {
                record(ix, now);
            } catch (Exception ex) {
                log.debug("[OiSnapshotBuffer] capture failed for {}: {}", ix, ex.getMessage());
            }
        }
    }

    public void record(IndexType indexType, Instant at) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) {
            return;
        }
        int atm = indexType.roundToATM(spot);
        double[] prem = atmPremiums(indexType, atm);
        ingest(indexType, at, spot, prem[0], prem[1]);
    }

    /** Archive warm-up — spot + ATM premiums without live cache. */
    public void ingest(IndexType indexType, Instant at, double spot, double atmCeLast, double atmPeLast) {
        if (spot <= 0 || at == null) {
            return;
        }
        Snapshot snap = new Snapshot(at, spot, indexType.roundToATM(spot), atmCeLast, atmPeLast);
        NavigableMap<Instant, Snapshot> map = byIndex.computeIfAbsent(indexType, k -> new TreeMap<>());
        synchronized (map) {
            map.put(at, snap);
            Instant cutoff = at.minus(RETENTION);
            map.headMap(cutoff, true).clear();
        }
    }

    public Optional<Snapshot> nearest(IndexType indexType, Instant target) {
        NavigableMap<Instant, Snapshot> map = byIndex.get(indexType);
        if (map == null || map.isEmpty()) {
            return Optional.empty();
        }
        synchronized (map) {
            Map.Entry<Instant, Snapshot> floor = map.floorEntry(target);
            Map.Entry<Instant, Snapshot> ceil = map.ceilingEntry(target);
            if (floor == null && ceil == null) {
                return Optional.empty();
            }
            if (floor == null) {
                return Optional.of(ceil.getValue());
            }
            if (ceil == null) {
                return Optional.of(floor.getValue());
            }
            long df = Math.abs(Duration.between(floor.getKey(), target).toMillis());
            long dc = Math.abs(Duration.between(ceil.getKey(), target).toMillis());
            return Optional.of(df <= dc ? floor.getValue() : ceil.getValue());
        }
    }

    private double[] atmPremiums(IndexType indexType, int atm) {
        double ce = 0;
        double pe = 0;
        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType || opt.getStrikePrice() != atm) {
                continue;
            }
            if ("CE".equals(opt.getOptionType())) {
                ce = opt.getLastPrice();
            } else {
                pe = opt.getLastPrice();
            }
        }
        return new double[]{ce, pe};
    }
}
