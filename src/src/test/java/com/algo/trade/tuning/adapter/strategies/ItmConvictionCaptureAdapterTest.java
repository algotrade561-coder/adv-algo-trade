package com.algo.trade.tuning.adapter.strategies;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.adapter.CadenceHint;
import com.algo.trade.tuning.infra.EpisodeAggregator;
import com.algo.trade.domain.IndexType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class ItmConvictionCaptureAdapterTest {

    private final ItmConvictionCaptureAdapter adapter = new ItmConvictionCaptureAdapter();

    @Test
    void declaresItmConvictionWithHighCadence() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.ITM_CONVICTION);
        assertThat(adapter.cadence()).isEqualTo(CadenceHint.HIGH);
        assertThat(adapter.bucketDimensions()).hasSize(3);
    }

    @Test
    void buildSignalEvent_parsesStrengthGapDepthAndVolume() {
        String reason = "ITM CE conviction: ATM23500 diff=1.20, ITM23450 diff=-0.40, gap=3.50, vol=12000";
        SignalRecordContext ctx = ctx(SignalType.BUY_CE, List.of(reason));
        var event = adapter.buildSignalEvent(ctx, "itm-1");

        assertThat(event.attributes()).containsEntry("strengthGap", 3.50);
        assertThat(event.attributes()).containsEntry("itmDepth", 1);
        assertThat(event.attributes()).containsEntry("volume", 12000L);
    }

    @Test
    void buildEvaluationEvent_includesEpisodeMetadata() {
        SignalRecordContext ctx = ctx(SignalType.NO_TRADE, List.of("strengthGapTooSmall"));
        var row = new EpisodeAggregator.EpisodeRow<>(
                IndexType.NIFTY, "strengthGapTooSmall",
                Instant.parse("2026-06-01T10:00:00Z"),
                Instant.parse("2026-06-01T10:00:30Z"), 2, ctx);

        var event = adapter.buildEvaluationEvent(row);

        assertThat(event.episodeTickCount()).isEqualTo(2);
        assertThat(event.attributes()).containsKey("episodeFirstAt");
    }

    private static SignalRecordContext ctx(SignalType type, List<String> reasons) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T10:00:00Z"),
                UnderlyingSymbol.NIFTY, type, BigDecimal.valueOf(23500),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Optional.of(OptionType.CE), true, java.util.Optional.empty(), true,
                BigDecimal.valueOf(75), reasons);
        return SignalRecordContext.builder()
                .strategyType(StrategyType.ITM_CONVICTION.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .ivRank(40.0)
                .build();
    }
}
