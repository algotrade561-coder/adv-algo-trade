package com.algo.trade.tuning.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.EvaluationEvent;
import com.algo.trade.tuning.EvaluationOutcome;
import com.algo.trade.tuning.ExecutionEvent;
import com.algo.trade.tuning.ExitEvent;
import com.algo.trade.tuning.ForwardCheckpointEvent;
import com.algo.trade.tuning.ShadowGateEvent;
import com.algo.trade.tuning.SignalEvent;
import com.algo.trade.tuning.TuningEvent;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.capture.CaptureToggleService;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mockito;

/**
 * Phase 1, Commit 3 — sync recorder. Verifies path routing, daily rotation, header
 * initialization, JSON attribute serialization, capture-toggle gating, and per-file
 * mutex correctness under concurrent writes.
 */
class TuningEventRecorderTest {

    @TempDir Path tempDir;

    private CaptureToggleService captureToggle;
    private TuningEventRecorder recorder;
    private Clock fakeClock;

    @BeforeEach
    void setUp() {
        captureToggle = Mockito.mock(CaptureToggleService.class);
        when(captureToggle.isEnabled(any(StrategyType.class), any(TuningEventType.class)))
                .thenReturn(true);

        fakeClock = Clock.fixed(Instant.parse("2026-06-01T09:30:00Z"), ZoneId.of("Asia/Kolkata"));
        recorder = new TuningEventRecorder(tempDir, captureToggle, new TuningEventCsvWriter(),
                new IstDayClock(fakeClock));
    }

    @Test
    void writesRowToCorrectPath_andCreatesHeaderOnFirstWrite() throws IOException {
        SignalEvent event = sampleSignal(Instant.parse("2026-06-01T09:30:15.123Z"));

        recorder.record(event);

        Path expected = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("signal.csv");
        assertThat(expected).exists();

        List<String> lines = Files.readAllLines(expected);
        assertThat(lines).hasSize(2);     // header + one row
        assertThat(lines.get(0))
                .startsWith("eventTime,recordedAtDeltaUs,index,correlationKey,");
        assertThat(lines.get(1))
                .startsWith("2026-06-01T09:30:15.123Z,")
                .contains(",NIFTY,decision-1,");
    }

    @Test
    void capturedOff_isNoop() throws IOException {
        when(captureToggle.isEnabled(StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL))
                .thenReturn(false);

        recorder.record(sampleSignal(Instant.parse("2026-06-01T09:30:15Z")));

        // No file written, no directory created.
        assertThat(Files.list(tempDir).count()).isZero();
        assertThat(recorder.totalWrites()).isZero();
    }

    @Test
    void appendingTwoEventsProducesThreeLines_headerWrittenOnce() throws IOException {
        recorder.record(sampleSignal(Instant.parse("2026-06-01T09:30:15Z")));
        recorder.record(sampleSignal(Instant.parse("2026-06-01T09:30:20Z")));

        Path file = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("signal.csv");
        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).startsWith("eventTime");
    }

    @Test
    void istDayRotation_writesToDifferentFileOnNewDay() throws IOException {
        // 2026-06-01T18:00 UTC = 2026-06-01T23:30 IST — same IST day
        recorder.record(sampleSignal(Instant.parse("2026-06-01T18:00:00Z")));
        // 2026-06-01T19:00 UTC = 2026-06-02T00:30 IST — different IST day
        recorder.record(sampleSignal(Instant.parse("2026-06-01T19:00:00Z")));

        Path d1 = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("signal.csv");
        Path d2 = tempDir.resolve("2026-06-02").resolve("oi_momentum").resolve("signal.csv");

        assertThat(d1).exists();
        assertThat(d2).exists();
        assertThat(Files.readAllLines(d1)).hasSize(2);   // header + 1 row
        assertThat(Files.readAllLines(d2)).hasSize(2);
    }

    @Test
    void routesEachEventTypeToItsOwnFile() throws IOException {
        Instant t = Instant.parse("2026-06-01T09:30:00Z");

        recorder.record(new EvaluationEvent(t, t, StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "ep-1", EvaluationOutcome.SKIPPED, null, 5, Map.of("biasScore", 38)));
        recorder.record(sampleSignal(t));
        recorder.record(new ExitEvent(t, t, StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "decision-1", "TRD-x", "TARGET",
                new BigDecimal("100"), new BigDecimal("120"),
                20.0, 600L, -3.5, 21.0, 240L, 540L, false, Map.of()));
        recorder.record(new ForwardCheckpointEvent(t, t, StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "decision-1", 23_500.0, 23_510.0, 23_520.0, 23_540.0, 23_580.0,
                0.34, -0.12, Map.of()));
        recorder.record(new ShadowGateEvent(t, t, StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "decision-1", "confirm_oiStillBuilding", true, null, Map.of()));
        recorder.record(new ExecutionEvent(t, t, StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "decision-1", "ENTRY-uuid", "FILLED", 65, 65,
                new BigDecimal("125.50"), 0.4, null, Map.of()));

        Path dir = tempDir.resolve("2026-06-01").resolve("oi_momentum");
        assertThat(dir.resolve("evaluation.csv")).exists();
        assertThat(dir.resolve("signal.csv")).exists();
        assertThat(dir.resolve("execution.csv")).exists();
        assertThat(dir.resolve("exit.csv")).exists();
        assertThat(dir.resolve("forward_checkpoint.csv")).exists();
        assertThat(dir.resolve("shadow_gate.csv")).exists();
    }

    @Test
    void serializesAttributesAsJsonInCsvQuotedColumn() throws IOException {
        Instant t = Instant.parse("2026-06-01T09:30:00Z");
        SignalEvent event = new SignalEvent(t, t, StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "decision-1", "NFO:NIFTY25JUN23500CE", 23_500, OptionType.CE,
                new BigDecimal("125.50"),
                Map.of("score", 72, "case", "CASE2_M+PCR", "biasScore", 38));

        recorder.record(event);

        Path file = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("signal.csv");
        String row = Files.readAllLines(file).get(1);

        // The attr_extra column is CSV-quoted JSON — RFC 4180 doubles every embedded
        // "; so JSON {"score":72} surfaces in the row as ""score"":72.
        assertThat(row).contains("\"\"score\"\":72");
        assertThat(row).contains("\"\"case\"\":\"\"CASE2_M+PCR\"\"");
        assertThat(row).contains("\"\"biasScore\"\":38");
    }

    @Test
    void existingFileFromPriorJvmRun_isAppendedTo_notRewrittenWithHeader() throws IOException {
        // Simulate a prior JVM session leaving a file behind.
        Path dir = tempDir.resolve("2026-06-01").resolve("oi_momentum");
        Files.createDirectories(dir);
        Path file = dir.resolve("signal.csv");
        Files.writeString(file, "eventTime,recordedAtDeltaUs,index,correlationKey,"
                + "instrumentKey,strike,optionType,entryPremium,attr_extra\n"
                + "2026-06-01T09:00:00Z,0,NIFTY,prev-key,NFO:X,23000,CE,100.0,{}\n");

        recorder.record(sampleSignal(Instant.parse("2026-06-01T09:30:00Z")));

        List<String> lines = Files.readAllLines(file);
        assertThat(lines).hasSize(3);   // header + prior row + new row
        assertThat(lines.get(0)).startsWith("eventTime");
        assertThat(lines.get(1)).contains("prev-key");
        assertThat(lines.get(2)).contains("decision-1");
    }

    @Test
    void concurrentWritesToSameFile_produceWellFormedLines() throws Exception {
        int threads = 8;
        int writesPerThread = 250;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            final int threadId = t;
            pool.submit(() -> {
                ready.countDown();
                try {
                    go.await();
                    for (int i = 0; i < writesPerThread; i++) {
                        Instant ts = Instant.parse("2026-06-01T09:30:00Z").plusMillis(threadId * 1000L + i);
                        recorder.record(new SignalEvent(ts, ts, StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                                "k-" + threadId + "-" + i,
                                "NFO:X", 23_500, OptionType.CE, new BigDecimal("100"), Map.of()));
                    }
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await(5, TimeUnit.SECONDS);
        go.countDown();
        assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        Path file = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("signal.csv");
        List<String> lines = Files.readAllLines(file);
        // Header + (threads * writesPerThread) rows
        assertThat(lines).hasSize(1 + threads * writesPerThread);
        // Every line has the same column count — no interleaving (count commas in row body
        // is unreliable because the JSON column may not contain commas; instead verify each
        // data row starts with an ISO-8601 timestamp).
        for (int i = 1; i < lines.size(); i++) {
            assertThat(lines.get(i))
                    .as("row %d should start with an ISO timestamp", i)
                    .matches("^\\d{4}-\\d{2}-\\d{2}T.*");
        }
    }

    @Test
    void recordSilentlySucceedsWhenWriteFailsForOneCall() throws Exception {
        // Use a read-only base dir to force IOException.
        Path readonly = Files.createDirectory(tempDir.resolve("readonly"));
        // Best-effort: not every OS honors this, but at minimum the failure path is exercised
        // via the next event whose parent dir creation may still succeed. So we directly
        // simulate by recording on a path that already exists as a directory.
        Path collisionPath = tempDir.resolve("2026-06-01").resolve("oi_momentum").resolve("signal.csv");
        Files.createDirectories(collisionPath);   // make it a directory; writeString will fail
        recorder.record(sampleSignal(Instant.parse("2026-06-01T09:30:00Z")));

        // The recorder caught the failure; counter incremented; no exception propagated.
        assertThat(recorder.totalFailures()).isPositive();
    }

    @Test
    void avgWriteLatencyMicrosIsNonZeroAfterWrites() {
        recorder.record(sampleSignal(Instant.parse("2026-06-01T09:30:00Z")));
        assertThat(recorder.totalWrites()).isEqualTo(1L);
        assertThat(recorder.avgWriteLatencyMicros()).isGreaterThan(0.0);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static SignalEvent sampleSignal(Instant t) {
        return new SignalEvent(t, t.plusMillis(1),
                StrategyType.OI_MOMENTUM, IndexType.NIFTY,
                "decision-1", "NFO:NIFTY25JUN23500CE", 23_500, OptionType.CE,
                new BigDecimal("125.50"),
                Map.of("score", 72, "case", "CASE2_M+PCR"));
    }
}
