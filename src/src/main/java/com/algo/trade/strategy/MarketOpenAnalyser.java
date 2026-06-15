package com.algo.trade.strategy;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.CandleClosedEvent;
import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.CandleHistory;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.marketdata.PCRMaxPainTracker;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-phase market open analysis engine.
 *
 * Phase 1: 9:15 AM — enter only with strong signals aligned.
 * Phase 2: 9:30 AM+ — enter after watching first 15 min of price action.
 */
@Component
public class MarketOpenAnalyser {

    private static final Logger log = LoggerFactory.getLogger(MarketOpenAnalyser.class);

    private final LiveInstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final MarketDataService marketDataService;
    private final MarketGuard marketGuard;
    private final PCRMaxPainTracker pcrTracker;

    private final Map<IndexType, CandleHistory> indexCandleHistory = new ConcurrentHashMap<>();
    private final Map<IndexType, Double> openPrices = new ConcurrentHashMap<>();
    private final Map<IndexType, double[]> orbRange = new ConcurrentHashMap<>();
    private final Map<IndexType, Boolean> orbCapturedPerIndex = new ConcurrentHashMap<>();
    private final Map<IndexType, List<Double>> priceSamples = new ConcurrentHashMap<>();
    private final Map<IndexType, java.util.Deque<Double>> pcrHistory = new ConcurrentHashMap<>();
    private static final int PCR_SMOOTHING_SAMPLES = 5;

    public MarketOpenAnalyser(LiveInstrumentCache instrumentCache,
                              ExpiryCalendar expiryCalendar,
                              MarketDataService marketDataService,
                              MarketGuard marketGuard,
                              PCRMaxPainTracker pcrTracker) {
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.marketDataService = marketDataService;
        this.marketGuard = marketGuard;
        this.pcrTracker = pcrTracker;
    }

    public boolean isEarlyEntryAllowed(IndexType indexType) {
        if (!isEarlyEntryWindow()) return false;

        double vix = marketGuard.getCurrentVix();
        if (vix > 0 && vix < 11) return false;
        if (vix > 28) return false;
        if (marketGuard.isEventDay() || marketGuard.isPreEventDay()) return false;

        double pcr = pcrTracker.getPCR(com.algo.trade.domain.UnderlyingSymbol.valueOf(indexType.name()));
        if (pcr >= 0.95 && pcr <= 1.05) return false;

        double spot = instrumentCache.getFuturesPrice(indexType);
        double open = openPrices.getOrDefault(indexType, 0.0);
        if (open > 0 && spot > 0 && Math.abs(spot - open) / spot < 0.10) {
            double gapPct = Math.abs((spot - open) / open) * 100;
            if (gapPct > 2.5) return false;
        }

        log.info("[MarketOpen] ✅ EARLY ENTRY ALLOWED: VIX={} PCR={} index={}",
                vix, String.format("%.2f", pcr), indexType);
        return true;
    }

    public boolean isPostOpenEntryAllowed(IndexType indexType) {
        if (!isPostOpenWindow()) return false;

        double vix = marketGuard.getCurrentVix();
        if (vix > 0 && vix < 11) return false;
        if ((marketGuard.isEventDay() || marketGuard.isPreEventDay()) && vix > 28) return false;

        return true;
    }

    public boolean isEntryAllowed(IndexType indexType) {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 15))) return false;

        if (expiryCalendar.isExpiryDay(indexType) && now.isAfter(LocalTime.of(14, 30))) return false;

        if (isEarlyEntryWindow()) return isEarlyEntryAllowed(indexType);
        if (isPostOpenWindow()) return isPostOpenEntryAllowed(indexType);
        return false;
    }

    public EntryBias getEntryBias(IndexType indexType) {
        double rawPcr = pcrTracker.getPCR(com.algo.trade.domain.UnderlyingSymbol.valueOf(indexType.name()));

        java.util.Deque<Double> pcrDeque = pcrHistory.computeIfAbsent(indexType,
                k -> new java.util.ArrayDeque<>());
        pcrDeque.addLast(rawPcr);
        if (pcrDeque.size() > PCR_SMOOTHING_SAMPLES) pcrDeque.pollFirst();
        double pcr = pcrDeque.stream().mapToDouble(Double::doubleValue).average().orElse(rawPcr);

        String pcrBias = pcr > 1.05 ? "BULLISH" : pcr < 0.95 ? "BEARISH" : "NEUTRAL";

        String candleBias = "NEUTRAL";
        CandleHistory history = indexCandleHistory.get(indexType);
        if (history != null && history.size() >= 1) {
            Candle c = history.getAll().get(history.size() - 1);
            candleBias = c.close().doubleValue() > c.open().doubleValue() ? "BULLISH" : "BEARISH";
        }

        return new EntryBias(pcrBias, candleBias, "NEUTRAL", pcr);
    }

    @EventListener
    public void onCandle(CandleClosedEvent event) {
        if (event.timeframe() != com.algo.trade.domain.Timeframe.ONE_MINUTE) return;

        for (IndexType indexType : IndexType.values()) {
            double futures = instrumentCache.getFuturesPrice(indexType);
            if (futures <= 0) continue;

            CandleHistory history = indexCandleHistory.computeIfAbsent(indexType, k -> new CandleHistory(20));
            history.add(event.candle());

            double candleOpen = event.candle().open().doubleValue();
            if (candleOpen > 1000) openPrices.putIfAbsent(indexType, candleOpen);

            LocalTime now = LocalTime.now();
            Candle c = event.candle();
            if (now.isAfter(LocalTime.of(9, 14)) && now.isBefore(LocalTime.of(9, 31))) {
                double[] range = orbRange.computeIfAbsent(indexType, k -> new double[]{Double.MIN_VALUE, Double.MAX_VALUE});
                range[0] = Math.max(range[0], c.high().doubleValue());
                range[1] = Math.min(range[1], c.low().doubleValue());
            } else if (now.isAfter(LocalTime.of(9, 30)) && !orbCapturedPerIndex.getOrDefault(indexType, false)) {
                orbCapturedPerIndex.put(indexType, true);
                double[] range = orbRange.get(indexType);
                if (range != null && range[0] != Double.MIN_VALUE) {
                    log.info("[ORB] {} Opening Range: High={} Low={} Width={}",
                            indexType, String.format("%.1f", range[0]), String.format("%.1f", range[1]),
                            String.format("%.1f", range[0] - range[1]));
                }
            }

            if (now.isAfter(LocalTime.of(9, 30))) {
                priceSamples.computeIfAbsent(indexType, k -> new java.util.ArrayList<>()).add(c.close().doubleValue());
                List<Double> samples = priceSamples.get(indexType);
                if (samples.size() > 30) samples.remove(0);
            }
            break;
        }
    }

    public String getORBBreakout(IndexType indexType) {
        double[] range = orbRange.get(indexType);
        if (range == null || range[0] == Double.MIN_VALUE) return "NONE";
        double spot = instrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return "NONE";
        double orbWidth = range[0] - range[1];
        double buffer = orbWidth * 0.1;
        if (spot > range[0] + buffer) return "BULLISH";
        if (spot < range[1] - buffer) return "BEARISH";
        return "NONE";
    }

    public double[] getORBRange(IndexType indexType) {
        double[] range = orbRange.get(indexType);
        if (range == null || range[0] == Double.MIN_VALUE) return null;
        return new double[]{range[0], range[1]};
    }

    public boolean isRangeBound(IndexType indexType, double maxRangePercent) {
        List<Double> samples = priceSamples.get(indexType);
        if (samples == null || samples.size() < 15) return false;
        double high = samples.stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double low = samples.stream().mapToDouble(Double::doubleValue).min().orElse(0);
        if (low <= 0) return false;
        double rangePct = ((high - low) / low) * 100;
        return rangePct < maxRangePercent;
    }

    public boolean isRangeBound(IndexType indexType) {
        double threshold = (indexType == IndexType.BANKNIFTY) ? 0.25 : 0.20;
        return isRangeBound(indexType, threshold);
    }

    public boolean isEarlyEntryWindow() {
        LocalTime now = LocalTime.now();
        return now.isAfter(LocalTime.of(9, 14)) && now.isBefore(LocalTime.of(9, 30));
    }

    public boolean isPostOpenWindow() {
        LocalTime now = LocalTime.now();
        return now.isAfter(LocalTime.of(9, 29)) && now.isBefore(LocalTime.of(15, 20));
    }

    public record EntryBias(String pcrBias, String candleBias, String oiBias, double pcr) {
        public boolean isBullish() {
            int bullCount = (pcrBias.equals("BULLISH") ? 1 : 0)
                    + (candleBias.equals("BULLISH") ? 1 : 0)
                    + (oiBias.equals("BULLISH") ? 1 : 0);
            return bullCount >= 2;
        }
        public boolean isBearish() {
            int bearCount = (pcrBias.equals("BEARISH") ? 1 : 0)
                    + (candleBias.equals("BEARISH") ? 1 : 0)
                    + (oiBias.equals("BEARISH") ? 1 : 0);
            return bearCount >= 2;
        }
        public boolean isNeutral() { return !isBullish() && !isBearish(); }
    }
}
