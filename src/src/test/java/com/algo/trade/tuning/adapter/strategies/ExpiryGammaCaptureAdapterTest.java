package com.algo.trade.tuning.adapter.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExpiryGammaCaptureAdapterTest {

    private final ExpiryGammaCaptureAdapter adapter = new ExpiryGammaCaptureAdapter();

    @Test
    void buildSignalEvent_carriesMomentumAndTimeOfDay() {
        SignalRecordContext ctx = ctx(SignalType.BUY_CE,
                List.of("Expiry Gamma: momentum=0.45% vol=1200 accelerating=true direction=BULLISH"),
                0.45, "BULLISH", null, 0.012);
        var event = adapter.buildSignalEvent(ctx, "eg-1");

        assertThat(event.attributes()).containsEntry("momentumPct", 0.45);
        assertThat(event.attributes()).containsEntry("gamma", 0.012);
        assertThat(event.attributes()).containsKey("timeOfDay");
    }

    @Test
    void buildImmediateEvaluationEvent_skipsWhenNotExpiryDay() {
        SignalRecordContext ctx = ctx(SignalType.NO_TRADE, List.of("notExpiryDay"), null, null, "notExpiryDay", null);
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.SKIPPED);
    }

    private static SignalRecordContext ctx(SignalType type, List<String> reasons, Double momentum,
                                           String direction, String blocker, Double gamma) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T13:45:00Z"),
                UnderlyingSymbol.NIFTY, type, BigDecimal.valueOf(23500),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of(OptionType.CE), false, java.util.Optional.empty(), false,
                BigDecimal.valueOf(65), reasons);
        var builder = SignalRecordContext.builder()
                .strategyType(StrategyType.EXPIRY_GAMMA.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .ivRank(25.0);
        if (momentum != null) {
            builder.bbBandwidth(momentum);
        }
        if (direction != null) {
            builder.scalpCrossType(direction);
        }
        if (blocker != null) {
            builder.firstFailedFilter(blocker);
        }
        if (gamma != null) {
            builder.gamma(gamma);
        }
        return builder.build();
    }
}
