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
 * Long Straddle — BUY ATM CE + BUY ATM PE at same strike.
 * Entry: IV rank < maxIvRankForBuying.
 * Exit: SL%, target%, or expiry danger zone.
 */
@Component
public class LongStraddleStrategy extends AbstractSpreadStrategy {

    public LongStraddleStrategy(ExpiryCalendar expiryCalendar,
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
            log.debug("LongStraddle: IV rank {:.1f} >= max {}, skipping", ctx.ivRank(), maxIvRank);
            return false;
        }

        // ── Filter 2: Bollinger Band squeeze — only enter when bands are tight ──
        var candles = ctx.trendCandles();
        if (candles.size() >= 20) {
            double[] closes = candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            double sma = 0;
            for (int i = closes.length - 20; i < closes.length; i++) sma += closes[i];
            sma /= 20;
            double variance = 0;
            for (int i = closes.length - 20; i < closes.length; i++) variance += Math.pow(closes[i] - sma, 2);
            double stdDev = Math.sqrt(variance / 20);
            double bandwidth = (stdDev * 4) / sma * 100;
            if (bandwidth > 2.0) {
                log.debug("LongStraddle: BB bandwidth {:.2f}% > 2% (no squeeze), skipping", bandwidth);
                return false;
            }
            log.debug("LongStraddle: BB squeeze detected, bandwidth={:.2f}%", bandwidth);
        }

        // ── Filter 3: Pre-event day OR squeeze required ──
        boolean isPreEvent = marketGuard.isPreEventDay() || marketGuard.isEventDay();
        if (!isPreEvent && (candles.size() < 20)) {
            log.debug("LongStraddle: not pre-event and insufficient candle data for squeeze, skipping");
            return false;
        }

        // ── Filter 4: Time window — first 90 min or pre-event only ──
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        boolean earlySession = now.isBefore(java.time.LocalTime.of(10, 45));
        if (!earlySession && !isPreEvent) {
            log.debug("LongStraddle: outside entry window (after 10:45 and not pre-event), skipping");
            return false;
        }

        // ── Filter 5: VIX must not be elevated (premiums expensive) ──
        double vix = marketGuard.getCurrentVix();
        if (vix > 20) {
            log.debug("LongStraddle: VIX={:.1f} > 20 (premiums expensive), skipping", vix);
            return false;
        }

        log.info("LongStraddle: all entry filters passed — ivRank={:.1f}, vix={:.1f}, preEvent={}, earlySession={}",
                ctx.ivRank(), vix, isPreEvent, earlySession);
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        LocalDate expiry = currentWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        String ceKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String peKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (ceKey == null || peKey == null) {
            log.warn("LongStraddle: could not find CE or PE instruments for ATM={}", atm);
            return List.of();
        }

        return List.of(
                new SpreadLeg(ceKey, atm, OptionType.CE, OrderSide.BUY, qty, expiry),
                new SpreadLeg(peKey, atm, OptionType.PE, OrderSide.BUY, qty, expiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        BigDecimal entryNet = netDebit(group.legs(), group.entryPrices());
        BigDecimal currentNet = netDebit(group.legs(), currentPrices);

        if (slHit(entryNet, currentNet, config.getStopLossPercent())) {
            log.info("LongStraddle: SL hit for group {}", group.groupId());
            return true;
        }
        if (targetHit(entryNet, currentNet, config.getTargetPercent())) {
            log.info("LongStraddle: target hit for group {}", group.groupId());
            return true;
        }
        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("LongStraddle: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.LONG_STRADDLE;
    }
}
