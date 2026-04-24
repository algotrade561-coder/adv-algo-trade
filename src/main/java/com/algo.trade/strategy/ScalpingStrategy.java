package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.EmaIndicator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * EMA Crossover Scalping Strategy — candle-based, 5-min timeframe.
 *
 * Requires 2 consecutive candles confirming EMA 9/21 crossover before entry.
 * Tight stops, 30-min max hold, no entries after 14:00.
 * Uses the existing EmaIndicator from the project.
 */
@Component
public class ScalpingStrategy {

    private static final Logger log = LoggerFactory.getLogger(ScalpingStrategy.class);
    private static final LocalTime CUTOFF = LocalTime.of(14, 0);

    private final EmaIndicator emaIndicator;

    // Per-underlying crossover confirmation counters (thread-safe)
    private final Map<String, Integer> bullishConfirm = new ConcurrentHashMap<>();
    private final Map<String, Integer> bearishConfirm = new ConcurrentHashMap<>();

    public ScalpingStrategy(EmaIndicator emaIndicator) {
        this.emaIndicator = emaIndicator;
    }

    public Optional<StrategyDecision> evaluate(List<Candle> candles5m, LocalTime marketTime,
                                               StrategyConfig config, UnderlyingSymbol underlying) {
        if (marketTime.isBefore(LocalTime.of(9, 30)) || marketTime.isAfter(CUTOFF))
            return Optional.empty();
        if (candles5m.size() < 22) return Optional.empty();

        String key = underlying.name();
        List<BigDecimal> closes = candles5m.stream().map(Candle::close).toList();
        List<BigDecimal> prevCloses = closes.subList(0, closes.size() - 1);

        double fastEma  = emaIndicator.calculate(closes, 9).doubleValue();
        double slowEma  = emaIndicator.calculate(closes, 21).doubleValue();
        double prevFast = emaIndicator.calculate(prevCloses, 9).doubleValue();
        double prevSlow = emaIndicator.calculate(prevCloses, 21).doubleValue();

        boolean bullishCross = prevFast <= prevSlow && fastEma > slowEma;
        boolean bearishCross = prevFast >= prevSlow && fastEma < slowEma;

        if (bullishCross) {
            bullishConfirm.merge(key, 1, Integer::sum);
            bearishConfirm.put(key, 0);
        } else if (bearishCross) {
            bearishConfirm.merge(key, 1, Integer::sum);
            bullishConfirm.put(key, 0);
        } else {
            bullishConfirm.put(key, 0);
            bearishConfirm.put(key, 0);
        }

        int bullCount = bullishConfirm.getOrDefault(key, 0);
        int bearCount = bearishConfirm.getOrDefault(key, 0);

        if (bullCount >= 2) {
            bullishConfirm.put(key, 0);
            log.info("[Scalping {}] Bullish EMA crossover confirmed (2 candles)", underlying);
            return Optional.of(signal(underlying, SignalType.BUY_CE, OptionType.CE,
                    candles5m.getLast().close(), fastEma, slowEma));
        }
        if (bearCount >= 2) {
            bearishConfirm.put(key, 0);
            log.info("[Scalping {}] Bearish EMA crossover confirmed (2 candles)", underlying);
            return Optional.of(signal(underlying, SignalType.BUY_PE, OptionType.PE,
                    candles5m.getLast().close(), fastEma, slowEma));
        }
        return Optional.empty();
    }

    private StrategyDecision signal(UnderlyingSymbol underlying, SignalType signalType,
                                     OptionType optionType, BigDecimal price,
                                     double fastEma, double slowEma) {
        return new StrategyDecision(
                Instant.now(), underlying, signalType, price,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(optionType),
                true, Optional.empty(), true,
                BigDecimal.valueOf(70),
                List.of("Scalping: EMA9/21 crossover (2-candle confirmation)",
                        "EMA9=" + String.format("%.1f", fastEma) +
                        " EMA21=" + String.format("%.1f", slowEma))
        );
    }
}
