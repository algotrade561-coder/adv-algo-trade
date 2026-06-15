package com.algo.trade.indicator;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.marketdata.LiveCandleBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sector Rotation Detector — tracks capital flow across sectors.
 *
 * Monitors relative performance of sector indices vs NIFTY to detect rotation:
 * - Banking outperforming → bullish bias for BANKNIFTY strategies
 * - IT/Pharma outperforming while NIFTY flat → defensive rotation
 * - All sectors up → broad rally, high conviction for NIFTY CE
 * - All sectors down → broad sell-off, high conviction for NIFTY PE
 *
 * Sector proxies (Kite instrument tokens):
 *   NIFTY BANK, NIFTY IT, NIFTY PHARMA, NIFTY FINANCIAL SERVICES
 */
@Component
public class SectorRotationDetector {

    private static final Logger log = LoggerFactory.getLogger(SectorRotationDetector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;

    @Value("${trading.sector-rotation.enabled:true}")
    private boolean enabled;

    // Sector instrument tokens (approximations — actual tokens from Kite instrument dump)
    private static final Map<String, Long> SECTOR_TOKENS = Map.of(
            "NIFTY", 256265L,
            "BANKNIFTY", 260105L,
            "NIFTY_IT", 259849L,
            "NIFTY_PHARMA", 261889L
    );

    public enum SectorBias { BANKING_LED, IT_LED, PHARMA_LED, BROAD_RALLY, BROAD_SELLOFF, MIXED }

    private volatile SectorBias currentBias = SectorBias.MIXED;
    private final Map<String, Double> sectorReturns = new ConcurrentHashMap<>();

    public SectorRotationDetector(LiveCandleBuilder candleBuilder) {
        this.candleBuilder = candleBuilder;
    }

    /**
     * Refresh sector analysis every 5 minutes.
     */
    @Scheduled(fixedDelay = 300_000)
    public void analyze() {
        if (!enabled) return;
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 30)) || now.isAfter(LocalTime.of(15, 30))) return;

        try {
            Map<String, Double> returns = new HashMap<>();

            for (Map.Entry<String, Long> entry : SECTOR_TOKENS.entrySet()) {
                List<Candle> candles = candleBuilder.getHistory(entry.getValue(), Timeframe.FIFTEEN_MINUTE);
                if (candles.size() < 5) continue;

                Candle first = candles.get(0);
                Candle last = candles.get(candles.size() - 1);
                double openPrice = first.open().doubleValue();
                double closePrice = last.close().doubleValue();

                if (openPrice > 0) {
                    double returnPct = ((closePrice - openPrice) / openPrice) * 100;
                    returns.put(entry.getKey(), returnPct);
                }
            }

            sectorReturns.clear();
            sectorReturns.putAll(returns);

            // Determine bias
            double niftyReturn = returns.getOrDefault("NIFTY", 0.0);
            double bankReturn = returns.getOrDefault("BANKNIFTY", 0.0);
            double itReturn = returns.getOrDefault("NIFTY_IT", 0.0);
            double pharmaReturn = returns.getOrDefault("NIFTY_PHARMA", 0.0);

            int positiveCount = (int) returns.values().stream().filter(r -> r > 0.1).count();
            int negativeCount = (int) returns.values().stream().filter(r -> r < -0.1).count();

            SectorBias newBias;
            if (positiveCount >= 3) {
                newBias = SectorBias.BROAD_RALLY;
            } else if (negativeCount >= 3) {
                newBias = SectorBias.BROAD_SELLOFF;
            } else if (bankReturn > niftyReturn + 0.3) {
                newBias = SectorBias.BANKING_LED;
            } else if (itReturn > niftyReturn + 0.3) {
                newBias = SectorBias.IT_LED;
            } else if (pharmaReturn > niftyReturn + 0.3) {
                newBias = SectorBias.PHARMA_LED;
            } else {
                newBias = SectorBias.MIXED;
            }

            if (newBias != currentBias) {
                log.info("[SectorRotation] Bias changed: {} → {} (NIFTY={:.2f}% BANK={:.2f}% IT={:.2f}%)",
                        currentBias, newBias, niftyReturn, bankReturn, itReturn);
            }
            currentBias = newBias;

        } catch (Exception e) {
            log.debug("[SectorRotation] Analysis error: {}", e.getMessage());
        }
    }

    public SectorBias getCurrentBias() { return currentBias; }

    public boolean isBroadRally() { return currentBias == SectorBias.BROAD_RALLY; }
    public boolean isBroadSelloff() { return currentBias == SectorBias.BROAD_SELLOFF; }
    public boolean isBankingLed() { return currentBias == SectorBias.BANKING_LED; }

    public Map<String, Double> getSectorReturns() { return Map.copyOf(sectorReturns); }

    /**
     * Get sector momentum score (-100 to +100).
     * Positive = bullish rotation, Negative = defensive rotation.
     */
    public int getMomentumScore() {
        if (sectorReturns.isEmpty()) return 0;
        double avg = sectorReturns.values().stream().mapToDouble(d -> d).average().orElse(0);
        return (int) Math.max(-100, Math.min(100, avg * 50)); // scale to -100..+100
    }
}
