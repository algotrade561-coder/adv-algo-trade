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
 * Volatility Breakout Strategy — BUY options on Bollinger Band squeeze breakout.
 *
 * Logic:
 * 1. Monitor 15-min candles for Bollinger Band squeeze (bands narrowing)
 * 2. When bandwidth < 1.5% AND IV rank < maxIvRank → market is coiled
 * 3. On breakout (price closes outside bands) → BUY in breakout direction
 *    CE if breakout above upper band, PE if breakout below lower band
 *
 * Best used when IV is cheap (IV rank < 30) — captures explosive moves.
 */
@Component
public class VolatilityBreakoutStrategy {

    private static final Logger log = LoggerFactory.getLogger(VolatilityBreakoutStrategy.class);
    private static final double SQUEEZE_THRESHOLD = 1.5; // bandwidth %

    /**
     * Evaluate using 15-min candles from the underlying.
     * @param candles15m 15-minute underlying candles (need at least 21)
     * @param ivRank current IV rank (0-100)
     * @param config strategy configuration
     * @return signal if breakout detected, empty otherwise
     */
    public Optional<StrategyDecision> evaluate(List<Candle> candles15m, double ivRank,
                                               StrategyConfig config, UnderlyingSymbol underlying) {
        if (candles15m.size() < 21) return Optional.empty();

        // IV rank check — only buy when options are cheap
        if (ivRank > config.getMaxIvRankForBuying().doubleValue()) {
            log.debug("[VolBreakout] IV rank {} too high (max {})", ivRank, config.getMaxIvRankForBuying());
            return Optional.empty();
        }

        // Bollinger Bands (20-period, 2 std dev)
        double[] bb = bollingerBands(candles15m, 20, 2.0);
        double upper = bb[0], middle = bb[1], lower = bb[2];
        double bandwidth = middle > 0 ? (upper - lower) / middle * 100 : 99;

        if (bandwidth >= SQUEEZE_THRESHOLD) {
            log.debug("[VolBreakout] No squeeze: bandwidth={}%", String.format("%.2f", bandwidth));
            return Optional.empty();
        }

        double latestClose = candles15m.getLast().close().doubleValue();
        OptionType direction = null;
        if (latestClose > upper) direction = OptionType.CE;
        else if (latestClose < lower) direction = OptionType.PE;

        if (direction == null) return Optional.empty();

        log.info("[VolBreakout] Breakout: {} bandwidth={}% ivRank={}", direction,
                String.format("%.2f", bandwidth), String.format("%.0f", ivRank));

        SignalType signalType = direction == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE;
        return Optional.of(new StrategyDecision(
                Instant.now(), underlying, signalType,
                candles15m.getLast().close(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(direction),
                true, Optional.empty(), true,
                BigDecimal.valueOf(75),
                List.of("BB squeeze breakout: bandwidth=" + String.format("%.2f", bandwidth) + "%",
                        "IV rank=" + String.format("%.0f", ivRank) + " (cheap)")
        ));
    }

    private double[] bollingerBands(List<Candle> candles, int period, double stdDevMult) {
        List<Candle> window = candles.subList(candles.size() - period, candles.size());
        double mean = window.stream().mapToDouble(c -> c.close().doubleValue()).average().orElse(0);
        double variance = window.stream()
                .mapToDouble(c -> Math.pow(c.close().doubleValue() - mean, 2))
                .average().orElse(0);
        double stdDev = Math.sqrt(variance);
        return new double[]{mean + stdDevMult * stdDev, mean, mean - stdDevMult * stdDev};
    }
}
