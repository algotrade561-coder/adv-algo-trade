package com.algo.trade.tuning.capture;

import com.algo.trade.strategy.StrategyType;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API backing the {@code /settings/tuning-capture} Angular page.
 *
 * <p>OAuth-protected via the existing SecurityConfig — every request carries an
 * {@link OidcUser} principal so audit entries record the operator email.</p>
 *
 * <ul>
 *   <li>{@code GET  /tuning/capture/strategies}      — list every strategy + current settings (unseeded → defaults OFF)</li>
 *   <li>{@code GET  /tuning/capture/{strategy}}      — single strategy snapshot</li>
 *   <li>{@code PUT  /tuning/capture/{strategy}}      — update; writes audit rows for changed fields</li>
 *   <li>{@code GET  /tuning/capture/{strategy}/audit} — recent audit entries (default last 50)</li>
 * </ul>
 */
@RestController
@RequestMapping("/tuning/capture")
public class TuningCaptureController {

    private static final Logger log = LoggerFactory.getLogger(TuningCaptureController.class);

    private final CaptureToggleService toggleService;
    private final TuningCaptureAuditRepository auditRepo;

    public TuningCaptureController(CaptureToggleService toggleService,
                                    TuningCaptureAuditRepository auditRepo) {
        this.toggleService = toggleService;
        this.auditRepo = auditRepo;
    }

    /**
     * Returns one entry per {@link StrategyType}. Strategies without a DB row get
     * {@link CaptureSettings#defaultOff} — the UI renders them all so the user has
     * full visibility even before adapters are registered.
     */
    @GetMapping("/strategies")
    public ResponseEntity<List<CaptureSettingsDto>> listStrategies() {
        // Build a map by strategy from the service's snapshot.
        Map<StrategyType, CaptureSettings> bySettings = toggleService.listAll().stream()
                .collect(java.util.stream.Collectors.toMap(CaptureSettings::strategy, s -> s));
        List<CaptureSettingsDto> out = new ArrayList<>();
        for (StrategyType strategy : StrategyType.values()) {
            CaptureSettings settings = bySettings.getOrDefault(strategy,
                    CaptureSettings.defaultOff(strategy));
            out.add(CaptureSettingsDto.from(strategy, settings));
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/{strategy}")
    public ResponseEntity<CaptureSettingsDto> getOne(@PathVariable("strategy") StrategyType strategy) {
        CaptureSettings settings = toggleService.settingsFor(strategy);
        return ResponseEntity.ok(CaptureSettingsDto.from(strategy, settings));
    }

    /**
     * Update a strategy's capture settings. Body must reference the same strategy
     * as the path. Audit rows are written for any field whose value changed.
     */
    @PutMapping("/{strategy}")
    public ResponseEntity<CaptureSettingsDto> update(@PathVariable("strategy") StrategyType strategy,
                                                      @RequestBody CaptureUpdateRequest body,
                                                      @AuthenticationPrincipal OidcUser principal) {
        String changedBy = principal != null ? principal.getEmail() : "unknown";
        String reason = body.reason() != null ? body.reason() : "";
        CaptureSettings newSettings = new CaptureSettings(
                strategy,
                body.captureEnabled(),
                body.captureEvaluations(),
                body.captureSignals(),
                body.captureExecutions(),
                body.captureExits(),
                body.captureForward(),
                body.captureShadow(),
                body.episodeWindowSec() > 0 ? body.episodeWindowSec() : 60,
                body.notes()
        );
        CaptureSettings saved = toggleService.updateConfig(strategy, newSettings, changedBy, reason);
        log.info("[TuningCaptureController] {} updated by={}", strategy, changedBy);
        return ResponseEntity.ok(CaptureSettingsDto.from(strategy, saved));
    }

    @GetMapping("/{strategy}/audit")
    public ResponseEntity<List<AuditEntryDto>> audit(@PathVariable("strategy") StrategyType strategy,
                                                      @RequestParam(value = "limit", defaultValue = "50") int limit) {
        List<TuningCaptureAuditEntity> rows = auditRepo
                .findByStrategyOrderByChangedAtDesc(strategy);
        return ResponseEntity.ok(rows.stream()
                .sorted(Comparator.comparing(TuningCaptureAuditEntity::getChangedAt).reversed())
                .limit(Math.max(1, limit))
                .map(AuditEntryDto::from)
                .toList());
    }

    // ── DTOs ──────────────────────────────────────────────────────────────

    /** Response DTO mirroring {@link CaptureSettings} plus strategy display info. */
    public record CaptureSettingsDto(
            String strategy,
            String displayName,
            boolean captureEnabled,
            boolean captureEvaluations,
            boolean captureSignals,
            boolean captureExecutions,
            boolean captureExits,
            boolean captureForward,
            boolean captureShadow,
            int episodeWindowSec,
            String notes
    ) {
        public static CaptureSettingsDto from(StrategyType strategy, CaptureSettings s) {
            return new CaptureSettingsDto(
                    strategy.name(),
                    strategy.displayName(),
                    s.captureEnabled(),
                    s.captureEvaluations(),
                    s.captureSignals(),
                    s.captureExecutions(),
                    s.captureExits(),
                    s.captureForward(),
                    s.captureShadow(),
                    s.episodeWindowSec(),
                    s.notes()
            );
        }
    }

    /** Request DTO for {@code PUT /tuning/capture/{strategy}}. */
    public record CaptureUpdateRequest(
            boolean captureEnabled,
            boolean captureEvaluations,
            boolean captureSignals,
            boolean captureExecutions,
            boolean captureExits,
            boolean captureForward,
            boolean captureShadow,
            int episodeWindowSec,
            String notes,
            String reason
    ) {}

    public record AuditEntryDto(
            long id,
            Instant changedAt,
            String changedBy,
            String fieldName,
            String oldValue,
            String newValue,
            String reason
    ) {
        public static AuditEntryDto from(TuningCaptureAuditEntity e) {
            return new AuditEntryDto(
                    e.getId() != null ? e.getId() : 0,
                    e.getChangedAt(),
                    e.getChangedBy(),
                    e.getFieldName(),
                    e.getOldValue(),
                    e.getNewValue(),
                    e.getReason()
            );
        }
    }
}
