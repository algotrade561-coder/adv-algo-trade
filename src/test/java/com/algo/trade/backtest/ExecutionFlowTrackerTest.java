package com.algo.trade.backtest;

import static org.assertj.core.api.Assertions.assertThat;

import com.algo.trade.backtest.ExecutionFlowTracker.BranchRecord;
import com.algo.trade.backtest.ExecutionFlowTracker.BranchStatus;
import com.algo.trade.backtest.ExecutionFlowTracker.ExecutionFlowCoverage;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExecutionFlowTrackerTest {

    @Test
    void recordHitIncrementsCount() {
        var tracker = new ExecutionFlowTracker();

        tracker.recordHit("exit.stop-loss");

        assertThat(tracker.getHitCount("exit.stop-loss")).isEqualTo(1);
    }

    @Test
    void multipleHitsOnSameBranchIncrementCorrectly() {
        var tracker = new ExecutionFlowTracker();

        tracker.recordHit("entry.signal-to-trade");
        tracker.recordHit("entry.signal-to-trade");
        tracker.recordHit("entry.signal-to-trade");

        assertThat(tracker.getHitCount("entry.signal-to-trade")).isEqualTo(3);
    }

    @Test
    void coverageReturnsAll21PreRegisteredBranches() {
        var tracker = new ExecutionFlowTracker();

        ExecutionFlowCoverage coverage = tracker.getCoverage();

        assertThat(coverage.branches()).hasSize(21);
        assertThat(coverage.branches()).containsKey("entry.signal-to-trade");
        assertThat(coverage.branches()).containsKey("exit.stop-loss");
        assertThat(coverage.branches()).containsKey("exit.end-of-data");
        assertThat(coverage.branches()).containsKey("expiry.no-entry-after-1pm");
        assertThat(coverage.branches()).containsKey("regime.score-computed");
        assertThat(coverage.branches()).containsKey("atr.computed");
    }

    @Test
    void unvisitedBranchesShowNotHitWithZeroCount() {
        var tracker = new ExecutionFlowTracker();

        ExecutionFlowCoverage coverage = tracker.getCoverage();
        BranchStatus status = coverage.branches().get("exit.target");

        assertThat(status.hit()).isFalse();
        assertThat(status.hitCount()).isZero();
        assertThat(status.firstHitDetail()).isNull();
    }

    @Test
    void visitedBranchesShowHitWithCorrectCount() {
        var tracker = new ExecutionFlowTracker();

        tracker.recordHit("exit.trailing-stop");
        tracker.recordHit("exit.trailing-stop");

        ExecutionFlowCoverage coverage = tracker.getCoverage();
        BranchStatus status = coverage.branches().get("exit.trailing-stop");

        assertThat(status.hit()).isTrue();
        assertThat(status.hitCount()).isEqualTo(2);
        assertThat(status.category()).isEqualTo("exit");
    }

    @Test
    void firstHitDetailIsCapturedAndPreservedOnSubsequentHits() {
        var tracker = new ExecutionFlowTracker();

        tracker.recordHit("exit.stop-loss", "SL at 42.5");
        tracker.recordHit("exit.stop-loss", "SL at 38.0");

        ExecutionFlowCoverage coverage = tracker.getCoverage();
        BranchStatus status = coverage.branches().get("exit.stop-loss");

        assertThat(status.firstHitDetail()).isEqualTo("SL at 42.5");
        assertThat(status.hitCount()).isEqualTo(2);
    }

    @Test
    void unregisteredBranchRecordedWithUnknownCategory() {
        var tracker = new ExecutionFlowTracker();

        tracker.recordHit("custom.new-branch", "some detail");

        ExecutionFlowCoverage coverage = tracker.getCoverage();
        BranchStatus status = coverage.branches().get("custom.new-branch");

        assertThat(status).isNotNull();
        assertThat(status.hit()).isTrue();
        assertThat(status.hitCount()).isEqualTo(1);
        assertThat(status.category()).isEqualTo("unknown");
    }

    @Test
    void getHitCountReturnsZeroForUnknownBranch() {
        var tracker = new ExecutionFlowTracker();

        assertThat(tracker.getHitCount("nonexistent.branch")).isZero();
    }

    @Test
    void categoriesAreCorrectForAllBranchTypes() {
        var tracker = new ExecutionFlowTracker();
        ExecutionFlowCoverage coverage = tracker.getCoverage();
        Map<String, BranchStatus> branches = coverage.branches();

        assertThat(branches.get("entry.signal-to-trade").category()).isEqualTo("entry");
        assertThat(branches.get("entry.premium-too-high").category()).isEqualTo("entry");
        assertThat(branches.get("entry.risk-rejected").category()).isEqualTo("entry");
        assertThat(branches.get("entry.market-guard-blocked").category()).isEqualTo("entry");

        assertThat(branches.get("exit.stop-loss").category()).isEqualTo("exit");
        assertThat(branches.get("exit.target").category()).isEqualTo("exit");
        assertThat(branches.get("exit.trailing-stop").category()).isEqualTo("exit");
        assertThat(branches.get("exit.forced-time").category()).isEqualTo("exit");
        assertThat(branches.get("exit.max-hold").category()).isEqualTo("exit");
        assertThat(branches.get("exit.expiry-afternoon").category()).isEqualTo("exit");
        assertThat(branches.get("exit.expiry-danger-zone").category()).isEqualTo("exit");
        assertThat(branches.get("exit.expiry-tightened-sl").category()).isEqualTo("exit");
        assertThat(branches.get("exit.dte-sl-scaling").category()).isEqualTo("exit");
        assertThat(branches.get("exit.end-of-data").category()).isEqualTo("exit");

        assertThat(branches.get("expiry.no-entry-after-1pm").category()).isEqualTo("expiry");
        assertThat(branches.get("expiry.vb-theta-guard").category()).isEqualTo("expiry");

        assertThat(branches.get("regime.score-computed").category()).isEqualTo("regime");
        assertThat(branches.get("regime.strategy-recommended").category()).isEqualTo("regime");

        assertThat(branches.get("atr.computed").category()).isEqualTo("atr");
        assertThat(branches.get("atr.dynamic-sl").category()).isEqualTo("atr");
        assertThat(branches.get("atr.trailing-sl").category()).isEqualTo("atr");
    }

    @Test
    void detailIsNullWhenRecordedWithoutDetail() {
        var tracker = new ExecutionFlowTracker();

        tracker.recordHit("atr.computed");

        BranchRecord record = tracker.getBranches().get("atr.computed");
        assertThat(record.hitCount()).isEqualTo(1);
        assertThat(record.firstDetail()).isNull();
    }

    @Test
    void detailCapturedOnFirstHitWhenInitialHitHasNoDetail() {
        var tracker = new ExecutionFlowTracker();

        tracker.recordHit("regime.score-computed");
        tracker.recordHit("regime.score-computed", "score=0.75");

        ExecutionFlowCoverage coverage = tracker.getCoverage();
        BranchStatus status = coverage.branches().get("regime.score-computed");

        assertThat(status.firstHitDetail()).isEqualTo("score=0.75");
        assertThat(status.hitCount()).isEqualTo(2);
    }
}
