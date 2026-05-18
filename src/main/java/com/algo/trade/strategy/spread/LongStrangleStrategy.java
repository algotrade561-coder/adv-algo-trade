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
 * Entry: IV rank < maxIvRankForBuying, BB squeeze, pre-event or early session.
 * Exit: SL%, target%, vega-collapse, or expiry danger zone.
 */
@Component
public class LongStrangleStrategy extends AbstractSpreadStrategy {

    /** BB bandwidth threshold — below this = squeeze detected. Industry standard ~1.0%. */
    private static final double BB_SQUEEZE_THRESHOLD_PCT = 1.0;

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
        IndexType indexType = ctx.indexType();

        // P0 #3: DTE check — don't enter with < 2 days to expiry (theta catastrophic)
        long dte = expiryCalendar.daysToExpiry(indexType);
        if (dte < 2) {
            log.debug("LongStrangle: DTE={} < 2, theta too high — skipping", dte);
            return false;
        }

        // ── Filter 1: IV Rank must be low (< 30) — buy when premiums are cheap ──
        double maxIvRank = Math.min(ctx.config().getMaxIvRankForBuying().doubleValue(), 30.0);
        if (ctx.ivRank() >= maxIvRank) {
            log.debug("LongStrangle: IV rank {} >= max {}, skipping",
                    String.format("%.1f", ctx.ivRank()), maxIvRank);
            return false;
        }

        // ── Filter 2: Bollinger Band squeeze — only enter when bands are tight ──
        var candles = ctx.trendCandles();
        if (candles.size() >= 20) {
            double bandwidth = com.algo.trade.indicator.BollingerBandIndicator.bandwidth(candles, 20);
            if (bandwidth >= 0 && bandwidth > BB_SQUEEZE_THRESHOLD_PCT) {
                log.debug("LongStrangle: BB bandwidth {}% > {}% (no squeeze), skipping",
                        String.format("%.2f", bandwidth), BB_SQUEEZE_THRESHOLD_PCT);
                return false;
            }
            if (bandwidth >= 0) {
                log.debug("LongStrangle: BB squeeze detected, bandwidth={}%", String.format("%.2f", bandwidth));
            }
        }

        // ── Filter 3: Pre-event day OR squeeze required ──
        boolean isPreEvent = marketGuard.isPreEventDay() || marketGuard.isEventDay();
        if (!isPreEvent && (candles.size() < 20)) {
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

        // P1 #4: VIX gate — allow event-day entries even with VIX > 20
        double vix = marketGuard.getCurrentVix();
        if (!isPreEvent && vix > 20) {
            log.debug("LongStrangle: VIX={} > 20 and not pre-event (premiums expensive), skipping",
                    String.format("%.1f", vix));
            return false;
        }

        log.info("LongStrangle: all entry filters passed — ivRank={}, vix={}, preEvent={}, earlySession={}, dte={}",
                String.format("%.1f", ctx.ivRank()), String.format("%.1f", vix), isPreEvent, earlySession, dte);
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        int otmStrikes = ctx.config().getOtmStrikes(indexType);
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

        // Capital cap enforced centrally in AbstractSpreadStrategy.evaluateAndEnter
        // (via StrategyConfig.getMaxCapitalPerTrade — also shrinks lots when premium allows it).
        return List.of(
                new SpreadLeg(ceKey, ceStrike, OptionType.CE, OrderSide.BUY, qty, expiry),
                new SpreadLeg(peKey, peStrike, OptionType.PE, OrderSide.BUY, qty, expiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.LONG_STRANGLE;
    }
}
