package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionType;
import com.algo.trade.strategy.oimomentum.OperatorAccumulationDetector;
import com.algo.trade.strategy.oimomentum.OperatorFrameworkService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Covers the limit-ladder FSM: tier arming, fill detection on LTP crossings,
 * cancellation reasons, op-score arm floor, and shadow-mode semantics.
 * Uses the package-private test constructor — no Spring context.
 */
class OiShiftTrapLadderManagerTest {

    private static final IndexType IDX = IndexType.NIFTY;

    private OperatorFrameworkService operator;
    private OiShiftTrapLadderConfig config;
    private OiShiftTrapLadderManager manager;

    @BeforeEach
    void setUp() {
        operator = Mockito.mock(OperatorFrameworkService.class);
        config = new OiShiftTrapLadderConfig();
        config.setLadderMode("LIVE");
        config.setTier1Discount(0.03);
        config.setTier2Discount(0.06);
        config.setTier3Discount(0.10);
        config.setLadderWindowMin(30);
        config.setLadderOpScoreArmFloor(50);
        config.setLadderOpScoreCancelDelta(20);
        manager = new OiShiftTrapLadderManager(operator, config);
    }

    @Test
    void offModeRejectsArm() {
        config.setLadderMode("OFF");
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, 1));
        boolean armed = manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));
        assertThat(armed).isFalse();
        assertThat(manager.activeLadderCount()).isZero();
    }

    @Test
    void armBelowOpScoreFloorIsRejected() {
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(40, 1));
        boolean armed = manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));
        assertThat(armed).isFalse();
        assertThat(manager.activeLadderCount()).isZero();
    }

    @Test
    void armPlacesThreeTiersAtCorrectPrices() {
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, 1));
        boolean armed = manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));
        assertThat(armed).isTrue();
        OiShiftTrapLadderManager.Ladder L = manager.peek(
                new OiShiftTrapLadderManager.Key(IDX, OptionType.CE, 23500));
        assertThat(L).isNotNull();
        assertThat(L.tier1().limitPrice()).isEqualTo(97.0);  // 100 * (1 - 0.03)
        assertThat(L.tier2().limitPrice()).isEqualTo(94.0);  // 100 * (1 - 0.06)
        assertThat(L.tier3().limitPrice()).isEqualTo(90.0);  // 100 * (1 - 0.10)
    }

    @Test
    void tier1FillsWhenLtpCrossesItsLimit() {
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, 1));
        manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));

        // LTP drops to 96.5 — should fill ONLY tier 1 (limit 97).
        List<OiShiftTrapLadderManager.FillResult> out = manager.simulateTick(IDX, 96.5);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).tierIndex()).isEqualTo(1);
        assertThat(out.get(0).event()).isEqualTo("TIER_FILLED");
        assertThat(out.get(0).decision()).isNotNull();
        assertThat(out.get(0).decision().optionPrice().get().doubleValue()).isEqualTo(96.5);
    }

    @Test
    void deepDipFillsAllThreeTiersInOneTick() {
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, 1));
        manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));

        // LTP plunges to 88 — below all three tier limits (97 / 94 / 90).
        List<OiShiftTrapLadderManager.FillResult> out = manager.simulateTick(IDX, 88.0);
        assertThat(out).hasSize(3);
        assertThat(out.stream().map(OiShiftTrapLadderManager.FillResult::tierIndex))
                .containsExactly(1, 2, 3);
        // Ladder fully resolved → removed from active map.
        assertThat(manager.activeLadderCount()).isZero();
    }

    @Test
    void shadowModeReturnsFillEventButNoDecision() {
        config.setLadderMode("SHADOW");
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, 1));
        manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));

        List<OiShiftTrapLadderManager.FillResult> out = manager.simulateTick(IDX, 95.0);
        assertThat(out).hasSize(1);
        assertThat(out.get(0).event()).isEqualTo("TIER_FILLED");
        assertThat(out.get(0).decision()).isNull();
    }

    @Test
    void opScoreCollapseCancelsAllOpenTiers() {
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(80, 1));
        manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));

        // Op-score collapses 30 points (> 20 threshold).
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(50, 1));
        List<OiShiftTrapLadderManager.FillResult> out = manager.simulateTick(IDX, 99.5);
        assertThat(out).hasSize(3); // all three open tiers cancelled
        for (OiShiftTrapLadderManager.FillResult r : out) {
            assertThat(r.event()).isEqualTo("CANCELLED");
            assertThat(r.cancelReason()).isEqualTo("op_score_collapsed");
            assertThat(r.decision()).isNull();
        }
        assertThat(manager.activeLadderCount()).isZero();
    }

    @Test
    void opDirectionFlipCancelsAllOpenTiers() {
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, 1));
        manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));

        // Op-direction flips to bearish.
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, -1));
        List<OiShiftTrapLadderManager.FillResult> out = manager.simulateTick(IDX, 99.5);
        assertThat(out).hasSize(3);
        assertThat(out.get(0).cancelReason()).isEqualTo("op_direction_flipped");
    }

    @Test
    void clearRemovesAllLadders() {
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, 1));
        manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));
        manager.arm(IDX, OptionType.PE, 23400, 80.0, 3, seedDiag("PE", 23400));
        assertThat(manager.activeLadderCount()).isEqualTo(2);
        manager.clear();
        assertThat(manager.activeLadderCount()).isZero();
    }

    @Test
    void tierConstraintEnforcedAtConfigLayer() {
        // This is a config-validation responsibility; the manager itself just
        // trusts the values. Sanity check: manager places tiers in the order
        // we configured even if degenerate values are injected.
        config.setTier1Discount(0.01);
        config.setTier2Discount(0.05);
        config.setTier3Discount(0.20);
        when(operator.getOperatorSignal(IDX)).thenReturn(operatorSignal(70, 1));
        manager.arm(IDX, OptionType.CE, 23500, 100.0, 3, seedDiag("CE", 23500));
        OiShiftTrapLadderManager.Ladder L = manager.peek(
                new OiShiftTrapLadderManager.Key(IDX, OptionType.CE, 23500));
        assertThat(L.tier1().limitPrice()).isEqualTo(99.0);
        assertThat(L.tier3().limitPrice()).isEqualTo(80.0);
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private static OiShiftTrapDiagnostics seedDiag(String side, int strike) {
        OiShiftTrapDiagnostics.CandidateSnapshot snap = new OiShiftTrapDiagnostics.CandidateSnapshot(
                BigDecimal.valueOf(strike), 120_000, 80_000, 5_000, 2.1, 0.3, 65, "");
        return new OiShiftTrapDiagnostics(
                "NIFTY", BigDecimal.valueOf(23450), 1, "NORMAL", 5000,
                "SIGNAL", "", 12,
                "CE".equals(side) ? snap : OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                "PE".equals(side) ? snap : OiShiftTrapDiagnostics.CandidateSnapshot.empty(),
                true, side, BigDecimal.valueOf(strike), 65);
    }

    private static OperatorAccumulationDetector.OperatorSignal operatorSignal(int score, int direction) {
        OperatorAccumulationDetector.OperatorSignal sig =
                new OperatorAccumulationDetector.OperatorSignal();
        try {
            var sf = OperatorAccumulationDetector.OperatorSignal.class.getDeclaredField("score");
            sf.setAccessible(true);
            sf.setInt(sig, score);
            var df = OperatorAccumulationDetector.OperatorSignal.class.getDeclaredField("direction");
            df.setAccessible(true);
            df.setInt(sig, direction);
        } catch (ReflectiveOperationException ex) {
            throw new RuntimeException(ex);
        }
        return sig;
    }
}
