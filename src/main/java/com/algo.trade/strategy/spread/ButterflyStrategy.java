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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Butterfly — BUY 1 lot lower wing, SELL 2 lots ATM, BUY 1 lot upper wing.
 * Uses CE for bullish bias, PE for bearish bias.
 * Exit: SL%, target%, or expiry danger zone.
 */
@Component
public class ButterflyStrategy extends AbstractSpreadStrategy {

    /** Bias for leg construction: true = bullish (CE), false = bearish (PE). */
    private boolean bullishBias = true;

    public ButterflyStrategy(ExpiryCalendar expiryCalendar,
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
        // Butterfly is a low-cost strategy — always eligible when enabled
        return true;
    }

    @Override
    protected List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx) {
        IndexType indexType = ctx.indexType();
        int atm = computeATMStrike(ctx.underlyingPrice(), indexType);
        int spreadStrikes = ctx.config().getSpreadStrikes();
        int interval = indexType.strikeInterval();
        LocalDate expiry = currentWeeklyExpiry(indexType);
        int qty = ctx.config().getLots() * indexType.lotSize();

        int lowerWing = atm - spreadStrikes * interval;
        int upperWing = atm + spreadStrikes * interval;
        OptionType optType = bullishBias ? OptionType.CE : OptionType.PE;

        String lowerKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(lowerWing), optType)
                .map(i -> i.instrumentKey()).orElse(null);
        String middleKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(atm), optType)
                .map(i -> i.instrumentKey()).orElse(null);
        String upperKey = instrumentCache.findOption(ctx.underlying(), expiry,
                BigDecimal.valueOf(upperWing), optType)
                .map(i -> i.instrumentKey()).orElse(null);

        if (lowerKey == null || middleKey == null || upperKey == null) {
            log.warn("Butterfly: could not find all 3 instruments");
            return List.of();
        }

        List<SpreadLeg> legs = new ArrayList<>();
        legs.add(new SpreadLeg(lowerKey, lowerWing, optType, OrderSide.BUY, qty, expiry));
        legs.add(new SpreadLeg(middleKey, atm, optType, OrderSide.SELL, qty * 2, expiry));
        legs.add(new SpreadLeg(upperKey, upperWing, optType, OrderSide.BUY, qty, expiry));
        return legs;
    }

    @Override
    protected boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        StrategyConfig config = new StrategyConfig(strategyType());
        BigDecimal entryNet = netDebit(group.legs(), group.entryPrices());
        BigDecimal currentNet = netDebit(group.legs(), currentPrices);

        if (slHit(entryNet, currentNet, config.getStopLossPercent())) {
            log.info("Butterfly: SL hit for group {}", group.groupId());
            return true;
        }
        if (targetHit(entryNet, currentNet, config.getTargetPercent())) {
            log.info("Butterfly: target hit for group {}", group.groupId());
            return true;
        }
        if (expiryCalendar.isExpiryDangerZone(IndexType.from(group.underlying()))) {
            log.info("Butterfly: expiry danger zone for group {}", group.groupId());
            return true;
        }
        return false;
    }

    @Override
    protected StrategyType strategyType() {
        return StrategyType.BUTTERFLY;
    }
}
