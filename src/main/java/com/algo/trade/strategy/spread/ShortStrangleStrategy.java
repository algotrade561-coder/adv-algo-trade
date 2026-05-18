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
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Short Strangle — SELL OTM CE + SELL OTM PE.
 * Entry: IV rank > 50 AND MarketGuard safe for short premium.
 * Exit: short leg doubling, target decay, or expiry danger zone.
 */
@Component
public class ShortStrangleStrategy extends AbstractSpreadStrategy {

    private static final MathContext MC = MathContext.DECIMAL64;
    private final MarketGuard marketGuard;

    public ShortStrangleStrategy(ExpiryCalendar expiryCalendar,
                                 InstrumentCache instrumentCache,
                                 MarketDataService marketDataService,
                                 ExecutionEngine executionEngine,
                                 StrategySignalCsvRecorder signalRecorder,
                                 EmaIndicator emaIndicator,
                                 AtrIndicator atrIndicator,
                                 MarketGuard marketGuard,
                                 PositionGroupRepository positionGroupRepository) {
        super(expiryCalendar, instrumentCache, marketDataService,
              executionEngine, signalRecorder, emaIndicator, atrIndicator, positionGroupRepository);
        this.marketGuard = marketGuard;
    }

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        if (ctx.ivRank() <= 50) {
            log.debug("ShortStrangle: IV rank {} <= 50, skipping", ctx.ivRank());
            return false;
        }
        if (!marketGuard.isSafeForShortPremium()) {
            log.debug("ShortStrangle: MarketGuard blocks short premium");
            return false;
        }

        // Range-bound check: price within 1.5% of 20-period SMA
        var candles = ctx.trendCandles();
        if (candles != null && candles.size() >= 20) {
            double[] closes = candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            double sma = 0;
            for (int i = closes.length - 20; i < closes.length; i++) sma += closes[i];
            sma /= 20;
            double deviation = Math.abs(closes[closes.length - 1] - sma) / sma * 100;
            if (deviation > 1.5) {
                log.debug("ShortStrangle: price deviation {:.2f}% from SMA > 1.5% (trending), skipping", deviation);
                return false;
            }
        }

        // DTE >= 2 — don't sell strangles near expiry
        IndexType indexType = ctx.indexType();
        long dte = expiryCalendar.daysToExpiry(indexType);
        if (dte < 2) {
            log.debug("ShortStrangle: DTE={} < 2 (too close to expiry), skipping", dte);
            return false;
        }

        // Time window — enter before 12:00 PM
        java.time.LocalTime now = java.time.LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        if (now.isAfter(java.time.LocalTime.of(12, 0))) {
            log.debug("ShortStrangle: after 12:00 PM, skipping");
            return false;
        }

        log.info("ShortStrangle: entry filters passed — ivRank={:.1f}, dte={}", ctx.ivRank(), dte);
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

        int sellCeStrike = atm + otmStrikes * interval;
        int sellPeStrike = atm - otmStrikes * interval;

        String sellCeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(sellCeStrike), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String sellPeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(sellPeStrike), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (sellCeKey == null || sellPeKey == null) {
            log.warn("ShortStrangle: could not find instruments for CE={} or PE={}", sellCeStrike, sellPeStrike);
            return List.of();
        }

        return List.of(
                new SpreadLeg(sellCeKey, sellCeStrike, OptionType.CE, OrderSide.SELL, qty, expiry),
                new SpreadLeg(sellPeKey, sellPeStrike, OptionType.PE, OrderSide.SELL, qty, expiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.SHORT_STRANGLE;
    }
}
