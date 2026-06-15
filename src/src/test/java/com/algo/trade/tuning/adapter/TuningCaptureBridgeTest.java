package com.algo.trade.tuning.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.algo.trade.tuning.adapter.strategies.DirectionalBuyCaptureAdapter;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TuningCaptureBridgeTest {

    private TuningEventRecorder recorder;
    private DirectionalBuyCaptureAdapter directionalBuy;
    private TuningCaptureBridge bridge;

    @BeforeEach
    void setUp() {
        recorder = mock(TuningEventRecorder.class);
        directionalBuy = new DirectionalBuyCaptureAdapter();
        bridge = new TuningCaptureBridge(recorder,
                Map.of(StrategyType.DIRECTIONAL_BUY, directionalBuy));
    }

    @Test
    void skipsInlineDualWriteStrategies() {
        TuningCaptureBridge inlineBridge = new TuningCaptureBridge(recorder,
                Map.of(StrategyType.OI_MOMENTUM, mockPipelineCapture(StrategyType.OI_MOMENTUM)));

        inlineBridge.record(sampleCtx(StrategyType.OI_MOMENTUM.name(), SignalType.NO_TRADE));

        verify(recorder, never()).record(any());
    }

    @Test
    void noOpWhenStrategyHasNoPipelineCapture() {
        bridge.record(sampleCtx(StrategyType.MOMENTUM.name(), SignalType.NO_TRADE));

        verify(recorder, never()).record(any());
    }

    @Test
    void recordsSignalEventForBuyDecision() {
        bridge.record(sampleCtx(StrategyType.DIRECTIONAL_BUY.name(), SignalType.BUY_CE));

        ArgumentCaptor<com.algo.trade.tuning.TuningEvent> captor =
                ArgumentCaptor.forClass(com.algo.trade.tuning.TuningEvent.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(SignalEvent.class);
    }

    @Test
    void recordsEvaluationEventForNoTrade() {
        SignalRecordContext ctx = SignalRecordContext.builder()
                .strategyType(StrategyType.DIRECTIONAL_BUY.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(new StrategyDecision(
                        Instant.now(), UnderlyingSymbol.NIFTY, SignalType.NO_TRADE,
                        BigDecimal.valueOf(23500), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(OptionType.CE), false, Optional.empty(), false,
                        BigDecimal.ZERO, List.of("Breakout condition failed")))
                .firstFailedFilter("breakout")
                .ivRank(42.0)
                .build();

        bridge.record(ctx);
        SignalRecordContext ctx2 = SignalRecordContext.builder()
                .strategyType(StrategyType.DIRECTIONAL_BUY.name())
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(new StrategyDecision(
                        Instant.now(), UnderlyingSymbol.NIFTY, SignalType.NO_TRADE,
                        BigDecimal.valueOf(23500), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of(OptionType.CE), false, Optional.empty(), false,
                        BigDecimal.ZERO, List.of("Volume spike missing")))
                .firstFailedFilter("volumeSpike")
                .ivRank(42.0)
                .build();
        bridge.record(ctx2);

        ArgumentCaptor<com.algo.trade.tuning.TuningEvent> captor =
                ArgumentCaptor.forClass(com.algo.trade.tuning.TuningEvent.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(EvaluationEvent.class);
    }

    @Test
    void isFiredSignal_detectsBuyAndSell() {
        assertThat(TuningCaptureBridge.isFiredSignal(new StrategyDecision(
                Instant.now(), UnderlyingSymbol.NIFTY, SignalType.BUY_PE,
                BigDecimal.ONE, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, BigDecimal.ZERO, List.of()))).isTrue();
        assertThat(TuningCaptureBridge.isFiredSignal(new StrategyDecision(
                Instant.now(), UnderlyingSymbol.NIFTY, SignalType.NO_TRADE,
                BigDecimal.ONE, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                false, Optional.empty(), false, BigDecimal.ZERO, List.of()))).isFalse();
    }

    private static SignalRecordContext sampleCtx(String strategyType, SignalType signalType) {
        return SignalRecordContext.builder()
                .strategyType(strategyType)
                .underlying(UnderlyingSymbol.NIFTY)
                .decision(new StrategyDecision(
                        Instant.now(), UnderlyingSymbol.NIFTY, signalType,
                        BigDecimal.valueOf(23500), Optional.of(BigDecimal.valueOf(100)),
                        Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of("NFO:TEST"), Optional.of(BigDecimal.valueOf(23500)),
                        Optional.of(OptionType.CE), true, Optional.empty(), false,
                        BigDecimal.valueOf(75), List.of("test")))
                .ivRank(30.0)
                .build();
    }

    private static TuningPipelineCapture mockPipelineCapture(StrategyType strategy) {
        TuningPipelineCapture capture = mock(TuningPipelineCapture.class);
        when(capture.strategy()).thenReturn(strategy);
        when(capture.cadence()).thenReturn(CadenceHint.MEDIUM);
        when(capture.defaultEpisodeWindowSec()).thenReturn(60);
        when(capture.buildImmediateEvaluationEvent(any(), any())).thenReturn(
                new EvaluationEvent(Instant.now(), Instant.now(), strategy, IndexType.NIFTY,
                        "EVAL-1", EvaluationOutcome.BLOCKED, "test", 1, Map.of()));
        when(capture.buildSignalEvent(any(), any())).thenReturn(
                new SignalEvent(Instant.now(), Instant.now(), strategy, IndexType.NIFTY,
                        "sig-1", "NFO:X", 23500, OptionType.CE, BigDecimal.TEN, Map.of()));
        return capture;
    }
}
