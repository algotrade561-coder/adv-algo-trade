package com.algo.trade.domain;



import com.algo.trade.strategy.StrategyType;



import java.math.BigDecimal;

import java.time.Instant;

import java.util.List;

import java.util.Map;



/**

 * A set of option legs belonging to a single multi-leg strategy entry,

 * identified by a shared group identifier.

 */

public record PositionGroup(

        String groupId,

        StrategyType strategyType,

        UnderlyingSymbol underlying,

        List<SpreadLeg> legs,

        Map<String, BigDecimal> entryPrices,

        Instant entryTime,

        PositionGroupStatus status,

        BigDecimal peakProfitPercent,

        BigDecimal effectiveStopLossPercent,

        BigDecimal effectiveTargetPercent,

        BigDecimal effectiveTrailActivationPercent,

        BigDecimal effectiveTrailGapPercent

) {

    public PositionGroup(

            String groupId,

            StrategyType strategyType,

            UnderlyingSymbol underlying,

            List<SpreadLeg> legs,

            Map<String, BigDecimal> entryPrices,

            Instant entryTime,

            boolean open

    ) {

        this(groupId, strategyType, underlying, legs, entryPrices, entryTime,

                open ? PositionGroupStatus.OPEN : PositionGroupStatus.CLOSED,

                null, null, null, null, null);

    }



    public PositionGroup(

            String groupId,

            StrategyType strategyType,

            UnderlyingSymbol underlying,

            List<SpreadLeg> legs,

            Map<String, BigDecimal> entryPrices,

            Instant entryTime,

            PositionGroupStatus status

    ) {

        this(groupId, strategyType, underlying, legs, entryPrices, entryTime, status,

                null, null, null, null, null);

    }



    public boolean open() {

        return status == PositionGroupStatus.OPEN;

    }

}

