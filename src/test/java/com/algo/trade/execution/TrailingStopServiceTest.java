package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.config.TradingProperties;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class TrailingStopServiceTest {

    private final TrailingStopService trailingStopService = new TrailingStopService(new TradingProperties(null,
            false, null, null, null, null, null, null, null, null, null, null, null));

    @Test
    void activatesTrailingStopAfterConfiguredProfitMove() {
        Optional<BigDecimal> stop = trailingStopService.nextStop(BigDecimal.valueOf(100),
                BigDecimal.valueOf(112), Optional.empty());

        assertThat(stop).hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo("105.28"));
    }

    @Test
    void doesNotLowerExistingTrailingStop() {
        Optional<BigDecimal> stop = trailingStopService.nextStop(BigDecimal.valueOf(100),
                BigDecimal.valueOf(113), Optional.of(BigDecimal.valueOf(108)));

        assertThat(stop).contains(BigDecimal.valueOf(108));
    }

    @Test
    void detectsStopHit() {
        assertThat(trailingStopService.isStopHit(BigDecimal.valueOf(105), BigDecimal.valueOf(106))).isTrue();
    }
}
