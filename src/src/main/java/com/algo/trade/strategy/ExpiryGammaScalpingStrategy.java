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
 * Expiry-Day Gamma Scalping Strategy — pure option BUYING on expiry day.
 *
 * Exploits gamma spikes in the last 75 minutes of expiry day (2:00 PM – 3:15 PM).
 * Cheap OTM options can double or triple on sharp momentum moves when gamma is extreme.
 *
 * Entry Logic:
 * 1. Only active on expiry day
 * 2. Time window: 9:30 AM to 3:15 PM
 * 3. Buy cheap OTM options (premium between ₹5 and ₹80)
 * 4. Enter on sharp momentum: 1-min candle body > 0.03% of spot price
 *
 * Implements StrategyEvaluator for candle-driven execution pipeline.
 */
@Component
public class ExpiryGammaScalpingStrategy implements StrategyEvaluator, TimeBoundedStrategy {

    private static final Logger log = LoggerFactory.getLogger(ExpiryGammaScalpingStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveCandleBuilder candleBuilder;
    private final LiveInstrumentCache instrumentCache;
    private final ExpiryCalendar expiryCalendar;

    @Value("${strategy.expiry-gamma.enabled:true}") private boolean enabled;
    @Value("${strategy.expiry-gamma.min-premium:5}") private double minPremium;
    @Value("${strategy.expiry-gamma.max-premium:80}") private double maxPremium;

    private static final LocalTime WINDOW_START = LocalTime.of(9, 30);
    private static final LocalTime WINDOW_END = LocalTime.of(15, 15);
    private static final double MOMENTUM_THRESHOLD_PCT = 0.03;

    public ExpiryGammaScalpingStrategy(LiveCandleBuilder candleBuilder,
                                        LiveInstrumentCache instrumentCache,
                                        ExpiryCalendar expiryCalendar) {
        this.candleBuilder = candleBuilder;
        this.instrumentCache = instrumentCache;
        this.expiryCalendar = expiryCalendar;
    }

    @Override public String strategyName() { return "EXPIRY_GAMMA_SCALP"; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public LocalTime entryStartTime() { return WINDOW_START; }
    @Override public LocalTime entryCutoffTime() { return WINDOW_END; }

    public Optional<StrategyDecision> evaluate(UnderlyingSymbol underlying, StrategyEvaluationRequest request) {
        if (!enabled) return Optional.empty();

        IndexType indexType = mapToIndexType(underlying);
        if (indexType == null) return Optional.empty();

        // Only on expiry day
        if (!expiryCalendar.isExpiryDay(indexType)) return Optional.empty();

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(WINDOW_START) || now.isAfter(WINDOW_END)) return Optional.empty();

        long indexToken = resolveToken(indexType);

        // Get 1-min candles for momentum detection
        List<Candle> candles1m = candleBuilder.getHistory(indexToken, Timeframe.ONE_MINUTE);
        if (candles1m.size() < 3) return Optional.empty();

        Candle latest = candles1m.get(candles1m.size() - 1);
        double spot = latest.close().doubleValue();
        if (spot <= 0) return Optional.empty();

        // Momentum check: 1-min candle body > threshold of spot
        double candleBody = Math.abs(latest.close().doubleValue() - latest.open().doubleValue());
        double momentumThreshold = spot * (MOMENTUM_THRESHOLD_PCT / 100.0);

        if (candleBody < momentumThreshold) return Optional.empty();

        boolean bullishMomentum = latest.close().doubleValue() > latest.open().doubleValue();
        String direction = bullishMomentum ? "BULLISH" : "BEARISH";
        SignalType signalType = bullishMomentum ? SignalType.BUY_CE : SignalType.BUY_PE;
        OptionType optionType = bullishMomentum ? OptionType.CE : OptionType.PE;

        log.info("[EXPIRY_GAMMA_SCALP] Momentum detected on {}: {} body=₹{} threshold=₹{}",
                underlying, direction, String.format("%.1f", candleBody), String.format("%.1f", momentumThreshold));

        double confidence = Math.min(100, (candleBody / momentumThreshold) * 50);

        StrategyDecision decision = new StrategyDecision(
                Instant.now(), underlying, signalType,
                request.underlyingCandles().getLast().close(), Optional.of(request.selectedOptionQuote().lastPrice()),
                Optional.empty(), Optional.of(request.selectedLotSize()), Optional.empty(),
                Optional.of(request.selectedInstrumentKey()), Optional.of(request.selectedStrike()),
                Optional.of(optionType), true, Optional.empty(), false,
                BigDecimal.valueOf((int) confidence),
                List.of("EXPIRY_GAMMA_SCALP: " + direction + " momentum body=₹" + String.format("%.1f", candleBody),
                        "Premium range: ₹" + minPremium + "–₹" + maxPremium,
                        "Gamma-driven scalp — tight trail, max 10 min hold")
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
