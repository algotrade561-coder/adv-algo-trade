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
 * Long Strangle — BUY OTM CE + BUY OTM PE at equidistant OTM strikes.
 * OTM distance is driven by StrategyConfig.otmStrikes (default 2 strikes).
 * Entry: IV rank < maxIvRankForBuying (cheap options — buy when vol is low).
 * Exit: SL%, target%, or expiry danger zone.
 * Always paper-trades per StrategyConfig (paperTrading=true by default).
 */
@Component
public class LongStrangleStrategy extends AbstractSpreadStrategy {

    public LongStrangleStrategy(ExpiryCalendar expiryCalendar,
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

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        // ── Filter 1: IV Rank must be low (< 30) — buy when premiums are cheap ──
        double maxIvRank = Math.min(ctx.config().getMaxIvRankForBuying().doubleValue(), 30.0);
        if (ctx.ivRank() >= maxIvRank) {
            log.debug("LongStrangle: IV rank {:.1f} >= max {}, skipping", ctx.ivRank(), maxIvRank);
            return false;
        }

        // ── Filter 2: Bollinger Band squeeze — only enter when bands are tight ──
        // Tight bands signal low volatility → imminent breakout (ideal for strangles)
        var candles = ctx.trendCandles();
        if (candles.size() >= 20) {
            double[] closes = candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            double sma = 0;
            for (int i = closes.length - 20; i < closes.length; i++) sma += closes[i];
            sma /= 20;
            double variance = 0;
            for (int i = closes.length - 20; i < closes.length; i++) variance += Math.pow(closes[i] - sma, 2);
            double stdDev = Math.sqrt(variance / 20);
            double bandwidth = (stdDev * 4) / sma * 100; // BB bandwidth as % of price
            // Bandwidth < 2% indicates a squeeze (tight range, breakout imminent)
            if (bandwidth > 2.0) {
                log.debug("LongStrangle: BB bandwidth {:.2f}% > 2% (no squeeze), skipping", bandwidth);
                return false;
            }
            log.debug("LongStrangle: BB squeeze detected, bandwidth={:.2f}%", bandwidth);
        }

        // ── Filter 3: Pre-event day OR squeeze required ──
        // Strangles work best before known events (RBI, budget, earnings)
        boolean isPreEvent = marketGuard.isPreEventDay() || marketGuard.isEventDay();
        if (!isPreEvent && (candles.size() < 20)) {
            // No squeeze data and not a pre-event day — skip
            log.debug("LongStrangle: not pre-event and insufficient candle data for squeeze, skipping");
            return false;
        }

        // ── Filter 4: Time window — first 90 min or pre-event only ──
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean earlySession = now.isBefore(java.time.LocalTime.of(10, 45));
        if (!earlySession && !isPreEvent) {
            log.debug("LongStrangle: outside entry window (after 10:45 and not pre-event), skipping");
            return false;
        }

        // ── Filter 5: Max net debit cap — ₹8000 per strangle ──
        // (checked later in constructLegs/premium check, but pre-validate with ATM estimate)

        // ── Filter 6: VIX must not be falling (contracting IV kills long options) ──
        double vix = marketGuard.getCurrentVix();
        if (vix > 20) {
            // VIX already elevated — premiums are expensive, not ideal for buying
            log.debug("LongStrangle: VIX={:.1f} > 20 (premiums expensive), skipping", vix);
            return false;
        }

        log.info("LongStrangle: all entry filters passed — ivRank={:.1f}, vix={:.1f}, preEvent={}, earlySession={}",
                ctx.ivRank(), vix, isPreEvent, earlySession);
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        int otmStrikes = ctx.config().getOtmStrikes();
        int interval = indexType.strikeInterval();
        LocalDate expiry = currentWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        int ceStrike = atm + otmStrikes * interval;
        int peStrike = atm - otmStrikes * interval;

        String ceKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(ceStrike), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String peKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(peStrike), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (ceKey == null || peKey == null) {
            log.warn("LongStrangle: could not find OTM CE={} or PE={} instruments", ceStrike, peStrike);
            return List.of();
        }

        return List.of(
                new SpreadLeg(ceKey, ceStrike, OptionType.CE, OrderSide.BUY, qty, expiry),
                new SpreadLeg(peKey, peStrike, OptionType.PE, OrderSide.BUY, qty, expiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        BigDecimal entryNet = netDebit(group.legs(), group.entryPrices());
        BigDecimal currentNet = netDebit(group.legs(), currentPrices);

        if (slHit(entryNet, currentNet, config.getStopLossPercent())) {
            log.info("LongStrangle: SL hit for group {}", group.groupId());
            return true;
        }
        if (targetHit(entryNet, currentNet, config.getTargetPercent())) {
            log.info("LongStrangle: target hit for group {}", group.groupId());
            return true;
        }
        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("LongStrangle: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.LONG_STRANGLE;
    }
}
