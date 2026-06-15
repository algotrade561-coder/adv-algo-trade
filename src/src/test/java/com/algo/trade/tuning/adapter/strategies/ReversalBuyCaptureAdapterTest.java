package com.algo.trade.tuning.adapter.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReversalBuyCaptureAdapterTest {

    private final ReversalBuyCaptureAdapter adapter = new ReversalBuyCaptureAdapter();

    @Test
    void declaresReversalBuyStrategy() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.REVERSAL_BUY);
    }

    @Test
    void buildSignalEvent_carriesRsiAndDirection() {
        SignalRecordContext ctx = firedCtx(24.5, "BULLISH_REVERSAL", OptionType.CE);
        SignalEvent event = adapter.buildSignalEvent(ctx, "rev-1");

        assertThat(event.strategy()).isEqualTo(StrategyType.REVERSAL_BUY);
        assertThat(event.attributes()).containsEntry("rsiValue", 24.5);
        assertThat(event.attributes()).containsEntry("direction", "CE");
    }

    @Test
    void buildImmediateEvaluationEvent_mapsRsiNeutralBlocker() {
        SignalRecordContext ctx = blockedCtx("rsiNeutral(rsi=45.2)", 45.2);
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("rsiNeutral");
        assertThat(event.attributes()).containsEntry("rsiValue", 45.2);
    }

    @Test
    void buildEvaluationEvent_mapsBearishReversalDirection() {
        SignalRecordContext ctx = firedCtx(82.0, "BEARISH_REVERSAL", OptionType.PE);
        var row = new EpisodeAggregator.EpisodeRow<>(
                IndexType.NIFTY, "fired", Instant.parse("2026-06-01T09:30:00Z"),
                Instant.parse("2026-06-01T09:45:00Z"), 1, ctx);

        EvaluationEvent event = adapter.buildEvaluationEvent(row);

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.FIRED);
        assertThat(event.attributes()).containsEntry("direction", "PE");
    }

    private static SignalRecordContext firedCtx(double rsi, String direction, OptionType optionType) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T09:30:00Z"),
                UnderlyingSymbol.NIFTY,
                optionType == OptionType.CE ? SignalType.BUY_CE : SignalType.BUY_PE,
                BigDecimal.valueOf(23500),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.of("NFO:TEST"),
                java.util.Optional.empty(),
                java.util.Optional.of(optionType),
                false,
                java.util.Optional.empty(),
                false,
                BigDecimal.valueOf(70),
                List.of("Reversal Buy: RSI=" + rsi + " volExhausted=true reversalCandle=true"));
        return SignalRecordContext.builder()
                .strategyType(StrategyType.REVERSAL_BUY.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .rsiValue(rsi)
                .scalpCrossType(direction)
                .ivRank(25.0)
                .build();
    }

    private static SignalRecordContext blockedCtx(String reason, double rsi) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T09:30:00Z"),
                UnderlyingSymbol.NIFTY,
                SignalType.NO_TRADE,
                BigDecimal.valueOf(23500),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                false,
                java.util.Optional.empty(),
                false,
                BigDecimal.ZERO,
                List.of(reason));
        return SignalRecordContext.builder()
                .strategyType(StrategyType.REVERSAL_BUY.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .firstFailedFilter(reason)
                .rsiValue(rsi)
                .ivRank(40.0)
                .build();
    }
}
