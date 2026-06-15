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
import com.algo.trade.tuning.adapter.CadenceHint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EventDrivenBuyCaptureAdapterTest {

    private final EventDrivenBuyCaptureAdapter adapter = new EventDrivenBuyCaptureAdapter();

    @Test
    void declaresEventDrivenBuyWithLowCadence() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.EVENT_DRIVEN_BUY);
        assertThat(adapter.cadence()).isEqualTo(CadenceHint.LOW);
    }

    @Test
    void buildSignalEvent_parsesDaysToEvent() {
        LocalDate eventDate = LocalDate.now().plusDays(2);
        SignalRecordContext ctx = ctx(SignalType.BUY_CE,
                List.of("Pre-event straddle: event=" + eventDate, "IV rank=35 (cheap, good to buy)"),
                35.0);
        var event = adapter.buildSignalEvent(ctx, "ev-1");

        assertThat(event.attributes()).containsEntry("daysToEvent", 2L);
        assertThat(event.attributes()).containsEntry("ivRank", 35.0);
    }

    @Test
    void buildImmediateEvaluationEvent_skipsWhenNoEventWindow() {
        SignalRecordContext ctx = ctx(SignalType.NO_TRADE,
                List.of("No signal conditions met for Event Driven Buy"), 42.0);
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.SKIPPED);
        assertThat(event.blocker()).isNull();
        assertThat(event.attributes()).containsKey("firstFailedFilter");
    }

    private static SignalRecordContext ctx(SignalType type, List<String> reasons, double ivRank) {
        StrategyDecision decision = new StrategyDecision(
                Instant.now(), UnderlyingSymbol.NIFTY, type, BigDecimal.valueOf(23500),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of(OptionType.CE), true, java.util.Optional.empty(), true,
                BigDecimal.valueOf(80), reasons);
        return SignalRecordContext.builder()
                .strategyType(StrategyType.EVENT_DRIVEN_BUY.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .ivRank(ivRank)
                .build();
    }
}
