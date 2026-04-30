package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.domain.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volume Delta Tracker — estimates buy vs sell volume from candle data.
 *
 * True volume delta requires tick-level trade data (which Zerodha WebSocket provides
 * as cumulative volume). This approximation uses candle close position within the
 * high-low range to estimate buying vs selling pressure:
 *
 *   buyVolume  = volume × (close - low) / (high - low)
 *   sellVolume = volume × (high - close) / (high - low)
 *   delta = buyVolume - sellVolume
 *   deltaPercent = delta / volume × 100
 *
 * Positive delta = buying pressure dominant.
 * Negative delta = selling pressure dominant.
 *
 * Updated every 30 seconds from 1-min candle history.
 */
@Component
public class VolumeDeltaTracker {

    private static final Logger log = LoggerFactory.getLogger(VolumeDeltaTracker.class);

    private final LiveCandleBuilder liveCandleBuilder;

    /** IndexType → latest delta snapshot. */
    private final Map<IndexType, DeltaSnapshot> snapshots = new ConcurrentHashMap<>();

    public VolumeDeltaTracker(LiveCandleBuilder liveCandleBuilder) {
        this.liveCandleBuilder = liveCandleBuilder;
    }

    public record DeltaSnapshot(
            double cumulativeDelta,
            double deltaPercent,
            double latestCandleDelta,
            long totalVolume,
            String bias // "BULLISH", "BEARISH", "NEUTRAL"
    ) {}

    @Scheduled(fixedDelay = 30_000, initialDelay = 60_000)
    public void update() {
        for (IndexType idx : IndexType.values()) {
            try {
                List<Candle> candles = liveCandleBuilder.getHistory(idx.spotToken(), Timeframe.ONE_MINUTE);
                if (candles.size() < 5) continue;

                // Compute cumulative delta over last 10 candles
                int lookback = Math.min(10, candles.size());
                double cumDelta = 0;
                long totalVol = 0;
                double latestDelta = 0;

                for (int i = candles.size() - lookback; i < candles.size(); i++) {
                    Candle c = candles.get(i);
                    double range = c.high().subtract(c.low()).doubleValue();
                    long vol = c.volume();
                    if (range <= 0 || vol <= 0) continue;

                    double closePosition = c.close().subtract(c.low()).doubleValue() / range;
                    double buyVol = vol * closePosition;
                    double sellVol = vol * (1.0 - closePosition);
                    double delta = buyVol - sellVol;

                    cumDelta += delta;
                    totalVol += vol;

                    if (i == candles.size() - 1) {
                        latestDelta = delta;
                    }
                }

                double deltaPct = totalVol > 0 ? (cumDelta / totalVol) * 100 : 0;
                String bias;
                if (deltaPct > 10) bias = "BULLISH";
                else if (deltaPct < -10) bias = "BEARISH";
                else bias = "NEUTRAL";

                snapshots.put(idx, new DeltaSnapshot(cumDelta, deltaPct, latestDelta, totalVol, bias));
                log.debug("[VolumeDelta] {} cumDelta={} deltaPct={}% bias={}",
                        idx, String.format("%.0f", cumDelta), String.format("%.1f", deltaPct), bias);

            } catch (Exception e) {
                log.debug("[VolumeDelta] Failed for {}: {}", idx, e.getMessage());
            }
        }
    }

    public DeltaSnapshot getSnapshot(IndexType indexType) {
        return snapshots.getOrDefault(indexType, new DeltaSnapshot(0, 0, 0, 0, "NEUTRAL"));
    }

    public String getBias(IndexType indexType) {
        return getSnapshot(indexType).bias();
    }

    public double getDeltaPercent(IndexType indexType) {
        return getSnapshot(indexType).deltaPercent();
    }
}
