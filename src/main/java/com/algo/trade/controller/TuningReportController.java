package com.algo.trade.controller;

import com.algo.trade.reporting.TuningReportService;
import com.algo.trade.reporting.MarketHoursBlockedException;
import com.algo.trade.reporting.ReportCooldownException;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.DecisionRecord;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.capture.TuningReportJobEntity;
import com.algo.trade.tuning.capture.TuningReportJobStatus;
import com.algo.trade.tuning.store.TuningEventStore;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/reports/tuning")
public class TuningReportController {

    private final TuningReportService reportService;
    private final TuningEventStore eventStore;

    @org.springframework.beans.factory.annotation.Value("${tuning.chain-snapshots-dir:data/chain-snapshots}")
    private String chainSnapshotsDir;

    public TuningReportController(TuningReportService reportService, TuningEventStore eventStore) {
        this.reportService = reportService;
        this.eventStore = eventStore;
    }

    @PostMapping("/jobs")
    public ResponseEntity<?> submitJob(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "*") String strategies,
            @RequestParam(defaultValue = "ui") String requestedBy,
            @RequestParam(defaultValue = "false") boolean force,
            @RequestParam(required = false) String forceReason
    ) {
        try {
            Set<StrategyType> set = parseStrategies(strategies);
            String jobId = reportService.submit(from, to, set, requestedBy, force, forceReason);
            return ResponseEntity.accepted().body(Map.of("jobId", jobId, "status", "QUEUED"));
        } catch (MarketHoursBlockedException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "error", "MARKET_HOURS",
                    "message", ex.getMessage(),
                    "nextEligible", ex.nextEligible().toString()));
        } catch (ReportCooldownException ex) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(Map.of(
                    "error", "COOLDOWN",
                    "remainingSeconds", ex.remaining().getSeconds()));
        }
    }

    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs(@RequestParam(defaultValue = "20") int limit) {
        return reportService.recentJobs(limit).stream().map(this::toJson).collect(Collectors.toList());
    }

    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> jobStatus(@PathVariable String jobId) {
        return reportService.findJob(jobId)
                .map(j -> ResponseEntity.ok(toJson(j)))
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/{jobId}/html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> jobHtml(@PathVariable String jobId) {
        try {
            return ResponseEntity.ok(reportService.readHtml(jobId));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
        }
    }

    /** The machine-readable ranked tuning actions ({@code actions.json}) for a completed report job. */
    @GetMapping(value = "/jobs/{jobId}/actions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> jobActions(@PathVariable String jobId) {
        try {
            return ResponseEntity.ok(reportService.readActions(jobId));
        } catch (IllegalStateException ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body("{\"error\":\"" + ex.getMessage() + "\"}");
        }
    }

    /**
     * Exports the canonical {@link DecisionRecord} for offline analysis / ML, as CSV. {@code grain=trade}
     * (signal→exec→exit→forward) or {@code grain=eval} (evaluation→reject-forward). Empty CSV if no data.
     */
    @GetMapping(value = "/decision-record", produces = "text/csv")
    public ResponseEntity<String> decisionRecord(
            @RequestParam LocalDate from,
            @RequestParam LocalDate to,
            @RequestParam(defaultValue = "trade") String grain,
            @RequestParam(defaultValue = "false") boolean chain) {
        TuningEventQuery query = new TuningEventQuery(from, to, Set.of(StrategyType.OI_MOMENTUM), eventStore);
        boolean eval = "eval".equalsIgnoreCase(grain);
        TuningEventType gate = eval ? TuningEventType.EVALUATION : TuningEventType.SIGNAL;
        if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, gate)) {
            return ResponseEntity.ok("# no " + (eval ? "evaluation" : "signal") + " data in range\n");
        }
        String sql;
        if (eval) {
            sql = DecisionRecord.evalRecordSql(query);
        } else {
            sql = DecisionRecord.tradeRecordSql(query);
            // Optional multi-source enrichment: ASOF-join option-chain context (PCR / ATM / OI) by nearest snapshot.
            if (chain) {
                var chainFiles = com.algo.trade.tuning.analyzer.ChainContext.snapshotFiles(
                        java.nio.file.Path.of(chainSnapshotsDir), from, to);
                sql = com.algo.trade.tuning.analyzer.ChainContext.enrichTradeSql(sql, chainFiles);
            }
        }
        List<Map<String, Object>> rows = eventStore.query(sql);
        return ResponseEntity.ok(toCsv(rows));
    }

    /** Minimal CSV serialiser (header from first row's keys; values quoted when they contain , " or newline). */
    private static String toCsv(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return "";
        }
        List<String> cols = new java.util.ArrayList<>(rows.get(0).keySet());
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(",", cols)).append('\n');
        for (Map<String, Object> r : rows) {
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(csvCell(r.get(cols.get(i))));
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String csvCell(Object v) {
        if (v == null) return "";
        String s = v.toString();
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    @GetMapping("/strategy/{name}/today")
    public Map<String, Object> strategyToday(@PathVariable String name) {
        StrategyType strategy = StrategyType.valueOf(name.toUpperCase(Locale.ROOT));
        LocalDate today = LocalDate.now();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategy", strategy.name());
        body.put("date", today.toString());
        Map<String, Integer> files = new LinkedHashMap<>();
        for (TuningEventType t : TuningEventType.values()) {
            files.put(t.name(), eventStore.listEventFiles(strategy, t, today, today).size());
        }
        body.put("eventFiles", files);
        return body;
    }

    @GetMapping("/explore/buckets")
    public Map<String, Object> exploreBuckets(
            @RequestParam String strategy,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to
    ) {
        StrategyType s = StrategyType.valueOf(strategy.toUpperCase(Locale.ROOT));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("strategy", s.name());
        body.put("from", from.toString());
        body.put("to", to.toString());
        body.put("note", "Bucket breakdown available in the generated HTML report.");
        return body;
    }

    private static Set<StrategyType> parseStrategies(String raw) {
        if (raw == null || raw.isBlank() || "*".equals(raw.trim())) {
            return EnumSet.allOf(StrategyType.class);
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> StrategyType.valueOf(s.toUpperCase(Locale.ROOT)))
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(StrategyType.class)));
    }

    private Map<String, Object> toJson(TuningReportJobEntity j) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("jobId", j.getJobId());
        m.put("status", j.getStatus() != null ? j.getStatus().name() : TuningReportJobStatus.QUEUED.name());
        m.put("requestedAt", j.getRequestedAt());
        m.put("requestedBy", j.getRequestedBy());
        m.put("strategies", j.getStrategies());
        m.put("fromDate", j.getFromDate());
        m.put("toDate", j.getToDate());
        m.put("forced", j.isForced());
        m.put("outputHtml", j.getOutputHtml());
        m.put("errorMessage", j.getErrorMessage());
        m.put("durationSec", j.getDurationSec());
        return m;
    }
}
