package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * RSI(14)-based mean reversion with exhaustion confirmation.
 *
 * Quality gates (vs. pure RSI threshold):
 *   1. RSI must be extreme (< 30 oversold, > 70 overbought)
 *   2. Volume must be declining on the spike (exhaustion, not acceleration)
 *   3. Latest candle must show reversal (opposite-direction body)
 *   4. IV rank check — don't buy expensive options
 *   5. Graduated confidence score
 */
@Component
public class ReversalBuyStrategy {

    private static final Logger log = LoggerFactory.getLogger(ReversalBuyStrategy.class);
    private static final int RSI_PERIOD = 14;
    private static final double RSI_OVERSOLD = 30.0;
    private static final double RSI_OVERBOUGHT = 70.0;
    /** Number of recent candles to check for volume exhaustion. */
    private static final int EXHAUSTION_LOOKBACK = 3;

    public Optional<StrategyDecision> evaluate(List<Candle> candles, double ivRank,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles, ivRank, config, underlying).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles, double ivRank,
                                                                    StrategyConfig config, UnderlyingSymbol underlying) {
        if (candles.size() < RSI_PERIOD + EXHAUSTION_LOOKBACK + 1) {
            return noTrade("notEnoughCandles(" + candles.size() + ")");
        }

        double maxIv = config.getMaxIvRankForBuying() != null
                ? config.getMaxIvRankForBuying().doubleValue() : 50.0;
        if (ivRank > maxIv) {
            return noTrade("ivRankTooHigh(" + String.format("%.1f", ivRank) + ">" + maxIv + ")");
        }

        double rsi = computeRsi(candles);
        BigDecimal spotPrice = candles.getLast().close();
        Candle latest = candles.getLast();

        if (rsi >= RSI_OVERSOLD && rsi <= RSI_OVERBOUGHT) {
            return noTrade("rsiNeutral(rsi=" + String.format("%.1f", rsi) + ")");
        }

        boolean oversold = rsi < RSI_OVERSOLD;

        // ── Gate: Volume exhaustion — volume should be declining, not accelerating ──
        boolean volumeExhausted = isVolumeExhausted(candles);
        if (!volumeExhausted) {
            return noTrade("noVolumeExhaustion(rsi=" + String.format("%.1f", rsi) + ")");
        }

        // ── Gate: Reversal candle — latest candle body must oppose the spike direction ──
        boolean reversalCandle;
        if (oversold) {
            // Oversold: latest candle should be bullish (close > open) — bounce starting
            reversalCandle = latest.close().compareTo(latest.open()) > 0;
        } else {
            // Overbought: latest candle should be bearish (close < open) — pullback starting
            reversalCandle = latest.close().compareTo(latest.open()) < 0;
        }
        if (!reversalCandle) {
            return noTrade("noReversalCandle(rsi=" + String.format("%.1f", rsi) + ")");
        }

        // ── Graduated confidence score ──
        int score = 55;
        if (oversold && rsi < 20) score += 10;       // deeply oversold
        else if (!oversold && rsi > 80) score += 10;  // deeply overbought
        if (volumeExhausted) score += 5;
        if (reversalCandle) score += 5;
        // Stronger reversal candle body = higher confidence
        double reversalBodyPct = Math.abs(latest.close().subtract(latest.open()).doubleValue()
                / latest.open().doubleValue() * 100);
        if (reversalBodyPct > 0.15) score += 5;
        if (ivRank < 30) score += 5; // cheap options
        score = Math.min(90, score);

        SignalType signalType = oversold ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = oversold ? OptionType.CE : OptionType.PE;
        String direction = oversold ? "BULLISH_REVERSAL" : "BEARISH_REVERSAL";

        log.info("[ReversalBuy] Signal: {} RSI={} volExhausted={} reversalCandle={} score={}",
                signalType, String.format("%.1f", rsi), volumeExhausted, reversalCandle, score);

        StrategyDecision signal = new StrategyDecision(
                Instant.now(), underlying, signalType, spotPrice,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                false, Optional.empty(), false,
                BigDecimal.valueOf(score),
                List.of(
                        "Reversal Buy: RSI=" + String.format("%.1f", rsi)
                                + (oversold ? " < " + RSI_OVERSOLD + " (oversold)" : " > " + RSI_OVERBOUGHT + " (overbought)")
                                + " volExhausted=" + volumeExhausted + " reversalCandle=" + reversalCandle,
                        "Signal: " + signalType + " — expecting " + direction.toLowerCase().replace("_", " ")
                )
        );
        return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                new StrategyDiagnostics(null, rsi, oversold ? RSI_OVERSOLD : RSI_OVERBOUGHT, direction,
                        null, null, null, null, null));
    }

    /**
     * Check if volume is declining over the last EXHAUSTION_LOOKBACK candles.
     * At least 2 of the last 3 candles should have lower volume than the one before.
     */
    private boolean isVolumeExhausted(List<Candle> candles) {
        int size = candles.size();
        if (size < EXHAUSTION_LOOKBACK + 1) return false;
        int declining = 0;
        for (int i = size - EXHAUSTION_LOOKBACK; i < size; i++) {
            if (candles.get(i).volume() < candles.get(i - 1).volume()) declining++;
        }
        return declining >= 2;
    }

    private double computeRsi(List<Candle> candles) {
        int start = candles.size() - RSI_PERIOD - 1;
        double gain = 0, loss = 0;
        for (int i = start + 1; i <= start + RSI_PERIOD; i++) {
            double change = candles.get(i).close().subtract(candles.get(i - 1).close()).doubleValue();
            if (change > 0) gain += change;
            else loss -= change;
        }
        gain /= RSI_PERIOD;
        loss /= RSI_PERIOD;
        if (loss == 0) return 100.0;
        return 100.0 - (100.0 / (1.0 + gain / loss));
    }

    private static StrategyDiagnostics.WithSignal noTrade(String reason) {
        return new StrategyDiagnostics.WithSignal(Optional.empty(),
                new StrategyDiagnostics(reason, null, null, null, null, null, null, null, null));
    }
}
