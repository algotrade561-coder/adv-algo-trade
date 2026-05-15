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
 * Jade Lizard — SELL OTM CE + SELL OTM PE + BUY further OTM PE (hedge).
 * Entry: MarketGuard safe for short premium.
 * Exit: target decay, SL expansion, or expiry danger zone.
 */
@Component
public class JadeLizardStrategy extends AbstractSpreadStrategy {

    private static final MathContext MC = MathContext.DECIMAL64;
    private final MarketGuard marketGuard;

    public JadeLizardStrategy(ExpiryCalendar expiryCalendar,
                              InstrumentCache instrumentCache,
                              MarketDataService marketDataService,
                              ExecutionEngine executionEngine,
                              StrategySignalCsvRecorder signalRecorder,
                              EmaIndicator emaIndicator,
                              AtrIndicator atrIndicator,
                              PositionGroupRepository positionGroupRepository,
                              MarketGuard marketGuard) {
        super(expiryCalendar, instrumentCache, marketDataService,
              executionEngine, signalRecorder, emaIndicator, atrIndicator, positionGroupRepository);
        this.marketGuard = marketGuard;
    }

    @Override
    protected boolean shouldEnter(SpreadEvaluationContext ctx) {
        if (!marketGuard.isSafeForShortPremium()) {
            log.debug("JadeLizard: MarketGuard blocks short premium");
            return false;
        }

        // IV Rank > 50 — Jade Lizard is a premium-selling strategy, needs elevated IV
        if (ctx.ivRank() < 50) {
            log.debug("JadeLizard: IV rank {:.1f} < 50 (need high IV for selling), skipping", ctx.ivRank());
            return false;
        }

        // Range-bound check: price within 1.5% of 20-period SMA
        var candles = ctx.trendCandles();
        if (candles != null && candles.size() >= 20) {
            double[] closes = candles.stream().mapToDouble(c -> c.close().doubleValue()).toArray();
            double sma = 0;
            for (int i = closes.length - 20; i < closes.length; i++) sma += closes[i];
            sma /= 20;
            double deviation = Math.abs(closes[closes.length - 1] - sma) / sma * 100;
            if (deviation > 1.5) {
                log.debug("JadeLizard: price deviation {:.2f}% from SMA > 1.5% (trending), skipping", deviation);
                return false;
            }
        }

        log.info("JadeLizard: entry filters passed — ivRank={:.1f}", ctx.ivRank());
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
        int sellPeStrike = atm - otmStrikes * interval;
        int buyPeStrike = atm - (otmStrikes + spreadStrikes) * interval;

        String sellCeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(sellCeStrike), OptionType.CE)
                .map(i -> i.instrumentKey()).orElse(null);
        String sellPeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(sellPeStrike), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);
        String buyPeKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(buyPeStrike), OptionType.PE)
                .map(i -> i.instrumentKey()).orElse(null);

        if (sellCeKey == null || sellPeKey == null || buyPeKey == null) {
            log.warn("JadeLizard: could not find all 3 instruments");
            return List.of();
        }

        List<SpreadLeg> legs = new ArrayList<>();
        legs.add(new SpreadLeg(sellCeKey, sellCeStrike, OptionType.CE, OrderSide.SELL, qty, expiry));
        legs.add(new SpreadLeg(sellPeKey, sellPeStrike, OptionType.PE, OrderSide.SELL, qty, expiry));
        legs.add(new SpreadLeg(buyPeKey, buyPeStrike, OptionType.PE, OrderSide.BUY, qty, expiry));
        return legs;
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config) {
        BigDecimal entryCredit = netCredit(group.legs(), group.entryPrices());
        BigDecimal currentCredit = netCredit(group.legs(), currentPrices);

        // Target decay: net credit has decayed by target%
        if (entryCredit.signum() > 0) {
            BigDecimal decayPercent = entryCredit.subtract(currentCredit)
                    .divide(entryCredit, MC)
                    .multiply(BigDecimal.valueOf(100), MC);
            if (decayPercent.compareTo(config.getTargetPercent()) >= 0) {
                log.info("JadeLizard: target decay hit for group {}", group.groupId());
                return true;
            }
        }

        // SL expansion: current net cost exceeds entry credit × (1 + SL%/100)
        if (entryCredit.signum() > 0) {
            BigDecimal slThreshold = entryCredit.multiply(
                    BigDecimal.ONE.add(config.getStopLossPercent().divide(BigDecimal.valueOf(100), MC)), MC);
            BigDecimal currentCost = netDebit(group.legs(), currentPrices);
            if (currentCost.compareTo(slThreshold) > 0) {
                log.info("JadeLizard: SL expansion hit for group {}", group.groupId());
                return true;
            }
        }

        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("JadeLizard: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    public StrategyType strategyType() {
        return StrategyType.JADE_LIZARD;
    }
}
