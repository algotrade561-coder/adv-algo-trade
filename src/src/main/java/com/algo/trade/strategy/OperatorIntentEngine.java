package com.algo.trade.strategy;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.marketdata.PCRMaxPainTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Operator Intent Engine — reverse-engineers institutional/operator positioning
 * by synthesizing OI shifts, PCR, max pain, volume patterns, and global cues.
 *
 * Output: OperatorBias per index (STRONG_BULLISH, BULLISH, NEUTRAL, BEARISH, STRONG_BEARISH)
 * with confidence score (0-100) and key support/resistance levels.
 */
@Component
public class OperatorIntentEngine {

    private static final Logger log = LoggerFactory.getLogger(OperatorIntentEngine.class);

    private final MarketDataService marketDataService;
    private final PCRMaxPainTracker pcrTracker;
    private final LiveInstrumentCache instrumentCache;
    private final LiveCandleBuilder candleBuilder;
    private final ExpiryCalendar expiryCalendar;

    private final Map<IndexType, OperatorBias> biasCache = new ConcurrentHashMap<>();
    private final Map<IndexType, OperatorBias> persistedBias = new ConcurrentHashMap<>();
    private final Map<IndexType, Integer> emaConfidence = new ConcurrentHashMap<>();

    private static final IndexType[] TRACKED_INDICES = {IndexType.NIFTY, IndexType.BANKNIFTY, IndexType.SENSEX};

    public record OperatorBias(
            String direction, int confidence, int supportLevel, int resistanceLevel,
            int maxPainStrike, String primarySignal, long timestamp
    ) {
        public boolean isBullish() { return direction.contains("BULLISH"); }
        public boolean isBearish() { return direction.contains("BEARISH"); }
    }

    public OperatorIntentEngine(MarketDataService marketDataService,
                                PCRMaxPainTracker pcrTracker,
                                LiveInstrumentCache instrumentCache,
                                LiveCandleBuilder candleBuilder,
                                ExpiryCalendar expiryCalendar) {
        this.marketDataService = marketDataService;
        this.pcrTracker = pcrTracker;
        this.instrumentCache = instrumentCache;
        this.candleBuilder = candleBuilder;
        this.expiryCalendar = expiryCalendar;
    }

    public OperatorBias getBias(IndexType indexType) {
        return biasCache.getOrDefault(indexType, new OperatorBias(
                "NEUTRAL", 0, 0, 0, 0, "NO_DATA", 0));
    }

    @Scheduled(fixedDelay = 120_000)
    public void analyze() {
        LocalTime now = LocalTime.now();
        if (now.isBefore(LocalTime.of(9, 20)) || now.isAfter(LocalTime.of(15, 25))) return;

        boolean openingPhase = now.isBefore(LocalTime.of(9, 25));

        for (IndexType idx : TRACKED_INDICES) {
            try {
                OperatorBias bias = computeBias(idx);
                bias = applyPersistence(idx, bias);

                if (openingPhase) {
                    bias = new OperatorBias(bias.direction(), bias.confidence() / 2,
                            bias.supportLevel(), bias.resistanceLevel(), bias.maxPainStrike(),
                            bias.primarySignal() + " [OPENING_DAMPENED]", bias.timestamp());
                }

                OperatorBias prev = biasCache.get(idx);
                biasCache.put(idx, bias);

                if (prev != null && !sameBaseDirection(prev.direction(), bias.direction())) {
                    log.warn("[OperatorIntent] {} BIAS CHANGED: {} → {} (confidence={}% signal={})",
                            idx, prev.direction(), bias.direction(), bias.confidence(), bias.primarySignal());
                }
            } catch (Exception e) {
                log.debug("[OperatorIntent] Error for {}: {}", idx, e.getMessage());
            }
        }
    }

    private boolean sameBaseDirection(String dir1, String dir2) {
        if (dir1 == null || dir2 == null) return false;
        return dir1.replace("STRONG_", "").equals(dir2.replace("STRONG_", ""));
    }

    private OperatorBias applyPersistence(IndexType idx, OperatorBias newBias) {
        OperatorBias prev = persistedBias.get(idx);
        if (prev == null) { persistedBias.put(idx, newBias); return newBias; }

        int adjustedConfidence = newBias.confidence();
        if (sameBaseDirection(newBias.direction(), prev.direction())) {
            adjustedConfidence = Math.min(100, (int)(0.7 * prev.confidence() + 0.3 * newBias.confidence()));
        } else {
            adjustedConfidence = Math.max(0, prev.confidence() - 10);
            if (adjustedConfidence < 20) {
                persistedBias.put(idx, newBias);
                return newBias;
            }
            OperatorBias held = new OperatorBias(prev.direction(), adjustedConfidence,
                    prev.supportLevel(), prev.resistanceLevel(), prev.maxPainStrike(),
                    prev.primarySignal() + " [DECAYING]", System.currentTimeMillis());
            persistedBias.put(idx, held);
            return held;
        }

        OperatorBias persisted = new OperatorBias(newBias.direction(), adjustedConfidence,
                newBias.supportLevel(), newBias.resistanceLevel(), newBias.maxPainStrike(),
                newBias.primarySignal(), System.currentTimeMillis());
        persistedBias.put(idx, persisted);
        return persisted;
    }

    private int smoothConfidence(IndexType idx, int newConf) {
        return emaConfidence.compute(idx, (k, v) -> v == null ? newConf : (int)(0.6 * v + 0.4 * newConf));
    }

    private OperatorBias computeBias(IndexType idx) {
        double spot = instrumentCache.getFuturesPrice(idx);
        if (spot <= 0) return new OperatorBias("NEUTRAL", 0, 0, 0, 0, "NO_SPOT", System.currentTimeMillis());

        // PCR + Max Pain
        double pcr = pcrTracker.getPCR(com.algo.trade.domain.UnderlyingSymbol.valueOf(idx.name()));
        BigDecimal maxPainStrike = pcrTracker.getMaxPainStrike(com.algo.trade.domain.UnderlyingSymbol.valueOf(idx.name()));
        int maxPain = maxPainStrike != null ? maxPainStrike.intValue() : 0;

        // Scoring
        int bullScore = 0, bearScore = 0;
        String primarySignal = "";

        // PCR signal
        if (pcr > 1.2) { bullScore += 25; primarySignal = "PCR=" + String.format("%.2f", pcr) + " (strong support)"; }
        else if (pcr > 1.05) { bullScore += 15; if (primarySignal.isEmpty()) primarySignal = "PCR=" + String.format("%.2f", pcr); }
        else if (pcr < 0.8) { bearScore += 25; primarySignal = "PCR=" + String.format("%.2f", pcr) + " (weak support)"; }
        else if (pcr < 0.95) { bearScore += 15; if (primarySignal.isEmpty()) primarySignal = "PCR=" + String.format("%.2f", pcr); }

        // Max pain magnet
        double distFromMaxPain = maxPain > 0 ? ((spot - maxPain) / maxPain) * 100 : 0;
        if (distFromMaxPain < -0.3) bullScore += 10;
        else if (distFromMaxPain > 0.3) bearScore += 10;

        // Determine direction and confidence
        int netScore = bullScore - bearScore;
        int confidence = smoothConfidence(idx, Math.min(100, Math.abs(netScore)));
        String direction;

        if (netScore >= 40) direction = "STRONG_BULLISH";
        else if (netScore >= 15) direction = "BULLISH";
        else if (netScore <= -40) direction = "STRONG_BEARISH";
        else if (netScore <= -15) direction = "BEARISH";
        else direction = "NEUTRAL";

        return new OperatorBias(direction, confidence, 0, 0, maxPain,
                primarySignal, System.currentTimeMillis());
    }
}
