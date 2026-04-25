package com.algo.trade.strategy.spread;

import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.SpreadEvaluationContext;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.indicator.AtrIndicator;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Bear Put Spread — BUY ATM PE + SELL OTM PE.
 * Entry: EMA-9 < EMA-21 on 15-min candles (bearish trend).
 * Exit: SL%, target%, or expiry danger zone.
 */
@Component
public class BearPutSpreadStrategy extends AbstractSpreadStrategy {

    private List<Candle> candles;

    public BearPutSpreadStrategy(ExpiryCalendar expiryCalendar,
                                 InstrumentCache instrumentCache,
                                 MarketDataService marketDataService,
                                 ExecutionEngine executionEngine,
                                 StrategySignalCsvRecorder signalRecorder,
                                 EmaIndicator emaIndicator,
                                 AtrIndicator atrIndicator) {
        super(expiryCalendar, instrumentCache, marketDataService,
              executionEngine, signalRecorder, emaIndicator, atrIndicator);
    }

    public Optional<StrategyDecision> evaluate(SpreadEvaluationContext ctx, List<Candle> fifteenMinCandles) {
        this.candles = fifteenMinCandles;
        return evaluateAndEnter(ctx);
    }

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        if (candles == null || candles.size() < 21) {
            log.debug("BearPutSpread: insufficient candles for EMA computation");
            return false;
        }

        List<BigDecimal> closes = candles.stream()
                .map(Candle::close)
                .collect(Collectors.toList());

        BigDecimal ema9 = emaIndicator.calculate(closes, 9);
        BigDecimal ema21 = emaIndicator.calculate(closes, 21);

        boolean bearish = ema9.compareTo(ema21) < 0;
        log.debug("BearPutSpread: EMA9={}, EMA21={}, bearish={}", ema9, ema21, bearish);
        return bearish;
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
            return List.of();
        }

        return List.of(
                new SpreadLeg(buyKey, atm, OptionType.PE, OrderSide.BUY, qty, expiry),
                new SpreadLeg(sellKey, sellStrike, OptionType.PE, OrderSide.SELL, qty, expiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        StrategyConfig config = new StrategyConfig(strategyType());
        BigDecimal entryNet = netDebit(group.legs(), group.entryPrices());
        BigDecimal currentNet = netDebit(group.legs(), currentPrices);

        if (slHit(entryNet, currentNet, config.getStopLossPercent())) {
            log.info("BearPutSpread: SL hit for group {}", group.groupId());
            return true;
        }
        if (targetHit(entryNet, currentNet, config.getTargetPercent())) {
            log.info("BearPutSpread: target hit for group {}", group.groupId());
            return true;
        }
        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("BearPutSpread: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    protected StrategyType strategyType() {
        return StrategyType.BEAR_PUT_SPREAD;
    }
}
