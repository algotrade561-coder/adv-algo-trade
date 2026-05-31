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

class ScalpingCaptureAdapterTest {

    private final ScalpingCaptureAdapter adapter = new ScalpingCaptureAdapter();

    @Test
    void declaresScalpingStrategy() {
        assertThat(adapter.strategy()).isEqualTo(StrategyType.SCALPING);
    }

    @Test
    void bucketDimensions_matchPlan() {
        assertThat(adapter.bucketDimensions()).hasSize(2);
        assertThat(adapter.bucketDimensions().get(0).name()).isEqualTo("scalpCrossType");
        assertThat(adapter.bucketDimensions().get(1).name()).isEqualTo("scalpConfirmCount");
    }

    @Test
    void buildSignalEvent_carriesCrossTypeAndConfirmCount() {
        SignalRecordContext ctx = firedCtx("BULLISH", 2, 23510.0, 23490.0);
        SignalEvent event = adapter.buildSignalEvent(ctx, "scalp-1");

        assertThat(event.strategy()).isEqualTo(StrategyType.SCALPING);
        assertThat(event.correlationKey()).isEqualTo("scalp-1");
        assertThat(event.attributes()).containsEntry("scalpCrossType", "BULLISH");
        assertThat(event.attributes()).containsEntry("scalpConfirmCount", 2);
        assertThat(event.attributes()).containsKey("emaGapPct");
    }

    @Test
    void buildImmediateEvaluationEvent_mapsNoEmaCrossBlocker() {
        SignalRecordContext ctx = blockedCtx("noEmaCross", "NONE", 0);
        EvaluationEvent event = adapter.buildImmediateEvaluationEvent(ctx, Instant.now());

        assertThat(event.outcome()).isEqualTo(EvaluationOutcome.BLOCKED);
        assertThat(event.blocker()).isEqualTo("noEmaCross");
        assertThat(event.attributes()).containsEntry("scalpCrossType", "NONE");
        assertThat(event.attributes()).containsEntry("scalpConfirmCount", 0);
    }

    @Test
    void buildEvaluationEvent_includesEpisodeMetadata() {
        SignalRecordContext ctx = blockedCtx("needsConfirmation(1/2)", "BULLISH", 1);
        Instant first = Instant.parse("2026-06-01T09:30:00Z");
        Instant last = Instant.parse("2026-06-01T09:35:00Z");
        var row = new EpisodeAggregator.EpisodeRow<>(
                IndexType.NIFTY, "needsConfirmation", first, last, 2, ctx);

        EvaluationEvent event = adapter.buildEvaluationEvent(row);

        assertThat(event.episodeTickCount()).isEqualTo(2);
        assertThat(event.attributes()).containsEntry("scalpConfirmCount", 1);
        assertThat(event.attributes()).containsKey("episodeFirstAt");
    }

    private static SignalRecordContext firedCtx(
            String crossType, int confirmCount, double ema9, double ema21) {
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
                true,
                java.util.Optional.empty(),
                true,
                BigDecimal.valueOf(70),
                List.of(
                        "Scalping: EMA9/21 crossover (2-candle confirmation)",
                        "EMA9=" + ema9 + " EMA21=" + ema21));
        return SignalRecordContext.builder()
                .strategyType(StrategyType.SCALPING.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .scalpCrossType(crossType)
                .scalpConfirmCount(confirmCount)
                .scalpEma9(ema9)
                .scalpEma21(ema21)
                .ivRank(35.0)
                .build();
    }

    private static SignalRecordContext blockedCtx(
            String reason, String crossType, int confirmCount) {
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
                .strategyType(StrategyType.SCALPING.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(decision)
                .firstFailedFilter(reason)
                .scalpCrossType(crossType)
                .scalpConfirmCount(confirmCount)
                .ivRank(30.0)
                .build();
    }
}
