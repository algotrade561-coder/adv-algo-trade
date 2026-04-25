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
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Diagonal Spread — SELL OTM current weekly expiry + BUY ATM next weekly expiry.
 * Bullish → CE, Bearish → PE.
 * Exit: SL%, target%, or near-expiry sell leg within 1 day.
 */
@Component
public class DiagonalSpreadStrategy extends AbstractSpreadStrategy {

    /** Bias: true = bullish (CE), false = bearish (PE). */
    private boolean bullishBias = true;

    public DiagonalSpreadStrategy(ExpiryCalendar expiryCalendar,
                                  InstrumentCache instrumentCache,
                                  MarketDataService marketDataService,
                                  ExecutionEngine executionEngine,
                                  StrategySignalCsvRecorder signalRecorder,
                                  EmaIndicator emaIndicator,
                                  AtrIndicator atrIndicator) {
        super(expiryCalendar, instrumentCache, marketDataService,
              executionEngine, signalRecorder, emaIndicator, atrIndicator);
    }

    public void setBullishBias(boolean bullishBias) {
        this.bullishBias = bullishBias;
    }

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        // Diagonal spread is a directional + theta play — always eligible when enabled
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        int otmStrikes = ctx.config().getOtmStrikes();
        int interval = indexType.strikeInterval();
        LocalDate nearExpiry = currentWeeklyExpiry(indexType);
        LocalDate farExpiry = nextWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        OptionType optType = bullishBias ? OptionType.CE : OptionType.PE;
        int sellStrike = bullishBias
                ? atm + otmStrikes * interval
                : atm - otmStrikes * interval;

        String sellKey = instrumentCache.findOption(ctx.underlying(), nearExpiry,
                BigDecimal.valueOf(sellStrike), optType)
                .map(i -> i.instrumentKey()).orElse(null);
        String buyKey = instrumentCache.findOption(ctx.underlying(), farExpiry,
                BigDecimal.valueOf(atm), optType)
                .map(i -> i.instrumentKey()).orElse(null);

        if (sellKey == null || buyKey == null) {
            log.warn("DiagonalSpread: could not find instruments for sell={} or buy ATM={}", sellStrike, atm);
            return List.of();
        }

        return List.of(
                new SpreadLeg(sellKey, sellStrike, optType, OrderSide.SELL, qty, nearExpiry),
                new SpreadLeg(buyKey, atm, optType, OrderSide.BUY, qty, farExpiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        StrategyConfig config = new StrategyConfig(strategyType());
        BigDecimal entryNet = netDebit(group.legs(), group.entryPrices());
        BigDecimal currentNet = netDebit(group.legs(), currentPrices);

        if (slHit(entryNet, currentNet, config.getStopLossPercent())) {
            log.info("DiagonalSpread: SL hit for group {}", group.groupId());
            return true;
        }
        if (targetHit(entryNet, currentNet, config.getTargetPercent())) {
            log.info("DiagonalSpread: target hit for group {}", group.groupId());
            return true;
        }

        // Exit near-expiry sell leg if within 1 day of expiry
        IndexType indexType = IndexType.from(group.underlying());
        if (expiryCalendar.isNearExpiry(indexType, 1)) {
            log.info("DiagonalSpread: near-expiry sell leg within 1 day for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    protected StrategyType strategyType() {
        return StrategyType.DIAGONAL_SPREAD;
    }
}
