package com.algo.trade.execution;

import com.algo.trade.domain.SpreadLeg;
import java.util.List;

/** Helpers for multi-leg spread quantity scaling. */
public final class SpreadLegs {

    private SpreadLegs() {
    }

    public static List<SpreadLeg> withLotQuantity(List<SpreadLeg> legs, int quantity) {
        return legs.stream()
                .map(leg -> new SpreadLeg(
                        leg.instrumentKey(),
                        leg.strike(),
                        leg.optionType(),
                        leg.side(),
                        quantity,
                        leg.expiry()))
                .toList();
    }

    public static int lotsFromLegs(List<SpreadLeg> legs, int lotSize) {
        if (legs == null || legs.isEmpty() || lotSize <= 0) {
            return 0;
        }
        return legs.getFirst().quantity() / lotSize;
    }
}
