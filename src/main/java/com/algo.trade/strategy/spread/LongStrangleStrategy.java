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
        double maxIvRank = ctx.config().getMaxIvRankForBuying().doubleValue();
        if (ctx.ivRank() >= maxIvRank) {
            log.debug("LongStrangle: IV rank {} >= max {}, skipping", ctx.ivRank(), maxIvRank);
            return false;
        }
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
