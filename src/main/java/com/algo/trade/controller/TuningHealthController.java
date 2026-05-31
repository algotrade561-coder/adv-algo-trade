package com.algo.trade.controller;

import com.algo.trade.reporting.TuningReportService;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.capture.CaptureSettings;
import com.algo.trade.tuning.capture.CaptureToggleService;
import com.algo.trade.tuning.capture.TuningReportJobEntity;
import com.algo.trade.tuning.capture.TuningReportJobStatus;
import com.algo.trade.tuning.infra.ForwardCheckpointService;
import com.algo.trade.tuning.recorder.TuningEventRecorder;
import com.algo.trade.tuning.store.ParquetRollerService;
import com.algo.trade.tuning.store.TuningEventStore;
import com.algo.trade.tuning.store.TuningEventStoreProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Rolled-up tuning-pipeline health. One round-trip for the main-dashboard
 * "Tuning Capture Health" widget — read-only, no toggles, no raw JSON.
 *
 * <p>Endpoint: {@code GET /tuning/health} — returns a stable schema regardless of
 * whether DuckDB has loaded, whether any captures are on, or whether any reports
 * have been generated yet (fields just go to {@code null} or {@code 0}).</p>
 */
@RestController
@RequestMapping("/tuning")
public class TuningHealthController {

    private final CaptureToggleService toggles;
    private final TuningEventStore eventStore;
    private final TuningEventStoreProperties storeProps;
    private final TuningEventRecorder recorder;
    private final ParquetRollerService roller;
    private final ForwardCheckpointService forwardSweep;
    private final TuningReportService reportService;

    @Autowired
    public TuningHealthController(CaptureToggleService toggles,
                                  TuningEventStore eventStore,
                                  TuningEventStoreProperties storeProps,
                                  @Autowired(required = false) TuningEventRecorder recorder,
                                  @Autowired(required = false) ParquetRollerService roller,
                                  @Autowired(required = false) ForwardCheckpointService forwardSweep,
                                  TuningReportService reportService) {
        this.toggles = toggles;
        this.eventStore = eventStore;
        this.storeProps = storeProps;
        this.recorder = recorder;
        this.roller = roller;
        this.forwardSweep = forwardSweep;
        this.reportService = reportService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("generatedAt", Instant.now().toString());

        body.put("captureToggles", captureTogglesBlock());
        body.put("todayEvents", todayEventsBlock());
        body.put("recorder", recorderBlock());
        body.put("lastRollerRun", lastRollerRunBlock());
        body.put("lastForwardSweep", lastForwardSweepBlock());
        body.put("duckdbTempDir", duckdbTempDirBlock());
        body.put("latestReport", latestReportBlock());

        return body;
    }

    private Map<String, Object> captureTogglesBlock() {
        List<CaptureSettings> all = toggles.listAll();
        long enabled = all.stream().filter(CaptureSettings::captureEnabled).count();
        List<String> enabledList = all.stream()
                .filter(CaptureSettings::captureEnabled)
                .map(s -> s.strategy().name())
                .sorted()
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalStrategies", StrategyType.values().length);
        m.put("enabledStrategies", (int) enabled);
        m.put("enabledList", enabledList);
        return m;
    }

    private Map<String, Object> todayEventsBlock() {
        LocalDate today = LocalDate.now();
        Map<String, Object> m = new LinkedHashMap<>();
        // Count of strategies that wrote at least one file of this event type today.
        for (TuningEventType type : TuningEventType.values()) {
            int strategiesWithFiles = 0;
            for (StrategyType strategy : StrategyType.values()) {
                if (!eventStore.listEventFiles(strategy, type, today, today).isEmpty()) {
                    strategiesWithFiles++;
                }
            }
            m.put(type.name(), strategiesWithFiles);
        }
        return m;
    }

    private Map<String, Object> recorderBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (recorder == null) {
            m.put("present", false);
            m.put("totalWrites", 0L);
            m.put("totalFailures", 0L);
            m.put("avgWriteLatencyMicros", 0.0);
            return m;
        }
        m.put("present", true);
        m.put("totalWrites", recorder.totalWrites());
        m.put("totalFailures", recorder.totalFailures());
        m.put("avgWriteLatencyMicros", recorder.avgWriteLatencyMicros());
        return m;
    }

    private Map<String, Object> lastRollerRunBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (roller == null) {
            m.put("present", false);
            return m;
        }
        ParquetRollerService.LastRun lr = roller.lastRun();
        m.put("present", true);
        if (lr == null) {
            m.put("at", null);
            m.put("success", null);
            m.put("datesProcessed", 0);
            m.put("filesRolled", 0);
            m.put("retentionPurged", 0);
            m.put("errorMessage", null);
            return m;
        }
        m.put("at", lr.at() != null ? lr.at().toString() : null);
        m.put("success", lr.success());
        m.put("datesProcessed", lr.datesProcessed());
        m.put("filesRolled", lr.filesRolled());
        m.put("retentionPurged", lr.retentionPurged());
        m.put("errorMessage", lr.errorMessage());
        return m;
    }

    private Map<String, Object> lastForwardSweepBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (forwardSweep == null) {
            m.put("present", false);
            return m;
        }
        ForwardCheckpointService.LastSweep ls = forwardSweep.lastSweep();
        m.put("present", true);
        if (ls == null) {
            m.put("at", null);
            m.put("success", null);
            m.put("newCheckpoints", 0);
            m.put("errorMessage", null);
            return m;
        }
        m.put("at", ls.at() != null ? ls.at().toString() : null);
        m.put("success", ls.success());
        m.put("newCheckpoints", ls.newCheckpoints());
        m.put("errorMessage", ls.errorMessage());
        return m;
    }

    private Map<String, Object> duckdbTempDirBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        String pathStr = storeProps.getTempDirectory();
        m.put("path", pathStr);
        try {
            Path p = Path.of(pathStr);
            boolean exists = Files.isDirectory(p);
            m.put("exists", exists);
            if (exists) {
                m.put("freeBytes", Files.getFileStore(p).getUsableSpace());
            } else {
                m.put("freeBytes", null);
            }
        } catch (Exception ex) {
            m.put("exists", false);
            m.put("freeBytes", null);
            m.put("error", ex.getMessage());
        }
        return m;
    }

    private Map<String, Object> latestReportBlock() {
        Map<String, Object> m = new LinkedHashMap<>();
        Optional<TuningReportJobEntity> latest = reportService.recentJobs(1).stream().findFirst();
        if (latest.isEmpty()) {
            m.put("present", false);
            return m;
        }
        TuningReportJobEntity j = latest.get();
        m.put("present", true);
        m.put("jobId", j.getJobId());
        m.put("status", j.getStatus() != null ? j.getStatus().name() : TuningReportJobStatus.QUEUED.name());
        m.put("requestedAt", j.getRequestedAt() != null ? j.getRequestedAt().toString() : null);
        m.put("fromDate", j.getFromDate() != null ? j.getFromDate().toString() : null);
        m.put("toDate", j.getToDate() != null ? j.getToDate().toString() : null);
        m.put("durationSec", j.getDurationSec());
        m.put("hasHtml", j.getOutputHtml() != null && !j.getOutputHtml().isBlank());
        return m;
    }
}
