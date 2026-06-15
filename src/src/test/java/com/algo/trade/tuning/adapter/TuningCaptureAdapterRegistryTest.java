package com.algo.trade.tuning.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.capture.CaptureSettings;
import com.algo.trade.tuning.capture.CaptureToggleService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Phase 2, Commit 1 — adapter registry. Verifies discovery + lookup, duplicate
 * detection, and that {@code ensureRowExists} is called with the adapter's
 * preferred {@code episodeWindowSec}.
 */
class TuningCaptureAdapterRegistryTest {

    private CaptureToggleService toggleService;

    @BeforeEach
    void setUp() {
        toggleService = Mockito.mock(CaptureToggleService.class);
        when(toggleService.ensureRowExists(Mockito.any(StrategyType.class), Mockito.anyInt()))
                .thenAnswer(inv -> CaptureSettings.defaultOff(inv.getArgument(0)));
    }

    private TuningCaptureAdapterRegistry newRegistry(List<TuningCaptureAdapter> adapters) {
        return new TuningCaptureAdapterRegistry(adapters, toggleService);
    }

    @Test
    void emptyAdapterList_initializesWithoutError() {
        TuningCaptureAdapterRegistry registry = newRegistry(List.of());
        registry.init();

        assertThat(registry.size()).isZero();
        assertThat(registry.all()).isEmpty();
        verify(toggleService, never()).ensureRowExists(Mockito.any(StrategyType.class), Mockito.anyInt());
    }

    @Test
    void registersOneAdapterAndSeedsRow() {
        TuningCaptureAdapter momentum = new StubAdapter(StrategyType.OI_MOMENTUM, CadenceHint.HIGH, 60);

        TuningCaptureAdapterRegistry registry = newRegistry(List.of(momentum));
        registry.init();

        assertThat(registry.find(StrategyType.OI_MOMENTUM)).contains(momentum);
        assertThat(registry.find(StrategyType.OI_SHIFT_TRAP)).isEmpty();
        assertThat(registry.isRegistered(StrategyType.OI_MOMENTUM)).isTrue();
        verify(toggleService).ensureRowExists(StrategyType.OI_MOMENTUM, 60);
    }

    @Test
    void respectsAdapterEpisodeWindowDefault() {
        TuningCaptureAdapter custom = new StubAdapter(StrategyType.OI_SHIFT_TRAP, CadenceHint.MEDIUM, 120);

        TuningCaptureAdapterRegistry registry = newRegistry(List.of(custom));
        registry.init();

        verify(toggleService).ensureRowExists(StrategyType.OI_SHIFT_TRAP, 120);
    }

    @Test
    void registersMultipleAdaptersIndependently() {
        TuningCaptureAdapter momentum = new StubAdapter(StrategyType.OI_MOMENTUM, CadenceHint.HIGH, 60);
        TuningCaptureAdapter trap = new StubAdapter(StrategyType.OI_SHIFT_TRAP, CadenceHint.MEDIUM, 60);
        TuningCaptureAdapter scalping = new StubAdapter(StrategyType.SCALPING, CadenceHint.MEDIUM, 60);

        TuningCaptureAdapterRegistry registry = newRegistry(List.of(momentum, trap, scalping));
        registry.init();

        assertThat(registry.size()).isEqualTo(3);
        verify(toggleService, times(3)).ensureRowExists(Mockito.any(StrategyType.class), eq(60));
    }

    @Test
    void duplicateAdapterForSameStrategyFailsFast() {
        TuningCaptureAdapter first = new StubAdapter(StrategyType.OI_MOMENTUM, CadenceHint.HIGH, 60);
        TuningCaptureAdapter dupe = new StubAdapter(StrategyType.OI_MOMENTUM, CadenceHint.HIGH, 90);

        TuningCaptureAdapterRegistry registry = newRegistry(List.of(first, dupe));

        assertThatThrownBy(registry::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Two TuningCaptureAdapter beans claim StrategyType OI_MOMENTUM")
                .hasMessageContaining("exactly one adapter per strategy");
    }

    @Test
    void adapterDeclarationsAreExposedThroughRegistry() {
        BucketDimension scoreDim = new BucketDimension(
                "score", "score", List.of(50.0, 60.0, 70.0, 80.0, 90.0),
                BucketDimension.BandStyle.NUMERIC_RANGE);
        ShadowGate momentumGate = ShadowGate.rule("confirm_momentumDecelerating", "test gate");

        TuningCaptureAdapter rich = new StubAdapter(StrategyType.OI_SHIFT_TRAP, CadenceHint.MEDIUM, 60) {
            @Override
            public List<BucketDimension> bucketDimensions() { return List.of(scoreDim); }
            @Override
            public List<ShadowGate> shadowGates() { return List.of(momentumGate); }
        };

        TuningCaptureAdapterRegistry registry = newRegistry(List.of(rich));
        registry.init();

        TuningCaptureAdapter found = registry.find(StrategyType.OI_SHIFT_TRAP).get();
        assertThat(found.bucketDimensions()).containsExactly(scoreDim);
        assertThat(found.shadowGates()).containsExactly(momentumGate);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Minimal adapter implementation for testing. */
    private static class StubAdapter implements TuningCaptureAdapter {
        private final StrategyType strategy;
        private final CadenceHint cadence;
        private final int windowSec;

        StubAdapter(StrategyType strategy, CadenceHint cadence, int windowSec) {
            this.strategy = strategy;
            this.cadence = cadence;
            this.windowSec = windowSec;
        }

        @Override
        public StrategyType strategy() { return strategy; }
        @Override
        public CadenceHint cadence() { return cadence; }
        @Override
        public int defaultEpisodeWindowSec() { return windowSec; }
    }
}
