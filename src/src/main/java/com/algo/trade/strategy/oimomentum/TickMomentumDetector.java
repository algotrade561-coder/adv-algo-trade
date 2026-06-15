package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Tick-level momentum detector — maintains rolling 30-min high/low per index
 * and detects breakouts, large candles, and velocity expansion.
 *
 * Updated every second from OIMomentumStrategy's execution loop.
 * Lock-free, thread-safe via ConcurrentLinkedDeque.
 */
@Component
public class TickMomentumDetector {

    private final LiveInstrumentCache liveInstrumentCache;

    // Rolling price samples: timestamp → price (kept for 30 minutes)
    private final Map<IndexType, Deque<PriceSample>> priceHistory = new ConcurrentHashMap<>();
    private static final long WINDOW_MS = 30 * 60 * 1000L; // 30 minutes

    // Longer-retention history dedicated to sustained-drift measurement. Kept
    // deliberately separate from the 30-min priceHistory so the existing 30-min
    // consumers (detect, detectSpike, getRolling30Min*) are completely unaffected.
    // 65 min of retention so a 60-min endpoint lookback always has a boundary sample.
    private final Map<IndexType, Deque<PriceSample>> driftHistory = new ConcurrentHashMap<>();
    private static final long DRIFT_WINDOW_MS = 65 * 60 * 1000L; // 65 minutes

    // Spike detection: 10-minute window for event spike
    private static final long SPIKE_WINDOW_MS = 10 * 60 * 1000L;

    public TickMomentumDetector(LiveInstrumentCache liveInstrumentCache) {
        this.liveInstrumentCache = liveInstrumentCache;
    }

    /**
     * Record current spot price. Call every 1 second.
     */
    public void tick(IndexType indexType) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return;
        Deque<PriceSample> history = priceHistory.computeIfAbsent(indexType, k -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        history.addLast(new PriceSample(now, spot));
        // Evict samples older than 30 minutes
        while (!history.isEmpty() && (now - history.peekFirst().timestamp) > WINDOW_MS) {
            history.pollFirst();
        }

        // Mirror the same sample into the longer drift-history window. Isolated
        // retention (65 min) so sustained-drift can look back a full hour without
        // perturbing the 30-min range/breakout logic above.
        Deque<PriceSample> drift = driftHistory.computeIfAbsent(indexType, k -> new ConcurrentLinkedDeque<>());
        drift.addLast(new PriceSample(now, spot));
        while (!drift.isEmpty() && (now - drift.peekFirst().timestamp) > DRIFT_WINDOW_MS) {
            drift.pollFirst();
        }
    }

    /**
     * Get rolling 30-minute high.
     */
    public double getRolling30MinHigh(IndexType indexType) {
        Deque<PriceSample> history = priceHistory.get(indexType);
        if (history == null || history.isEmpty()) return 0;
        return history.stream().mapToDouble(s -> s.price).max().orElse(0);
    }

    /**
     * Get rolling 30-minute low.
     */
    public double getRolling30MinLow(IndexType indexType) {
        Deque<PriceSample> history = priceHistory.get(indexType);
        if (history == null || history.isEmpty()) return 0;
        return history.stream().mapToDouble(s -> s.price).min().orElse(0);
    }

    /**
     * Detect momentum signal. Returns direction: 1 = bullish, -1 = bearish, 0 = none.
     */
    public MomentumSignal detect(IndexType indexType, double momentumThresholdPct) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return MomentumSignal.NONE;

        Deque<PriceSample> history = priceHistory.get(indexType);
        if (history == null || history.size() < 300) return MomentumSignal.NONE; // 5-min warm-up (300 samples at 1/sec)

        long now = System.currentTimeMillis();
        long fiveSecsAgo = now - 5_000L;

        // Compute high/low EXCLUDING the latest 5 seconds (by timestamp, not count)
        double high30m = 0, low30m = Double.MAX_VALUE;
        for (PriceSample sample : history) {
            if (sample.timestamp > fiveSecsAgo) break; // Skip recent 5 seconds
            if (sample.price > high30m) high30m = sample.price;
            if (sample.price < low30m) low30m = sample.price;
        }
        if (high30m <= 0 || low30m == Double.MAX_VALUE) return MomentumSignal.NONE;

        // Breakout above 30-min high (excluding recent ticks)
        if (spot > high30m) {
            double breakoutPct = (spot - high30m) / high30m * 100;
            if (breakoutPct > 0.02) {
                return new MomentumSignal(1, "30M_HIGH_BREAK", breakoutPct, spot);
            }
        }

        // Breakdown below 30-min low (excluding recent ticks)
        if (spot < low30m) {
            double breakdownPct = (low30m - spot) / low30m * 100;
            if (breakdownPct > 0.02) {
                return new MomentumSignal(-1, "30M_LOW_BREAK", breakdownPct, spot);
            }
        }

        // Large candle: check price change over last ~5 seconds (by timestamp)
        PriceSample fiveSecSample = null;
        for (PriceSample sample : history) {
            if (sample.timestamp <= fiveSecsAgo) {
                fiveSecSample = sample; // Keep updating — last one before cutoff is closest
            } else {
                break;
            }
        }
        if (fiveSecSample != null) {
            double movePct = (spot - fiveSecSample.price) / fiveSecSample.price * 100;
            if (Math.abs(movePct) >= momentumThresholdPct) {
                int direction = movePct > 0 ? 1 : -1;
                return new MomentumSignal(direction, "LARGE_MOVE", Math.abs(movePct), spot);
            }
        }

        return MomentumSignal.NONE;
    }

    /**
     * Detect event spike: index moved > threshold% in 10 minutes.
     */
    public MomentumSignal detectSpike(IndexType indexType, double spikeThresholdPct) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return MomentumSignal.NONE;

        Deque<PriceSample> history = priceHistory.get(indexType);
        if (history == null || history.isEmpty()) return MomentumSignal.NONE;

        long now = System.currentTimeMillis();
        // Find price 10 minutes ago
        double price10mAgo = 0;
        for (PriceSample sample : history) {
            if ((now - sample.timestamp) >= SPIKE_WINDOW_MS - 30_000
                    && (now - sample.timestamp) <= SPIKE_WINDOW_MS + 30_000) {
                price10mAgo = sample.price;
                break;
            }
        }
        if (price10mAgo <= 0) {
            // Use oldest available sample if < 10 min of data
            PriceSample oldest = history.peekFirst();
            if (oldest != null && (now - oldest.timestamp) >= 5 * 60_000) {
                price10mAgo = oldest.price;
            } else {
                return MomentumSignal.NONE;
            }
        }

        double movePct = (spot - price10mAgo) / price10mAgo * 100;
        if (Math.abs(movePct) >= spikeThresholdPct) {
            int direction = movePct > 0 ? 1 : -1;
            return new MomentumSignal(direction, "EVENT_SPIKE", Math.abs(movePct), spot);
        }
        return MomentumSignal.NONE;
    }

    /**
     * Detect momentum within a shorter rolling window (e.g. 5 or 15 minutes).
     *
     * Complements the primary 30M detector by catching intraday trend momentum that
     * hasn't yet broken the 30-minute range. A 5-minute breakout with strong OI
     * confirmation is an early-entry signal used by operators before price overruns
     * the 30M level.
     *
     * Signal labels: "5M_HIGH_BREAK", "15M_HIGH_BREAK", etc.
     * Minimum 60 seconds of data required; excludes the last 5 seconds (same as detect()).
     *
     * @param windowMinutes  5 or 15 — the rolling window size
     * @param thresholdPct   minimum breakout distance (percent above/below the window high/low)
     */
    public MomentumSignal detectInWindow(IndexType indexType, double thresholdPct, int windowMinutes) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return MomentumSignal.NONE;

        Deque<PriceSample> history = priceHistory.get(indexType);
        if (history == null || history.size() < 60) return MomentumSignal.NONE; // need 60 s minimum

        long now = System.currentTimeMillis();
        long windowMs = (long) windowMinutes * 60_000L;
        long fiveSecsAgo = now - 5_000L;

        double high = 0, low = Double.MAX_VALUE;
        int sampleCount = 0;
        for (PriceSample sample : history) {
            long age = now - sample.timestamp;
            if (age > windowMs) continue;          // outside window
            if (sample.timestamp > fiveSecsAgo) continue; // skip last 5 seconds (noise)
            if (sample.price > high) high = sample.price;
            if (sample.price < low) low = sample.price;
            sampleCount++;
        }
        // Need at least half of the expected samples (30 per minute) to have a reliable window
        if (sampleCount < windowMinutes * 15 || high <= 0 || low == Double.MAX_VALUE) {
            return MomentumSignal.NONE;
        }

        // Breakout above the short-window high
        if (spot > high) {
            double breakoutPct = (spot - high) / high * 100;
            if (breakoutPct > thresholdPct) {
                return new MomentumSignal(1, windowMinutes + "M_HIGH_BREAK", breakoutPct, spot);
            }
        }
        // Breakdown below the short-window low
        if (spot < low) {
            double breakdownPct = (low - spot) / low * 100;
            if (breakdownPct > thresholdPct) {
                return new MomentumSignal(-1, windowMinutes + "M_LOW_BREAK", breakdownPct, spot);
            }
        }
        return MomentumSignal.NONE;
    }

    /**
     * Returns the N-minute rolling high for an index.
     * Used for position-level stop logic and operator zone tracking.
     */
    public double getRollingHighInWindow(IndexType indexType, int windowMinutes) {
        Deque<PriceSample> history = priceHistory.get(indexType);
        if (history == null || history.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        long windowMs = (long) windowMinutes * 60_000L;
        return history.stream()
                .filter(s -> (now - s.timestamp) <= windowMs)
                .mapToDouble(s -> s.price).max().orElse(0);
    }

    /**
     * Returns the N-minute rolling low for an index.
     */
    public double getRollingLowInWindow(IndexType indexType, int windowMinutes) {
        Deque<PriceSample> history = priceHistory.get(indexType);
        if (history == null || history.isEmpty()) return 0;
        long now = System.currentTimeMillis();
        long windowMs = (long) windowMinutes * 60_000L;
        return history.stream()
                .filter(s -> (now - s.timestamp) <= windowMs)
                .mapToDouble(s -> s.price).min().orElse(0);
    }

    /**
     * True endpoint-to-endpoint signed drift over the last {@code windowMinutes}.
     *
     * <p>Replaces the legacy midpoint approximation that D2 SUSTAINED_DRIFT used to
     * rely on. That approach measured distance from the rolling-window midpoint and
     * therefore reported only ~half the real move on a clean one-way trend — on the
     * 2026-06-12 afternoon rally it read ~0.13% (below the 0.20% gate) so D2 never
     * fired all day despite a textbook sustained drift.</p>
     *
     * <p>Drift = (currentSpot − spotAtWindowStart) / currentSpot × 100, where
     * spotAtWindowStart is the most recent sample at or before the window boundary
     * (or the oldest available sample before a full window has accumulated). Reads
     * the isolated {@link #driftHistory} (65-min retention). Requires at least
     * {@code min(windowMinutes, 30)} minutes of history so a "60-min sustained
     * drift" is never computed off a few minutes of ticks.</p>
     *
     * @return signed drift plus the window actually measured; {@link DriftSample#INVALID}
     *         when spot/history is unavailable or the measured window is too short.
     */
    public DriftSample getSignedDriftPct(IndexType indexType, int windowMinutes) {
        double spot = liveInstrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return DriftSample.INVALID;
        Deque<PriceSample> history = driftHistory.get(indexType);
        if (history == null || history.isEmpty()) return DriftSample.INVALID;

        long now = System.currentTimeMillis();
        long boundaryTs = now - (long) windowMinutes * 60_000L;

        // Walk oldest → newest, keeping the last sample at or before the window
        // boundary — that is the spot ~windowMinutes ago.
        PriceSample startSample = null;
        for (PriceSample s : history) {
            if (s.timestamp <= boundaryTs) {
                startSample = s;
            } else {
                break;
            }
        }
        // Not enough history to reach the boundary yet — fall back to the oldest sample.
        if (startSample == null) {
            startSample = history.peekFirst();
        }
        if (startSample == null || startSample.price <= 0) return DriftSample.INVALID;

        int measuredMin = (int) Math.round((now - startSample.timestamp) / 60_000.0);
        if (measuredMin < Math.min(windowMinutes, 30)) return DriftSample.INVALID;

        double driftPct = (spot - startSample.price) / spot * 100.0;
        return new DriftSample(driftPct, measuredMin, true);
    }

    /**
     * Get current spot price for an index.
     */
    public double getSpot(IndexType indexType) {
        return liveInstrumentCache.getFuturesPrice(indexType);
    }

    // ── Records ──

    private record PriceSample(long timestamp, double price) {}

    /** Result of an endpoint-drift measurement. {@code valid=false} ⇒ ignore the values. */
    public record DriftSample(double driftPct, int windowMinutesMeasured, boolean valid) {
        public static final DriftSample INVALID = new DriftSample(0, 0, false);
    }

    public record MomentumSignal(int direction, String type, double magnitude, double spotPrice) {
        public static final MomentumSignal NONE = new MomentumSignal(0, "NONE", 0, 0);
        public boolean isPresent() { return direction != 0; }
        public boolean isBullish() { return direction > 0; }
        public boolean isBearish() { return direction < 0; }
    }
}
