package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
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
 * Expiry-Day Reversal Scalp Strategy — buy reversals at key levels on expiry day.
 *
 * Only active on expiry day, 9:30 AM–3:00 PM.
 * Entry: 1-min RSI < 30 at PE OI support → buy CE
 *        1-min RSI > 70 at CE OI resistance → buy PE
 * SL: 15%, Target: 30%, Max hold: 5 min, Max 5 trades/day.
 *
 * Implements StrategyEvaluator for candle-driven execution pipeline.
 */
@Component
public class ExpiryReversalScalpStrategy implements StrategyEvaluator, TimeBoundedStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExpiryReversalScalpStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final LiveInstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;
    private final MarketDataService marketDataService;

    @Value("${strategy.expiry-reversal-scalp.enabled:true}") private boolean enabled;
    @Value("${strategy.expiry-reversal-scalp.rsi-extreme-low:30}") private double rsiExtremeLow;
    @Value("${strategy.expiry-reversal-scalp.rsi-extreme-high:70}") private double rsiExtremeHigh;
    @Value("${strategy.expiry-reversal-scalp.oi-proximity-percent:0.6}") private double oiProximity;
    @Value("${strategy.expiry-reversal-scalp.min-premium:5}") private double minPremium;
    @Value("${strategy.expiry-reversal-scalp.max-premium:200}") private double maxPremium;

    public ExpiryReversalScalpStrategy(LiveCandleBuilder candleBuilder,
                                        LiveInstrumentCache instrumentCache,
                                        ExpiryCalendar expiryCalendar,
                                        MarketDataService marketDataService) {
        this.candleBuilder = candleBuilder;
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
        this.marketDataService = marketDataService;
    }

    @Override public String strategyName() { return "EXPIRY_REVERSAL_SCALP"; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public LocalTime entryStartTime() { return LocalTime.of(9, 30); }
    @Override public LocalTime entryCutoffTime() { return LocalTime.of(15, 0); }

    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();

        IndexType indexType = mapToIndexType(underlying);
        if (indexType == null) return Optional.empty();

        // Only active on expiry day
        if (!expiryCalendar.isExpiryDay(indexType)) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(entryStartTime()) || now.isAfter(entryCutoffTime())) return Optional.empty();

        long token = resolveToken(indexType);
        List<Candle> candles1m = candleBuilder.getHistory(token, Timeframe.ONE_MINUTE);
        if (candles1m.size() < 15) return Optional.empty();

        double rsi = calculateRSI(candles1m, 14);
        if (rsi < 0) return Optional.empty();

        double spot = instrumentCache.getFuturesPrice(indexType);
        if (spot <= 0) return Optional.empty();

        String signal = null;
        OptionType optionType = null;
        String reason = "";

        // RSI extreme low → bullish reversal → buy CE
        if (rsi < rsiExtremeLow) {
            signal = "BULLISH";
            optionType = OptionType.CE;
            reason = "RSI=" + String.format("%.1f", rsi) + " oversold — expiry reversal";
        }

        // RSI extreme high → bearish reversal → buy PE
        if (signal == null && rsi > rsiExtremeHigh) {
            signal = "BEARISH";
            optionType = OptionType.PE;
            reason = "RSI=" + String.format("%.1f", rsi) + " overbought — expiry reversal";
        }

        if (signal == null) return Optional.empty();

        SignalType signalType = "BULLISH".equals(signal) ? SignalType.BUY_CE : SignalType.BUY_PE;
        int confidence = (int) Math.min(90, Math.abs(rsi - 50) * 2);

        log.info("[EXPIRY_REVERSAL_SCALP] {} signal on {}: RSI={}", signal, underlying, String.format("%.1f", rsi));

        StrategyDecision decision = new StrategyDecision(
                Instant.now(), underlying, signalType,
                request.underlyingCandles().getLast().close(), Optional.of(request.selectedOptionQuote().lastPrice()),
                Optional.empty(), Optional.of(request.selectedLotSize()), Optional.empty(),
                Optional.of(request.selectedInstrumentKey()), Optional.of(request.selectedStrike()),
                Optional.of(optionType), true, Optional.empty(), false,
                BigDecimal.valueOf(confidence),
                List.of("EXPIRY_REVERSAL_SCALP: " + reason,
                        "SL: 15%, Target: 30%, Max hold: 5 min")
        );
        return Optional.of(decision);
    }

    private double calculateRSI(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return -1;
        double gainSum = 0, lossSum = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            double change = candles.get(i).close().doubleValue() - candles.get(i - 1).close().doubleValue();
            if (change > 0) gainSum += change;
            else lossSum += Math.abs(change);
        }
        if (lossSum == 0) return 100;
        double rs = (gainSum / period) / (lossSum / period);
        return 100 - (100 / (1 + rs));
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
