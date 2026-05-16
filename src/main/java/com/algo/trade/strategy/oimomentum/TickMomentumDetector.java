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

        double high30m = getRolling30MinHigh(indexType);
        double low30m = getRolling30MinLow(indexType);
        if (high30m <= 0 || low30m <= 0) return MomentumSignal.NONE;

        // Breakout above 30-min high
        if (spot > high30m && high30m > 0) {
            double breakoutPct = (spot - high30m) / high30m * 100;
            if (breakoutPct > 0.01) { // Meaningful break (not just noise)
                return new MomentumSignal(1, "30M_HIGH_BREAK", breakoutPct, spot);
            }
        }

        // Breakdown below 30-min low
        if (spot < low30m && low30m > 0) {
            double breakdownPct = (low30m - spot) / low30m * 100;
            if (breakdownPct > 0.01) {
                return new MomentumSignal(-1, "30M_LOW_BREAK", breakdownPct, spot);
            }
        }

        // Large candle: check 1-second price change vs threshold
        Deque<PriceSample> history = priceHistory.get(indexType);
        if (history != null && history.size() >= 5) {
            // Compare current price to price 5 seconds ago
            PriceSample[] recent = history.stream()
                    .skip(Math.max(0, history.size() - 6))
                    .toArray(PriceSample[]::new);
            if (recent.length >= 5) {
                double priceAgo = recent[0].price;
                double movePct = (spot - priceAgo) / priceAgo * 100;
                if (Math.abs(movePct) >= momentumThresholdPct) {
                    int direction = movePct > 0 ? 1 : -1;
                    return new MomentumSignal(direction, "LARGE_MOVE", Math.abs(movePct), spot);
                }
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
     * Get current spot price for an index.
     */
    public double getSpot(IndexType indexType) {
        return liveInstrumentCache.getFuturesPrice(indexType);
    }

    // ── Records ──

    private record PriceSample(long timestamp, double price) {}

    public record MomentumSignal(int direction, String type, double magnitude, double spotPrice) {
        public static final MomentumSignal NONE = new MomentumSignal(0, "NONE", 0, 0);
        public boolean isPresent() { return direction != 0; }
        public boolean isBullish() { return direction > 0; }
        public boolean isBearish() { return direction < 0; }
    }
}
