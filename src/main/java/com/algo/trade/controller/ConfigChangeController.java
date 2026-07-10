package com.algo.trade.controller;

import com.algo.trade.tuning.change.ConfigChangeLog;
import com.algo.trade.tuning.change.ConfigChangeService;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * §10c — record / list / revert tuning config-change tags. SUPERUSER only. This audit trail is what the
 * report's "change impact" section measures against; it never writes live trading config (the change itself
 * is made in the settings screen).
 */
@RestController
@RequestMapping("/admin/config-changes")
public class ConfigChangeController {

    private final ConfigChangeService service;

    public ConfigChangeController(ConfigChangeService service) {
        this.service = service;
    }

    public record RecordRequest(String area, String field, String oldValue, String newValue,
                                String note, String sourceJobId) {}

    @PostMapping
    @PreAuthorize("hasRole('SUPERUSER')")
    public ResponseEntity<?> record(@RequestBody RecordRequest req, Authentication auth) {
        if (req == null || req.field() == null || req.field().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "field required"));
        }
        ConfigChangeLog saved = service.record(req.area(), req.field(), req.oldValue(), req.newValue(),
                req.note(), req.sourceJobId(), auth != null ? auth.getName() : "system");
        return ResponseEntity.ok(saved);
    }

    @GetMapping
    public List<ConfigChangeLog> list(@RequestParam(defaultValue = "50") int limit) {
        return service.recent(limit);
    }

    @PostMapping("/{id}/revert")
    @PreAuthorize("hasRole('SUPERUSER')")
    public ResponseEntity<?> revert(@PathVariable Long id, Authentication auth) {
        try {
            return ResponseEntity.ok(service.revert(id, auth != null ? auth.getName() : "system"));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
        }
    }
}
