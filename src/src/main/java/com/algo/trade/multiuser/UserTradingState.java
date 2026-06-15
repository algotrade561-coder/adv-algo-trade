package com.algo.trade.multiuser;

import com.algo.trade.risk.HaltMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-user trading state — each user has their own independent:
 * - Running/stopped state
 * - Kill switch
 * - Halt mode
 * - Daily P&L tracking
 * - Approval gate
 *
 * This replaces the singleton TradingStateService for multi-user scenarios.
 * The singleton TradingStateService delegates to this per-user state.
 */
public class UserTradingState {

    private final Long userId;

    private volatile boolean running = false;
    private volatile boolean killSwitchEnabled = false;
    private volatile HaltMode haltMode = HaltMode.NONE;
    private volatile boolean dailyApproved = true;
    private volatile LocalDate approvalDate = LocalDate.now();
    private volatile double dailyLossExtension = 0;
    private volatile int extensionsUsedToday = 0;
    private volatile Instant lastScanAt = null;
    private volatile boolean schedulerEnabled = false;

    public UserTradingState(Long userId) {
        this.userId = userId;
    }

    public Long getUserId() { return userId; }

    // ── Scanner state ─────────────────────────────────────────────────────────
    public boolean isRunning() { return running; }
    public void start() { this.running = true; }
    public void stop() { this.running = false; }
    public void recordScan() { this.lastScanAt = Instant.now(); }
    public Instant getLastScanAt() { return lastScanAt; }

    // ── Kill switch ───────────────────────────────────────────────────────────
    public boolean isKillSwitchEnabled() { return killSwitchEnabled; }
    public void enableKillSwitch() { killSwitchEnabled = true; running = false; }
    public void clearKillSwitch() { killSwitchEnabled = false; }

    // ── Halt mode ─────────────────────────────────────────────────────────────
    public HaltMode getHaltMode() { return haltMode; }
    public void softHalt(String reason) { haltMode = HaltMode.SOFT; }
    public void hardHalt(String reason) { haltMode = HaltMode.HARD; running = false; killSwitchEnabled = true; }
    public void resumeFromHalt() { haltMode = HaltMode.NONE; killSwitchEnabled = false; }

    // ── Entry allowed check ───────────────────────────────────────────────────
    public boolean isEntryAllowed() {
        return running && !killSwitchEnabled && haltMode == HaltMode.NONE && dailyApproved;
    }
    public boolean isExitAllowed() { return haltMode != HaltMode.HARD; }

    // ── Daily approval gate ───────────────────────────────────────────────────
    public boolean isDailyApproved() {
        LocalDate today = LocalDate.now();
        if (!today.equals(approvalDate)) { dailyApproved = false; approvalDate = today; }
        return dailyApproved;
    }
    public void approveToday() { dailyApproved = true; approvalDate = LocalDate.now(); }
    public void revokeApproval() { dailyApproved = false; }

    // ── Daily loss extension ──────────────────────────────────────────────────
    public double getDailyLossExtension() { return dailyLossExtension; }
    public int getExtensionsUsedToday() { return extensionsUsedToday; }

    public void extendDailyLimit(double amount) {
        dailyLossExtension += amount;
        extensionsUsedToday++;
        resumeFromHalt();
    }

    // ── Scheduler ─────────────────────────────────────────────────────────────
    public boolean isSchedulerEnabled() { return schedulerEnabled; }
    public void setSchedulerEnabled(boolean enabled) { this.schedulerEnabled = enabled; }

    // ── Daily reset ───────────────────────────────────────────────────────────
    public void resetDaily() {
        dailyApproved = false;
        dailyLossExtension = 0;
        extensionsUsedToday = 0;
    }

    public void autoApprove() {
        if (!dailyApproved) {
            dailyApproved = true;
            approvalDate = LocalDate.now();
        }
    }
}
