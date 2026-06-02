package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * T5 — Capture / entry-path heartbeat.
 *
 * <p>Tracks the wall-clock time of the most recent {@code detectEntry} pass per
 * index and the most recent successful tune-CSV write. On 1 Jun 2026 the OI
 * Momentum strategy went silent from 13:51 onwards — the chain snapshot capture
 * stopped writing files and the strategy itself was no longer running its
 * entry loop. Nothing alerted; the operator only noticed end-of-day.</p>
 *
 * <p>This service runs a scheduled check every minute during market hours
 * ({@code 09:15–15:30 IST}). If any of the three indices has not had a
 * {@code detectEntry} tick within {@code captureHeartbeatStaleMinutes} (default
 * 10), it emits exactly one Telegram alert and logs WARN. Re-arms once
 * activity resumes.</p>
 *
 * <p>The strategy calls {@link #recordEntryPathTick(IndexType)} from inside
 * its detectEntry loop on every tick — successful or rejected; the heartbeat
 * is about <em>process liveness</em>, not signal quality. {@link
 * #recordTuneWriteFailure(String)} records tune CSV write errors so a separate
 * alert can fire when capture is failing silently.</p>
 */
@Service
public class EntryPathHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(EntryPathHeartbeatService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OIMomentumConfig config;

    @Autowired(required = false)
    private TelegramAlertService telegramAlertService;

    /**
     * A3 (2026-06-02): optional reference to OIMomentumStrategy so the heartbeat
     * can skip alerts for indices the operator has disabled via UI. Marked
     * {@code @Lazy} to break the circular reference — OIMomentumStrategy
     * already injects this service, so eager wiring both ways forms a cycle.
     * The strategy reference is only used inside the scheduled checkHeartbeat
     * loop (which runs minutes after startup), so lazy resolution is fine.
     */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private OIMomentumStrategy oiMomentumStrategy;

    private final Map<IndexType, AtomicLong> lastTickEpochMs = new EnumMap<>(IndexType.class);
    private final Map<IndexType, AtomicLong> staleAlertedEpochMs = new EnumMap<>(IndexType.class);
    private final AtomicLong lastTuneWriteFailureEpochMs = new AtomicLong(0);
    private final AtomicLong tuneFailureAlertedEpochMs = new AtomicLong(0);

    public EntryPathHeartbeatService(OIMomentumConfig config) {
        this.config = config;
        for (IndexType ix : IndexType.values()) {
            lastTickEpochMs.put(ix, new AtomicLong(0));
            staleAlertedEpochMs.put(ix, new AtomicLong(0));
        }
    }

    /** Startup confirmation so we can see the bean was instantiated. */
    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("[Heartbeat] EntryPathHeartbeatService READY — enabled={}, staleMinutes={}, "
                + "marketHours=09:15-15:30 IST",
                config.isCaptureHeartbeatEnabled(),
                config.getCaptureHeartbeatStaleMinutes());
    }

    /** Called by OIMomentumStrategy.detectEntry() on every tick — success or reject. */
    public void recordEntryPathTick(IndexType ix) {
        if (ix == null) return;
        lastTickEpochMs.get(ix).set(System.currentTimeMillis());
        // Re-arm: a tick after a stale alert resets the alert flag so a subsequent
        // gap can fire again.
        AtomicLong alerted = staleAlertedEpochMs.get(ix);
        if (alerted.get() != 0) {
            log.info("[Heartbeat][{}] entry-path resumed after {}s stale window",
                    ix, (System.currentTimeMillis() - alerted.get()) / 1000);
            alerted.set(0);
        }
    }

    /** Called from any tune-CSV writer when an IOException is caught. */
    public void recordTuneWriteFailure(String detail) {
        lastTuneWriteFailureEpochMs.set(System.currentTimeMillis());
        log.warn("[Heartbeat] tune CSV write failure: {}", detail);
    }

    /** Heartbeat check — runs once per minute during market hours. */
    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void checkHeartbeat() {
        if (!config.isCaptureHeartbeatEnabled()) {
            log.debug("[Heartbeat] check skipped — disabled via config");
            return;
        }

        LocalTime now = LocalTime.now(IST);
        // Market hours guard — outside of 09:15–15:30 IST a silent strategy is normal.
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) {
            log.debug("[Heartbeat] check skipped — outside market hours (now={} IST)", now);
            return;
        }
        log.info("[Heartbeat] check at {} IST — last entry-path ticks: {}",
                now,
                lastTickEpochMs.entrySet().stream()
                        .map(e -> e.getKey() + "=" + (e.getValue().get() == 0
                                ? "never"
                                : ((System.currentTimeMillis() - e.getValue().get()) / 1000) + "s ago"))
                        .toList());

        long nowMs = System.currentTimeMillis();
        long staleMs = Duration.ofMinutes(config.getCaptureHeartbeatStaleMinutes()).toMillis();

        for (Map.Entry<IndexType, AtomicLong> e : lastTickEpochMs.entrySet()) {
            IndexType ix = e.getKey();
            long lastMs = e.getValue().get();
            AtomicLong alerted = staleAlertedEpochMs.get(ix);
            if (lastMs == 0) continue;  // never started — covered by startup-alert path
            // A3 (2026-06-02): suppress alert if operator has disabled this index.
            if (oiMomentumStrategy != null
                    && !oiMomentumStrategy.getEnabledIndices().contains(ix)) {
                log.debug("[Heartbeat][{}] skipped — operator-disabled index", ix);
                continue;
            }
            long gapMs = nowMs - lastMs;
            if (gapMs >= staleMs && alerted.get() == 0) {
                long gapMin = gapMs / 60_000;
                String msg = String.format(
                        "[OIMomentum][%s] HEARTBEAT STALE — no entry-path tick for %d min "
                        + "(threshold %d min). Strategy may be hung or feed dropped.",
                        ix, gapMin, config.getCaptureHeartbeatStaleMinutes());
                log.warn(msg);
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(msg);
                }
                alerted.set(nowMs);
            }
        }

        // Tune-CSV failure — alert once per market session.
        long lastFailMs = lastTuneWriteFailureEpochMs.get();
        long alertedFailMs = tuneFailureAlertedEpochMs.get();
        if (lastFailMs > 0 && lastFailMs > alertedFailMs) {
            String msg = String.format(
                    "[OIMomentum] Tune CSV writes are FAILING (last error %ds ago). "
                    + "Tuning capture compromised. Inspect logs immediately.",
                    (nowMs - lastFailMs) / 1000);
            log.warn(msg);
            if (telegramAlertService != null) {
                telegramAlertService.systemAlert(msg);
            }
            tuneFailureAlertedEpochMs.set(nowMs);
        }
    }
}
