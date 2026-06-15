package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.LiveCandleBuilder;
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
 * Morning Momentum Strategy — captures the first 30 minutes of directional momentum.
 *
 * Entry: 9:15–9:45 AM only. Buys options in the direction of the first strong 5-min candle.
 * Requires:
 *   - First 5-min candle body > 60% of range (strong directional candle)
 *   - Move > 0.15% from open (meaningful momentum)
 *   - Volume > 1.5× average (institutional participation)
 *
 * Exit: Max hold 30 min, tight SL 20%, target 40%, trail 12% after 25% profit.
 *
 * This is a time-bounded strategy that only operates in the opening window.
 */
@Component
public class MorningMomentumStrategy implements StrategyEvaluator, TimeBoundedStrategy, com.algo.trade.execution.DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(MorningMomentumStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;

    @Value("${trading.strategy.morning-momentum.enabled:true}")
    private boolean enabled;

    @Value("${trading.strategy.morning-momentum.min-move-percent:0.15}")
    private double minMovePercent;

    @Value("${trading.strategy.morning-momentum.volume-surge-multiplier:1.5}")
    private double volumeSurgeMultiplier;

    @Value("${trading.strategy.morning-momentum.min-body-ratio:0.6}")
    private double minBodyRatio;

    @Value("${trading.strategy.morning-momentum.stop-loss-percent:20}")
    private double stopLossPercent;

    @Value("${trading.strategy.morning-momentum.target-percent:40}")
    private double targetPercent;

    @Value("${trading.strategy.morning-momentum.max-hold-minutes:30}")
    private int maxHoldMinutes;

    private volatile boolean entryAttemptedToday = false;

    public MorningMomentumStrategy(LiveCandleBuilder candleBuilder) {
        this.candleBuilder = candleBuilder;
    }

    @Override
    public String strategyName() { return "MORNING_MOMENTUM"; }

    @Override
    public boolean isEnabled() { return enabled; }

    @Override
    public LocalTime entryStartTime() { return LocalTime.of(9, 20); }

    @Override
    public LocalTime entryCutoffTime() { return LocalTime.of(9, 45); }

    /**
     * Evaluate the opening candle(s) for momentum entry.
     */
    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled || entryAttemptedToday) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        long token = resolveToken(underlying);
        List<Candle> candles = candleBuilder.getHistory(token, Timeframe.FIVE_MINUTE);
        if (candles.isEmpty()) return Optional.empty();

        // Get the first completed 5-min candle of the day (9:15–9:20)
        Candle firstCandle = candles.get(candles.size() - 1);
        double open = firstCandle.open().doubleValue();
        double close = firstCandle.close().doubleValue();
        double high = firstCandle.high().doubleValue();
        double low = firstCandle.low().doubleValue();

        double range = high - low;
        if (range <= 0) return Optional.empty();

        double body = Math.abs(close - open);
        double bodyRatio = body / range;

        // Check body strength
        if (bodyRatio < minBodyRatio) return Optional.empty();

        // Check move magnitude
        double movePct = ((close - open) / open) * 100;
        if (Math.abs(movePct) < minMovePercent) return Optional.empty();

        // Volume confirmation
        double avgVolume = candles.size() > 5
                ? candles.subList(0, candles.size() - 1).stream().mapToLong(Candle::volume).average().orElse(0)
                : 0;
        if (avgVolume > 0 && firstCandle.volume() < avgVolume * volumeSurgeMultiplier) return Optional.empty();

        // Determine direction
        SignalType signalType = movePct > 0 ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = movePct > 0 ? OptionType.CE : OptionType.PE;

        log.info("[MORNING_MOMENTUM] {} opening momentum: {}% move, body={}%, vol={}×",
                underlying, String.format("%.2f", movePct),
                String.format("%.0f", bodyRatio * 100),
                avgVolume > 0 ? String.format("%.1f", firstCandle.volume() / avgVolume) : "N/A");

        entryAttemptedToday = true;

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
                BigDecimal.valueOf(75),
                List.of("MORNING_MOMENTUM: " + String.format("%.2f", movePct) + "% opening move",
                        "Body ratio: " + String.format("%.0f", bodyRatio * 100) + "%",
                        "Volume: " + (avgVolume > 0 ? String.format("%.1f", firstCandle.volume() / avgVolume) + "× avg" : "OK"))
        ));
    }

    public void resetDaily() {
        entryAttemptedToday = false;
    }

    private long resolveToken(UnderlyingSymbol underlying) {
        return switch (underlying) {
            case BANKNIFTY -> 260105L;
            case SENSEX -> 265L;
            default -> 256265L;
        };
    }
}
