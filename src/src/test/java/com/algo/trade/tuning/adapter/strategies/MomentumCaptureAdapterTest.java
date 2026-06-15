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

class MomentumCaptureAdapterTest {

    private final MomentumCaptureAdapter adapter = new MomentumCaptureAdapter();

    @Test
    void declaresMomentumStrategy() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.MOMENTUM);
    }

    @Test
    void bucketDimensions_matchPlan() {
        assertThat(adapter.bucketDimensions()).hasSize(3);
        assertThat(adapter.bucketDimensions().get(0).name()).isEqualTo("rocPct");
        assertThat(adapter.bucketDimensions().get(1).name()).isEqualTo("ema21Gap");
        assertThat(adapter.bucketDimensions().get(2).name()).isEqualTo("volRatio");
    }

    @Test
    void buildSignalEvent_carriesRocEmaGapAndVolume() {
        SignalRecordContext ctx = firedCtx(
                List.of(
                        "Momentum: ROC=0.55% accelerating=true EMA21=23500.00 direction=BULLISH",
                        "Volume=1.8x ATR=0.320%"),
                0.55,
                "BULLISH");
        SignalEvent event = adapter.buildSignalEvent(ctx, "mom-1");

        assertThat(event.strategy()).isEqualTo(StrategyType.MOMENTUM);
        assertThat(event.correlationKey()).isEqualTo("mom-1");
        assertThat(event.attributes()).containsEntry("rocPct", 0.55);
        assertThat(event.attributes()).containsEntry("direction", "BULLISH");
        assertThat(event.attributes()).containsEntry("volumeRatio", 1.8);
        assertThat(event.attributes()).containsKey("ema21Gap");
    }

    @Test
    void buildImmediateEvaluationEvent_mapsRocTooWeakBlocker() {
        SignalRecordContext ctx = blockedCtx("rocTooWeak(0.15%)");
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("rocTooWeak");
        assertThat(event.attributes()).containsEntry("firstFailedFilter", "rocTooWeak(0.15%)");
        assertThat(event.attributes()).containsEntry("rocPct", 0.15);
    }

    @Test
    void buildEvaluationEvent_parsesTrendMisalignedGap() {
        SignalRecordContext ctx = blockedCtx("trendMisaligned(price=23510.00,ema21=23500.00)");
        Instant first = Instant.parse("2026-06-01T09:30:00Z");
        Instant last = Instant.parse("2026-06-01T09:30:45Z");
        var row = new EpisodeAggregator.EpisodeRow<>(
                IndexType.NIFTY, "trendMisaligned", first, last, 2, ctx);

        EvaluationEvent event = adapter.buildEvaluationEvent(row);

        assertThat(event.episodeTickCount()).isEqualTo(2);
        assertThat(event.attributes()).containsEntry("ema21Gap", 0.0425531914893617);
    }

    private static SignalRecordContext firedCtx(List<String> reasons, double roc, String direction) {
        StrategyDecision decision = new StrategyDecision(
                Instant.parse("2026-06-01T09:30:00Z"),
                UnderlyingSymbol.NIFTY,
                SignalType.BUY_CE,
                BigDecimal.valueOf(23510),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.of("NFO:TEST"),
                java.util.Optional.empty(),
                java.util.Optional.of(OptionType.CE),
                false,
                java.util.Optional.empty(),
                false,
                BigDecimal.valueOf(70),
                reasons);
        return SignalRecordContext.builder()
                .strategyType(StrategyType.MOMENTUM.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .bbBandwidth(roc)
                .scalpCrossType(direction)
                .ivRank(40.0)
                .build();
    }

    private static SignalRecordContext blockedCtx(String reason) {
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
                .strategyType(StrategyType.MOMENTUM.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .firstFailedFilter(reason)
                .ivRank(30.0)
                .build();
    }
}
