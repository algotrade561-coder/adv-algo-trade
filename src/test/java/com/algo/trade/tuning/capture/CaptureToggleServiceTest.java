package com.algo.trade.tuning.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;

/**
 * Phase 1, Commit 2 — capture toggle service. Verifies cache loading, default-OFF
 * semantics for unseeded strategies, per-event-type gating, update + audit, and
 * idempotent {@link CaptureToggleService#ensureRowExists}.
 */
class CaptureToggleServiceTest {

    private TuningCaptureConfigRepository configRepo;
    private TuningCaptureAuditRepository auditRepo;
    private CaptureToggleProperties properties;
    private CaptureToggleService service;

    @BeforeEach
    void setUp() {
        configRepo = Mockito.mock(TuningCaptureConfigRepository.class);
        auditRepo = Mockito.mock(TuningCaptureAuditRepository.class);
        properties = new CaptureToggleProperties();
        properties.setPollIntervalSec(30);

        when(configRepo.findAll()).thenReturn(List.of());
        when(auditRepo.save(any(TuningCaptureAuditEntity.class)))
                .thenAnswer(returnFirstArg());

        service = new CaptureToggleService(configRepo, auditRepo, properties);
        service.initialLoad();
    }

    @Test
    void emptyDb_defaultsToOffForEveryStrategy() {
        CaptureSettings s = service.settingsFor(StrategyType.OI_MOMENTUM);

        assertThat(s.captureEnabled()).isFalse();
        assertThat(s.episodeWindowSec()).isEqualTo(60);
        assertThat(s.strategy()).isEqualTo(StrategyType.OI_MOMENTUM);
        assertThat(service.isCaptureEnabled(StrategyType.OI_MOMENTUM)).isFalse();
        assertThat(service.isEnabled(StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL)).isFalse();
    }

    @Test
    void cacheLoadsFromDbOnStartup() {
        TuningCaptureConfigEntity row = newRow(StrategyType.OI_SHIFT_TRAP, true, true, true, true, true, true, true);
        when(configRepo.findAll()).thenReturn(List.of(row));
        service.refresh();

        CaptureSettings s = service.settingsFor(StrategyType.OI_SHIFT_TRAP);
        assertThat(s.captureEnabled()).isTrue();
        assertThat(service.isEnabled(StrategyType.OI_SHIFT_TRAP, TuningEventType.SIGNAL)).isTrue();
    }

    @Test
    void perEventTypeFlagsGateIndependently() {
        TuningCaptureConfigEntity row = newRow(StrategyType.OI_MOMENTUM,
                /* master */ true,
                /* eval */ false, /* signal */ true, /* exec */ true,
                /* exit */ true,  /* fwd */ true,    /* shadow */ false);
        when(configRepo.findAll()).thenReturn(List.of(row));
        service.refresh();

        assertThat(service.isEnabled(StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION)).isFalse();
        assertThat(service.isEnabled(StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL)).isTrue();
        assertThat(service.isEnabled(StrategyType.OI_MOMENTUM, TuningEventType.EXIT)).isTrue();
        assertThat(service.isEnabled(StrategyType.OI_MOMENTUM, TuningEventType.SHADOW_GATE)).isFalse();
    }

    @Test
    void masterOffWinsOverPerEventTypeOn() {
        TuningCaptureConfigEntity row = newRow(StrategyType.OI_MOMENTUM,
                /* master */ false,
                true, true, true, true, true, true);
        when(configRepo.findAll()).thenReturn(List.of(row));
        service.refresh();

        // Per-event flags are all true but master is false — everything off.
        for (TuningEventType type : TuningEventType.values()) {
            assertThat(service.isEnabled(StrategyType.OI_MOMENTUM, type))
                    .as("type %s should be disabled when master is off", type)
                    .isFalse();
        }
    }

    @Test
    void updateConfigPersistsAndAuditsChangedFields() {
        // Seed an existing row.
        TuningCaptureConfigEntity existing = newRow(StrategyType.OI_MOMENTUM,
                false, true, true, true, true, true, true);
        when(configRepo.findById(StrategyType.OI_MOMENTUM)).thenReturn(Optional.of(existing));
        when(configRepo.save(any(TuningCaptureConfigEntity.class))).thenAnswer(returnFirstArg());

        CaptureSettings newSettings = new CaptureSettings(
                StrategyType.OI_MOMENTUM,
                /* master */ true,        // changed
                /* eval */ false,         // changed
                /* signal */ true,
                /* exec */ true,
                /* exit */ true,
                /* fwd */ true,
                /* shadow */ true,
                /* window */ 90,          // changed
                "data week starts today"
        );

        CaptureSettings out = service.updateConfig(StrategyType.OI_MOMENTUM, newSettings,
                "lionel", "open data week for OI_MOMENTUM");

        assertThat(out.captureEnabled()).isTrue();
        assertThat(out.captureEvaluations()).isFalse();
        assertThat(out.episodeWindowSec()).isEqualTo(90);

        // 3 fields changed → 3 audit rows written.
        ArgumentCaptor<TuningCaptureAuditEntity> auditCaptor =
                ArgumentCaptor.forClass(TuningCaptureAuditEntity.class);
        verify(auditRepo, times(3)).save(auditCaptor.capture());
        assertThat(auditCaptor.getAllValues())
                .extracting(TuningCaptureAuditEntity::getFieldName)
                .containsExactlyInAnyOrder("captureEnabled", "captureEvaluations", "episodeWindowSec");

        // Cache reflects new settings without waiting for refresh.
        assertThat(service.isCaptureEnabled(StrategyType.OI_MOMENTUM)).isTrue();
        assertThat(service.isEnabled(StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION)).isFalse();
    }

    @Test
    void updateConfigRejectsMismatchedStrategy() {
        CaptureSettings wrong = CaptureSettings.defaultOff(StrategyType.OI_SHIFT_TRAP);
        assertThatThrownBy(() -> service.updateConfig(StrategyType.OI_MOMENTUM, wrong, "x", "y"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strategy mismatch");
    }

    @Test
    void ensureRowExistsWithCustomWindow_persistsAdapterValue() {
        when(configRepo.findById(StrategyType.OI_MOMENTUM)).thenReturn(Optional.empty());
        when(configRepo.save(any(TuningCaptureConfigEntity.class))).thenAnswer(returnFirstArg());

        CaptureSettings out = service.ensureRowExists(StrategyType.OI_MOMENTUM, 120);

        assertThat(out.episodeWindowSec()).isEqualTo(120);

        ArgumentCaptor<TuningCaptureConfigEntity> entityCaptor =
                ArgumentCaptor.forClass(TuningCaptureConfigEntity.class);
        verify(configRepo).save(entityCaptor.capture());
        assertThat(entityCaptor.getValue().getEpisodeWindowSec()).isEqualTo(120);
    }

    @Test
    void ensureRowExistsWithCustomWindow_preservesExistingValue() {
        // Existing row has window=300 (user customized via UI).
        TuningCaptureConfigEntity existing = newRow(StrategyType.OI_MOMENTUM,
                false, true, true, true, true, true, true);
        existing.setEpisodeWindowSec(300);
        when(configRepo.findById(StrategyType.OI_MOMENTUM)).thenReturn(Optional.of(existing));

        // Adapter re-registers with its own default of 60.
        CaptureSettings out = service.ensureRowExists(StrategyType.OI_MOMENTUM, 60);

        // User's saved value wins; adapter's default is ignored on re-registration.
        assertThat(out.episodeWindowSec()).isEqualTo(300);
        // Save is not called — existing row is returned as-is.
        verify(configRepo, never()).save(any());
    }

    @Test
    void ensureRowExistsIsIdempotent() {
        when(configRepo.findById(StrategyType.OI_SHIFT_TRAP)).thenReturn(Optional.empty());
        when(configRepo.save(any(TuningCaptureConfigEntity.class))).thenAnswer(returnFirstArg());

        CaptureSettings first = service.ensureRowExists(StrategyType.OI_SHIFT_TRAP);
        assertThat(first.captureEnabled()).isFalse();
        verify(configRepo, times(1)).save(any());

        // Subsequent call finds existing row (we now stub it).
        TuningCaptureConfigEntity existing = newRow(StrategyType.OI_SHIFT_TRAP,
                false, true, true, true, true, true, true);
        when(configRepo.findById(StrategyType.OI_SHIFT_TRAP)).thenReturn(Optional.of(existing));

        CaptureSettings second = service.ensureRowExists(StrategyType.OI_SHIFT_TRAP);
        assertThat(second.captureEnabled()).isFalse();
        // Save not invoked again — idempotent.
        verify(configRepo, times(1)).save(any());
    }

    @Test
    void refreshFailureKeepsPriorSnapshot() {
        TuningCaptureConfigEntity row = newRow(StrategyType.OI_MOMENTUM,
                true, true, true, true, true, true, true);
        when(configRepo.findAll()).thenReturn(List.of(row));
        service.refresh();
        assertThat(service.isCaptureEnabled(StrategyType.OI_MOMENTUM)).isTrue();

        // Next refresh blows up — service should keep old snapshot, not zero it out.
        when(configRepo.findAll()).thenThrow(new RuntimeException("db is down"));
        service.refresh();

        assertThat(service.isCaptureEnabled(StrategyType.OI_MOMENTUM)).isTrue();
        verify(configRepo, atLeastOnce()).findAll();
    }

    @Test
    void listAllReturnsSnapshotCopy() {
        TuningCaptureConfigEntity row = newRow(StrategyType.OI_MOMENTUM,
                true, true, true, true, true, true, true);
        when(configRepo.findAll()).thenReturn(List.of(row));
        service.refresh();

        List<CaptureSettings> all = service.listAll();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).strategy()).isEqualTo(StrategyType.OI_MOMENTUM);
    }

    @Test
    void noopUpdateWritesZeroAuditRows() {
        TuningCaptureConfigEntity existing = newRow(StrategyType.OI_MOMENTUM,
                true, true, true, true, true, true, true);
        when(configRepo.findById(StrategyType.OI_MOMENTUM)).thenReturn(Optional.of(existing));
        when(configRepo.save(any())).thenAnswer(returnFirstArg());

        CaptureSettings same = new CaptureSettings(StrategyType.OI_MOMENTUM,
                true, true, true, true, true, true, true, 60, null);
        service.updateConfig(StrategyType.OI_MOMENTUM, same, "lionel", "no-op resave");

        verify(auditRepo, never()).save(any());
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static TuningCaptureConfigEntity newRow(StrategyType strategy,
                                                     boolean master, boolean eval, boolean signal,
                                                     boolean exec, boolean exit, boolean fwd,
                                                     boolean shadow) {
        TuningCaptureConfigEntity row = new TuningCaptureConfigEntity();
        row.setStrategy(strategy);
        row.setCaptureEnabled(master);
        row.setCaptureEvaluations(eval);
        row.setCaptureSignals(signal);
        row.setCaptureExecutions(exec);
        row.setCaptureExits(exit);
        row.setCaptureForward(fwd);
        row.setCaptureShadow(shadow);
        row.setEpisodeWindowSec(60);
        return row;
    }

    private static <T> org.mockito.stubbing.Answer<T> returnFirstArg() {
        return (InvocationOnMock inv) -> inv.getArgument(0);
    }
}
