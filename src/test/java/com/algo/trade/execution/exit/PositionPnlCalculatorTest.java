package com.algo.trade.execution.exit;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.persistence.TradeEntity;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PositionPnlCalculatorTest {

    @Test
    void longProfitWhenPriceRises() {
        double pct = PositionPnlCalculator.profitPercent(
                BigDecimal.valueOf(100), BigDecimal.valueOf(120), false);
        assertThat(pct).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void shortProfitWhenPriceFalls() {
        double pct = PositionPnlCalculator.profitPercent(
                BigDecimal.valueOf(100), BigDecimal.valueOf(80), true);
        assertThat(pct).isCloseTo(20.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    void detectsShortFromStrategyType() {
        TradeEntity trade = mock(TradeEntity.class);
        when(trade.getStrategyType()).thenReturn("SHORT_STRADDLE");
        assertThat(PositionPnlCalculator.isShortEntry(trade)).isTrue();
    }
}
