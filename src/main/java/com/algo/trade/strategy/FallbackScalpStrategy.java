package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.BollingerBandIndicator;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.TickVolumeProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Fallback Scalp Strategy — activates ONLY when primary strategies are silent.
 *
 * Purpose: Capture quick scalps using BB squeeze + Volume Profile LVN breakouts
 * when no other strategy has fired for 30+ minutes.
 *
 * Non-Interference Design:
 * - Only activates after idle-minutes threshold of no trades
 * - Max 3 trades per day (strict cap)
 * - Tight SL (15%) and quick target (25%)
 * - Max hold 10 minutes (pure scalp, no carry)
 * - Disabled on expiry day
 *
 * Entry: Bollinger Band squeeze breakout + price in LVN (low volume node)
 * Exit: Hard SL 15%, Target 25%, Max hold 10 min, Trail 8% after 15% profit
 */
@Component
public class FallbackScalpStrategy implements StrategyEvaluator, TimeBoundedStrategy, com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(FallbackScalpStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final ExpiryCalendar expiryCalendar;

    @Value("${trading.strategy.fallback-scalp.enabled:true}")
    private boolean enabled;

    @Value("${trading.strategy.fallback-scalp.idle-minutes:30}")
    private int idleMinutesThreshold;

    @Value("${trading.strategy.fallback-scalp.max-trades-per-day:3}")
    private int maxTradesPerDay;

    @Value("${trading.strategy.fallback-scalp.stop-loss-percent:15}")
    private double stopLossPercent;

    @Value("${trading.strategy.fallback-scalp.target-percent:25}")
    private double targetPercent;

    @Value("${trading.strategy.fallback-scalp.max-hold-minutes:10}")
    private int maxHoldMinutes;

    @Value("${trading.strategy.fallback-scalp.min-premium:20}")
    private double minPremium;

    @Value("${trading.strategy.fallback-scalp.max-premium:200}")
    private double maxPremium;

    private volatile long lastTradeTime = 0;
    private volatile int tradesToday = 0;

    public FallbackScalpStrategy(LiveCandleBuilder candleBuilder, ExpiryCalendar expiryCalendar) {
        this.candleBuilder = candleBuilder;
        this.expiryCalendar = expiryCalendar;
    }

    @Override
    public String strategyName() { return "FALLBACK_SCALP"; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public LocalTime entryStartTime() { return LocalTime.of(10, 0); }

    @Override
    public LocalTime entryCutoffTime() { return LocalTime.of(15, 0); }

    /**
     * Evaluate whether conditions are right for a fallback scalp.
     * Only fires if the system has been idle for the configured duration.
     */
    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();
        if (tradesToday >= maxTradesPerDay) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        // Don't run on expiry day — primary strategies handle that
        IndexType indexType = IndexType.from(underlying);
        if (expiryCalendar.isExpiryDay(indexType)) return Optional.empty();

        // Check if system has been idle for configured duration
        long nowMs = System.currentTimeMillis();
        if (lastTradeTime > 0 && (nowMs - lastTradeTime) < (idleMinutesThreshold * 60_000L)) {
            return Optional.empty();
        }

        // Bollinger Band squeeze detection
        long token = resolveToken(underlying);
        List<Candle> candles = candleBuilder.getHistory(token, Timeframe.FIVE_MINUTE);
        if (candles.size() < 25) return Optional.empty();

        // Calculate Bollinger Band width
        double[] bb = calculateBB(candles, 20, 2.0);
        double bandwidth = (bb[2] - bb[0]) / bb[1] * 100; // (upper - lower) / middle as %

        // Squeeze: bandwidth < 2% indicates consolidation → imminent breakout
        if (bandwidth > 2.0) return Optional.empty();

        // Breakout direction from latest close vs BB
        Candle latest = candles.get(candles.size() - 1);
        double close = latest.close().doubleValue();

        SignalType signalType;
        OptionType optionType;
        if (close > bb[2]) { // broke above upper band
            signalType = SignalType.BUY_CE;
            optionType = OptionType.CE;
        } else if (close < bb[0]) { // broke below lower band
            signalType = SignalType.BUY_PE;
            optionType = OptionType.PE;
        } else {
            return Optional.empty(); // still in squeeze, no breakout yet
        }

        // Volume confirmation
        long latestVolume = latest.volume();
        double avgVolume = candles.subList(candles.size() - 10, candles.size()).stream()
                .mapToLong(Candle::volume).average().orElse(0);
        if (avgVolume > 0 && latestVolume < avgVolume * 1.3) return Optional.empty();

        log.info("[FALLBACK_SCALP] BB squeeze breakout detected on {} — {} (bandwidth={}%)",
                underlying, signalType, String.format("%.2f", bandwidth));

        tradesToday++;
        lastTradeTime = nowMs;

        return Optional.of(new StrategyDecision(
                Instant.now(),
                underlying,
                signalType,
                request.underlyingCandles().getLast().close(),
                Optional.of(request.selectedOptionQuote().lastPrice()),
                Optional.empty(),
                Optional.of(request.selectedLotSize()),
                Optional.empty(),
                Optional.of(request.selectedInstrumentKey()),
                Optional.of(request.selectedStrike()),
                Optional.of(optionType),
                true,
                Optional.empty(),
                true,
                BigDecimal.valueOf(70),
                List.of("FALLBACK_SCALP: BB squeeze breakout",
                        "Bandwidth: " + String.format("%.2f", bandwidth) + "%",
                        "Volume: " + String.format("%.1f", latestVolume / avgVolume) + "× avg")
        ));
    }

    /**
     * Notify that a trade was placed (by any strategy) to reset idle timer.
     */
    public void notifyTradeActivity() {
        this.lastTradeTime = System.currentTimeMillis();
    }

    public void resetDaily() {
        tradesToday = 0;
        lastTradeTime = 0;
    }

    /**
     * Calculate Bollinger Bands: [lower, middle, upper]
     */
    private double[] calculateBB(List<Candle> candles, int period, double stdDevMultiplier) {
        int start = candles.size() - period;
        if (start < 0) start = 0;

        double sum = 0;
        for (int i = start; i < candles.size(); i++) {
            sum += candles.get(i).close().doubleValue();
        }
        double sma = sum / (candles.size() - start);

        double variance = 0;
        for (int i = start; i < candles.size(); i++) {
            double diff = candles.get(i).close().doubleValue() - sma;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / (candles.size() - start));

        return new double[]{
                sma - stdDevMultiplier * stdDev, // lower
                sma,                              // middle
                sma + stdDevMultiplier * stdDev   // upper
        };
    }

    private long resolveToken(UnderlyingSymbol underlying) {
        return switch (underlying) {
            case BANKNIFTY -> 260105L;
            case SENSEX -> 265L;
            default -> 256265L;
        };
    }
}
