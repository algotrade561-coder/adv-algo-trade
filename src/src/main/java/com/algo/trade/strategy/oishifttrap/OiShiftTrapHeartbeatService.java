package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.domain.IndexType;
import com.algo.trade.notification.TelegramAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
 * Liveness heartbeat for the OI Shift Trap evaluation loop.
 *
 * <p>The 2 Jun 2026 EOD analysis showed OI Shift Trap stopped evaluating at
 * 12:10 IST and never resumed for the rest of the session — 20 minutes before
 * a 25-minute +225-pt NIFTY squeeze that OIST should have been a candidate to
 * catch. Nothing alerted because {@link com.algo.trade.strategy.oimomentum.EntryPathHeartbeatService}
 * only watches OI Momentum's tick, not OIST's.</p>
 *
 * <p>This service is symmetric to {@code EntryPathHeartbeatService}: per-index
 * last-tick timestamps, scheduled check during market hours, one Telegram alert
 * per stale episode that re-arms on resume.</p>
 *
 * <p>Wiring: {@code AlgoTradeExecution} calls {@link #recordTick(IndexType)}
 * right before {@code oiShiftTrapStrategy.evaluateWithDiagnostics}. The scheduled
 * check runs once per minute during market hours.</p>
 */
@Service
public class OiShiftTrapHeartbeatService {

    private static final Logger log = LoggerFactory.getLogger(OiShiftTrapHeartbeatService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Autowired(required = false)
    private TelegramAlertService telegramAlertService;

    /** Stale threshold — overridable via YAML. Default 10 min (same as OI Momentum heartbeat). */
    @Value("${trading.oi-shift-trap.heartbeat-stale-minutes:10}")
    private int staleMinutes = 10;

    /** Master kill-switch for the heartbeat alerts. */
    @Value("${trading.oi-shift-trap.heartbeat-enabled:true}")
    private boolean enabled = true;

    private final Map<IndexType, AtomicLong> lastTickEpochMs = new EnumMap<>(IndexType.class);
    private final Map<IndexType, AtomicLong> staleAlertedEpochMs = new EnumMap<>(IndexType.class);

    public OiShiftTrapHeartbeatService() {
        for (IndexType ix : IndexType.values()) {
            lastTickEpochMs.put(ix, new AtomicLong(0));
            staleAlertedEpochMs.put(ix, new AtomicLong(0));
        }
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        log.info("[OIST-Heartbeat] READY — enabled={}, staleMinutes={}, marketHours=09:15-15:30 IST",
                enabled, staleMinutes);
    }

    /** Called from AlgoTradeExecution before every OI Shift Trap evaluation. */
    public void recordTick(IndexType ix) {
        if (ix == null) return;
        lastTickEpochMs.get(ix).set(System.currentTimeMillis());
        AtomicLong alerted = staleAlertedEpochMs.get(ix);
        if (alerted.get() != 0) {
            log.info("[OIST-Heartbeat][{}] OIST tick resumed after {}s stale window",
                    ix, (System.currentTimeMillis() - alerted.get()) / 1000);
            alerted.set(0);
        }
    }

    @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
    public void checkHeartbeat() {
        if (!enabled) {
            log.debug("[OIST-Heartbeat] check skipped — disabled via config");
            return;
        }
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) {
            log.debug("[OIST-Heartbeat] check skipped — outside market hours");
            return;
        }
        log.info("[OIST-Heartbeat] check at {} IST — last ticks: {}",
                now,
                lastTickEpochMs.entrySet().stream()
                        .map(e -> e.getKey() + "=" + (e.getValue().get() == 0
                                ? "never"
                                : ((System.currentTimeMillis() - e.getValue().get()) / 1000) + "s ago"))
                        .toList());

        long nowMs = System.currentTimeMillis();
        long staleMs = Duration.ofMinutes(staleMinutes).toMillis();
        for (Map.Entry<IndexType, AtomicLong> e : lastTickEpochMs.entrySet()) {
            IndexType ix = e.getKey();
            long lastMs = e.getValue().get();
            AtomicLong alerted = staleAlertedEpochMs.get(ix);
            if (lastMs == 0) continue;
            long gapMs = nowMs - lastMs;
            if (gapMs >= staleMs && alerted.get() == 0) {
                long gapMin = gapMs / 60_000;
                String msg = String.format(
                        "[OiShiftTrap][%s] HEARTBEAT STALE — no eval tick for %d min "
                        + "(threshold %d min). OIST loop may be stalled.",
                        ix, gapMin, staleMinutes);
                log.warn(msg);
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(msg);
                }
                alerted.set(nowMs);
            }
        }
    }
}
