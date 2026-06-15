package com.algo.trade.execution.exit;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SpreadPremiumExitHelperTest {

    @Test
    void creditStopLossWhenAdverseWideningExceedsSl() {
        assertThat(SpreadPremiumExitHelper.creditStopLossHit(-55, 50)).isTrue();
        assertThat(SpreadPremiumExitHelper.creditStopLossHit(-30, 50)).isFalse();
        assertThat(SpreadPremiumExitHelper.creditStopLossHit(55, 50)).isFalse();
    }

    @Test
    void creditStopLossFromPricesWhenCreditWidens() {
        BigDecimal entry = BigDecimal.valueOf(100);
        BigDecimal adverse = BigDecimal.valueOf(160);
        assertThat(SpreadPremiumExitHelper.creditStopLossHit(entry, adverse, 50)).isTrue();
        assertThat(SpreadPremiumExitHelper.creditAdverseLossPercent(entry, adverse)).isEqualTo(60.0);
    }

    @Test
    void creditTargetWhenDecayHitsTarget() {
        assertThat(SpreadPremiumExitHelper.creditTargetHit(50, 50)).isTrue();
    }
}
