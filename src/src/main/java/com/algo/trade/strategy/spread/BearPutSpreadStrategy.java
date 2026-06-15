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
import com.algo.trade.strategy.PipelineSignalCapture;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Bear Put Spread — BUY ATM PE + SELL OTM PE.
 * Entry: EMA-9 < EMA-21 on 15-min candles (bearish trend).
 * Exit: SL%, target%, or expiry danger zone.
 */
@Component
public class BearPutSpreadStrategy extends AbstractSpreadStrategy {

    public BearPutSpreadStrategy(ExpiryCalendar expiryCalendar,
                                 InstrumentCache instrumentCache,
                                 MarketDataService marketDataService,
                                 ExecutionEngine executionEngine,
                                 PipelineSignalCapture signalRecorder,
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
            log.debug("BearPutSpread: insufficient candles for EMA computation");
            return rejectEntry("insufficientTrendCandles(n=" + (candles == null ? 0 : candles.size()) + ")");
        }

        List<BigDecimal> closes = candles.stream()
                .map(Candle::close)
                .collect(Collectors.toList());

        BigDecimal ema9 = emaIndicator.calculate(closes, 9);
        BigDecimal ema21 = emaIndicator.calculate(closes, 21);

        // EMA crossover must be bearish
        boolean bearish = ema9.compareTo(ema21) < 0;
        if (!bearish) {
            log.debug("BearPutSpread: EMA9={} >= EMA21={}, not bearish", ema9, ema21);
            return rejectEntry("emaNotBearish(ema9=" + ema9 + ",ema21=" + ema21 + ")");
        }

        // Trend strength: EMA gap must be > 0.1% of price (avoid flat crossovers)
        double emaGapPct = ema21.subtract(ema9).doubleValue() / ema21.doubleValue() * 100;
        if (emaGapPct < 0.1) {
            log.debug("BearPutSpread: EMA gap {:.3f}% < 0.1% (weak crossover), skipping", emaGapPct);
            return rejectEntry("emaGapTooSmall(gap=" + String.format("%.3f", emaGapPct) + "%)");
        }

        // Volume confirmation: latest candle volume > average of last 5
        if (candles.size() >= 5) {
            long latestVol = candles.getLast().volume();
            double avgVol = candles.subList(candles.size() - 5, candles.size()).stream()
                    .mapToLong(Candle::volume).average().orElse(0);
            if (latestVol < avgVol * 1.1) {
                log.debug("BearPutSpread: volume {} < 1.1x avg {:.0f}, skipping", latestVol, avgVol);
                return rejectEntry("lowVolume(latest=" + latestVol + ",avg5=" + String.format("%.0f", avgVol) + ")");
            }
        }

        // IV Rank < 50 — don't buy expensive spreads
        if (ctx.ivRank() > 50) {
            log.debug("BearPutSpread: IV rank {:.1f} > 50 (premiums expensive), skipping", ctx.ivRank());
            return rejectEntry("ivRankTooHigh(ivRank=" + String.format("%.1f", ctx.ivRank()) + ",max=50.0)");
        }

        log.debug("BearPutSpread: entry passed — EMA9={}, EMA21={}, gap={:.3f}%", ema9, ema21, emaGapPct);
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        int spreadStrikes = ctx.config().getSpreadStrikes();
        int sellStrike = atm - spreadStrikes * indexType.strikeInterval();
        LocalDate expiry = currentWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        String buyKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);
        String sellKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(sellStrike), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (buyKey == null || sellKey == null) {
            log.warn("BearPutSpread: could not find instruments for ATM={} or sell strike={}", atm, sellStrike);
            rejectEntryLegs("missingInstruments(buyAtm=" + atm + ",sell=" + sellStrike + ")");
            return List.of();
        }

        return List.of(
                new SpreadLeg(buyKey, atm, OptionType.PE, OrderSide.BUY, qty, expiry),
                new SpreadLeg(sellKey, sellStrike, OptionType.PE, OrderSide.SELL, qty, expiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.BEAR_PUT_SPREAD;
    }
}
