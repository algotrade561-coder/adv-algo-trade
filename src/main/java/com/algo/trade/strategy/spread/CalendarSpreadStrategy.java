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
import java.util.List;
import java.util.Map;

/**
 * Calendar Spread — SELL near-expiry ATM + BUY next-expiry ATM (same strike).
 * CE for neutral-to-bullish, PE for bearish.
 * Exit: SL%, target%, or near-expiry leg within 1 day.
 */
@Component
public class CalendarSpreadStrategy extends AbstractSpreadStrategy {

    /** Bias: true = neutral-to-bullish (CE), false = bearish (PE). */
    private boolean bullishBias = true;

    public CalendarSpreadStrategy(ExpiryCalendar expiryCalendar,
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
        // Calendar spread profits from theta decay when underlying stays near ATM
        var candles = ctx.trendCandles();
        if (candles == null || candles.size() < 20) {
            log.debug("CalendarSpread: insufficient candles for range analysis");
            return false;
        }

        // IV Rank > 30 — calendar benefits from front-month IV being higher than back-month
        if (ctx.ivRank() < 30) {
            log.debug("CalendarSpread: IV rank {:.1f} < 30 (need moderate IV), skipping", ctx.ivRank());
            return false;
        }

        // Range-bound check: BB bandwidth < 3% (not trending strongly)
        double[] closes = candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
        double sma = 0;
        for (int i = closes.length - 20; i < closes.length; i++) sma += closes[i];
        sma /= 20;
        double variance = 0;
        for (int i = closes.length - 20; i < closes.length; i++) variance += Math.pow(closes[i] - sma, 2);
        double stdDev = Math.sqrt(variance / 20);
        double bandwidth = (stdDev * 4) / sma * 100;
        if (bandwidth > 3.0) {
            log.debug("CalendarSpread: BB bandwidth {:.2f}% > 3% (too volatile for calendar), skipping", bandwidth);
            return false;
        }

        // MarketGuard safe for short premium (selling near-expiry leg)
        if (!marketGuard.isSafeForShortPremium()) {
            log.debug("CalendarSpread: MarketGuard blocks short premium");
            return false;
        }

        // DTE >= 2 — don't sell near-expiry leg too close to expiry
        IndexType indexType = ctx.indexType();
        long dte = expiryCalendar.daysToExpiry(indexType);
        if (dte < 2) {
            log.debug("CalendarSpread: DTE={} < 2 (too close to expiry), skipping", dte);
            return false;
        }

        // Time window — enter before 12:00 PM
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (now.isAfter(java.time.LocalTime.of(12, 0))) {
            log.debug("CalendarSpread: after 12:00 PM, skipping");
            return false;
        }

        log.info("CalendarSpread: entry filters passed — ivRank={:.1f}, bandwidth={:.2f}%, dte={}", ctx.ivRank(), bandwidth, dte);
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        LocalDate nearExpiry = currentWeeklyExpiry(indexType);
        LocalDate farExpiry = nextWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();
        OptionType optType = bullishBias ? OptionType.CE : OptionType.PE;

        String sellKey = instrumentCache.findOption(ctx.underlying(), nearExpiry,
                BigDecimal.valueOf(atm), optType)
                .map(i -> i.instrumentKey()).orElse(null);
        String buyKey = instrumentCache.findOption(ctx.underlying(), farExpiry,
                BigDecimal.valueOf(atm), optType)
                .map(i -> i.instrumentKey()).orElse(null);

        if (sellKey == null || buyKey == null) {
            log.warn("CalendarSpread: could not find instruments for ATM={}", atm);
            return List.of();
        }

        return List.of(
                new SpreadLeg(sellKey, atm, optType, OrderSide.SELL, qty, nearExpiry),
                new SpreadLeg(buyKey, atm, optType, OrderSide.BUY, qty, farExpiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        BigDecimal entryNet = netDebit(group.legs(), group.entryPrices());
        BigDecimal currentNet = netDebit(group.legs(), currentPrices);

        if (slHit(entryNet, currentNet, config.getStopLossPercent())) {
            log.info("CalendarSpread: SL hit for group {}", group.groupId());
            return true;
        }
        if (targetHit(entryNet, currentNet, config.getTargetPercent())) {
            log.info("CalendarSpread: target hit for group {}", group.groupId());
            return true;
        }

        // Exit near-expiry leg if within 1 day of expiry
        IndexType indexType = IndexType.from(group.underlying());
        if (expiryCalendar.isNearExpiry(indexType, 1)) {
            log.info("CalendarSpread: near-expiry leg within 1 day for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.CALENDAR_SPREAD;
    }
}
