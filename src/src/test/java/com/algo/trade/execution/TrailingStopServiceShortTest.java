package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.ExecutionMode;
import com.algo.trade.domain.MarketDataMode;
import com.algo.trade.domain.TradingMode;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TrailingStopServiceShortTest {

    private TrailingStopService service;

    @BeforeEach
    void setUp() {
        var properties = new TradingProperties(TradingMode.PAPER, MarketDataMode.MOCK, ExecutionMode.PAPER,
                false, null, null, null, null, null, null, null, null, null, null, null, null);
        service = new TrailingStopService(properties);
    }

    @Test
    void shortTrailingRatchetsStopDownAsPriceFalls() {
        BigDecimal entry = BigDecimal.valueOf(100);
        BigDecimal act = BigDecimal.valueOf(10);
        BigDecimal gap = BigDecimal.valueOf(5);

        Optional<BigDecimal> first = service.nextStop(entry, BigDecimal.valueOf(80), Optional.empty(), act, gap, true);
        assertThat(first).isPresent();
        assertThat(first.get()).isEqualByComparingTo("84.0");

        Optional<BigDecimal> tighter = service.nextStop(entry, BigDecimal.valueOf(60), first, act, gap, true);
        assertThat(tighter).isPresent();
        assertThat(tighter.get()).isEqualByComparingTo("63.0");
        assertThat(tighter.get().compareTo(first.get())).isLessThan(0);
    }

    @Test
    void shortStopHitWhenPriceRalliesAboveStop() {
        assertThat(service.isStopHit(BigDecimal.valueOf(70), BigDecimal.valueOf(65), true)).isTrue();
        assertThat(service.isStopHit(BigDecimal.valueOf(60), BigDecimal.valueOf(65), true)).isFalse();
    }
}
