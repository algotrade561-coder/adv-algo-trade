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

class ExpiryReversalCaptureAdapterTest {

    private final ExpiryReversalCaptureAdapter adapter = new ExpiryReversalCaptureAdapter();

    @Test
    void buildSignalEvent_parsesSpikeAndDte() {
        SignalRecordContext ctx = ctx(SignalType.BUY_PE,
                List.of("Expiry Reversal: spike=0.85% volExhausted=true hesitation=true direction=FADING_SPIKE_UP",
                        "daysToExpiry=0 — fade expected near key strike"),
                0.85, "FADING_SPIKE_UP", null, 0L);
        var event = adapter.buildSignalEvent(ctx, "er-1");

        assertThat(event.attributes()).containsEntry("spikePct", 0.85);
        assertThat(event.attributes()).containsEntry("daysToExpiry", 0L);
    }

    @Test
    void buildImmediateEvaluationEvent_skipsWhenNotNearExpiry() {
        SignalRecordContext ctx = ctx(SignalType.NO_TRADE,
                List.of("notNearExpiry(daysToExpiry=5)"), null, null,
                "notNearExpiry(daysToExpiry=5)", 5L);
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.SKIPPED);
    }

    private static SignalRecordContext ctx(SignalType type, List<String> reasons, Double spike,
                                           String direction, String blocker, Long dte) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T11:00:00Z"),
                UnderlyingSymbol.NIFTY, type, BigDecimal.valueOf(23500),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of(type == SignalType.BUY_PE ? OptionType.PE : OptionType.CE),
                false, java.util.Optional.empty(), false, BigDecimal.valueOf(60), reasons);
        var builder = SignalRecordContext.builder()
                .strategyType(StrategyType.EXPIRY_REVERSAL.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .ivRank(20.0);
        if (spike != null) {
            builder.bbBandwidth(spike);
        }
        if (direction != null) {
            builder.scalpCrossType(direction);
        }
        if (blocker != null) {
            builder.firstFailedFilter(blocker);
        }
        if (dte != null) {
            builder.daysToExpiry(dte);
        }
        return builder.build();
    }
}
