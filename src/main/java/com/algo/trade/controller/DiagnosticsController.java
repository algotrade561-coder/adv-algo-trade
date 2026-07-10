package com.algo.trade.controller;

import com.algo.trade.monitoring.OrderAuditService;
import com.algo.trade.monitoring.SchedulerRegistry;
import com.algo.trade.monitoring.SystemDiagnosticsService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Diagnostics REST API — system health, order audit trail, failure analysis.
 *
 * GET /diagnostics/health          — full system health snapshot
 * GET /diagnostics/audit/{tradeId} — order lifecycle for a specific trade
 * GET /diagnostics/search          — search by instrument, strategy, or date
 * GET /diagnostics/failures        — all failures and blocked entries
 */
@RestController
@RequestMapping("/diagnostics")
public class DiagnosticsController {

    private final SystemDiagnosticsService diagnosticsService;
    private final OrderAuditService orderAuditService;
    private final com.algo.trade.monitoring.ErrorEventService errorEventService;
    private final SchedulerRegistry schedulerRegistry;
    private final com.algo.trade.persistence.DatabaseRetentionService databaseRetentionService;

    public DiagnosticsController(SystemDiagnosticsService diagnosticsService,
                                  OrderAuditService orderAuditService,
                                  com.algo.trade.monitoring.ErrorEventService errorEventService,
                                  SchedulerRegistry schedulerRegistry,
                                  com.algo.trade.persistence.DatabaseRetentionService databaseRetentionService) {
        this.diagnosticsService = diagnosticsService;
        this.orderAuditService = orderAuditService;
        this.errorEventService = errorEventService;
        this.schedulerRegistry = schedulerRegistry;
        this.databaseRetentionService = databaseRetentionService;
    }

    /** Full system health snapshot — WebSocket, REST, DB, scanner, orders, trades. */
    @GetMapping("/health")
    public SystemDiagnosticsService.DiagnosticSnapshot health() {
        return diagnosticsService.getSnapshot();
    }

    /** Order audit trail for a specific trade — full lifecycle from signal to close. */
    @GetMapping("/audit/{tradeId}")
    public Map<String, Object> auditByTradeId(@PathVariable String tradeId) {
        return orderAuditService.auditByTradeId(tradeId);
    }

    /** Search by instrument key. */
    @GetMapping("/search/instrument")
    public Map<String, Object> searchByInstrument(@RequestParam String key,
                                                    @RequestParam(defaultValue = "TODAY") String period) {
        return orderAuditService.searchByInstrument(key, period);
    }

    /** Search by strategy type. */
    @GetMapping("/search/strategy")
    public Map<String, Object> searchByStrategy(@RequestParam String type,
                                                  @RequestParam(defaultValue = "TODAY") String period) {
        return orderAuditService.searchByStrategy(type, period);
    }

    /** All failures — rejected orders, error events, blocked entries. */
    @GetMapping("/failures")
    public Map<String, Object> failures(@RequestParam(defaultValue = "TODAY") String period) {
        return orderAuditService.failures(period);
    }

    /** Available trade IDs and instrument keys for autocomplete dropdowns. */
    @GetMapping("/lookup")
    public Map<String, Object> lookup(@RequestParam(defaultValue = "TODAY") String period) {
        return orderAuditService.lookupValues(period);
    }

    /** Recent error events with severity breakdown. */
    @GetMapping("/errors/recent")
    public Map<String, Object> recentErrors(@RequestParam(defaultValue = "50") int limit) {
        var errors = errorEventService.recentErrors(limit);
        var counts = errorEventService.errorCountsBySeverity();
        return Map.of(
                "errors", errors.stream().map(e -> Map.of(
                        "id", e.getId(),
                        "timestamp", e.getTimestamp().toString(),
                        "component", e.getComponent() != null ? e.getComponent() : "",
                        "severity", e.getSeverity() != null ? e.getSeverity() : "MEDIUM",
                        "message", e.getMessage() != null ? e.getMessage() : ""
                )).toList(),
                "counts", counts
        );
    }

    /** All registered scheduler tasks with status, health, and run counts. */
    @GetMapping("/schedulers")
    public java.util.List<SchedulerRegistry.TaskStatus> schedulers() {
        return schedulerRegistry.getAllStatus();
    }

    /** Enable or disable a scheduler task at runtime. */
    @PostMapping("/schedulers/{name}/toggle")
    public Map<String, Object> toggleScheduler(@PathVariable String name, @RequestBody Map<String, Boolean> body) {
        boolean enabled = body.getOrDefault("enabled", true);
        schedulerRegistry.setEnabled(name, enabled);
        return Map.of("name", name, "enabled", enabled);
    }

    /** Force-trigger a scheduler task immediately. */
    @PostMapping("/schedulers/{name}/trigger")
    public Map<String, Object> triggerScheduler(@PathVariable String name) {
        boolean triggered = schedulerRegistry.triggerNow(name);
        return Map.of("name", name, "triggered", triggered);
    }

    /**
     * Force-run the DB retention purge now and return per-table counts.
     * {@code ?dryRun=true} previews what would be deleted without deleting.
     * (Also runnable via Schedulers → {@code dbRetention} → trigger.)
     */
    @PostMapping("/db-retention/run")
    public Map<String, Object> runDbRetention(@RequestParam(defaultValue = "false") boolean dryRun) {
        return databaseRetentionService.runNow(dryRun);
    }
}
