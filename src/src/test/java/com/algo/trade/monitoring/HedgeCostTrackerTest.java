package com.algo.trade.monitoring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HedgeCostTrackerTest {

    @Test
    void widenWhenEfficiencyLow() {
        assertThat(HedgeCostTracker.getHedgeDistanceAdjustment(30)).isEqualTo("WIDEN");
    }

    @Test
    void tightenWhenEfficiencyHigh() {
        assertThat(HedgeCostTracker.getHedgeDistanceAdjustment(85)).isEqualTo("TIGHTEN");
    }

    @Test
    void holdInMiddleBand() {
        assertThat(HedgeCostTracker.getHedgeDistanceAdjustment(60)).isEqualTo("HOLD");
    }
}
