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
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DirectionalBuyCaptureAdapterTest {

    private final DirectionalBuyCaptureAdapter adapter = new DirectionalBuyCaptureAdapter();

    @Test
    void declaresDirectionalBuyStrategy() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.DIRECTIONAL_BUY);
    }

    @Test
    void buildSignalEvent_carriesConfidenceAndFilters() {
        SignalRecordContext ctx = sampleCtx(SignalType.BUY_CE, List.of("Trend condition passed"));
        SignalEvent event = adapter.buildSignalEvent(ctx, "dk-1");

        assertThat(event.strategy()).isEqualTo(StrategyType.DIRECTIONAL_BUY);
        assertThat(event.correlationKey()).isEqualTo("dk-1");
        assertThat(event.attributes()).containsEntry("confidenceScore", BigDecimal.valueOf(75));
        assertThat(event.attributes()).containsKey("funnel_vwap");
    }

    @Test
    void buildImmediateEvaluationEvent_mapsBlocker() {
        SignalRecordContext ctx = sampleCtx(SignalType.NO_TRADE, List.of("Breakout condition failed"));
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("breakout");
        assertThat(event.attributes()).containsEntry("firstFailedFilter", "breakout");
    }

    @Test
    void buildEvaluationEvent_includesEpisodeMetadata() {
        SignalRecordContext ctx = sampleCtx(SignalType.NO_TRADE, List.of("Volume spike missing"));
        Instant first = Instant.parse("2026-06-01T09:30:00Z");
        Instant last = Instant.parse("2026-06-01T09:30:45Z");
        var row = new EpisodeAggregator.EpisodeRow<>(
                com.algo.trade.domain.IndexType.NIFTY, "volumeSpike", first, last, 3, ctx);

        EvaluationEvent event = adapter.buildEvaluationEvent(row);

        assertThat(event.episodeTickCount()).isEqualTo(3);
        assertThat(event.attributes()).containsKey("episodeFirstAt");
    }

    private static SignalRecordContext sampleCtx(SignalType signalType, List<String> reasons) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T09:30:00Z"),
                UnderlyingSymbol.NIFTY,
                signalType,
                BigDecimal.valueOf(23500),
                java.util.Optional.of(BigDecimal.valueOf(100)),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.of("NFO:TEST"),
                java.util.Optional.of(BigDecimal.valueOf(23500)),
                java.util.Optional.of(OptionType.CE),
                true,
                java.util.Optional.of(BigDecimal.valueOf(1.2)),
                true,
                BigDecimal.valueOf(75),
                reasons);
        return SignalRecordContext.builder()
                .strategyType(StrategyType.DIRECTIONAL_BUY.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .breakoutPassed(true)
                .oiPassed(true)
                .ivPassed(true)
                .liquidityPassed(true)
                .timePassed(true)
                .ivRank(35.0)
                .build();
    }
}
