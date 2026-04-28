package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * RSI(14)-based mean reversion: buys CE when price is oversold (RSI < 30),
 * buys PE when overbought (RSI > 70). Fades extreme intraday moves.
 */
@Component
public class ReversalBuyStrategy {

    private static final int RSI_PERIOD = 14;
    private static final double RSI_OVERSOLD = 30.0;
    private static final double RSI_OVERBOUGHT = 70.0;

    public Optional<StrategyDecision> evaluate(List<Candle> candles, double ivRank,
                                                StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles, ivRank, config, underlying).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles, double ivRank,
                                                                    StrategyConfig config, UnderlyingSymbol underlying) {
        if (candles.size() < RSI_PERIOD + 1) {
            return noTrade("notEnoughCandles(" + candles.size() + "<" + (RSI_PERIOD + 1) + ")");
        }

        double maxIv = config.getMaxIvRankForBuying() != null
                ? config.getMaxIvRankForBuying().doubleValue() : 50.0;
        if (ivRank > maxIv) {
            return noTrade("ivRankTooHigh(" + String.format("%.1f", ivRank) + ">" + maxIv + ")");
        }

        double rsi = computeRsi(candles);
        BigDecimal spotPrice = candles.getLast().close();

        if (rsi < RSI_OVERSOLD) {
            StrategyDecision signal = new StrategyDecision(
                    Instant.now(), underlying, SignalType.BUY_CE, spotPrice,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(OptionType.CE),
                    false, Optional.empty(), false,
                    BigDecimal.valueOf(68),
                    List.of("Reversal Buy: RSI=" + String.format("%.1f", rsi) + " < " + RSI_OVERSOLD + " (oversold)",
                            "Signal: BUY_CE — expecting upside bounce")
            );
            return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                    new StrategyDiagnostics(null, rsi, RSI_OVERSOLD, "BULLISH_REVERSAL",
                            null, null, null, null, null));
        }

        if (rsi > RSI_OVERBOUGHT) {
            StrategyDecision signal = new StrategyDecision(
                    Instant.now(), underlying, SignalType.BUY_PE, spotPrice,
                    Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.of(OptionType.PE),
                    false, Optional.empty(), false,
                    BigDecimal.valueOf(68),
                    List.of("Reversal Buy: RSI=" + String.format("%.1f", rsi) + " > " + RSI_OVERBOUGHT + " (overbought)",
                            "Signal: BUY_PE — expecting downside reversal")
            );
            return new StrategyDiagnostics.WithSignal(Optional.of(signal),
                    new StrategyDiagnostics(null, rsi, RSI_OVERBOUGHT, "BEARISH_REVERSAL",
                            null, null, null, null, null));
        }

        return noTrade("rsiNeutral(rsi=" + String.format("%.1f", rsi) + ")");
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
