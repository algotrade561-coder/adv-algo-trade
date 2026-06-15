package com.algo.trade.marketdata;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tick-based Volume Profile (VPVR) service.
 *
 * Accumulates real tick volume at each price level (0.05% buckets) per instrument token.
 * Resets daily at market open. Provides POC, VAH, VAL for strategy use.
 *
 * POC  = price level with highest cumulative tick volume
 * VAH  = top of the 70% value area (above POC)
 * VAL  = bottom of the 70% value area (below POC)
 */
@Service
public class TickVolumeProfileService {

    private static final Logger log = LoggerFactory.getLogger(TickVolumeProfileService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Bucket size as a fraction of price (0.05% = fine enough for NIFTY/BANKNIFTY). */
    private static final double BUCKET_PCT = 0.0005;
    /** Value area covers 70% of total volume. */
    private static final double VALUE_AREA_PCT = 0.70;
    /** Max buckets per token to cap memory. */
    private static final int MAX_BUCKETS = 2000;

    /** token → (bucketIndex → cumulative volume) */
    private final Map<Long, long[]> profiles = new ConcurrentHashMap<>();
    /** token → reference price used to anchor bucket indices */
    private final Map<Long, Double> anchorPrices = new ConcurrentHashMap<>();
    /** token → total volume accumulated today */
    private final Map<Long, AtomicLong> totalVolume = new ConcurrentHashMap<>();
    /** token → last seen cumulative volume from Kite (to compute delta) */
    private final Map<Long, Long> lastCumVolume = new ConcurrentHashMap<>();

    private volatile LocalDate currentDate = LocalDate.now(IST);

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Feed a tick into the volume profile.
     * Called by KiteWebSocketClient for every underlying index tick.
     *
     * @param token       instrument token
     * @param price       last traded price
     * @param cumVolume   cumulative daily volume from Kite (not delta)
     */
    public void onTick(long token, double price, long cumVolume) {
        if (price <= 0 || cumVolume <= 0) return;
        resetIfNewDay();

        // Compute delta from last seen cumulative volume
        long prev = lastCumVolume.getOrDefault(token, 0L);
        long delta = cumVolume - prev;
        if (delta <= 0) return; // no new volume or out-of-order tick
        lastCumVolume.put(token, cumVolume);

        double anchor = anchorPrices.computeIfAbsent(token, t -> price);
        int bucket = priceToBucket(price, anchor);
        if (bucket < 0 || bucket >= MAX_BUCKETS) return;

        long[] profile = profiles.computeIfAbsent(token, t -> new long[MAX_BUCKETS]);
        profile[bucket] += delta;
        totalVolume.computeIfAbsent(token, t -> new AtomicLong()).addAndGet(delta);
    }

    /**
     * Get the Volume Profile result for a token.
     * Returns null if insufficient data (< 100 ticks worth of volume).
     */
    public VolumeProfile getProfile(long token) {
        long[] profile = profiles.get(token);
        Double anchor = anchorPrices.get(token);
        AtomicLong total = totalVolume.get(token);
        if (profile == null || anchor == null || total == null || total.get() < 100) return null;

        int pocBucket = findPocBucket(profile);
        double poc = bucketToPrice(pocBucket, anchor);
        int[] vaBuckets = computeValueAreaBuckets(profile, pocBucket, total.get());
        double val = bucketToPrice(vaBuckets[0], anchor);
        double vah = bucketToPrice(vaBuckets[1], anchor);

        return new VolumeProfile(poc, val, vah, total.get());
    }

    /** Reset all profiles (e.g. on market open or reconnect). */
    public void reset() {
        profiles.clear();
        anchorPrices.clear();
        totalVolume.clear();
        lastCumVolume.clear();
        currentDate = LocalDate.now(IST);
        log.info("[VPVR] Profiles reset");
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(currentDate)) {
            reset();
            currentDate = today;
        }
    }

    private int priceToBucket(double price, double anchor) {
        double bucketSize = anchor * BUCKET_PCT;
        if (bucketSize <= 0) return -1;
        int bucket = (int) ((price - anchor) / bucketSize) + MAX_BUCKETS / 2;
        return Math.max(0, Math.min(MAX_BUCKETS - 1, bucket));
    }

    private double bucketToPrice(int bucket, double anchor) {
        double bucketSize = anchor * BUCKET_PCT;
        return anchor + (bucket - MAX_BUCKETS / 2.0) * bucketSize;
    }

    private int findPocBucket(long[] profile) {
        int poc = 0;
        for (int i = 1; i < MAX_BUCKETS; i++) {
            if (profile[i] > profile[poc]) poc = i;
        }
        return poc;
    }

    /**
     * Expand outward from POC until 70% of total volume is covered.
     * @return [loBucket, hiBucket] indices
     */
    private int[] computeValueAreaBuckets(long[] profile, int pocBucket, long total) {
        long target = (long) (total * VALUE_AREA_PCT);
        long accumulated = profile[pocBucket];
        int lo = pocBucket, hi = pocBucket;

        while (accumulated < target && (lo > 0 || hi < MAX_BUCKETS - 1)) {
            long addLo = lo > 0 ? profile[lo - 1] : 0;
            long addHi = hi < MAX_BUCKETS - 1 ? profile[hi + 1] : 0;
            if (addHi >= addLo) { hi++; accumulated += addHi; }
            else                { lo--; accumulated += addLo; }
        }
        return new int[]{lo, hi};
    }

    // ── Result type ───────────────────────────────────────────────────────────

    /**
     * Volume Profile snapshot for a single instrument.
     *
     * @param poc   Point of Control — price with highest tick volume
     * @param val   Value Area Low  — bottom of 70% volume zone
     * @param vah   Value Area High — top of 70% volume zone
     * @param totalVolume total tick volume accumulated today
     */
    public record VolumeProfile(double poc, double val, double vah, long totalVolume) {

        /** Is the given price inside the value area (between VAL and VAH)? */
        public boolean inValueArea(double price) {
            return price >= val && price <= vah;
        }

        /** Distance from POC as a percentage of POC price. */
        public double distFromPocPct(double price) {
            return poc > 0 ? Math.abs(price - poc) / poc * 100 : 0;
        }
    }
}
