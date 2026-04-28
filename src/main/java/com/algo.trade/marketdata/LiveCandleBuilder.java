package com.algo.trade.marketdata;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.CandleClosedEvent;
import com.algo.trade.domain.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aggregates raw ticks into OHLCV candles for 1m, 5m, and 15m timeframes.
 * Publishes a CandleClosedEvent when a candle period closes.
 *
 * This replaces the 60-second REST historical candle poll for real-time data.
 * Historical candles (lookback) are still fetched via REST on startup.
 *
 * Strategies react to CandleClosedEvent instead of a fixed timer,
 * firing the moment a candle closes — no polling delay.
 */
@Component
public class LiveCandleBuilder {

    private static final Logger log = LoggerFactory.getLogger(LiveCandleBuilder.class);
    private static final int MAX_HISTORY = 120;

    private static final Timeframe[] TRACKED_TIMEFRAMES = {
        Timeframe.ONE_MINUTE, Timeframe.FIVE_MINUTE, Timeframe.FIFTEEN_MINUTE, Timeframe.ONE_HOUR
    };

    private final ApplicationEventPublisher eventPublisher;

    // Key: "token:TIMEFRAME" → current open candle
    private final Map<String, OpenCandle> openCandles = new ConcurrentHashMap<>();
    // Key: "token:TIMEFRAME" → completed candle history
    private final Map<String, CandleHistory> history = new ConcurrentHashMap<>();

    public LiveCandleBuilder(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    // ── Tick ingestion ────────────────────────────────────────────────────────

    /**
     * Feed a tick into the candle builder.
     * Called by KiteWebSocketClient for every incoming option/index tick.
     */
    public void onTick(long instrumentToken, double price, long volume, long oi, Instant timestamp) {
        if (price <= 0) return;
        for (Timeframe tf : TRACKED_TIMEFRAMES) {
            processTick(instrumentToken, price, volume, oi, timestamp, tf);
        }
    }

    private void processTick(long token, double price, long volume, long oi,
                               Instant timestamp, Timeframe tf) {
        long bucketSeconds = tf.duration().toSeconds();
        long bucketEpoch = (timestamp.getEpochSecond() / bucketSeconds) * bucketSeconds;
        Instant bucketStart = Instant.ofEpochSecond(bucketEpoch);
        String key = token + ":" + tf.name();

        OpenCandle open = openCandles.get(key);

        if (open != null && !open.bucketStart().equals(bucketStart)) {
            // Period closed — publish event and start new candle
            Candle closed = open.toCandle(String.valueOf(token), tf);
            getHistory(key).add(closed);
            eventPublisher.publishEvent(new CandleClosedEvent(closed, tf, token));
            log.debug("Candle closed: token={} tf={} close={}", token, tf, closed.close());
            open = null;
        }

        BigDecimal p = BigDecimal.valueOf(price);
        if (open == null) {
            // startVolume == latestCumVol at candle open; deltaVolume() will be 0 until next tick
            openCandles.put(key, new OpenCandle(bucketStart, p, p, p, p, volume, volume, oi));
        } else {
            openCandles.put(key, open.update(p, volume, oi));
        }
    }

    // ── History access ────────────────────────────────────────────────────────

    public List<Candle> getHistory(long instrumentToken, Timeframe tf) {
        return getHistory(instrumentToken + ":" + tf.name()).getAll();
    }

    /** Returns true if at least one tick has arrived for this token+timeframe (open candle exists). */
    public boolean hasOpenCandle(long instrumentToken, Timeframe tf) {
        return openCandles.containsKey(instrumentToken + ":" + tf.name());
    }

    /** Seed history from REST historical candles fetched on startup. */
    public void seedHistory(long instrumentToken, Timeframe tf, List<Candle> candles) {
        String key = instrumentToken + ":" + tf.name();
        CandleHistory h = getHistory(key);
        candles.forEach(h::add);
        log.info("Candle history seeded: token={} tf={} count={}", instrumentToken, tf, candles.size());
    }

    private CandleHistory getHistory(String key) {
        return history.computeIfAbsent(key, k -> new CandleHistory(MAX_HISTORY));
    }

    // ── Analysis methods ──────────────────────────────────────────────────────

    /**
     * Calculate EMA (Exponential Moving Average) for a token and timeframe.
     * @param instrumentToken the instrument token
     * @param tf the timeframe
     * @param period EMA period (e.g., 9, 21)
     * @return EMA value, or 0 if insufficient data
     */
    public double calculateEMA(long instrumentToken, Timeframe tf, int period) {
        List<Candle> candles = getHistory(instrumentToken, tf);
        if (candles.size() < period) return 0;
        double multiplier = 2.0 / (period + 1);
        double ema = candles.get(candles.size() - period).close().doubleValue();
        for (int i = candles.size() - period + 1; i < candles.size(); i++) {
            ema = (candles.get(i).close().doubleValue() - ema) * multiplier + ema;
        }
        return ema;
    }

    /**
     * Detect trend direction across multiple timeframes.
     * Compares EMA9 vs EMA21 on 5m, 15m, and 1hr candles.
     * @return 1 = bullish (all aligned up), -1 = bearish (all down), 0 = mixed/no data
     */
    public int detectMultiTFTrend(long instrumentToken) {
        int bullCount = 0, bearCount = 0;
        for (Timeframe tf : new Timeframe[]{Timeframe.FIVE_MINUTE, Timeframe.FIFTEEN_MINUTE, Timeframe.ONE_HOUR}) {
            double ema9 = calculateEMA(instrumentToken, tf, 9);
            double ema21 = calculateEMA(instrumentToken, tf, 21);
            if (ema9 == 0 || ema21 == 0) continue;
            if (ema9 > ema21) bullCount++;
            else bearCount++;
        }
        if (bullCount >= 3) return 1;
        if (bearCount >= 3) return -1;
        return 0;
    }

    /**
     * Get support and resistance levels from 15-min candle history.
     * @return [support, resistance] — lowest low and highest high of recent candles
     */
    public double[] getSupportResistance(long instrumentToken) {
        List<Candle> candles = getHistory(instrumentToken, Timeframe.FIFTEEN_MINUTE);
        if (candles.size() < 5) return new double[]{0, 0};
        double support = candles.stream().mapToDouble(c -> c.low().doubleValue()).min().orElse(0);
        double resistance = candles.stream().mapToDouble(c -> c.high().doubleValue()).max().orElse(0);
        return new double[]{support, resistance};
    }

    /**
     * Detect VIX trend direction from recent VIX candles.
     * @param vixToken the VIX instrument token (264969 for India VIX)
     * @return 1 = VIX rising (danger), -1 = VIX falling (opportunity), 0 = flat
     */
    public int detectVixTrend(long vixToken) {
        List<Candle> candles = getHistory(vixToken, Timeframe.FIFTEEN_MINUTE);
        if (candles.size() < 5) return 0;
        double ema3 = calculateEMA(vixToken, Timeframe.FIFTEEN_MINUTE, 3);
        double ema8 = calculateEMA(vixToken, Timeframe.FIFTEEN_MINUTE, 8);
        if (ema3 == 0 || ema8 == 0) return 0;
        double diff = (ema3 - ema8) / ema8 * 100;
        if (diff > 2) return 1;   // VIX rising > 2%
        if (diff < -2) return -1; // VIX falling > 2%
        return 0;
    }

    // ── Inner types ───────────────────────────────────────────────────────────

    private record OpenCandle(
            Instant bucketStart,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            long startVolume,   // cumulative daily vol at first tick of this period
            long latestCumVol,  // most recent cumulative daily vol (Zerodha sends cumulative)
            long openInterest
    ) {
        OpenCandle update(BigDecimal price, long vol, long oi) {
            // Only advance latestCumVol when Zerodha sends a non-zero cumulative
            long newLatest = vol > 0 ? vol : latestCumVol;
            return new OpenCandle(bucketStart, open,
                    high.max(price), low.min(price), price,
                    startVolume, newLatest, oi > 0 ? oi : openInterest);
        }

        // Volume traded during this candle period = delta between cumulative readings
        long deltaVolume() { return Math.max(0, latestCumVol - startVolume); }

        Candle toCandle(String instrumentKey, Timeframe tf) {
            return new Candle(instrumentKey, bucketStart, tf, open, high, low, close, deltaVolume(), openInterest);
        }
    }
}
