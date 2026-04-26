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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Iron Condor — SELL OTM CE + BUY further OTM CE + SELL OTM PE + BUY further OTM PE.
 * Entry: IV rank > 40 AND MarketGuard safe for short premium.
 * Exit: short leg doubling, target decay, SL expansion, or expiry danger zone.
 */
@Component
public class IronCondorStrategy extends AbstractSpreadStrategy {

    private static final MathContext MC = MathContext.DECIMAL64;
    private final MarketGuard marketGuard;

    public IronCondorStrategy(ExpiryCalendar expiryCalendar,
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
        if (ctx.ivRank() <= 40) {
            log.debug("IronCondor: IV rank {} <= 40, skipping", ctx.ivRank());
            return false;
        }
        if (!marketGuard.isSafeForShortPremium()) {
            log.debug("IronCondor: MarketGuard blocks short premium");
            return false;
        }
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        int otmStrikes = ctx.config().getOtmStrikes();
        int spreadStrikes = ctx.config().getSpreadStrikes();
        int interval = indexType.strikeInterval();
        LocalDate expiry = currentWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        int sellCeStrike = atm + otmStrikes * interval;
        int buyCeStrike = atm + spreadStrikes * interval;
        int sellPeStrike = atm - otmStrikes * interval;
        int buyPeStrike = atm - spreadStrikes * interval;

        List<SpreadLeg> legs = new ArrayList<>();

        String sellCeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(sellCeStrike), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String buyCeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(buyCeStrike), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String sellPeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(sellPeStrike), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);
        String buyPeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(buyPeStrike), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (sellCeKey == null || buyCeKey == null || sellPeKey == null || buyPeKey == null) {
            log.warn("IronCondor: could not find all 4 instruments");
            return List.of();
        }

        legs.add(new SpreadLeg(sellCeKey, sellCeStrike, OptionType.CE, OrderSide.SELL, qty, expiry));
        legs.add(new SpreadLeg(buyCeKey, buyCeStrike, OptionType.CE, OrderSide.BUY, qty, expiry));
        legs.add(new SpreadLeg(sellPeKey, sellPeStrike, OptionType.PE, OrderSide.SELL, qty, expiry));
        legs.add(new SpreadLeg(buyPeKey, buyPeStrike, OptionType.PE, OrderSide.BUY, qty, expiry));

        return legs;
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        // Short leg doubling check
        for (SpreadLeg leg : group.legs()) {
            if (leg.side() == OrderSide.SELL) {
                BigDecimal entryPrice = group.entryPrices().getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
                BigDecimal currentPrice = currentPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
                if (entryPrice.signum() > 0 && currentPrice.compareTo(entryPrice.multiply(BigDecimal.valueOf(2))) >= 0) {
                    log.info("IronCondor: short leg {} doubled (entry={}, current={})",
                            leg.instrumentKey(), entryPrice, currentPrice);
                    return true;
                }
            }
        }

        // Target decay and SL expansion using net credit
        BigDecimal entryCredit = netCredit(group.legs(), group.entryPrices());
        BigDecimal currentCredit = netCredit(group.legs(), currentPrices);

        // Target: net credit has decayed (profit for seller)
        if (entryCredit.signum() > 0) {
            BigDecimal decayPercent = entryCredit.subtract(currentCredit)
                    .divide(entryCredit, MC)
                    .multiply(BigDecimal.valueOf(100), MC);
            if (decayPercent.compareTo(config.getTargetPercent()) >= 0) {
                log.info("IronCondor: target decay hit for group {}", group.groupId());
                return true;
            }
        }

        // SL expansion: position has lost more than SL% of the initial credit received.
        // Loss % = (entryCredit - currentCredit) / entryCredit × 100
        if (entryCredit.signum() > 0) {
            BigDecimal lossPct = entryCredit.subtract(currentCredit)
                    .divide(entryCredit, MC)
                    .multiply(BigDecimal.valueOf(100), MC);
            if (lossPct.compareTo(config.getStopLossPercent()) >= 0) {
                log.info("IronCondor: SL expansion hit (loss={}%) for group {}", lossPct.setScale(1, java.math.RoundingMode.HALF_UP), group.groupId());
                return true;
            }
        }

        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("IronCondor: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.IRON_CONDOR;
    }
}
