package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.ExpiryCalendar;
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
 * Event Spike Strategy — catches sudden large moves (>0.5% in 10 min).
 *
 * When the index moves sharply (news, RBI, global event):
 * - Immediately enter in the spike direction
 * - Override all other filters (event-driven priority)
 * - Tight stop loss (15% below entry)
 * - Quick exit if momentum stalls
 *
 * Implements StrategyEvaluator to integrate with adv-algo-trade's execution pipeline.
 * Called by AlgoTradeExecution on every candle close — detects spike by comparing
 * recent candle data.
 */
@Component
public class EventSpikeStrategy implements StrategyEvaluator, TimeBoundedStrategy {

    private static final Logger log = LoggerFactory.getLogger(EventSpikeStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final ExpiryCalendar expiryCalendar;

    @Value("${trading.strategy.event-spike.enabled:true}")
    private boolean enabled;

    @Value("${trading.strategy.event-spike.spike-threshold-percent:0.5}")
    private double spikeThreshold;

    @Value("${trading.strategy.event-spike.spike-window-candles:2}")
    private int spikeWindowCandles;

    @Value("${trading.strategy.event-spike.stop-loss-percent:15}")
    private double stopLossPercent;

    @Value("${trading.strategy.event-spike.target-percent:30}")
    private double targetPercent;

    @Value("${trading.strategy.event-spike.max-trades-per-day:5}")
    private int maxTradesPerDay;

    public EventSpikeStrategy(LiveCandleBuilder candleBuilder, ExpiryCalendar expiryCalendar) {
        this.candleBuilder = candleBuilder;
        this.expiryCalendar = expiryCalendar;
    }

    @Override
    public String strategyName() {
        return "EVENT_SPIKE";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public LocalTime entryStartTime() {
        return LocalTime.of(9, 16);
    }

    @Override
    public LocalTime entryCutoffTime() {
        return LocalTime.of(15, 15);
    }

    /**
     * Evaluate whether a spike has occurred on the given underlying.
     * Called by the scheduler after every candle close event.
     */
    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        long token = resolveToken(underlying);
        List<Candle> candles = candleBuilder.getHistory(token, Timeframe.FIVE_MINUTE);
        if (candles.size() < spikeWindowCandles + 1) return Optional.empty();

        // Compare current price to price N candles ago
        Candle current = candles.get(candles.size() - 1);
        Candle reference = candles.get(candles.size() - 1 - spikeWindowCandles);

        double movePct = ((current.close().doubleValue() - reference.open().doubleValue())
                / reference.open().doubleValue()) * 100;

        if (Math.abs(movePct) < spikeThreshold) return Optional.empty();

        SignalType signalType = movePct > 0 ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = movePct > 0 ? OptionType.CE : OptionType.PE;

        // Volume confirmation: current candle should have at least average volume
        double avgVol = candles.stream().mapToLong(Candle::volume).average().orElse(0);
        if (avgVol > 0 && current.volume() < avgVol) return Optional.empty();

        log.warn("[EVENT_SPIKE] SPIKE DETECTED on {}: {}% in {}min — signal={}",
                underlying, String.format("%.2f", movePct), spikeWindowCandles * 5, signalType);

        BigDecimal confidence = BigDecimal.valueOf(Math.min(100, Math.abs(movePct) * 100 / spikeThreshold));

        StrategyDecision decision = new StrategyDecision(
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
                true, // volumeSpike = true
                confidence,
                List.of("EVENT_SPIKE: " + String.format("%.2f", movePct) + "% move detected",
                        "Window: " + (spikeWindowCandles * 5) + " min",
                        "Threshold: " + spikeThreshold + "%")
        );

        return Optional.of(decision);
    }

    private long resolveToken(UnderlyingSymbol underlying) {
        return switch (underlying) {
            case BANKNIFTY -> 260105L;
            case SENSEX -> 265L;
            default -> 256265L;
        };
    }
}
