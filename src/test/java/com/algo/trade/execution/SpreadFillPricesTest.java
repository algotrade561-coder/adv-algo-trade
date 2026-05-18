package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.execution.SpreadOrderExecutor.LegResult;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SpreadFillPricesTest {

    @Test
    void collectsFillPricesFromSuccessfulLegs() {
        SpreadLeg leg = new SpreadLeg("NFO:X", 24000, OptionType.CE, OrderSide.BUY, 75, LocalDate.now());
        List<LegResult> results = List.of(
                new LegResult(leg, true, "ok", Optional.of(new BigDecimal("120.5")), 75),
                new LegResult(leg, false, "fail", Optional.empty(), 0));

        Map<String, BigDecimal> prices = SpreadFillPrices.fromLegResults(results);

        assertThat(prices).containsEntry("NFO:X", new BigDecimal("120.5"));
        assertThat(prices).hasSize(1);
    }
}
