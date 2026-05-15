package com.algo.trade.strategy.spread;

import com.algo.trade.domain.Candle;
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
import java.util.stream.Collectors;

/**
 * Bull Call Spread — BUY ATM CE + SELL OTM CE.
 * Entry: EMA-9 > EMA-21 on 15-min candles (bullish trend).
 * Exit: SL%, target%, or expiry danger zone.
 */
@Component
public class BullCallSpreadStrategy extends AbstractSpreadStrategy {

    public BullCallSpreadStrategy(ExpiryCalendar expiryCalendar,
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
        List<Candle> candles = ctx.trendCandles();
        if (candles == null || candles.size() < 21) {
            log.debug("BullCallSpread: insufficient candles for EMA computation");
            return false;
        }

        List<BigDecimal> closes = candles.stream()
                .map(Candle::close)
                .collect(Collectors.toList());

        BigDecimal ema9 = emaIndicator.calculate(closes, 9);
        BigDecimal ema21 = emaIndicator.calculate(closes, 21);

        // EMA crossover must be bullish
        boolean bullish = ema9.compareTo(ema21) > 0;
        if (!bullish) {
            log.debug("BullCallSpread: EMA9={} <= EMA21={}, not bullish", ema9, ema21);
            return false;
        }

        // Trend strength: EMA gap must be > 0.1% of price (avoid flat crossovers)
        double emaGapPct = ema9.subtract(ema21).abs().doubleValue() / ema21.doubleValue() * 100;
        if (emaGapPct < 0.1) {
            log.debug("BullCallSpread: EMA gap {:.3f}% < 0.1% (weak crossover), skipping", emaGapPct);
            return false;
        }

        // Volume confirmation: latest candle volume > average of last 5
        if (candles.size() >= 5) {
            long latestVol = candles.getLast().volume();
            double avgVol = candles.subList(candles.size() - 5, candles.size()).stream()
                    .mapToLong(Candle::volume).average().orElse(0);
            if (latestVol < avgVol * 1.1) {
                log.debug("BullCallSpread: volume {} < 1.1x avg {:.0f}, skipping", latestVol, avgVol);
                return false;
            }
        }

        // IV Rank < 50 — don't buy expensive spreads
        if (ctx.ivRank() > 50) {
            log.debug("BullCallSpread: IV rank {:.1f} > 50 (premiums expensive), skipping", ctx.ivRank());
            return false;
        }

        log.debug("BullCallSpread: entry passed — EMA9={}, EMA21={}, gap={:.3f}%", ema9, ema21, emaGapPct);
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        int spreadStrikes = ctx.config().getSpreadStrikes();
        int sellStrike = atm + spreadStrikes * indexType.strikeInterval();
        LocalDate expiry = currentWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        String buyKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String sellKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(sellStrike), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (buyKey == null || sellKey == null) {
            log.warn("BullCallSpread: could not find instruments for ATM={} or sell strike={}", atm, sellStrike);
            return List.of();
        }

        return List.of(
                new SpreadLeg(buyKey, atm, OptionType.CE, OrderSide.BUY, qty, expiry),
                new SpreadLeg(sellKey, sellStrike, OptionType.CE, OrderSide.SELL, qty, expiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        BigDecimal entryNet = netDebit(group.legs(), group.entryPrices());
        BigDecimal currentNet = netDebit(group.legs(), currentPrices);

        if (slHit(entryNet, currentNet, config.getStopLossPercent())) {
            log.info("BullCallSpread: SL hit for group {}", group.groupId());
            return true;
        }
        if (targetHit(entryNet, currentNet, config.getTargetPercent())) {
            log.info("BullCallSpread: target hit for group {}", group.groupId());
            return true;
        }
        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("BullCallSpread: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.BULL_CALL_SPREAD;
    }
}
