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
 * Short Straddle — SELL ATM CE + SELL ATM PE at the same strike.
 * Entry: IV rank >= 30 AND MarketGuard safe for short premium.
 * Exit: either leg doubles in price, target decay reached, or expiry danger zone.
 * Always paper-trades per StrategyConfig (paperTrading=true by default).
 */
@Component
public class ShortStraddleStrategy extends AbstractSpreadStrategy {

    private static final MathContext MC = MathContext.DECIMAL64;
    private final MarketGuard marketGuard;

    public ShortStraddleStrategy(ExpiryCalendar expiryCalendar,
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

    private static final double MIN_IV_RANK_FOR_SHORT_STRADDLE = 30.0;

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        if (ctx.ivRank() < MIN_IV_RANK_FOR_SHORT_STRADDLE) {
            log.debug("ShortStraddle: IV rank {} < {}, skipping (need elevated IV to sell premium)",
                    ctx.ivRank(), MIN_IV_RANK_FOR_SHORT_STRADDLE);
            return false;
        }
        if (!marketGuard.isSafeForShortPremium()) {
            log.debug("ShortStraddle: MarketGuard blocks short premium");
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

        String sellCeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String sellPeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (sellCeKey == null || sellPeKey == null) {
            log.warn("ShortStraddle: could not find ATM={} CE or PE instruments", atm);
            return List.of();
        }

        BigDecimal minPremium = ctx.config().getMinCombinedPremium();
        if (minPremium != null && minPremium.signum() > 0) {
            BigDecimal cePrice = marketDataService.quote(sellCeKey)
                    .map(q -> q.lastPrice()).orElse(BigDecimal.ZERO);
            BigDecimal pePrice = marketDataService.quote(sellPeKey)
                    .map(q -> q.lastPrice()).orElse(BigDecimal.ZERO);
            BigDecimal combined = cePrice.add(pePrice);
            if (combined.compareTo(minPremium) < 0) {
                log.debug("ShortStraddle: combined premium {} < minCombinedPremium {}, skipping",
                        combined, minPremium);
                return List.of();
            }
        }

        return List.of(
                new SpreadLeg(sellCeKey, atm, OptionType.CE, OrderSide.SELL, qty, expiry),
                new SpreadLeg(sellPeKey, atm, OptionType.PE, OrderSide.SELL, qty, expiry)
        );
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        for (SpreadLeg leg : group.legs()) {
            if (leg.side() == OrderSide.SELL) {
                BigDecimal entryPrice = group.entryPrices().getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
                BigDecimal currentPrice = currentPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
                if (entryPrice.signum() > 0
                        && currentPrice.compareTo(entryPrice.multiply(BigDecimal.valueOf(2))) >= 0) {
                    log.info("ShortStraddle: short leg {} doubled (entry={}, current={})",
                            leg.instrumentKey(), entryPrice, currentPrice);
                    return true;
                }
            }
        }

        BigDecimal entryCredit = netCredit(group.legs(), group.entryPrices());
        BigDecimal currentCredit = netCredit(group.legs(), currentPrices);

        if (entryCredit.signum() > 0) {
            BigDecimal decayPercent = entryCredit.subtract(currentCredit)
                    .divide(entryCredit, MC)
                    .multiply(BigDecimal.valueOf(100), MC);
            if (decayPercent.compareTo(config.getTargetPercent()) >= 0) {
                log.info("ShortStraddle: target decay {}% hit for group {}", decayPercent, group.groupId());
                return true;
            }
        }

        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("ShortStraddle: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.SHORT_STRADDLE;
    }
}
