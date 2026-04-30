package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.AtrIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

/**
 * Momentum Strategy — buys in the direction of sustained price momentum.
 *
 * Unlike EMA crossover (lagging) or breakout (single event), this tracks
 * the RATE of price change across multiple timeframes and enters when
 * momentum is strong, accelerating, and confirmed by volume.
 *
 * Entry conditions (all must pass):
 *   1. Rate of Change (ROC) over 5 candles > threshold (0.30%)
 *   2. ROC is accelerating (current ROC > prior ROC)
 *   3. Price above EMA-21 for CE, below for PE (trend alignment)
 *   4. Volume on latest candle > 1.2x average (momentum has participation)
 *   5. ATR > minimum threshold (market is moving, not dead)
 */
@Component
public class MomentumStrategy {

    private static final Logger log = LoggerFactory.getLogger(MomentumStrategy.class);
    private static final int ROC_PERIOD = 5;
    private static final double MIN_ROC_PERCENT = 0.30;
    private static final int EMA_PERIOD = 21;
    private static final double MIN_VOLUME_RATIO = 1.2;
    private static final int ATR_PERIOD = 14;

    public Optional<StrategyDecision> evaluate(List<Candle> candles, LocalTime marketTime,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles, marketTime, config, underlying).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles, LocalTime marketTime,
                                                                    StrategyConfig config, UnderlyingSymbol underlying) {
        if (candles.size() < Math.max(ROC_PERIOD * 2 + 1, EMA_PERIOD + 1)) {
            return noTrade("notEnoughCandles(" + candles.size() + ")");
        }

        int size = candles.size();
        BigDecimal current = candles.get(size - 1).close();
        BigDecimal priorForRoc = candles.get(size - 1 - ROC_PERIOD).close();
        BigDecimal prevRocBase = candles.get(size - 2 - ROC_PERIOD).close();
        BigDecimal prevRocEnd = candles.get(size - 2).close();

        if (priorForRoc.signum() == 0 || prevRocBase.signum() == 0) {
            return noTrade("zeroPriceInHistory");
        }

        // 1. Rate of Change
        double roc = current.subtract(priorForRoc)
                .divide(priorForRoc, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        if (Math.abs(roc) < MIN_ROC_PERCENT) {
            return noTrade("rocTooWeak(" + String.format("%.2f", roc) + "%)");
        }

        // 2. ROC acceleration
        double prevRoc = prevRocEnd.subtract(prevRocBase)
                .divide(prevRocBase, 6, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100)).doubleValue();

        boolean bullish = roc > 0;
        boolean accelerating = bullish ? (roc > prevRoc) : (roc < prevRoc);
        if (!accelerating) {
            return noTrade("rocDecelerating(roc=" + String.format("%.2f", roc) + ",prev=" + String.format("%.2f", prevRoc) + ")");
        }

        // 3. EMA trend alignment
        double ema = calculateEma(candles, EMA_PERIOD);
        boolean trendAligned = bullish
                ? current.doubleValue() > ema
                : current.doubleValue() < ema;
        if (!trendAligned) {
            return noTrade("trendMisaligned(price=" + current + ",ema21=" + String.format("%.2f", ema) + ")");
        }

        // 4. Volume confirmation
        long latestVolume = candles.get(size - 1).volume();
        double avgVolume = candles.subList(Math.max(0, size - 5), size).stream()
                .mapToLong(Candle::volume).average().orElse(0);
        if (avgVolume > 0 && latestVolume < avgVolume * MIN_VOLUME_RATIO) {
            return noTrade("lowVolume(latest=" + latestVolume + ",avg=" + String.format("%.0f", avgVolume) + ")");
        }

        // 5. ATR minimum (market must be moving)
        double atr = calculateAtr(candles, ATR_PERIOD);
        double atrPercent = current.doubleValue() > 0 ? (atr / current.doubleValue()) * 100 : 0;
        if (atrPercent < 0.10) {
            return noTrade("atrTooLow(" + String.format("%.3f", atrPercent) + "%)");
        }

        // Graduated confidence score
        int score = 55;
        if (Math.abs(roc) > 0.60) score += 10;
        else if (Math.abs(roc) > 0.40) score += 5;
        if (accelerating && Math.abs(roc) > Math.abs(prevRoc) * 1.5) score += 10; // strong acceleration
        if (latestVolume > avgVolume * 2.0) score += 10;
        else if (latestVolume > avgVolume * 1.5) score += 5;
        if (atrPercent > 0.25) score += 5;
        score = Math.min(90, score);

        SignalType signalType = bullish ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        String direction = bullish ? "BULLISH" : "BEARISH";

        log.info("[Momentum] Signal: {} ROC={}% prevROC={}% EMA21={} vol={}x ATR={}% score={}",
                signalType, String.format("%.2f", roc), String.format("%.2f", prevRoc),
                String.format("%.2f", ema), String.format("%.1f", latestVolume / Math.max(avgVolume, 1)),
                String.format("%.3f", atrPercent), score);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, current,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(score),
                List.of(
                        "Momentum: ROC=" + String.format("%.2f", roc) + "% accelerating=" + accelerating
                                + " EMA21=" + String.format("%.2f", ema) + " direction=" + direction,
                        "Volume=" + String.format("%.1f", latestVolume / Math.max(avgVolume, 1)) + "x ATR=" + String.format("%.3f", atrPercent) + "%"
                )
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics(null, null, null, direction, ROC_PERIOD,
                        null, null, Math.abs(roc), bullish));
    }

    private double calculateEma(List<Candle> candles, int period) {
        if (candles.size() < period) return 0;
        double multiplier = 2.0 / (period + 1);
        double ema = candles.get(candles.size() - period).close().doubleValue();
        for (int i = candles.size() - period + 1; i < candles.size(); i++) {
            ema = (candles.get(i).close().doubleValue() - ema) * multiplier + ema;
        }
        return ema;
    }

    private double calculateAtr(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 0;
        double sum = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            Candle c = candles.get(i);
            Candle prev = candles.get(i - 1);
            double tr = Math.max(c.high().subtract(c.low()).doubleValue(),
                    Math.max(Math.abs(c.high().subtract(prev.close()).doubleValue()),
                            Math.abs(c.low().subtract(prev.close()).doubleValue())));
            sum += tr;
        }
        return sum / period;
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
