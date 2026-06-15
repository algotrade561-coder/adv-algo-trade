package com.algo.trade.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.SpreadLeg;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpreadLegsTest {

    @Test
    void withLotQuantityScalesAllLegs() {
        SpreadLeg leg = new SpreadLeg("NFO:X", 24000, OptionType.CE, OrderSide.BUY, 75, LocalDate.now());
        List<SpreadLeg> scaled = SpreadLegs.withLotQuantity(List.of(leg), 150);
        assertThat(scaled.getFirst().quantity()).isEqualTo(150);
    }

    @Test
    void lotsFromLegsDividesByLotSize() {
        SpreadLeg leg = new SpreadLeg("NFO:X", 24000, OptionType.CE, OrderSide.BUY, 150, LocalDate.now());
        assertThat(SpreadLegs.lotsFromLegs(List.of(leg), 75)).isEqualTo(2);
    }
}
