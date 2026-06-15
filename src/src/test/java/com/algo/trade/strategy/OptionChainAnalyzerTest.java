package com.algo.trade.strategy;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OptionChainAnalyzerTest {

    @Test
    void identifiesSupportResistanceAndNearbyImbalance() {
        OptionChainSnapshot snapshot = new OptionChainSnapshot(UnderlyingSymbol.NIFTY, Instant.now(),
                BigDecimal.valueOf(24_050), List.of(
                level(24_000, 1000, 3000),
                level(24_050, 2000, 2500),
                level(24_100, 5000, 1000)
        ));

        OptionChainAnalysis analysis = new OptionChainAnalyzer().analyze(snapshot, 1);

        assertThat(analysis.resistanceStrike()).contains(BigDecimal.valueOf(24_100));
        assertThat(analysis.supportStrike()).contains(BigDecimal.valueOf(24_000));
        assertThat(analysis.nearbyPutCallOiImbalance()).isGreaterThan(BigDecimal.ZERO);
    }

    private OptionChainLevel level(int strike, long callOi, long putOi) {
        return new OptionChainLevel(BigDecimal.valueOf(strike), callOi, putOi, 0, 0,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100));
    }
}
