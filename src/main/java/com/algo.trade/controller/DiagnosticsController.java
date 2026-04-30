package com.algo.trade.controller;

import com.algo.trade.monitoring.OrderAuditService;
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

    public DiagnosticsController(SystemDiagnosticsService diagnosticsService,
                                  OrderAuditService orderAuditService) {
        this.diagnosticsService = diagnosticsService;
        this.orderAuditService = orderAuditService;
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
}
