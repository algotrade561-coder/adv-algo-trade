package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
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
 * Momentum Breakout Strategy — detects strong intraday moves and buys in the direction.
 *
 * Entry signals (ANY one triggers):
 *   1. Price breaks above/below the last 30-min high/low with volume surge
 *   2. 3 consecutive bullish/bearish 5-min candles
 *   3. RSI crosses above 60 (bullish) or below 40 (bearish) from neutral zone
 *   4. Large single candle: 5-min body > 0.2% of spot price
 *
 * Implements StrategyEvaluator for candle-driven execution pipeline.
 */
@Component
public class MomentumBreakoutStrategy implements StrategyEvaluator, TimeBoundedStrategy {

    private static final Logger log = LoggerFactory.getLogger(MomentumBreakoutStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final LiveInstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;

    @Value("${strategy.momentum-breakout.enabled:true}") private boolean enabled;

    public MomentumBreakoutStrategy(LiveCandleBuilder candleBuilder,
                                     LiveInstrumentCache instrumentCache,
                                     ExpiryCalendar expiryCalendar) {
        this.candleBuilder = candleBuilder;
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    @Override public String strategyName() { return "MOMENTUM_BREAKOUT"; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public LocalTime entryStartTime() { return LocalTime.of(9, 30); }
    @Override public LocalTime entryCutoffTime() { return LocalTime.of(15, 0); }

    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        IndexType idx = mapToIndexType(underlying);
        long token = resolveToken(idx);
        List<Candle> candles5m = candleBuilder.getHistory(token, Timeframe.FIVE_MINUTE);
        if (candles5m.size() < 6) return Optional.empty();

        double spot = instrumentCache.getFuturesPrice(idx);
        if (spot <= 0) return Optional.empty();

        int bullishCount = 0, bearishCount = 0;
        String primaryReason = "";

        Candle latest = candles5m.get(candles5m.size() - 1);
        double avgVol = candles5m.stream().mapToLong(Candle::volume).average().orElse(0);
        boolean volumeSurge = avgVol > 0 && latest.volume() > avgVol * 1.3;

        // Signal 1: 30-min high/low breakout
        double rolling30mHigh = 0, rolling30mLow = Double.MAX_VALUE;
        int lookback = Math.min(6, candles5m.size());
        for (int i = candles5m.size() - lookback; i < candles5m.size() - 1; i++) {
            Candle c = candles5m.get(i);
            rolling30mHigh = Math.max(rolling30mHigh, c.high().doubleValue());
            rolling30mLow = Math.min(rolling30mLow, c.low().doubleValue());
        }
        if (latest.close().doubleValue() > rolling30mHigh && volumeSurge) { bullishCount++; primaryReason = "30m breakout+vol"; }
        else if (latest.close().doubleValue() < rolling30mLow && volumeSurge) { bearishCount++; primaryReason = "30m breakout+vol"; }

        // Signal 2: 3 consecutive directional candles
        if (candles5m.size() >= 3) {
            Candle c1 = candles5m.get(candles5m.size() - 3);
            Candle c2 = candles5m.get(candles5m.size() - 2);
            if (c1.close().doubleValue() > c1.open().doubleValue()
                    && c2.close().doubleValue() > c2.open().doubleValue()
                    && latest.close().doubleValue() > latest.open().doubleValue()) {
                bullishCount++; if (primaryReason.isEmpty()) primaryReason = "3 bullish candles";
            }
            if (c1.close().doubleValue() < c1.open().doubleValue()
                    && c2.close().doubleValue() < c2.open().doubleValue()
                    && latest.close().doubleValue() < latest.open().doubleValue()) {
                bearishCount++; if (primaryReason.isEmpty()) primaryReason = "3 bearish candles";
            }
        }

        // Signal 3: Large single candle
        double bodyPct = Math.abs(latest.close().doubleValue() - latest.open().doubleValue())
                / latest.open().doubleValue() * 100;
        if (bodyPct > 0.15) {
            if (latest.close().doubleValue() > latest.open().doubleValue()) {
                bullishCount++; if (primaryReason.isEmpty()) primaryReason = "big candle " + String.format("%.2f", bodyPct) + "%";
            } else {
                bearishCount++; if (primaryReason.isEmpty()) primaryReason = "big candle " + String.format("%.2f", bodyPct) + "%";
            }
        }

        // Require at least 2 signals or 1 strong signal
        String signal = null;
        boolean strongBullish = bullishCount >= 1 && (primaryReason.contains("breakout") || bodyPct > 0.15);
        boolean strongBearish = bearishCount >= 1 && (primaryReason.contains("breakout") || bodyPct > 0.15);

        if (bullishCount >= 2 || strongBullish) signal = "BULLISH";
        else if (bearishCount >= 2 || strongBearish) signal = "BEARISH";

        if (signal == null) return Optional.empty();

        SignalType signalType = "BULLISH".equals(signal) ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = "BULLISH".equals(signal) ? OptionType.CE : OptionType.PE;
        int confidence = Math.min(100, Math.max(bullishCount, bearishCount) * 30);

        StrategyDecision decision = new StrategyDecision(
                Instant.now(), underlying, signalType,
                request.underlyingCandles().getLast().close(), Optional.of(request.selectedOptionQuote().lastPrice()),
                Optional.empty(), Optional.of(request.selectedLotSize()), Optional.empty(),
                Optional.of(request.selectedInstrumentKey()), Optional.of(request.selectedStrike()),
                Optional.of(optionType), true, Optional.empty(), volumeSurge,
                BigDecimal.valueOf(confidence),
                List.of("MOMENTUM_BREAKOUT: " + primaryReason + " (" + Math.max(bullishCount, bearishCount) + " signals)")
        );
        return Optional.of(decision);
    }

    private IndexType mapToIndexType(UnderlyingSymbol underlying) {
        return switch (underlying) {
            case BANKNIFTY -> IndexType.BANKNIFTY;
            case SENSEX -> IndexType.SENSEX;
            default -> IndexType.NIFTY;
        };
    }

    private long resolveToken(IndexType idx) {
        return idx == IndexType.BANKNIFTY ? 260105L : 256265L;
    }
}
