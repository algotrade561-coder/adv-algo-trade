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

class GapAndGoCaptureAdapterTest {

    private final GapAndGoCaptureAdapter adapter = new GapAndGoCaptureAdapter();

    @Test
    void declaresGapAndGoWithLowCadence() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.GAP_AND_GO);
        assertThat(adapter.cadence()).isEqualTo(CadenceHint.LOW);
        assertThat(adapter.bucketDimensions()).hasSize(3);
    }

    @Test
    void buildSignalEvent_parsesGapBodyAndVolume() {
        SignalRecordContext ctx = ctx(SignalType.BUY_CE,
                List.of("Gap & Go: gap=0.35% body=0.55% volume=1.8x direction=BULLISH"),
                0.55, "BULLISH", null);
        var event = adapter.buildSignalEvent(ctx, "gg-1");

        assertThat(event.attributes()).containsEntry("gapPct", 0.35);
        assertThat(event.attributes()).containsEntry("bodyPct", 0.55);
        assertThat(event.attributes()).containsEntry("volumeRatio", 1.8);
    }

    @Test
    void buildImmediateEvaluationEvent_skipsOutsideWindow() {
        SignalRecordContext ctx = ctx(SignalType.NO_TRADE, List.of("timeWindow"), null, null, "timeWindow");
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.SKIPPED);
        assertThat(event.blocker()).isNull();
    }

    private static SignalRecordContext ctx(SignalType type, List<String> reasons, Double bodyPct,
                                           String direction, String blocker) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T09:25:00Z"),
                UnderlyingSymbol.NIFTY,
                type,
                BigDecimal.valueOf(23500),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of(OptionType.CE), false, java.util.Optional.empty(), false,
                BigDecimal.valueOf(type == SignalType.NO_TRADE ? 0 : 70), reasons);
        var builder = SignalRecordContext.builder()
                .strategyType(StrategyType.GAP_AND_GO.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .ivRank(30.0);
        if (bodyPct != null) {
            builder.bbBandwidth(bodyPct);
        }
        if (direction != null) {
            builder.scalpCrossType(direction);
        }
        if (blocker != null) {
            builder.firstFailedFilter(blocker);
        }
        return builder.build();
    }
}
