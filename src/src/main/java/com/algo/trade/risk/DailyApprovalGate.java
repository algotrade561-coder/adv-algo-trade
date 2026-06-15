package com.algo.trade.risk;

import com.algo.trade.notification.AlertRateLimiter;
import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Borrowed from friend's repo (Phase 1.3, 2 Jun 2026 evening).
 *
 * <p>First trade of the day requires manual approval — a daily safety
 * checkpoint. Workflow:</p>
 *
 * <ol>
 *   <li>09:00 IST: Telegram alert "Market opening — approve trading for today?"</li>
 *   <li>Operator either clicks the dashboard "Approve" button OR replies via
 *       any of the API endpoints (POST /api/dashboard/approve-today).</li>
 *   <li>Until approved, {@link #isApproved()} returns false and entry
 *       strategies skip new entries (callers check this).</li>
 *   <li>10:00 IST: reminder if still not approved.</li>
 *   <li>10:30 IST: auto-approve if {@code risk.daily-approval.auto-approve}
 *       is true (default). Set to false to require manual approval daily.</li>
 *   <li>Midnight: reset for the next trading day.</li>
 * </ol>
 *
 * <p>Strategies that want to gate on this:</p>
 * <pre>
 *   if (dailyApprovalGate != null && !dailyApprovalGate.isApproved()) {
 *       return; // skip new entries until operator approves
 *   }
 * </pre>
 *
 * <p>The gate does NOT affect exits, squareoffs, or open-position management —
 * only new entry signals. The operator can always close positions even if the
 * day hasn't been approved.</p>
 */
@Component
public class DailyApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(DailyApprovalGate.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired(required = false)
    private TelegramAlertService telegramAlertService;

    @Autowired(required = false)
    private AlertRateLimiter alertRateLimiter;

    @Value("${trading.daily-approval.auto-approve:true}")
    private boolean autoApproveEnabled = true;

    @Value("${trading.daily-approval.auto-approve-time:10:30}")
    private String autoApproveTimeStr = "10:30";

    @Value("${trading.daily-approval.morning-alert-time:09:00}")
    private String morningAlertTimeStr = "09:00";

    @Value("${trading.daily-approval.reminder-time:10:00}")
    private String reminderTimeStr = "10:00";

    @Value("${trading.daily-approval.enabled:true}")
    private boolean enabled = true;

    private final AtomicBoolean approvedToday = new AtomicBoolean(false);
    private final AtomicReference<LocalDate> approvalDate = new AtomicReference<>(null);
    private final AtomicBoolean morningAlertSent = new AtomicBoolean(false);
    private final AtomicBoolean reminderSent = new AtomicBoolean(false);
    private final AtomicReference<String> approvedBy = new AtomicReference<>("");

    /** Startup confirmation log — mirrors EntryPathHeartbeatService.init() pattern. */
    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("[DailyApprovalGate] READY — enabled={}, autoApprove={}, "
                + "morningAlert={}, reminder={}, autoApproveAt={}",
                enabled, autoApproveEnabled, morningAlertTimeStr,
                reminderTimeStr, autoApproveTimeStr);
    }

    /** Returns true if today is approved for trading. */
    public boolean isApproved() {
        if (!enabled) return true;   // gate off → always approved
        LocalDate today = LocalDate.now(IST);
        LocalDate approvalDay = approvalDate.get();
        return approvedToday.get() && today.equals(approvalDay);
    }

    /** Operator-driven approve. Records the email/identity of who approved. */
    public synchronized void approve(String operatorIdentity) {
        LocalDate today = LocalDate.now(IST);
        approvedToday.set(true);
        approvalDate.set(today);
        approvedBy.set(operatorIdentity != null ? operatorIdentity : "manual");
        log.warn("[DailyApprovalGate] APPROVED for {} by '{}'", today, approvedBy.get());
        sendAlert("✅ Trading APPROVED for " + today + " by " + approvedBy.get());
    }

    /** Operator-driven revoke (rare). */
    public synchronized void revoke(String operatorIdentity, String reason) {
        approvedToday.set(false);
        approvalDate.set(null);
        log.warn("[DailyApprovalGate] REVOKED by '{}' — reason: {}", operatorIdentity, reason);
        sendAlert("⛔ Trading REVOKED by " + operatorIdentity + " — " + reason);
    }

    public boolean isAutoApproved() {
        return "auto-approve".equals(approvedBy.get());
    }

    public String approvedBy() { return approvedBy.get(); }
    public LocalDate approvalDate() { return approvalDate.get(); }

    /**
     * Scheduled every minute — runs the morning alert, reminder, and
     * auto-approve actions at their configured IST times.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void scheduledCheck() {
        if (!enabled) return;
        LocalTime now = LocalTime.now(IST);
        LocalDate today = LocalDate.now(IST);

        // Reset state at start of new trading day (also via daily-roll-over heuristic)
        LocalDate stored = approvalDate.get();
        if (stored == null || !stored.equals(today)) {
            if (now.isBefore(LocalTime.of(9, 0))) {
                // Pre-market: clear stale yesterday's state once.
                if (morningAlertSent.compareAndSet(true, false)) {
                    log.info("[DailyApprovalGate] new day {} — alert flags reset", today);
                }
                reminderSent.set(false);
            }
        }

        LocalTime morningAlert = parseOrDefault(morningAlertTimeStr, LocalTime.of(9, 0));
        LocalTime reminder = parseOrDefault(reminderTimeStr, LocalTime.of(10, 0));
        LocalTime autoApprove = parseOrDefault(autoApproveTimeStr, LocalTime.of(10, 30));

        // 09:00 morning alert
        if (!isApproved() && !morningAlertSent.get() && !now.isBefore(morningAlert)
                && now.isBefore(reminder)) {
            morningAlertSent.set(true);
            sendAlert("🌅 " + today + " — Market opening. Approve trading via "
                    + "dashboard or POST /advalgotrade/api/dashboard/approve-today");
        }

        // 10:00 reminder
        if (!isApproved() && !reminderSent.get() && !now.isBefore(reminder)
                && now.isBefore(autoApprove)) {
            reminderSent.set(true);
            sendAlert("⏰ " + today + " — Still NOT approved. Auto-approve at "
                    + autoApprove + " IST if auto-approve flag is on.");
        }

        // 10:30 auto-approve
        if (!isApproved() && autoApproveEnabled && !now.isBefore(autoApprove)) {
            approvedToday.set(true);
            approvalDate.set(today);
            approvedBy.set("auto-approve");
            log.warn("[DailyApprovalGate] AUTO-APPROVED at {} (operator did not action)",
                    autoApprove);
            sendAlert("🤖 " + today + " — Auto-approved at " + autoApprove
                    + " IST (operator did not action)");
        }
    }

    private LocalTime parseOrDefault(String s, LocalTime fallback) {
        try { return LocalTime.parse(s); } catch (Exception ex) { return fallback; }
    }

    private void sendAlert(String message) {
        if (telegramAlertService == null) return;
        if (alertRateLimiter != null) {
            alertRateLimiter.send(telegramAlertService, "DAILY_APPROVAL", message);
        } else {
            telegramAlertService.systemAlert(message);
        }
    }
}
