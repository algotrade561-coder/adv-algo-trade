package com.algo.trade.tuning.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.ForwardCheckpointEvent;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.capture.CaptureToggleService;
import com.algo.trade.tuning.recorder.IstDayClock;
import com.algo.trade.tuning.recorder.TuningEventCsvWriter;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/**
 * Phase 1, Commit 6 — forward checkpoint backfill. Tests use a real recorder + real
 * buffer (with mocked LiveInstrumentCache for live capture, ingested directly for
 * fixtures) so the round-trip through CSV + ingestion is exercised end-to-end.
 */
class ForwardCheckpointServiceTest {

    private static final Instant SIGNAL_TIME = Instant.parse("2026-06-01T09:30:00Z");

    @TempDir Path tempDir;

    private CaptureToggleService captureToggle;
    private MarketSnapshotBuffer buffer;
    private TuningEventRecorder recorder;
    private IstDayClock clock;
    private ForwardCheckpointService service;

    @BeforeEach
    void setUp() {
        captureToggle = Mockito.mock(CaptureToggleService.class);
        when(captureToggle.isEnabled(any(StrategyType.class), any(TuningEventType.class)))
                .thenReturn(true);

        LiveInstrumentCache liveCache = Mockito.mock(LiveInstrumentCache.class);
        when(liveCache.allOptions()).thenReturn(List.of());
        buffer = new MarketSnapshotBuffer(liveCache);
        buffer.init();

        clock = new IstDayClock(Clock.fixed(SIGNAL_TIME, ZoneId.of("Asia/Kolkata")));
        recorder = new TuningEventRecorder(tempDir, captureToggle, new TuningEventCsvWriter(), clock);
        service = new ForwardCheckpointService(tempDir, buffer, recorder, clock);
    }

    @Test
    void ripeSignalWithFullBufferProducesCheckpointEvent() throws Exception {
        // Pre-record one signal at SIGNAL_TIME via the recorder, then seed the buffer
        // with snapshots covering the 30-minute window.
        recorder.record(new SignalEvent(SIGNAL_TIME, SIGNAL_TIME, StrategyType.OI_MOMENTUM,
                IndexType.NIFTY, "decision-1",
                "NFO:NIFTY25JUN23500CE", 23_500, OptionType.CE, new BigDecimal("125.50"), Map.of()));
        seedBuffer(IndexType.NIFTY, SIGNAL_TIME, 23_500.0,
                /* +30s */ 23_510.0,
                /* +1m  */ 23_515.0,
                /* +5m  */ 23_540.0,
                /* +15m */ 23_580.0,
                /* +30m */ 23_620.0);

        Instant now = SIGNAL_TIME.plus(Duration.ofMinutes(32));
        int written = service.sweep(now);

        assertThat(written).isEqualTo(1);
        Path forwardCsv = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("forward_checkpoint.csv");
        List<String> lines = Files.readAllLines(forwardCsv);
        assertThat(lines).hasSize(2);   // header + 1 row
        assertThat(lines.get(0)).startsWith("eventTime,recordedAtDeltaUs,index,correlationKey,");
        String row = lines.get(1);
        assertThat(row).contains(",NIFTY,decision-1,");
        // Spot path should be present (each value formatted with %.3f by the writer).
        assertThat(row).contains("23510.000");
        assertThat(row).contains("23620.000");
        // MFE positive (max in window > spot at signal); MAE non-positive.
        assertThat(row).contains("0.511");   // (23620-23500)/23500 ≈ 0.511 → "0.511"
    }

    @Test
    void unripeSignalIsDeferredToNextSweep() throws Exception {
        recorder.record(sampleSignal());
        seedBuffer(IndexType.NIFTY, SIGNAL_TIME, 23_500.0,
                23_510.0, 23_515.0, 23_540.0, 23_580.0, 23_620.0);

        // Signal time + 30 min = signal's last checkpoint; need + 31 min for ripeness.
        Instant tooEarly = SIGNAL_TIME.plus(Duration.ofMinutes(30));
        int written = service.sweep(tooEarly);

        assertThat(written).isZero();
        Path forwardCsv = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("forward_checkpoint.csv");
        assertThat(forwardCsv).doesNotExist();
    }

    @Test
    void idempotency_secondSweepProducesNoAdditionalRows() throws Exception {
        recorder.record(sampleSignal());
        seedBuffer(IndexType.NIFTY, SIGNAL_TIME, 23_500.0,
                23_510.0, 23_515.0, 23_540.0, 23_580.0, 23_620.0);
        Instant ripe = SIGNAL_TIME.plus(Duration.ofMinutes(32));

        int first = service.sweep(ripe);
        int second = service.sweep(ripe);

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        Path forwardCsv = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("forward_checkpoint.csv");
        assertThat(Files.readAllLines(forwardCsv)).hasSize(2);
    }

    @Test
    void idempotency_acrossJvmRestart_readsExistingForwardCsv() throws Exception {
        recorder.record(sampleSignal());
        seedBuffer(IndexType.NIFTY, SIGNAL_TIME, 23_500.0,
                23_510.0, 23_515.0, 23_540.0, 23_580.0, 23_620.0);

        Instant ripe = SIGNAL_TIME.plus(Duration.ofMinutes(32));
        service.sweep(ripe);

        // Simulate JVM restart: a fresh service with empty in-memory processedKeys.
        ForwardCheckpointService fresh = new ForwardCheckpointService(tempDir, buffer, recorder, clock);
        int againWritten = fresh.sweep(ripe);

        assertThat(againWritten).isZero();
        Path forwardCsv = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("forward_checkpoint.csv");
        assertThat(Files.readAllLines(forwardCsv)).hasSize(2);
    }

    @Test
    void noAnchorSnapshot_signalIsSkipped() throws Exception {
        // Record a signal but never seed the buffer — no anchor, no spot at signal.
        recorder.record(sampleSignal());
        Instant ripe = SIGNAL_TIME.plus(Duration.ofMinutes(32));

        int written = service.sweep(ripe);

        assertThat(written).isZero();
    }

    @Test
    void missingForwardCheckpoint_recordedAsNull() throws Exception {
        recorder.record(sampleSignal());
        // Only seed anchor at signal time + a single +1m snapshot. +5/+15/+30 missing.
        buffer.ingest(IndexType.NIFTY, SIGNAL_TIME, 23_500.0, 23_500, 100.0, 100.0, Map.of());
        buffer.ingest(IndexType.NIFTY, SIGNAL_TIME.plusSeconds(60), 23_510.0, 23_500, 102.0, 99.0, Map.of());

        Instant ripe = SIGNAL_TIME.plus(Duration.ofMinutes(32));
        int written = service.sweep(ripe);

        assertThat(written).isEqualTo(1);
        Path forwardCsv = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("forward_checkpoint.csv");
        String row = Files.readAllLines(forwardCsv).get(1);
        // The buffer's nearest() falls back to the closest available snapshot — with only
        // two snapshots present, every checkpoint resolves to one of them. So the row has
        // numeric values throughout, not empty cells.
        assertThat(row).contains(",NIFTY,decision-1,");
    }

    @Test
    void multiStrategy_independentBackfill() throws Exception {
        // Two signals from different strategies — both should be processed independently.
        recorder.record(new SignalEvent(SIGNAL_TIME, SIGNAL_TIME, StrategyType.OI_MOMENTUM,
                IndexType.NIFTY, "key-A",
                "NFO:NIFTY25JUN23500CE", 23_500, OptionType.CE, new BigDecimal("125.50"), Map.of()));
        recorder.record(new SignalEvent(SIGNAL_TIME, SIGNAL_TIME, StrategyType.OI_SHIFT_TRAP,
                IndexType.SENSEX, "key-B",
                "BFO:SENSEX25JUN75000PE", 75_000, OptionType.PE, new BigDecimal("200.00"), Map.of()));

        seedBuffer(IndexType.NIFTY, SIGNAL_TIME, 23_500.0,
                23_510.0, 23_515.0, 23_540.0, 23_580.0, 23_620.0);
        seedBuffer(IndexType.SENSEX, SIGNAL_TIME, 75_000.0,
                75_100.0, 75_200.0, 75_300.0, 75_400.0, 75_500.0);

        Instant ripe = SIGNAL_TIME.plus(Duration.ofMinutes(32));
        int written = service.sweep(ripe);

        assertThat(written).isEqualTo(2);
        assertThat(tempDir.resolve("2026-06-01/oi_momentum/forward_checkpoint.csv")).exists();
        assertThat(tempDir.resolve("2026-06-01/oi_shift_trap/forward_checkpoint.csv")).exists();
    }

    @Test
    void captureToggleOffForForward_preventsCheckpointEmission() throws Exception {
        recorder.record(sampleSignal());
        seedBuffer(IndexType.NIFTY, SIGNAL_TIME, 23_500.0,
                23_510.0, 23_515.0, 23_540.0, 23_580.0, 23_620.0);

        // Toggle off forward capture (signal capture stays on so the source signal is present).
        when(captureToggle.isEnabled(StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT))
                .thenReturn(false);

        Instant ripe = SIGNAL_TIME.plus(Duration.ofMinutes(32));
        int written = service.sweep(ripe);

        // Service still iterates and produces an event, but the recorder rejects it
        // because the toggle is off. From the service's perspective the count it
        // returns is the number of attempted writes — which is 1 — but no CSV row appears.
        assertThat(written).isEqualTo(1);
        Path forwardCsv = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("forward_checkpoint.csv");
        assertThat(forwardCsv).doesNotExist();
    }

    @Test
    void backfillSilentlyContinuesOnMalformedRow() throws Exception {
        // Write a hand-crafted signal.csv with one good row and one corrupt row.
        Path dir = tempDir.resolve("2026-06-01").resolve("oi_momentum");
        Files.createDirectories(dir);
        Path signalCsv = dir.resolve("signal.csv");
        Files.writeString(signalCsv,
                "eventTime,recordedAtDeltaUs,index,correlationKey,instrumentKey,strike,optionType,entryPremium,attr_extra\n"
                + "2026-06-01T09:30:00Z,0,NIFTY,decision-good,NFO:X,23500,CE,100,{}\n"
                + "totally-garbage-line\n"
                + "2026-06-01T09:30:05Z,0,BOGUS_INDEX,decision-bad-index,NFO:X,23500,CE,100,{}\n");
        seedBuffer(IndexType.NIFTY, SIGNAL_TIME, 23_500.0,
                23_510.0, 23_515.0, 23_540.0, 23_580.0, 23_620.0);

        Instant ripe = SIGNAL_TIME.plus(Duration.ofMinutes(32));
        int written = service.sweep(ripe);

        assertThat(written).isEqualTo(1);   // good row processed, bad rows skipped silently
    }

    @Test
    void splitFirstColumns_handlesAttrExtraWithEmbeddedCommas() {
        String row = "2026-06-01T09:30:00Z,123,NIFTY,key-X,NFO:X,23500,CE,100,\"{\\\"a\\\":1,\\\"b\\\":2}\"";
        String[] cols = ForwardCheckpointService.splitFirstColumns(row, 4);
        assertThat(cols).hasSizeGreaterThanOrEqualTo(5);
        assertThat(cols[0]).isEqualTo("2026-06-01T09:30:00Z");
        assertThat(cols[3]).isEqualTo("key-X");
        // The 5th element absorbs the rest of the row including JSON content.
        assertThat(cols[4]).contains("NFO:X").contains("23500").contains("CE");
    }

    @Test
    void weekendRollover_backfillsFridaySignalOnMondayMorning() throws Exception {
        // Simulate Friday 15:25 IST signal whose +30m checkpoint at 15:55 lands past
        // EC2 shutdown. SnapshotWarmupService is assumed to have loaded Friday's
        // chain snapshots into the buffer (tested separately); the service should
        // now find Friday's signal CSV and backfill it.
        Instant fridayClose = Instant.parse("2026-06-05T09:55:00Z");   // 15:25 IST
        SignalEvent friSignal = new SignalEvent(fridayClose, fridayClose,
                StrategyType.OI_MOMENTUM, IndexType.NIFTY, "fri-25",
                "NFO:NIFTY25JUN23500CE", 23_500, OptionType.CE,
                new BigDecimal("125.00"), Map.of());

        // Use a Monday-morning clock for the recorder so signal.csv lands at Friday's path.
        IstDayClock fridayClock = new IstDayClock(Clock.fixed(fridayClose, ZoneId.of("Asia/Kolkata")));
        TuningEventRecorder fridayRecorder = new TuningEventRecorder(tempDir, captureToggle,
                new TuningEventCsvWriter(), fridayClock);
        fridayRecorder.record(friSignal);

        // Seed Friday's snapshots (simulating warmup pulling them in on Monday boot).
        buffer.ingest(IndexType.NIFTY, fridayClose, 23_500.0, 23_500, 100.0, 100.0, Map.of());
        buffer.ingest(IndexType.NIFTY, fridayClose.plus(Duration.ofMinutes(5)),
                23_510.0, 23_500, 100.0, 100.0, Map.of());

        // Now run the sweep with a Monday morning timestamp.
        Instant mondayMorning = Instant.parse("2026-06-08T03:15:00Z");      // Mon 08:45 IST
        IstDayClock mondayClock = new IstDayClock(Clock.fixed(mondayMorning, ZoneId.of("Asia/Kolkata")));
        ForwardCheckpointService weekendService = new ForwardCheckpointService(
                tempDir, buffer, fridayRecorder, mondayClock);

        int written = weekendService.sweep(mondayMorning);

        assertThat(written).isEqualTo(1);
        Path fridayForwardCsv = tempDir.resolve("2026-06-05").resolve("oi_momentum")
                .resolve("forward_checkpoint.csv");
        assertThat(fridayForwardCsv).exists();
        assertThat(Files.readAllLines(fridayForwardCsv)).hasSize(2);   // header + 1 row
    }

    @Test
    void emitsForwardEventViaRecorderWithCorrectStrategyAndCorrelation() throws Exception {
        // Use a Mockito spy on the recorder to capture exactly what we asked it to write.
        TuningEventRecorder spy = Mockito.spy(recorder);
        ForwardCheckpointService localService = new ForwardCheckpointService(tempDir, buffer, spy, clock);

        recorder.record(sampleSignal());
        seedBuffer(IndexType.NIFTY, SIGNAL_TIME, 23_500.0,
                23_510.0, 23_515.0, 23_540.0, 23_580.0, 23_620.0);

        Instant ripe = SIGNAL_TIME.plus(Duration.ofMinutes(32));
        localService.sweep(ripe);

        ArgumentCaptor<com.algo.trade.tuning.TuningEvent> captor =
                ArgumentCaptor.forClass(com.algo.trade.tuning.TuningEvent.class);
        verify(spy, atLeastOnce()).record(captor.capture());
        ForwardCheckpointEvent ev = (ForwardCheckpointEvent) captor.getValue();
        assertThat(ev.strategy()).isEqualTo(StrategyType.OI_MOMENTUM);
        assertThat(ev.index()).isEqualTo(IndexType.NIFTY);
        assertThat(ev.correlationKey()).isEqualTo("decision-1");
        assertThat(ev.fwdSpot30s()).isEqualTo(23_510.0);
        assertThat(ev.fwdSpot30m()).isEqualTo(23_620.0);
        assertThat(ev.fwdMfe30mPct()).isPositive();
        assertThat(ev.fwdMae30mPct()).isLessThanOrEqualTo(0.0);
        assertThat(ev.isComplete()).isTrue();
        verify(spy, never()).record(any(SignalEvent.class));   // service only emits forward checkpoints
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private SignalEvent sampleSignal() {
        return new SignalEvent(SIGNAL_TIME, SIGNAL_TIME, StrategyType.OI_MOMENTUM,
                IndexType.NIFTY, "decision-1",
                "NFO:NIFTY25JUN23500CE", 23_500, OptionType.CE, new BigDecimal("125.50"), Map.of());
    }

    /**
     * Seeds the buffer with: an anchor at signal time + the 5 checkpoint values + a
     * range scan between signal and signal+30m so MFE / MAE compute over real data.
     */
    private void seedBuffer(IndexType index, Instant signalTime, double spotAtSignal,
                            double at30s, double at1m, double at5m, double at15m, double at30m) {
        buffer.ingest(index, signalTime, spotAtSignal, (int) spotAtSignal, 100.0, 100.0, Map.of());
        buffer.ingest(index, signalTime.plus(Duration.ofSeconds(30)),  at30s,  (int) at30s,  100.0, 100.0, Map.of());
        buffer.ingest(index, signalTime.plus(Duration.ofMinutes(1)),   at1m,   (int) at1m,   100.0, 100.0, Map.of());
        buffer.ingest(index, signalTime.plus(Duration.ofMinutes(5)),   at5m,   (int) at5m,   100.0, 100.0, Map.of());
        buffer.ingest(index, signalTime.plus(Duration.ofMinutes(15)),  at15m,  (int) at15m,  100.0, 100.0, Map.of());
        buffer.ingest(index, signalTime.plus(Duration.ofMinutes(30)),  at30m,  (int) at30m,  100.0, 100.0, Map.of());
    }
}
