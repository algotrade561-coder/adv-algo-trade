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

class VolatilityBreakoutCaptureAdapterTest {

    private final VolatilityBreakoutCaptureAdapter adapter = new VolatilityBreakoutCaptureAdapter();

    @Test
    void declaresVolatilityBreakoutStrategy() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.VOLATILITY_BREAKOUT);
    }

    @Test
    void bucketDimensions_matchPlan() {
        assertThat(adapter.bucketDimensions()).hasSize(3);
        assertThat(adapter.bucketDimensions().get(0).name()).isEqualTo("squeezeRatio");
        assertThat(adapter.bucketDimensions().get(2).name()).isEqualTo("pocDistancePct");
    }

    @Test
    void buildSignalEvent_carriesSqueezeRatioAndIvRank() {
        SignalRecordContext ctx = firedCtx(1.2, 35.0);
        SignalEvent event = adapter.buildSignalEvent(ctx, "vb-1");

        assertThat(event.strategy()).isEqualTo(StrategyType.VOLATILITY_BREAKOUT);
        assertThat(event.attributes()).containsEntry("squeezeRatio", 1.2);
        assertThat(event.attributes()).containsEntry("ivRank", 35.0);
        assertThat(event.attributes()).containsEntry("bbSqueeze", true);
    }

    @Test
    void buildImmediateEvaluationEvent_mapsNoSqueezeBlocker() {
        SignalRecordContext ctx = blockedCtx("noSqueeze(bw=2.50%)", 2.5, 45.0);
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("noSqueeze");
        assertThat(event.attributes()).containsEntry("squeezeRatio", 2.5);
    }

    @Test
    void buildEvaluationEvent_parsesPocDistanceFromReason() {
        SignalRecordContext ctx = blockedCtx(
                "breakoutIntoHVN(poc=23500,dist=0.20%)", 1.1, 30.0);
        var row = new EpisodeAggregator.EpisodeRow<>(
                IndexType.NIFTY, "breakoutIntoHVN", Instant.parse("2026-06-01T09:30:00Z"),
                Instant.parse("2026-06-01T09:45:00Z"), 1, ctx);

        EvaluationEvent event = adapter.buildEvaluationEvent(row);

        assertThat(event.attributes()).containsEntry("pocDistancePct", 0.20);
    }

    private static SignalRecordContext firedCtx(double bandwidth, double ivRank) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T09:30:00Z"),
                UnderlyingSymbol.NIFTY,
                SignalType.BUY_CE,
                BigDecimal.valueOf(23500),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.of("NFO:TEST"),
                java.util.Optional.empty(),
                java.util.Optional.of(OptionType.CE),
                true,
                java.util.Optional.empty(),
                true,
                BigDecimal.valueOf(75),
                List.of("BB squeeze breakout: bandwidth=" + bandwidth + "%",
                        "IV rank=" + ivRank + " (cheap)"));
        return SignalRecordContext.builder()
                .strategyType(StrategyType.VOLATILITY_BREAKOUT.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .bbBandwidth(bandwidth)
                .bbSqueeze(true)
                .ivRank(ivRank)
                .build();
    }

    private static SignalRecordContext blockedCtx(String reason, double bandwidth, double ivRank) {
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
                .strategyType(StrategyType.VOLATILITY_BREAKOUT.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .firstFailedFilter(reason)
                .bbBandwidth(bandwidth)
                .bbSqueeze(bandwidth < 1.5)
                .ivRank(ivRank)
                .build();
    }
}
