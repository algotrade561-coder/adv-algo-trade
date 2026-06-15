package com.algo.trade.tuning.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.ExitEvent;
import com.algo.trade.tuning.adapter.strategies.MomentumCaptureAdapter;
import com.algo.trade.tuning.infra.MaeMfeTracker;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PipelineCaptureExitSupportTest {

    private TuningEventRecorder recorder;
    private TuningCaptureBridge bridge;

    @BeforeEach
    void setUp() {
        recorder = mock(TuningEventRecorder.class);
        bridge = new TuningCaptureBridge(recorder,
                Map.of(StrategyType.MOMENTUM, new MomentumCaptureAdapter()));
    }

    @Test
    void buildExitEvent_computesRealizedPnlAndMaeMfe() {
        TradeEntity trade = trade("PAPER-TRD-1", StrategyType.MOMENTUM.name());
        MaeMfeTracker.Snapshot snapshot = snapshot(trade.getTradeId(), "corr-1");

        ExitEvent event = PipelineCaptureExitSupport.buildExitEvent(
                StrategyType.MOMENTUM,
                IndexType.NIFTY,
                trade,
                snapshot,
                "corr-1",
                BigDecimal.valueOf(110),
                "TARGET",
                false);

        assertThat(event.realizedPnlPct()).isEqualTo(10.0);
        assertThat(event.maePct()).isEqualTo(-3.0);
        assertThat(event.mfePct()).isEqualTo(12.0);
        assertThat(event.correlationKey()).isEqualTo("corr-1");
    }

    @Test
    void recordExit_writesExitEventForPipelineStrategy() {
        TradeEntity trade = trade("PAPER-TRD-2", StrategyType.MOMENTUM.name());
        MaeMfeTracker.Snapshot snapshot = snapshot(trade.getTradeId(), "corr-2");

        bridge.recordExit(trade, snapshot, BigDecimal.valueOf(105), "STOP_LOSS", false);

        ArgumentCaptor<com.algo.trade.tuning.TuningEvent> captor =
                ArgumentCaptor.forClass(com.algo.trade.tuning.TuningEvent.class);
        verify(recorder).record(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ExitEvent.class);
        assertThat(((ExitEvent) captor.getValue()).exitReason()).isEqualTo("STOP_LOSS");
    }

    @Test
    void recordExit_skipsInlineDualWriteStrategies() {
        TradeEntity trade = trade("TRD-3", StrategyType.OI_MOMENTUM.name());

        bridge.recordExit(trade, null, BigDecimal.TEN, "TARGET", false);

        verify(recorder, never()).record(any());
    }

    private static TradeEntity trade(String tradeId, String strategyType) {
        TradeEntity trade = new TradeEntity(
                tradeId,
                "NFO:TEST",
                IndexType.NIFTY.name(),
                OptionType.CE.name(),
                TradeStatus.OPEN,
                50,
                BigDecimal.valueOf(100),
                Instant.now().minusSeconds(300),
                "test entry");
        trade.setStrategyType(strategyType);
        return trade;
    }

    private static MaeMfeTracker.Snapshot snapshot(String tradeId, String correlationKey) {
        MaeMfeTracker.EntryContext entry = new MaeMfeTracker.EntryContext(
                tradeId,
                StrategyType.MOMENTUM,
                IndexType.NIFTY,
                correlationKey,
                MaeMfeTracker.Direction.LONG,
                "NFO:TEST",
                23500,
                OptionType.CE,
                BigDecimal.valueOf(100),
                23500.0,
                Instant.now().minusSeconds(300));
        return new MaeMfeTracker.Snapshot(
                entry, -3.0, Instant.now().minusSeconds(240), 23490.0,
                12.0, Instant.now().minusSeconds(180), 23520.0,
                4, BigDecimal.valueOf(110));
    }
}
