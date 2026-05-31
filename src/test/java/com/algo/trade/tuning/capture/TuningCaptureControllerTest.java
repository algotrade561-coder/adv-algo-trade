package com.algo.trade.tuning.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.capture.TuningCaptureController.CaptureSettingsDto;
import com.algo.trade.tuning.capture.TuningCaptureController.CaptureUpdateRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Phase 1, Commit 10 — REST controller behaviour. Mocks the service + repository
 * and verifies endpoint contracts: full-list response includes every strategy
 * even when unseeded, update wires the right principal email, audit pagination.
 */
class TuningCaptureControllerTest {

    private CaptureToggleService toggleService;
    private TuningCaptureAuditRepository auditRepo;
    private TuningCaptureController controller;

    @BeforeEach
    void setUp() {
        toggleService = Mockito.mock(CaptureToggleService.class);
        auditRepo = Mockito.mock(TuningCaptureAuditRepository.class);
        controller = new TuningCaptureController(toggleService, auditRepo);
    }

    @Test
    void listStrategies_returnsRowForEveryStrategyType_evenWhenUnseeded() {
        // Only OI_MOMENTUM has been seeded in the DB.
        when(toggleService.listAll()).thenReturn(List.of(
                new CaptureSettings(StrategyType.OI_MOMENTUM,
                        true, true, true, true, true, true, true, 60, "data week")));
        when(toggleService.settingsFor(any(StrategyType.class)))
                .thenAnswer(inv -> CaptureSettings.defaultOff(inv.getArgument(0)));

        List<CaptureSettingsDto> rows = controller.listStrategies().getBody();

        assertThat(rows).hasSize(StrategyType.values().length);
        // OI_MOMENTUM is enabled.
        assertThat(rows).filteredOn(r -> r.strategy().equals("OI_MOMENTUM"))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.captureEnabled()).isTrue();
                    assertThat(r.notes()).isEqualTo("data week");
                });
        // Anything other than OI_MOMENTUM falls back to default-OFF.
        assertThat(rows).filteredOn(r -> !r.strategy().equals("OI_MOMENTUM"))
                .allSatisfy(r -> assertThat(r.captureEnabled()).isFalse());
    }

    @Test
    void getOne_returnsCurrentSettings() {
        when(toggleService.settingsFor(StrategyType.OI_SHIFT_TRAP)).thenReturn(
                new CaptureSettings(StrategyType.OI_SHIFT_TRAP,
                        true, true, true, true, true, true, true, 60, "active"));

        CaptureSettingsDto dto = controller.getOne(StrategyType.OI_SHIFT_TRAP).getBody();
        assertThat(dto.captureEnabled()).isTrue();
        assertThat(dto.displayName()).isNotBlank();
    }

    @Test
    void update_propagatesPrincipalEmailToService() {
        CaptureUpdateRequest body = new CaptureUpdateRequest(
                true, true, true, true, true, true, true, 60,
                "starting data week", "open capture for OI_MOMENTUM");

        when(toggleService.updateConfig(eq(StrategyType.OI_MOMENTUM), any(CaptureSettings.class),
                eq("lionel@example.com"), eq("open capture for OI_MOMENTUM")))
                .thenReturn(new CaptureSettings(StrategyType.OI_MOMENTUM,
                        true, true, true, true, true, true, true, 60, "starting data week"));

        OidcUser principal = mockPrincipal("lionel@example.com");
        CaptureSettingsDto returned = controller.update(StrategyType.OI_MOMENTUM, body, principal).getBody();

        assertThat(returned.captureEnabled()).isTrue();
        verify(toggleService, times(1)).updateConfig(
                eq(StrategyType.OI_MOMENTUM), any(CaptureSettings.class),
                eq("lionel@example.com"), eq("open capture for OI_MOMENTUM"));
    }

    @Test
    void update_principalNullFallsBackToUnknown() {
        CaptureUpdateRequest body = new CaptureUpdateRequest(
                true, true, true, true, true, true, true, 60, null, null);

        when(toggleService.updateConfig(any(), any(), eq("unknown"), eq("")))
                .thenReturn(CaptureSettings.defaultOff(StrategyType.OI_MOMENTUM));

        controller.update(StrategyType.OI_MOMENTUM, body, null);

        verify(toggleService).updateConfig(eq(StrategyType.OI_MOMENTUM), any(),
                eq("unknown"), eq(""));
    }

    @Test
    void update_episodeWindowZeroBecomesDefault60() {
        CaptureUpdateRequest body = new CaptureUpdateRequest(
                true, true, true, true, true, true, true, 0, null, "tweak");

        when(toggleService.updateConfig(eq(StrategyType.OI_MOMENTUM),
                any(CaptureSettings.class), any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));

        OidcUser principal = mockPrincipal("lionel@example.com");
        CaptureSettingsDto out = controller.update(StrategyType.OI_MOMENTUM, body, principal).getBody();

        assertThat(out.episodeWindowSec()).isEqualTo(60);
    }

    @Test
    void audit_returnsRowsInReverseChronoOrder_andRespectsLimit() {
        TuningCaptureAuditEntity older = mockAudit(1L, Instant.parse("2026-06-01T09:00:00Z"),
                "captureEnabled", "false", "true");
        TuningCaptureAuditEntity newer = mockAudit(2L, Instant.parse("2026-06-01T15:00:00Z"),
                "captureSignals", "true", "false");
        TuningCaptureAuditEntity newest = mockAudit(3L, Instant.parse("2026-06-02T09:00:00Z"),
                "captureEnabled", "true", "false");

        when(auditRepo.findByStrategyOrderByChangedAtDesc(StrategyType.OI_MOMENTUM))
                .thenReturn(List.of(older, newer, newest));   // unsorted input

        // Limit = 2 → top 2 most recent.
        var rows = controller.audit(StrategyType.OI_MOMENTUM, 2).getBody();
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).id()).isEqualTo(3L);  // newest first
        assertThat(rows.get(1).id()).isEqualTo(2L);

        // Limit = 0 → defaults to 1 (min).
        var oneRow = controller.audit(StrategyType.OI_MOMENTUM, 0).getBody();
        assertThat(oneRow).hasSize(1);
    }

    @Test
    void update_doesNotCallAuditRepoDirectly_serviceOwnsAuditWrites() {
        CaptureUpdateRequest body = new CaptureUpdateRequest(
                true, true, true, true, true, true, true, 60, null, "x");
        when(toggleService.updateConfig(any(), any(), any(), any()))
                .thenReturn(CaptureSettings.defaultOff(StrategyType.OI_MOMENTUM));

        controller.update(StrategyType.OI_MOMENTUM, body, mockPrincipal("x@y.com"));

        // The controller never touches the audit repository — auditing is the
        // service's job (verified in CaptureToggleServiceTest).
        verify(auditRepo, never()).save(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static OidcUser mockPrincipal(String email) {
        OidcUser p = Mockito.mock(OidcUser.class);
        when(p.getEmail()).thenReturn(email);
        return p;
    }

    private static TuningCaptureAuditEntity mockAudit(long id, Instant at, String field,
                                                       String oldVal, String newVal) {
        TuningCaptureAuditEntity e = Mockito.mock(TuningCaptureAuditEntity.class);
        when(e.getId()).thenReturn(id);
        when(e.getChangedAt()).thenReturn(at);
        when(e.getChangedBy()).thenReturn("lionel@example.com");
        when(e.getStrategy()).thenReturn(StrategyType.OI_MOMENTUM);
        when(e.getFieldName()).thenReturn(field);
        when(e.getOldValue()).thenReturn(oldVal);
        when(e.getNewValue()).thenReturn(newVal);
        when(e.getReason()).thenReturn("test");
        return e;
    }
}
