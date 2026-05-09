package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.domain.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Volume Delta Tracker — estimates buy vs sell volume from option candle data.
 *
 * Uses ATM ± 2 strikes option candles (which carry real volume from WebSocket)
 * instead of spot index tokens (which have no volume on Kite).
 *
 * Approximation uses candle close position within the high-low range:
 *   buyVolume  = volume × (close - low) / (high - low)
 *   sellVolume = volume × (high - close) / (high - low)
 *   delta = buyVolume - sellVolume
 *
 * Positive delta = buying pressure dominant (bullish for CE, bearish for PE).
 * Net delta = CE delta - PE delta (positive = bullish overall).
 *
 * Updated every 30 seconds from 1-min candle history.
 */
@Component
public class VolumeDeltaTracker {

    private static final Logger log = LoggerFactory.getLogger(VolumeDeltaTracker.class);

    private final LiveCandleBuilder liveCandleBuilder;
    private final LiveInstrumentCache liveInstrumentCache;
    private final ExpiryCalendar expiryCalendar;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    /** IndexType → latest delta snapshot. */
    private final Map<IndexType, DeltaSnapshot> snapshots = new ConcurrentHashMap<>();

    public VolumeDeltaTracker(LiveCandleBuilder liveCandleBuilder,
                              LiveInstrumentCache liveInstrumentCache,
                              ExpiryCalendar expiryCalendar) {
        this.liveCandleBuilder = liveCandleBuilder;
        this.liveInstrumentCache = liveInstrumentCache;
        this.expiryCalendar = expiryCalendar;
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
        if (schedulerRegistry != null) schedulerRegistry.recordRun("volumeDelta");
        for (IndexType idx : IndexType.values()) {
            try {
                // Get ATM ± 2 option tokens for this index (these have real volume)
                List<Long> optionTokens = getAtmOptionTokens(idx, 2);
                if (optionTokens.isEmpty()) {
                    // Fallback: try spot token candles (will be 0 volume but won't crash)
                    computeFromCandles(idx, liveCandleBuilder.getHistory(idx.spotToken(), Timeframe.ONE_MINUTE));
                    continue;
                }

                // Aggregate volume delta across all nearby option candles
                double cumDelta = 0;
                long totalVol = 0;
                double latestDelta = 0;

                for (Long token : optionTokens) {
                    List<Candle> candles = liveCandleBuilder.getHistory(token, Timeframe.ONE_MINUTE);
                    if (candles.size() < 3) continue;

                    int lookback = Math.min(10, candles.size());
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
                            latestDelta += delta;
                        }
                    }
                }

                double deltaPct = totalVol > 0 ? (cumDelta / totalVol) * 100 : 0;
                String bias;
                if (deltaPct > 10) bias = "BULLISH";
                else if (deltaPct < -10) bias = "BEARISH";
                else bias = "NEUTRAL";

                snapshots.put(idx, new DeltaSnapshot(cumDelta, deltaPct, latestDelta, totalVol, bias));
                log.debug("[VolumeDelta] {} cumDelta={} deltaPct={}% bias={} totalVol={}",
                        idx, String.format("%.0f", cumDelta), String.format("%.1f", deltaPct), bias, totalVol);

            } catch (Exception e) {
                log.debug("[VolumeDelta] Failed for {}: {}", idx, e.getMessage());
            }
        }
    }

    /**
     * Get ATM ± N strikes option tokens for volume aggregation.
     * Returns tokens that are subscribed and receiving ticks.
     */
    private List<Long> getAtmOptionTokens(IndexType idx, int strikesEachSide) {
        double spot = liveInstrumentCache.getFuturesPrice(idx);
        if (spot <= 0) return List.of();

        LocalDate expiry;
        try {
            expiry = expiryCalendar.getCurrentWeeklyExpiry(idx);
        } catch (Exception e) {
            return List.of();
        }

        int atm = idx.roundToATM(spot);
        int interval = idx.strikeInterval();
        List<Long> tokens = new ArrayList<>();

        for (int i = -strikesEachSide; i <= strikesEachSide; i++) {
            int strike = atm + (i * interval);
            liveInstrumentCache.getOption(idx, strike, "CE", expiry)
                    .ifPresent(o -> tokens.add(o.getInstrumentToken()));
            liveInstrumentCache.getOption(idx, strike, "PE", expiry)
                    .ifPresent(o -> tokens.add(o.getInstrumentToken()));
        }
        return tokens;
    }

    /** Fallback: compute from candle list (used when option tokens unavailable). */
    private void computeFromCandles(IndexType idx, List<Candle> candles) {
        if (candles.size() < 5) return;

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
            if (i == candles.size() - 1) latestDelta = delta;
        }

        double deltaPct = totalVol > 0 ? (cumDelta / totalVol) * 100 : 0;
        String bias = deltaPct > 10 ? "BULLISH" : deltaPct < -10 ? "BEARISH" : "NEUTRAL";
        snapshots.put(idx, new DeltaSnapshot(cumDelta, deltaPct, latestDelta, totalVol, bias));
        log.debug("[VolumeDelta] {} cumDelta={} deltaPct={}% bias={} (fallback)",
                idx, String.format("%.0f", cumDelta), String.format("%.1f", deltaPct), bias);
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
