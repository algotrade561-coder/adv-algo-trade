package com.algo.trade.backtest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which execution branches are hit during a backtest verification run.
 * Each branch is a named checkpoint (e.g. "exit.stop-loss", "entry.signal-to-trade").
 *
 * <p>All known branches are pre-registered at construction time so that
 * {@link #getCoverage()} reports both hit and unhit branches.</p>
 */
public class ExecutionFlowTracker {

    private final ConcurrentHashMap<String, BranchRecord> branches = new ConcurrentHashMap<>();

    /** All pre-registered branch names mapped to their category. */
    private static final Map<String, String> REGISTERED_BRANCHES;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        // Entry branches
        m.put("entry.signal-to-trade", "entry");
        m.put("entry.premium-too-high", "entry");
        m.put("entry.risk-rejected", "entry");
        m.put("entry.market-guard-blocked", "entry");
        // Exit branches
        m.put("exit.stop-loss", "exit");
        m.put("exit.target", "exit");
        m.put("exit.trailing-stop", "exit");
        m.put("exit.forced-time", "exit");
        m.put("exit.max-hold", "exit");
        m.put("exit.expiry-afternoon", "exit");
        m.put("exit.expiry-danger-zone", "exit");
        m.put("exit.expiry-tightened-sl", "exit");
        m.put("exit.dte-sl-scaling", "exit");
        m.put("exit.end-of-data", "exit");
        // Expiry branches
        m.put("expiry.no-entry-after-1pm", "expiry");
        m.put("expiry.vb-theta-guard", "expiry");
        // Regime branches
        m.put("regime.score-computed", "regime");
        m.put("regime.strategy-recommended", "regime");
        // ATR branches
        m.put("atr.computed", "atr");
        m.put("atr.dynamic-sl", "atr");
        m.put("atr.trailing-sl", "atr");
        REGISTERED_BRANCHES = Collections.unmodifiableMap(m);
    }

    public ExecutionFlowTracker() {
        // Pre-register all known branches with zero hits
        for (Map.Entry<String, String> entry : REGISTERED_BRANCHES.entrySet()) {
            branches.put(entry.getKey(), new BranchRecord(0, null));
        }
    }

    /** Record a hit on the named branch. */
    public void recordHit(String branchName) {
        recordHit(branchName, null);
    }

    /** Record a hit on the named branch with an optional detail string. */
    public void recordHit(String branchName, String detail) {
        branches.compute(branchName, (key, existing) -> {
            if (existing == null) {
                return new BranchRecord(1, detail);
            }
            return new BranchRecord(existing.hitCount + 1,
                    existing.firstDetail != null ? existing.firstDetail : detail);
        });
    }

    /**
     * Returns the execution flow coverage, including all pre-registered branches
     * and any additional branches that were recorded at runtime.
     */
    public ExecutionFlowCoverage getCoverage() {
        Map<String, BranchStatus> result = new LinkedHashMap<>();
        for (Map.Entry<String, BranchRecord> entry : branches.entrySet()) {
            String name = entry.getKey();
            BranchRecord rec = entry.getValue();
            String category = REGISTERED_BRANCHES.getOrDefault(name, "unknown");
            result.put(name, new BranchStatus(
                    rec.hitCount > 0,
                    rec.hitCount,
                    rec.firstDetail,
                    category
            ));
        }
        return new ExecutionFlowCoverage(Collections.unmodifiableMap(result));
    }

    /** Returns a snapshot of all recorded branches. */
    public Map<String, BranchRecord> getBranches() {
        return Map.copyOf(branches);
    }

    /** Returns the hit count for a specific branch, or 0 if never hit. */
    public int getHitCount(String branchName) {
        BranchRecord record = branches.get(branchName);
        return record != null ? record.hitCount : 0;
    }

    /** Returns the set of all pre-registered branch names. */
    public static Map<String, String> getRegisteredBranches() {
        return REGISTERED_BRANCHES;
    }

    /** Simple record tracking hit count and first detail. */
    public record BranchRecord(int hitCount, String firstDetail) {}

    /** Coverage report for all tracked execution branches. */
    public record ExecutionFlowCoverage(Map<String, BranchStatus> branches) {}

    /** Status of a single tracked branch. */
    public record BranchStatus(boolean hit, int hitCount, String firstHitDetail, String category) {}
}
