package com.algo.trade.execution;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.risk.MarketGuard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * BTST Morning Classifier — determines whether an overnight gap will sustain (trend day)
 * or fade (mean-revert within 30 minutes).
 *
 * Called by BTSTPositionManager at 9:30–9:45 AM to decide:
 *   - HOLD: gap is likely to sustain → keep position, widen trail
 *   - EXIT_QUICK: gap is likely to fade → exit within first 15 min on any profit
 *   - MONITOR: unclear → apply normal trailing logic
 *
 * Scoring Model (0–100):
 *   Factor 1: Gap Size vs ATR (0–25 pts)
 *   Factor 2: First 15-min Candle Direction (0–25 pts)
 *   Factor 3: Opening Volume vs Average (0–20 pts)
 *   Factor 4: VIX Behavior at Open (0–15 pts)
 *   Factor 5: Previous Day Close Strength (0–15 pts)
 *
 * Decision:
 *   Score >= 65 → HOLD (trend day likely)
 *   Score 35–64 → MONITOR (apply normal trailing)
 *   Score < 35  → EXIT_QUICK (fade likely)
 */
@Component
public class BTSTMorningClassifier {

    private static final Logger log = LoggerFactory.getLogger(BTSTMorningClassifier.class);

    private final LiveCandleBuilder candleBuilder;
    private final MarketGuard marketGuard;

    @Value("${trading.btst.hold-threshold:65}")
    private int holdThreshold;

    @Value("${trading.btst.exit-quick-threshold:35}")
    private int exitQuickThreshold;

    public enum MorningVerdict { HOLD, MONITOR, EXIT_QUICK }

    public record ClassificationResult(
            MorningVerdict verdict,
            int score,
            String gapDirection,
            double gapPercent,
            String reasoning
    ) {}

    public BTSTMorningClassifier(LiveCandleBuilder candleBuilder, MarketGuard marketGuard) {
        this.candleBuilder = candleBuilder;
        this.marketGuard = marketGuard;
    }

    /**
     * Classify the morning opening for a BTST position.
     * Call after 9:30 AM when the first 15-min candle is available.
     */
    public ClassificationResult classify(IndexType indexType, String positionDirection) {
        long token = resolveToken(indexType);
        StringBuilder reasoning = new StringBuilder();
        int totalScore = 0;

        List<Candle> candles15m = candleBuilder.getHistory(token, Timeframe.FIFTEEN_MINUTE);
        if (candles15m.size() < 6) {
            return new ClassificationResult(MorningVerdict.MONITOR, 50, "UNKNOWN", 0,
                    "Insufficient candle data — defaulting to MONITOR");
        }

        // Previous day close and today's open
        Candle todayFirst = candles15m.get(candles15m.size() - 1);
        double prevClose = candles15m.get(candles15m.size() - 2).close().doubleValue();
        double todayOpen = todayFirst.open().doubleValue();

        if (prevClose <= 0) {
            return new ClassificationResult(MorningVerdict.MONITOR, 50, "UNKNOWN", 0,
                    "Cannot determine previous close — defaulting to MONITOR");
        }

        // Calculate gap
        double gapPercent = ((todayOpen - prevClose) / prevClose) * 100;
        String gapDirection = gapPercent > 0 ? "UP" : "DOWN";
        double absGap = Math.abs(gapPercent);

        // Check alignment with position direction
        boolean gapAligned = ("BULLISH".equals(positionDirection) && gapPercent > 0)
                || ("BEARISH".equals(positionDirection) && gapPercent < 0);

        if (!gapAligned) {
            return new ClassificationResult(MorningVerdict.EXIT_QUICK, 10, gapDirection, absGap,
                    "Gap " + gapDirection + " AGAINST position — exit immediately");
        }

        // Factor 1: Gap Size vs ATR (0–25 points)
        double atr = calculateATR(candles15m);
        double atrPercent = prevClose > 0 ? (atr / prevClose) * 100 : 0.5;
        double gapToAtrRatio = atrPercent > 0 ? absGap / atrPercent : 0;

        int gapScore;
        if (gapToAtrRatio >= 1.5) { gapScore = 25; reasoning.append("Large gap (").append(String.format("%.1f", gapToAtrRatio)).append("× ATR); "); }
        else if (gapToAtrRatio >= 1.0) { gapScore = 15; reasoning.append("Medium gap; "); }
        else if (gapToAtrRatio >= 0.5) { gapScore = 8; reasoning.append("Small gap; "); }
        else { gapScore = 0; reasoning.append("Tiny gap; "); }
        totalScore += gapScore;

        // Factor 2: First 15-min Candle Direction (0–25 points)
        double candleBody = todayFirst.close().doubleValue() - todayFirst.open().doubleValue();
        double candleRange = todayFirst.high().doubleValue() - todayFirst.low().doubleValue();
        double bodyRatio = candleRange > 0 ? Math.abs(candleBody) / candleRange : 0;
        boolean candleExtendsGap = (gapPercent > 0 && candleBody > 0) || (gapPercent < 0 && candleBody < 0);

        int candleScore;
        if (candleExtendsGap && bodyRatio > 0.6) { candleScore = 25; reasoning.append("Strong continuation candle; "); }
        else if (candleExtendsGap) { candleScore = 12; reasoning.append("Weak continuation; "); }
        else { candleScore = 0; reasoning.append("Reversal candle; "); }
        totalScore += candleScore;

        // Factor 3: Opening Volume (0–20 points)
        long openingVolume = todayFirst.volume();
        double avgVolume = candles15m.stream().mapToLong(Candle::volume).average().orElse(1);
        double volumeRatio = avgVolume > 0 ? openingVolume / avgVolume : 1;

        int volumeScore;
        if (volumeRatio >= 2.5) { volumeScore = 20; reasoning.append("Heavy volume; "); }
        else if (volumeRatio >= 1.5) { volumeScore = 12; reasoning.append("Above-avg volume; "); }
        else { volumeScore = 0; reasoning.append("Low volume; "); }
        totalScore += volumeScore;

        // Factor 4: VIX Behavior (0–15 points)
        double currentVix = marketGuard.getCurrentVix();
        int vixScore;
        if (gapPercent > 0 && currentVix < 14) { vixScore = 15; reasoning.append("VIX low on gap-up; "); }
        else if (gapPercent < 0 && currentVix > 18) { vixScore = 15; reasoning.append("VIX high on gap-down; "); }
        else if (gapPercent > 0 && currentVix > 18) { vixScore = 0; reasoning.append("VIX high on gap-up (caution); "); }
        else { vixScore = 7; reasoning.append("VIX neutral; "); }
        totalScore += vixScore;

        // Factor 5: Previous Day Close Strength (0–15 points)
        int closeScore = calculateCloseStrength(candles15m, gapDirection);
        totalScore += closeScore;

        // Decision
        MorningVerdict verdict;
        if (totalScore >= holdThreshold) verdict = MorningVerdict.HOLD;
        else if (totalScore < exitQuickThreshold) verdict = MorningVerdict.EXIT_QUICK;
        else verdict = MorningVerdict.MONITOR;

        log.info("[BTSTMorning] {} {} gap={}% score={} verdict={} — {}",
                indexType, gapDirection, String.format("%.2f", absGap), totalScore, verdict, reasoning);

        return new ClassificationResult(verdict, totalScore, gapDirection, absGap, reasoning.toString());
    }

    private double calculateATR(List<Candle> candles) {
        if (candles.size() < 5) return 0;
        double atrSum = 0;
        int count = 0;
        int start = Math.max(0, candles.size() - 12);
        int end = candles.size() - 2;
        for (int i = start + 1; i <= end && i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double tr = Math.max(c.high().doubleValue() - c.low().doubleValue(),
                    Math.max(Math.abs(c.high().doubleValue() - prev.close().doubleValue()),
                            Math.abs(c.low().doubleValue() - prev.close().doubleValue())));
            atrSum += tr;
            count++;
        }
        return count > 0 ? atrSum / count : 0;
    }

    private int calculateCloseStrength(List<Candle> candles, String gapDirection) {
        if (candles.size() < 3) return 5;
        double dayHigh = 0, dayLow = Double.MAX_VALUE, dayClose = 0;
        int end = candles.size() - 1;
        int start = Math.max(0, end - 6);
        for (int i = start; i < end; i++) {
            Candle c = candles.get(i);
            dayHigh = Math.max(dayHigh, c.high().doubleValue());
            dayLow = Math.min(dayLow, c.low().doubleValue());
            dayClose = c.close().doubleValue();
        }
        if (dayHigh <= dayLow) return 5;
        double closePosition = (dayClose - dayLow) / (dayHigh - dayLow);
        if ("UP".equals(gapDirection)) {
            if (closePosition > 0.75) return 15;
            if (closePosition > 0.5) return 8;
            return 0;
        } else {
            if (closePosition < 0.25) return 15;
            if (closePosition < 0.5) return 8;
            return 0;
        }
    }

    private long resolveToken(IndexType indexType) {
        return switch (indexType) {
            case BANKNIFTY -> 260105L;
            case SENSEX -> 265L;
            default -> 256265L;
        };
    }
}
