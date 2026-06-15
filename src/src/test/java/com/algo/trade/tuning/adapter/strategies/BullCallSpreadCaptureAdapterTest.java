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
import java.util.List;
import org.junit.jupiter.api.Test;

class BullCallSpreadCaptureAdapterTest {

    private final BullCallSpreadCaptureAdapter adapter = new BullCallSpreadCaptureAdapter();

    @Test
    void declaresStrategyWithLowCadence() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.BULL_CALL_SPREAD);
        assertThat(adapter.cadence()).isEqualTo(CadenceHint.LOW);
        assertThat(adapter.bucketDimensions()).hasSize(2);
    }

    @Test
    void buildSignalEvent_parsesNetDebit() {
        SignalRecordContext ctx = ctx(SignalType.BUY_CE,
                List.of("Bull Call Spread entry signal", "Net debit: 125.50"), null);
        var event = adapter.buildSignalEvent(ctx, "bcs-group-1");
        assertThat(event.attributes()).containsEntry("netDebit", 125.50);
    }

    @Test
    void buildImmediateEvaluationEvent_skipsWhenConditionNotMet() {
        SignalRecordContext ctx = ctx(SignalType.NO_TRADE,
                List.of("spreadEntryBlocked:emaNotBullish"), "emaNotBullish");
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());
        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.SKIPPED);
        assertThat(event.blocker()).isNull();
    }

    @Test
    void buildImmediateEvaluationEvent_blocksOnGate() {
        SignalRecordContext ctx = ctx(SignalType.NO_TRADE,
                List.of("spreadEntryBlocked:marketGuard(vix)"), "marketGuard(vix)");
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());
        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("marketGuard");
    }

    private static SignalRecordContext ctx(SignalType type, List<String> reasons, String blocker) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T10:00:00Z"),
                UnderlyingSymbol.NIFTY,
                type,
                BigDecimal.valueOf(23500),
                type == SignalType.BUY_CE ? java.util.Optional.of(BigDecimal.valueOf(125.5)) : java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of("bcs-group-1"), java.util.Optional.empty(),
                java.util.Optional.of(OptionType.CE), false, java.util.Optional.empty(), false,
                BigDecimal.valueOf(type == SignalType.NO_TRADE ? 0 : 70), reasons);
        var builder = SignalRecordContext.builder()
                .strategyType(StrategyType.BULL_CALL_SPREAD.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .ivRank(35.0);
        if (blocker != null) {
            builder.firstFailedFilter(blocker);
        }
        return builder.build();
    }
}
