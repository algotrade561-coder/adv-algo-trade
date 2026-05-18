package com.algo.trade.strategy.spread;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.SpreadEvaluationContext;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.indicator.AtrIndicator;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Butterfly — BUY 1 lot lower wing, SELL 2 lots ATM, BUY 1 lot upper wing.
 * Uses CE for bullish bias, PE for bearish bias.
 * Exit: SL%, target%, or expiry danger zone.
 */
@Component
public class ButterflyStrategy extends AbstractSpreadStrategy {

    /** Bias for leg construction: true = bullish (CE), false = bearish (PE). */
    private boolean bullishBias = true;

    public ButterflyStrategy(ExpiryCalendar expiryCalendar,
                             InstrumentCache instrumentCache,
                             MarketDataService marketDataService,
                             ExecutionEngine executionEngine,
                             StrategySignalCsvRecorder signalRecorder,
                             EmaIndicator emaIndicator,
                             AtrIndicator atrIndicator,
                             PositionGroupRepository positionGroupRepository) {
        super(expiryCalendar, instrumentCache, marketDataService,
              executionEngine, signalRecorder, emaIndicator, atrIndicator, positionGroupRepository);
    }

    public void setBullishBias(boolean bullishBias) {
        this.bullishBias = bullishBias;
    }

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        // Butterfly profits from range-bound markets — need confirmation the market is consolidating
        var candles = ctx.trendCandles();
        if (candles == null || candles.size() < 20) {
            log.debug("Butterfly: insufficient candles for range analysis");
            return false;
        }

        // IV Rank > 40 — butterfly benefits from IV contraction (sell 2x ATM)
        if (ctx.ivRank() < 40) {
            log.debug("Butterfly: IV rank {:.1f} < 40 (need elevated IV for premium selling), skipping", ctx.ivRank());
            return false;
        }

        // Range-bound check: price must be within 1% of 20-period SMA (consolidating)
        double[] closes = candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double sma = 0;
        for (int i = closes.length - 20; i < closes.length; i++) sma += closes[i];
        sma /= 20;
        double deviation = Math.abs(closes[closes.length - 1] - sma) / sma * 100;
        if (deviation > 1.0) {
            log.debug("Butterfly: price deviation {:.2f}% from SMA > 1% (not range-bound), skipping", deviation);
            return false;
        }

        // MarketGuard safe for short premium (butterfly has net short gamma)
        if (!marketGuard.isSafeForShortPremium()) {
            log.debug("Butterfly: MarketGuard blocks short premium");
            return false;
        }

        // DTE >= 2 — don't sell near expiry (gamma risk on the 2x short ATM leg)
        IndexType indexType = ctx.indexType();
        long dte = expiryCalendar.daysToExpiry(indexType);
        if (dte < 2) {
            log.debug("Butterfly: DTE={} < 2 (too close to expiry), skipping", dte);
            return false;
        }

        // Time window — enter before 12:00 PM
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (now.isAfter(java.time.LocalTime.of(12, 0))) {
            log.debug("Butterfly: after 12:00 PM, skipping");
            return false;
        }

        log.info("Butterfly: entry filters passed — ivRank={:.1f}, deviation={:.2f}%, dte={}", ctx.ivRank(), deviation, dte);
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        int spreadStrikes = ctx.config().getSpreadStrikes();
        int interval = indexType.strikeInterval();
        LocalDate expiry = currentWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        int lowerWing = atm - spreadStrikes * interval;
        int upperWing = atm + spreadStrikes * interval;
        OptionType optType = bullishBias ? OptionType.CE : OptionType.PE;

        String lowerKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(lowerWing), optType)
                .map(i -> i.instrumentKey()).orElse(null);
        String middleKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), optType)
                .map(i -> i.instrumentKey()).orElse(null);
        String upperKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(upperWing), optType)
                .map(i -> i.instrumentKey()).orElse(null);

        if (lowerKey == null || middleKey == null || upperKey == null) {
            log.warn("Butterfly: could not find all 3 instruments");
            return List.of();
        }

        List<SpreadLeg> legs = new ArrayList<>();
        legs.add(new SpreadLeg(lowerKey, lowerWing, optType, OrderSide.BUY, qty, expiry));
        legs.add(new SpreadLeg(middleKey, atm, optType, OrderSide.SELL, qty * 2, expiry));
        legs.add(new SpreadLeg(upperKey, upperWing, optType, OrderSide.BUY, qty, expiry));
        return legs;
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.BUTTERFLY;
    }
}
