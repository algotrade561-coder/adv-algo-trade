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
        // Short leg doubling check
        for (SpreadLeg leg : group.legs()) {
            if (leg.side() == OrderSide.SELL) {
                BigDecimal entryPrice = group.entryPrices().getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
                BigDecimal currentPrice = currentPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
                if (entryPrice.signum() > 0 && currentPrice.compareTo(entryPrice.multiply(BigDecimal.valueOf(2))) >= 0) {
                    log.info("ShortStrangle: short leg {} doubled (entry={}, current={})",
                            leg.instrumentKey(), entryPrice, currentPrice);
                    return true;
                }
            }
        }

        // Target decay using net credit
        BigDecimal entryCredit = netCredit(group.legs(), group.entryPrices());
        BigDecimal currentCredit = netCredit(group.legs(), currentPrices);

        if (entryCredit.signum() > 0) {
            BigDecimal decayPercent = entryCredit.subtract(currentCredit)
                    .divide(entryCredit, MC)
                    .multiply(BigDecimal.valueOf(100), MC);
            if (decayPercent.compareTo(config.getTargetPercent()) >= 0) {
                log.info("ShortStrangle: target decay hit for group {}", group.groupId());
                return true;
            }
        }

        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("ShortStrangle: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.SHORT_STRANGLE;
    }
}
