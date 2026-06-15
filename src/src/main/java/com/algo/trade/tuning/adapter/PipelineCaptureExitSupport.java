package com.algo.trade.tuning.adapter;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.ExitEvent;
import com.algo.trade.tuning.infra.MaeMfeTracker;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Shared {@link ExitEvent} builder for Phase 3 pipeline-strategy adapters. */
public final class PipelineCaptureExitSupport {

    private PipelineCaptureExitSupport() {
    }

    public static ExitEvent buildExitEvent(StrategyType strategy,
                                           IndexType index,
                                           TradeEntity trade,
                                           MaeMfeTracker.Snapshot snapshot,
                                           String correlationKey,
                                           BigDecimal exitPrice,
                                           String exitReason,
                                           boolean reversal) {
        BigDecimal entryPrice = trade.getEntryPrice() != null
                ? trade.getEntryPrice() : BigDecimal.ZERO;
        double realizedPnlPct = 0.0;
        if (entryPrice.signum() > 0 && exitPrice != null) {
            realizedPnlPct = exitPrice.subtract(entryPrice)
                    .divide(entryPrice, MathContext.DECIMAL64)
                    .doubleValue() * 100.0;
        }
        long holdSec = 0;
        if (trade.getEntryTime() != null) {
            holdSec = Duration.between(trade.getEntryTime(), Instant.now()).getSeconds();
        }

        double maePct = snapshot != null ? snapshot.maePct() : 0.0;
        double mfePct = snapshot != null ? snapshot.mfePct() : 0.0;
        long timeToMaeSec = snapshot != null ? snapshot.timeToMaeSec() : 0L;
        long timeToMfeSec = snapshot != null ? snapshot.timeToMfeSec() : 0L;

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("instrumentKey", trade.getInstrumentKey());
        attrs.put("quantity", trade.getQuantity());
        attrs.put("optionType", trade.getOptionType());
        if (snapshot != null) {
            attrs.put("tickCount", snapshot.tickCount());
            if (snapshot.spotAtMae() > 0) {
                attrs.put("spotAtMae", snapshot.spotAtMae());
            }
            if (snapshot.spotAtMfe() > 0) {
                attrs.put("spotAtMfe", snapshot.spotAtMfe());
            }
        }

        return new ExitEvent(
                Instant.now(),
                Instant.now(),
                strategy,
                index,
                correlationKey != null ? correlationKey : trade.getTradeId(),
                trade.getTradeId(),
                exitReason != null ? exitReason : "UNKNOWN",
                entryPrice,
                exitPrice != null ? exitPrice : BigDecimal.ZERO,
                realizedPnlPct,
                holdSec,
                maePct,
                mfePct,
                timeToMaeSec,
                timeToMfeSec,
                reversal,
                attrs
        );
    }

    public static ExitEvent buildSpreadExitEvent(StrategyType strategy,
                                                 IndexType index,
                                                 PositionGroup group,
                                                 MaeMfeTracker.Snapshot snapshot,
                                                 String correlationKey,
                                                 Map<String, BigDecimal> exitPrices,
                                                 String exitReason) {
        BigDecimal entryNet = netSpreadDebit(group.legs(), group.entryPrices());
        BigDecimal exitNet = netSpreadDebit(group.legs(), exitPrices != null ? exitPrices : group.entryPrices());
        BigDecimal entryAbs = entryNet.abs();
        double realizedPnlPct = 0.0;
        if (entryAbs.signum() > 0) {
            realizedPnlPct = entryNet.subtract(exitNet, MathContext.DECIMAL64)
                    .divide(entryAbs, MathContext.DECIMAL64)
                    .doubleValue() * 100.0;
        }
        long holdSec = 0;
        if (group.entryTime() != null) {
            holdSec = Duration.between(group.entryTime(), Instant.now()).getSeconds();
        }
        double maePct = snapshot != null ? snapshot.maePct() : 0.0;
        double mfePct = snapshot != null ? snapshot.mfePct() : 0.0;
        long timeToMaeSec = snapshot != null ? snapshot.timeToMaeSec() : 0L;
        long timeToMfeSec = snapshot != null ? snapshot.timeToMfeSec() : 0L;

        Map<String, Object> attrs = new LinkedHashMap<>();
        attrs.put("legCount", group.legs().size());
        attrs.put("entryNetDebit", entryNet);
        attrs.put("exitNetDebit", exitNet);
        if (snapshot != null) {
            attrs.put("tickCount", snapshot.tickCount());
        }

        return new ExitEvent(
                Instant.now(),
                Instant.now(),
                strategy,
                index,
                correlationKey != null ? correlationKey : group.groupId(),
                group.groupId(),
                exitReason != null ? exitReason : "UNKNOWN",
                entryAbs,
                exitNet.abs(),
                realizedPnlPct,
                holdSec,
                maePct,
                mfePct,
                timeToMaeSec,
                timeToMfeSec,
                false,
                attrs
        );
    }

    private static BigDecimal netSpreadDebit(List<SpreadLeg> legs, Map<String, BigDecimal> prices) {
        BigDecimal total = BigDecimal.ZERO;
        for (SpreadLeg leg : legs) {
            BigDecimal price = prices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            BigDecimal legCost = price.multiply(BigDecimal.valueOf(leg.quantity()));
            total = leg.side() == OrderSide.BUY ? total.add(legCost) : total.subtract(legCost);
        }
        return total;
    }
}
