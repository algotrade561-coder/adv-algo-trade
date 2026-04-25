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
                                AtrIndicator atrIndicator) {
        super(expiryCalendar, instrumentCache, marketDataService,
              executionEngine, signalRecorder, emaIndicator, atrIndicator);
    }

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        double maxIvRank = ctx.config().getMaxIvRankForBuying().doubleValue();
        if (ctx.ivRank() >= maxIvRank) {
            log.debug("LongStraddle: IV rank {} >= max {}, skipping", ctx.ivRank(), maxIvRank);
            return false;
        }
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
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        StrategyConfig config = new StrategyConfig(strategyType());
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
    protected StrategyType strategyType() {
        return StrategyType.LONG_STRADDLE;
    }
}
