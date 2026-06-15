package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
 * Minute ring buffer of spot, ATM premiums, and ±5 strike OI for forward checkpoint backfill.
 */
@Component
public class ShiftTrapChainSnapshotBuffer {

    private static final Logger log = LoggerFactory.getLogger(ShiftTrapChainSnapshotBuffer.class);
    private static final Duration RETENTION = Duration.ofHours(3);
    private static final int STRIKE_RADIUS = 5;

    static Duration retention() {
        return RETENTION;
    }

    public record StrikeOi(long oiCe, long oiPe, long oiCeChange, long oiPeChange) {}

    public record ChainSnapshot(
            Instant at,
            double spot,
            int atm,
            double atmCeLast,
            double atmPeLast,
            Map<Integer, StrikeOi> strikes
    ) {
        public StrikeOi strikeOi(int strike) {
            return strikes.getOrDefault(strike, new StrikeOi(0, 0, 0, 0));
        }
    }

    private final LiveInstrumentCache liveInstrumentCache;
    private final Map<IndexType, NavigableMap<Instant, ChainSnapshot>> byIndex = new ConcurrentHashMap<>();

    public ShiftTrapChainSnapshotBuffer(LiveInstrumentCache liveInstrumentCache) {
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
                log.debug("[ShiftTrapSnapshot] capture failed for {}: {}", ix, ex.getMessage());
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
        Map<Integer, StrikeOi> strikes = strikeOiMap(indexType, atm);
        ingest(indexType, at, spot, atm, prem[0], prem[1], strikes);
    }

    /** Archive warm-up — same shape as live {@link #record}. */
    public void ingest(IndexType indexType, Instant at, double spot, int atm,
                       double atmCeLast, double atmPeLast, Map<Integer, StrikeOi> strikes) {
        if (spot <= 0 || at == null) {
            return;
        }
        ChainSnapshot snap = new ChainSnapshot(at, spot, atm, atmCeLast, atmPeLast,
                strikes != null ? strikes : Map.of());
        NavigableMap<Instant, ChainSnapshot> map = byIndex.computeIfAbsent(indexType, k -> new TreeMap<>());
        synchronized (map) {
            map.put(at, snap);
            Instant cutoff = at.minus(RETENTION);
            map.headMap(cutoff, true).clear();
        }
    }

    public Optional<ChainSnapshot> nearest(IndexType indexType, Instant target) {
        NavigableMap<Instant, ChainSnapshot> map = byIndex.get(indexType);
        if (map == null || map.isEmpty()) {
            return Optional.empty();
        }
        synchronized (map) {
            Map.Entry<Instant, ChainSnapshot> floor = map.floorEntry(target);
            Map.Entry<Instant, ChainSnapshot> ceil = map.ceilingEntry(target);
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

    private Map<Integer, StrikeOi> strikeOiMap(IndexType indexType, int atm) {
        Map<Integer, StrikeOi> map = new HashMap<>();
        for (OptionInstrument opt : liveInstrumentCache.allOptions()) {
            if (opt.getIndexType() != indexType) {
                continue;
            }
            int strike = opt.getStrikePrice();
            if (Math.abs(strike - atm) > STRIKE_RADIUS * indexType.strikeInterval()) {
                continue;
            }
            StrikeOi cur = map.getOrDefault(strike, new StrikeOi(0, 0, 0, 0));
            if ("CE".equals(opt.getOptionType())) {
                map.put(strike, new StrikeOi(opt.getOpenInterest(), cur.oiPe(), opt.getOiChange(), cur.oiPeChange()));
            } else {
                map.put(strike, new StrikeOi(cur.oiCe(), opt.getOpenInterest(), cur.oiCeChange(), opt.getOiChange()));
            }
        }
        return map;
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
