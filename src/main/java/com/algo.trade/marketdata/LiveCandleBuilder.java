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
        Timeframe.ONE_MINUTE, Timeframe.FIVE_MINUTE, Timeframe.FIFTEEN_MINUTE
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
            openCandles.put(key, new OpenCandle(bucketStart, p, p, p, p, volume, oi));
        } else {
            openCandles.put(key, open.update(p, volume, oi));
        }
    }

    // ── History access ────────────────────────────────────────────────────────

    public List<Candle> getHistory(long instrumentToken, Timeframe tf) {
        return getHistory(instrumentToken + ":" + tf.name()).getAll();
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

    // ── Inner types ───────────────────────────────────────────────────────────

    private record OpenCandle(
            Instant bucketStart,
            BigDecimal open, BigDecimal high, BigDecimal low, BigDecimal close,
            long volume, long openInterest
    ) {
        OpenCandle update(BigDecimal price, long vol, long oi) {
            return new OpenCandle(bucketStart, open,
                    high.max(price), low.min(price), price,
                    volume + vol, oi > 0 ? oi : openInterest);
        }

        Candle toCandle(String instrumentKey, Timeframe tf) {
            return new Candle(instrumentKey, bucketStart, tf, open, high, low, close, volume, openInterest);
        }
    }
}
