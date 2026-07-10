package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Detects early operator footprints via OI surge velocity — catches signals BEFORE
 * price reacts by measuring the rate-of-change of OI at ATM±3 strikes.
 *
 * <p>Logic:
 * <ol>
 *   <li>Samples total CE/PE OI for ATM±3 strikes every 30 seconds (piggybacks on
 *       the existing OI ring buffer ticks).</li>
 *   <li>Computes rolling 5-minute average OI change rate (baseline).</li>
 *   <li>Fires a SURGE signal when the latest 1-minute OI change exceeds the rolling
 *       average by {@code surgeMultiplier} (default 2.0×) — i.e., OI is building at
 *       double the normal rate.</li>
 *   <li>Direction: CE OI surging = bullish, PE OI surging = bearish.</li>
 * </ol>
 *
 * <p>This detector is additive — it provides a signal that the OIMomentumStrategy
 * can use as an alternate momentum source (like OPERATOR_OI_LED or PREMIUM_VELOCITY).
 * It does NOT modify any existing entry logic.</p>
 */
@Component
public class OiSurgeDetector {

    private static final Logger log = LoggerFactory.getLogger(OiSurgeDetector.class);

    /** Minimum absolute OI change to qualify as a surge (lowered for low-vol regimes). */
    private static final long MIN_OI_DELTA_ABS = 30_000L;
    /** Number of samples needed before we can compute a valid rolling average. */
    private static final int MIN_SAMPLES = 4; // 4 × 30s = 2 minutes (was 6 = 3 min)
    /** Rolling window size for average computation (10 × 30s = 5 minutes). */
    private static final int ROLLING_WINDOW = 10;
    /** Multiplier: surge fires when latest rate > rolling_avg × this. */
    private static final double SURGE_MULTIPLIER = 1.5; // was 2.0 — catches more subtle buildups

    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;

    /** Per-index OI snapshot history (CE total, PE total) at ATM±3. */
    private final Map<IndexType, Deque<OiSnapshot>> history = new ConcurrentHashMap<>();

    record OiSnapshot(long timestampMs, long totalCeOi, long totalPeOi) {}

    public record SurgeSignal(int direction, String reason, double surgeRatio, long oiDelta) {
        public static final SurgeSignal NONE = new SurgeSignal(0, "NONE", 0, 0);
        public boolean isPresent() { return direction != 0; }
    }

    public OiSurgeDetector(LiveInstrumentCache liveInstrumentCache,
                           ExpiryCalendar expiryCalendar) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    /**
     * Sample the current OI state for an index. Call every 30 seconds (from OIMomentumStrategy tick loop).
     */
    public void sample(IndexType indexType) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;

        int atm = indexType.roundToATM(spot);
        int interval = indexType.strikeInterval();
        LocalDate expiry = expiryCalendar.getCurrentExpiry(indexType);

        long totalCe = 0, totalPe = 0;
        for (int i = -3; i <= 3; i++) {
            int strike = atm + (i * interval);
            var ceOpt = liveInstrumentCache.getOption(indexType, strike, "CE", expiry);
            var peOpt = liveInstrumentCache.getOption(indexType, strike, "PE", expiry);
            if (ceOpt.isPresent()) totalCe += ceOpt.get().getOpenInterest();
            if (peOpt.isPresent()) totalPe += peOpt.get().getOpenInterest();
        }

        Deque<OiSnapshot> deque = history.computeIfAbsent(indexType, k -> new ConcurrentLinkedDeque<>());
        deque.addLast(new OiSnapshot(System.currentTimeMillis(), totalCe, totalPe));

        // Trim to retain only the last 20 samples (10 minutes at 30s intervals)
        while (deque.size() > 20) deque.pollFirst();
    }

    /**
     * Detect an OI surge signal. Returns direction (+1 bullish, -1 bearish, 0 none).
     *
     * <p>A surge is detected when the 1-minute OI change rate (2 most recent samples)
     * exceeds the 5-minute rolling average by {@code SURGE_MULTIPLIER}.</p>
     */
    public SurgeSignal detect(IndexType indexType) {
        Deque<OiSnapshot> deque = history.get(indexType);
        if (deque == null || deque.size() < MIN_SAMPLES) return SurgeSignal.NONE;

        // Recent rate: last 2 samples (~60s window)
        OiSnapshot[] arr = deque.toArray(new OiSnapshot[0]);
        int n = arr.length;
        OiSnapshot latest = arr[n - 1];
        OiSnapshot recent = arr[n - 2];

        long recentCeDelta = latest.totalCeOi - recent.totalCeOi;
        long recentPeDelta = latest.totalPeOi - recent.totalPeOi;

        // Rolling average: over the full window (excluding the last 2 samples)
        int windowEnd = Math.max(0, n - 2);
        int windowStart = Math.max(0, windowEnd - ROLLING_WINDOW);
        if (windowEnd - windowStart < 2) return SurgeSignal.NONE;

        long avgCeDelta = 0, avgPeDelta = 0;
        for (int i = windowStart + 1; i <= windowEnd; i++) {
            avgCeDelta += arr[i].totalCeOi - arr[i - 1].totalCeOi;
            avgPeDelta += arr[i].totalPeOi - arr[i - 1].totalPeOi;
        }
        int periods = windowEnd - windowStart;
        double avgCeRate = (double) avgCeDelta / periods;
        double avgPeRate = (double) avgPeDelta / periods;

        // Check CE surge (bullish: CE OI building = institutions buying calls for directional bet)
        if (recentCeDelta > MIN_OI_DELTA_ABS && avgCeRate > 0) {
            double ratio = recentCeDelta / avgCeRate;
            if (ratio >= SURGE_MULTIPLIER) {
                log.info("[OiSurge][{}] CE_SURGE: delta={} vs avgRate={} ratio={}x — bullish buildup",
                        indexType, recentCeDelta, String.format("%.0f", avgCeRate),
                        String.format("%.1f", ratio));
                return new SurgeSignal(1, "CE_OI_SURGE", ratio, recentCeDelta);
            }
        }

        // Check PE surge (bearish: PE OI building = institutions buying puts for protection/direction)
        if (recentPeDelta > MIN_OI_DELTA_ABS && avgPeRate > 0) {
            double ratio = recentPeDelta / avgPeRate;
            if (ratio >= SURGE_MULTIPLIER) {
                log.info("[OiSurge][{}] PE_SURGE: delta={} vs avgRate={} ratio={}x — bearish buildup",
                        indexType, recentPeDelta, String.format("%.0f", avgPeRate),
                        String.format("%.1f", ratio));
                return new SurgeSignal(-1, "PE_OI_SURGE", ratio, recentPeDelta);
            }
        }

        return SurgeSignal.NONE;
    }

    /** Reset history for an index (e.g., on daily reset). */
    public void reset(IndexType indexType) {
        history.remove(indexType);
    }

    /** Reset all history. */
    public void resetAll() {
        history.clear();
    }
}
