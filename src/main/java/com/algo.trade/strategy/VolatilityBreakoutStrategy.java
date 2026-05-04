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
 */
@Component
public class VolatilityBreakoutStrategy {

    private static final Logger log = LoggerFactory.getLogger(VolatilityBreakoutStrategy.class);
    private static final double SQUEEZE_THRESHOLD = 1.5;
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

    public Optional<StrategyDecision> evaluate(List<Candle> candles15m, double ivRank,
                                               StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles15m, ivRank, config, underlying, null).signal();
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles15m, double ivRank,
                                                                   StrategyConfig config, UnderlyingSymbol underlying) {
        return evaluateWithDiagnostics(candles15m, ivRank, config, underlying, null);
    }

    public StrategyDiagnostics.WithSignal evaluateWithDiagnostics(List<Candle> candles15m, double ivRank,
                                                                   StrategyConfig config, UnderlyingSymbol underlying,
                                                                   LocalTime marketTime) {
        if (candles15m.size() < 21) {
            return new StrategyDiagnostics.WithSignal(Optional.empty(),
                    new StrategyDiagnostics("insufficientCandles(" + candles15m.size() + "/21)",
                            null, null, null, null, null, null, null, null));
        }

        // Bug fix: use passed marketTime (consistent with all other strategies, correct for backtesting)
        // Fall back to LocalTime.now() only when marketTime is not provided (legacy callers)
        LocalTime now = marketTime != null ? marketTime : LocalTime.now(IST);
        LocalTime entryCutoff = globalConfigService != null
                ? globalConfigService.getEntryCutoffTime() : LocalTime.of(15, 10);
        LocalTime entryStart = globalConfigService != null
                ? globalConfigService.getEntryStartTime() : LocalTime.of(9, 25);
        if (now.isBefore(entryStart) || now.isAfter(entryCutoff)) {
            log.debug("[VolBreakout] Outside entry window: now={} window={}-{}", now, entryStart, entryCutoff);
            return new StrategyDiagnostics.WithSignal(Optional.empty(),
                    new StrategyDiagnostics("timeWindow", null, null, null, null, null, null, null, null));
        }

        // Bug fix: null guard — getMaxIvRankForBuying() can be null if not configured
        double maxIvRank = config.getMaxIvRankForBuying() != null
                ? config.getMaxIvRankForBuying().doubleValue() : 50.0;
        if (ivRank > maxIvRank) {
            log.debug("[VolBreakout] IV rank {} too high (max {})", ivRank, maxIvRank);
            return new StrategyDiagnostics.WithSignal(Optional.empty(),
                    new StrategyDiagnostics("ivRankTooHigh(" + String.format("%.0f", ivRank) + ")",
                            null, null, null, null, null, null, null, null));
        }

        double[] bb = bollingerBands(candles15m, 20, 2.0);
        double upper = bb[0], middle = bb[1], lower = bb[2];
        double bandwidth = middle > 0 ? (upper - lower) / middle * 100 : 99;
        boolean squeeze = bandwidth < SQUEEZE_THRESHOLD;

        if (!squeeze) {
            log.debug("[VolBreakout] No squeeze: bandwidth={}%", String.format("%.2f", bandwidth));
            return new StrategyDiagnostics.WithSignal(Optional.empty(),
                    new StrategyDiagnostics("noSqueeze(bw=" + String.format("%.2f", bandwidth) + "%)",
                            null, null, null, null, upper, lower, bandwidth, false));
        }

        OptionType direction = confirmBreakout(candles15m, upper, lower);
        if (direction == null) {
            return new StrategyDiagnostics.WithSignal(Optional.empty(),
                    new StrategyDiagnostics("noBreakout",
                            null, null, null, null, upper, lower, bandwidth, true));
        }

        double latestClose = candles15m.getLast().close().doubleValue();
        log.info("[VolBreakout] Confirmed breakout: {} bandwidth={}% ivRank={} close={}",
                direction, String.format("%.2f", bandwidth), String.format("%.0f", ivRank), latestClose);

        SignalType signalType = direction == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE;
        StrategyDiagnostics diag = new StrategyDiagnostics(null, null, null, null, null,
                upper, lower, bandwidth, true);
        return new StrategyDiagnostics.WithSignal(Optional.of(new StrategyDecision(
                Instant.now(), underlying, signalType,
                candles15m.getLast().close(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.of(direction),
                true, Optional.empty(), true,
                BigDecimal.valueOf(75),
                List.of("BB squeeze breakout: bandwidth=" + String.format("%.2f", bandwidth) + "%",
                        "IV rank=" + String.format("%.0f", ivRank) + " (cheap)",
                        "Confirmed: " + BREAKOUT_CONFIRMATION_CANDLES + " consecutive candles outside band")
        )), diag);
    }

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
        double variance = window.stream()
                .mapToDouble(c -> Math.pow(c.close().doubleValue() - mean, 2))
                .sum() / (window.size() - 1);
        double stdDev = Math.sqrt(variance);
        return new double[]{mean + stdDevMult * stdDev, mean, mean - stdDevMult * stdDev};
    }
}
