package com.algo.trade.strategy;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.domain.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Volatility Breakout Strategy — BUY options on Bollinger Band squeeze breakout.
 *
 * Logic:
 * 1. Monitor 15-min candles for Bollinger Band squeeze (bands narrowing)
 * 2. When bandwidth < 1.5% AND IV rank < maxIvRank → market is coiled
 * 3. On breakout (price closes outside bands for 2 consecutive candles) → BUY in breakout direction
 *    CE if breakout above upper band, PE if breakout below lower band
 *
 * Best used when IV is cheap (IV rank < 30) — captures explosive moves.
 */
@Component
public class VolatilityBreakoutStrategy {

    private static final Logger log = LoggerFactory.getLogger(VolatilityBreakoutStrategy.class);
    private static final double SQUEEZE_THRESHOLD = 1.5; // bandwidth %
    private static final int BREAKOUT_CONFIRMATION_CANDLES = 2;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final GlobalConfigService globalConfigService;

    public VolatilityBreakoutStrategy(GlobalConfigService globalConfigService) {
        this.globalConfigService = globalConfigService;
    }

    /** Backtest/test constructor — no GlobalConfigService, uses hardcoded defaults. */
    public VolatilityBreakoutStrategy() {
        this.globalConfigService = null;
    }

    /**
     * Evaluate using 15-min candles from the underlying.
     * @param candles15m 15-minute underlying candles (need at least 21)
     * @param ivRank current IV rank (0-100)
     * @param config strategy configuration
     * @return signal if confirmed breakout detected, empty otherwise
     */
    public Optional<StrategyDecision> evaluate(List<Candle> candles15m, double ivRank,
                                               StrategyConfig config, UnderlyingSymbol underlying) {
        if (candles15m.size() < 21) return Optional.empty();

        // Entry time window check — don't enter near market close
        LocalTime now = LocalTime.now(IST);
        LocalTime entryCutoff = globalConfigService != null
                ? globalConfigService.getEntryCutoffTime()
                : LocalTime.of(15, 10);
        LocalTime entryStart = globalConfigService != null
                ? globalConfigService.getEntryStartTime()
                : LocalTime.of(9, 25);
        if (now.isBefore(entryStart) || now.isAfter(entryCutoff)) {
            log.debug("[VolBreakout] Outside entry window: now={} window={}-{}", now, entryStart, entryCutoff);
            return Optional.empty();
        }

        // IV rank check — only buy when options are cheap
        if (ivRank > config.getMaxIvRankForBuying().doubleValue()) {
            log.debug("[VolBreakout] IV rank {} too high (max {})", ivRank, config.getMaxIvRankForBuying());
            return Optional.empty();
        }

        // Bollinger Bands (20-period, 2 std dev) — using sample variance (N-1)
        double[] bb = bollingerBands(candles15m, 20, 2.0);
        double upper = bb[0], middle = bb[1], lower = bb[2];
        double bandwidth = middle > 0 ? (upper - lower) / middle * 100 : 99;

        if (bandwidth >= SQUEEZE_THRESHOLD) {
            log.debug("[VolBreakout] No squeeze: bandwidth={}%", String.format("%.2f", bandwidth));
            return Optional.empty();
        }

        // Breakout confirmation: require last N candles ALL closing outside the band
        OptionType direction = confirmBreakout(candles15m, upper, lower);
        if (direction == null) return Optional.empty();

        double latestClose = candles15m.getLast().close().doubleValue();
        log.info("[VolBreakout] Confirmed breakout: {} bandwidth={}% ivRank={} close={}",
                direction, String.format("%.2f", bandwidth), String.format("%.0f", ivRank), latestClose);

        SignalType signalType = direction == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE;
        return Optional.of(new StrategyDecision(
                Instant.now(), underlying, signalType,
                candles15m.getLast().close(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(direction),
                true, Optional.empty(), true,
                BigDecimal.valueOf(75),
                List.of("BB squeeze breakout: bandwidth=" + String.format("%.2f", bandwidth) + "%",
                        "IV rank=" + String.format("%.0f", ivRank) + " (cheap)",
                        "Confirmed: " + BREAKOUT_CONFIRMATION_CANDLES + " consecutive candles outside band")
        ));
    }

    /**
     * Require BREAKOUT_CONFIRMATION_CANDLES consecutive closes outside the band.
     * Returns CE if all above upper, PE if all below lower, null if not confirmed.
     */
    private OptionType confirmBreakout(List<Candle> candles, double upper, double lower) {
        if (candles.size() < BREAKOUT_CONFIRMATION_CANDLES) return null;

        List<Candle> recent = candles.subList(candles.size() - BREAKOUT_CONFIRMATION_CANDLES, candles.size());

        boolean allAbove = recent.stream().allMatch(c -> c.close().doubleValue() > upper);
        if (allAbove) return OptionType.CE;

        boolean allBelow = recent.stream().allMatch(c -> c.close().doubleValue() < lower);
        if (allBelow) return OptionType.PE;

        return null;
    }

    private double[] bollingerBands(List<Candle> candles, int period, double stdDevMult) {
        List<Candle> window = candles.subList(candles.size() - period, candles.size());
        double mean = window.stream().mapToDouble(c -> c.close().doubleValue()).average().orElse(0);
        // Sample variance (N-1) — standard Bollinger Band convention
        double variance = window.stream()
                .mapToDouble(c -> Math.pow(c.close().doubleValue() - mean, 2))
                .sum() / (window.size() - 1);
        double stdDev = Math.sqrt(variance);
        return new double[]{mean + stdDevMult * stdDev, mean, mean - stdDevMult * stdDev};
    }
}
