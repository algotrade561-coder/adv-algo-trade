package com.kiteapioptions.risk;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OptionType;
import com.kiteapioptions.domain.SignalType;
import com.kiteapioptions.domain.StrategyDecision;
import com.kiteapioptions.domain.UnderlyingSymbol;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RiskEngineTest {

    private final RiskEngine riskEngine = new RiskEngine(new TradingProperties(null, false, null, null,
            null, null, null, null, null, null, null, null));

    @Test
    void sizesQuantityByRiskAndLotSize() {
        PositionSizingResult result = riskEngine.calculateQuantity(BigDecimal.valueOf(100), 75);

        assertThat(result.allowed()).isTrue();
        assertThat(result.quantity()).isEqualTo(300);
        assertThat(result.estimatedCost()).isEqualByComparingTo(BigDecimal.valueOf(30_000));
    }

    @Test
    void rejectsWhenOneOpenTradeLimitIsReached() {
        RiskCheckResult result = riskEngine.evaluateEntry(buyDecision(), 1, 0, BigDecimal.ZERO, 0, false);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasons()).contains("One-open-trade-at-a-time limit reached");
    }

    @Test
    void rejectsWhenKillSwitchIsEnabled() {
        RiskCheckResult result = riskEngine.evaluateEntry(buyDecision(), 0, 0, BigDecimal.ZERO, 0, true);

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasons()).contains("Kill switch is enabled");
    }

    private StrategyDecision buyDecision() {
        return new StrategyDecision(Instant.now(), UnderlyingSymbol.NIFTY, SignalType.BUY_CE,
                BigDecimal.valueOf(24_000), Optional.of("NFO:NIFTY24APR24000CE"),
                Optional.of(BigDecimal.valueOf(24_000)), Optional.of(OptionType.CE), true,
                Optional.of(BigDecimal.ONE), true, List.of("test"));
    }
}
