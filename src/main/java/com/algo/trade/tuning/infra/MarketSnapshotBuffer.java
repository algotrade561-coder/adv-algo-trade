package com.algo.trade.tuning.infra;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Unified minute-cadence ring buffer of spot + ATM CE/PE LTP + ±5 strike OI per index.
 *
 * <p>Replaces both {@code OiMarketSnapshotBuffer} and {@code ShiftTrapChainSnapshotBuffer}.
 * The data model is the strict superset of both: every consumer that used the OI Momentum
 * version (spot + ATM LTP only) can read this; consumers that need the Shift Trap version
 * (with strike-level OI) get the {@link MarketSnapshot#strikes()} map.</p>
 *
 * <h2>Capture</h2>
 * <ul>
 *   <li>Live: a {@link Scheduled} task fires every 60s and snapshots every {@link IndexType}
 *       from {@link LiveInstrumentCache}.</li>
 *   <li>Warm-up: {@link SnapshotWarmupService} ingests today's archived chain snapshots
 *       on JVM start, so backfill works immediately after a mid-day restart.</li>
 * </ul>
 *
 * <h2>Retention</h2>
 * Sliding 3-hour window. Snapshots older than {@link #RETENTION} are dropped on each
 * ingest call. {@link ForwardCheckpointService} (Commit 6) only needs ~31 minutes; 3h
 * gives generous headroom for slow scheduled-task hiccups.
 *
 * <h2>Query API</h2>
 * <ul>
 *   <li>{@link #nearest nearest(index, target)} — closest snapshot by absolute time delta.</li>
 *   <li>{@link #floor floor(index, target)} — most recent snapshot at or before {@code target}.</li>
 *   <li>{@link #ceiling ceiling(index, target)} — earliest snapshot at or after {@code target}.</li>
 * </ul>
 * Forward-checkpoint reads use {@link #nearest} to get the closest available snapshot to
 * each target time (signal + 30s / 1m / 5m / 15m / 30m).
 *
 * <h2>Thread safety</h2>
 * Each index has its own {@link TreeMap} guarded by {@code synchronized (map)}.
 * Captures and reads only contend with themselves per index — never across indices.
 */
@Component
public class MarketSnapshotBuffer {

    private static final Logger log = LoggerFactory.getLogger(MarketSnapshotBuffer.class);
    /**
     * Retention window. 10 calendar days comfortably spans the worst realistic Indian
     * market non-trading stretch (Diwali Sat–Wed = 6 days, Christmas–New Year stretch
     * = 6 days) with ~4 days of margin for anomalies like an extended EC2 outage on
     * top. Memory cost is bounded: ~3 indices × 60 entries/h × 8h × 10d × ~200B
     * ≈ 2.8 MB at full saturation.
     */
    private static final Duration RETENTION = Duration.ofDays(10);
    private static final int STRIKE_RADIUS = 5;

    /** Per-strike OI summary captured for the ±5 strikes around ATM. */
    public record StrikeOi(long oiCe, long oiPe, long oiCeChange, long oiPeChange) {
        public static StrikeOi empty() {
            return new StrikeOi(0, 0, 0, 0);
        }
    }

    /** Immutable snapshot record stored in the ring buffer. */
    public record MarketSnapshot(
            Instant at,
            double spot,
            int atm,
            double atmCeLast,
            double atmPeLast,
            Map<Integer, StrikeOi> strikes
    ) {
        public StrikeOi strikeOi(int strike) {
            return strikes.getOrDefault(strike, StrikeOi.empty());
        }
    }

    private final LiveInstrumentCache liveInstrumentCache;
    private final Map<IndexType, NavigableMap<Instant, MarketSnapshot>> byIndex = new ConcurrentHashMap<>();

    public MarketSnapshotBuffer(LiveInstrumentCache liveInstrumentCache) {
        this.liveInstrumentCache = liveInstrumentCache;
    }

    @PostConstruct
    void init() {
        for (IndexType ix : IndexType.values()) {
            byIndex.put(ix, new TreeMap<>());
        }
    }

    /** Snapshot retention window — exposed for tests + warm-up cutoff computation. */
    public static Duration retention() {
        return RETENTION;
    }

    // ── Live capture (Scheduled) ──────────────────────────────────────────

    /**
     * Fires every 60s. Snapshots every index from {@link LiveInstrumentCache}. Failures
     * for one index do not prevent others from being captured.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 15_000)
    public void captureMinuteSnapshots() {
        Instant now = Instant.now();
        for (IndexType ix : IndexType.values()) {
            try {
                captureLive(ix, now);
            } catch (Exception ex) {
                log.debug("[MarketSnapshotBuffer] live capture failed for {}: {}", ix, ex.getMessage());
            }
        }
    }

    /** Builds a snapshot from {@link LiveInstrumentCache} and ingests it. */
    public void captureLive(IndexType indexType, Instant at) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) {
            return;
        }
        int atm = indexType.roundToATM(spot);
        double[] prem = atmPremiums(indexType, atm);
        Map<Integer, StrikeOi> strikes = strikeOiMap(indexType, atm);
        ingest(indexType, at, spot, atm, prem[0], prem[1], strikes);
    }

    // ── Public ingest (used by warm-up + tests) ──────────────────────────

    /**
     * Ingest a pre-built snapshot. Use from {@link SnapshotWarmupService} on JVM start
     * and from tests.
     */
    public void ingest(IndexType indexType, Instant at, double spot, int atm,
                       double atmCeLast, double atmPeLast,
                       Map<Integer, StrikeOi> strikes) {
        if (spot <= 0 || at == null) {
            return;
        }
        Objects.requireNonNull(indexType, "indexType");
        MarketSnapshot snap = new MarketSnapshot(at, spot, atm, atmCeLast, atmPeLast,
                strikes != null ? Map.copyOf(strikes) : Map.of());
        NavigableMap<Instant, MarketSnapshot> map =
                byIndex.computeIfAbsent(indexType, k -> new TreeMap<>());
        synchronized (map) {
            map.put(at, snap);
            // Cutoff is relative to the LATEST entry in the buffer — not the just-ingested
            // one. This lets warmup ingest older snapshots (e.g. Friday's data on Monday
            // morning, before any Monday live capture) without them being evicted by the
            // very ingest that adds them.
            Instant latest = map.lastKey();
            Instant cutoff = latest.minus(RETENTION);
            map.headMap(cutoff, true).clear();
        }
    }

    // ── Query API ─────────────────────────────────────────────────────────

    /** Closest snapshot by absolute time delta, or empty if the index has no data. */
    public Optional<MarketSnapshot> nearest(IndexType indexType, Instant target) {
        NavigableMap<Instant, MarketSnapshot> map = byIndex.get(indexType);
        if (map == null || map.isEmpty() || target == null) {
            return Optional.empty();
        }
        synchronized (map) {
            Map.Entry<Instant, MarketSnapshot> floor = map.floorEntry(target);
            Map.Entry<Instant, MarketSnapshot> ceil = map.ceilingEntry(target);
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

    /** Most recent snapshot at or before {@code target}. Empty if none. */
    public Optional<MarketSnapshot> floor(IndexType indexType, Instant target) {
        NavigableMap<Instant, MarketSnapshot> map = byIndex.get(indexType);
        if (map == null || map.isEmpty() || target == null) {
            return Optional.empty();
        }
        synchronized (map) {
            Map.Entry<Instant, MarketSnapshot> e = map.floorEntry(target);
            return e == null ? Optional.empty() : Optional.of(e.getValue());
        }
    }

    /** Earliest snapshot at or after {@code target}. Empty if none. */
    public Optional<MarketSnapshot> ceiling(IndexType indexType, Instant target) {
        NavigableMap<Instant, MarketSnapshot> map = byIndex.get(indexType);
        if (map == null || map.isEmpty() || target == null) {
            return Optional.empty();
        }
        synchronized (map) {
            Map.Entry<Instant, MarketSnapshot> e = map.ceilingEntry(target);
            return e == null ? Optional.empty() : Optional.of(e.getValue());
        }
    }

    /** Number of snapshots currently buffered for the given index. */
    public int snapshotCount(IndexType indexType) {
        NavigableMap<Instant, MarketSnapshot> map = byIndex.get(indexType);
        if (map == null) return 0;
        synchronized (map) {
            return map.size();
        }
    }

    /**
     * Returns all snapshots in the inclusive range {@code [from, to]} for the given
     * index. Used by {@link ForwardCheckpointService} to compute MFE/MAE over a
     * 30-minute window. Empty list if no snapshots match.
     */
    public java.util.List<MarketSnapshot> entriesBetween(IndexType indexType,
                                                         Instant from, Instant to) {
        NavigableMap<Instant, MarketSnapshot> map = byIndex.get(indexType);
        if (map == null || map.isEmpty() || from == null || to == null || from.isAfter(to)) {
            return java.util.List.of();
        }
        synchronized (map) {
            return java.util.List.copyOf(map.subMap(from, true, to, true).values());
        }
    }

    // ── Live build helpers ────────────────────────────────────────────────

    private double[] atmPremiums(IndexType indexType, int atm) {
        double ce = 0, pe = 0;
        for (OptionInstrument opt : safeAllOptions()) {
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

    private Map<Integer, StrikeOi> strikeOiMap(IndexType indexType, int atm) {
        Map<Integer, StrikeOi> map = new HashMap<>();
        int radius = STRIKE_RADIUS * indexType.strikeInterval();
        for (OptionInstrument opt : safeAllOptions()) {
            if (opt.getIndexType() != indexType) {
                continue;
            }
            int strike = opt.getStrikePrice();
            if (Math.abs(strike - atm) > radius) {
                continue;
            }
            StrikeOi cur = map.getOrDefault(strike, StrikeOi.empty());
            if ("CE".equals(opt.getOptionType())) {
                map.put(strike, new StrikeOi(opt.getOpenInterest(), cur.oiPe(),
                        opt.getOiChange(), cur.oiPeChange()));
            } else {
                map.put(strike, new StrikeOi(cur.oiCe(), opt.getOpenInterest(),
                        cur.oiCeChange(), opt.getOiChange()));
            }
        }
        return map;
    }

    private Collection<OptionInstrument> safeAllOptions() {
        try {
            return liveInstrumentCache.allOptions();
        } catch (Exception ex) {
            return java.util.List.of();
        }
    }
}
