package com.algo.trade.tuning.capture;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-strategy capture toggle service. Holds a volatile cache of {@link CaptureSettings}
 * keyed by {@link StrategyType}, refreshed from the DB every 30 seconds (configurable
 * via {@link CaptureToggleProperties#getPollIntervalSec()}).
 *
 * <h2>Read path (called from the trading hot path)</h2>
 * {@link #isEnabled(StrategyType, TuningEventType)} and {@link #settingsFor(StrategyType)}
 * are lock-free O(1) lookups against the cache. Safe to call millions of times per day.
 *
 * <h2>Write path (called from UI controller)</h2>
 * {@link #updateConfig(StrategyType, CaptureSettings, String, String)} runs in a single
 * transaction: load row → diff → write row → write one {@link TuningCaptureAuditEntity}
 * per changed field → publish new snapshot. Subsequent reads see the change immediately.
 *
 * <h2>Defaults</h2>
 * If no DB row exists for a strategy, the service returns
 * {@link CaptureSettings#defaultOff(StrategyType)} — capture OFF. Rows are seeded on
 * demand when a {@code TuningCaptureAdapter} registers (call
 * {@link #ensureRowExists(StrategyType)} from the adapter registry in Phase 2).
 */
@Service
public class CaptureToggleService {

    private static final Logger log = LoggerFactory.getLogger(CaptureToggleService.class);

    private final TuningCaptureConfigRepository configRepo;
    private final TuningCaptureAuditRepository auditRepo;
    private final CaptureToggleProperties properties;

    /** Snapshot map — replaced atomically on every refresh. */
    private final AtomicReference<Map<StrategyType, CaptureSettings>> cache =
            new AtomicReference<>(new EnumMap<>(StrategyType.class));

    private volatile Instant lastRefreshAt;

    public CaptureToggleService(TuningCaptureConfigRepository configRepo,
                                TuningCaptureAuditRepository auditRepo,
                                CaptureToggleProperties properties) {
        this.configRepo = configRepo;
        this.auditRepo = auditRepo;
        this.properties = properties;
    }

    @PostConstruct
    void initialLoad() {
        refresh();
        log.info("[CaptureToggleService] initial load: {} strategies cached, pollInterval={}s",
                cache.get().size(), properties.getPollIntervalSec());
    }

    /**
     * Periodically reloads the cache from the DB. {@code fixedDelay} (not fixedRate) so
     * a slow DB call doesn't queue up subsequent refreshes.
     */
    @Scheduled(fixedDelayString = "${tuning.capture.poll-interval-sec:30}",
               timeUnit = java.util.concurrent.TimeUnit.SECONDS,
               initialDelay = 30)
    public void refresh() {
        try {
            List<TuningCaptureConfigEntity> rows = configRepo.findAll();
            Map<StrategyType, CaptureSettings> fresh = new EnumMap<>(StrategyType.class);
            int enabledCount = 0;
            StringBuilder summary = new StringBuilder();
            for (TuningCaptureConfigEntity row : rows) {
                CaptureSettings s = CaptureSettings.from(row);
                fresh.put(row.getStrategy(), s);
                if (s.captureEnabled()) enabledCount++;
                summary.append(row.getStrategy().name()).append('=')
                        .append(s.captureEnabled() ? "ON" : "off")
                        .append("(eval=").append(s.captureEvaluations() ? '1' : '0')
                        .append(",sig=").append(s.captureSignals() ? '1' : '0')
                        .append(",exit=").append(s.captureExits() ? '1' : '0')
                        .append(") ");
            }
            cache.set(fresh);
            lastRefreshAt = Instant.now();
            // Diagnostic — log loaded settings when enabled-count changes
            // (and always on first load). Keeps running log readable.
            int prev = lastLoggedEnabledCount.getAndSet(enabledCount);
            if (prev != enabledCount) {
                log.info("[CaptureToggleService] refresh: rows={} enabled={}/{} settings=[{}]",
                        rows.size(), enabledCount, rows.size(), summary.toString().trim());
            }
        } catch (Exception ex) {
            log.warn("[CaptureToggleService] refresh failed; keeping previous snapshot: {}", ex.getMessage());
        }
    }

    private final java.util.concurrent.atomic.AtomicInteger lastLoggedEnabledCount =
            new java.util.concurrent.atomic.AtomicInteger(-1);

    // ── Read API (trading hot path) ───────────────────────────────────────

    /** True iff capture is enabled for the strategy AND the specified event type is allowed. */
    public boolean isEnabled(StrategyType strategy, TuningEventType type) {
        return settingsFor(strategy).isEnabledFor(type);
    }

    /** True iff the master capture toggle is on for this strategy. */
    public boolean isCaptureEnabled(StrategyType strategy) {
        return settingsFor(strategy).captureEnabled();
    }

    /** Snapshot for a strategy. Returns {@link CaptureSettings#defaultOff} when no DB row exists. */
    public CaptureSettings settingsFor(StrategyType strategy) {
        CaptureSettings s = cache.get().get(strategy);
        return s != null ? s : CaptureSettings.defaultOff(strategy);
    }

    /** All current capture snapshots. Used by the UI to render the settings page. */
    public List<CaptureSettings> listAll() {
        return new ArrayList<>(cache.get().values());
    }

    public Optional<Instant> lastRefreshAt() {
        return Optional.ofNullable(lastRefreshAt);
    }

    // ── Write API (UI controller / admin) ─────────────────────────────────

    /**
     * Persists {@code newSettings} for the given strategy and writes one audit row per
     * changed field. Refreshes the cache before returning so the caller sees the new
     * values immediately.
     *
     * @return the persisted snapshot.
     */
    @Transactional
    public CaptureSettings updateConfig(StrategyType strategy,
                                        CaptureSettings newSettings,
                                        String changedBy,
                                        String reason) {
        Objects.requireNonNull(strategy, "strategy");
        Objects.requireNonNull(newSettings, "newSettings");
        if (newSettings.strategy() != strategy) {
            throw new IllegalArgumentException("strategy mismatch: path=" + strategy
                    + " body=" + newSettings.strategy());
        }

        TuningCaptureConfigEntity row = configRepo.findById(strategy)
                .orElseGet(() -> newRowFor(strategy, -1));

        recordIfChanged(strategy, "captureEnabled", row.isCaptureEnabled(),
                newSettings.captureEnabled(), changedBy, reason);
        recordIfChanged(strategy, "captureEvaluations", row.isCaptureEvaluations(),
                newSettings.captureEvaluations(), changedBy, reason);
        recordIfChanged(strategy, "captureSignals", row.isCaptureSignals(),
                newSettings.captureSignals(), changedBy, reason);
        recordIfChanged(strategy, "captureExecutions", row.isCaptureExecutions(),
                newSettings.captureExecutions(), changedBy, reason);
        recordIfChanged(strategy, "captureExits", row.isCaptureExits(),
                newSettings.captureExits(), changedBy, reason);
        recordIfChanged(strategy, "captureForward", row.isCaptureForward(),
                newSettings.captureForward(), changedBy, reason);
        recordIfChanged(strategy, "captureShadow", row.isCaptureShadow(),
                newSettings.captureShadow(), changedBy, reason);
        recordIfChanged(strategy, "episodeWindowSec", row.getEpisodeWindowSec(),
                newSettings.episodeWindowSec(), changedBy, reason);

        row.setCaptureEnabled(newSettings.captureEnabled());
        row.setCaptureEvaluations(newSettings.captureEvaluations());
        row.setCaptureSignals(newSettings.captureSignals());
        row.setCaptureExecutions(newSettings.captureExecutions());
        row.setCaptureExits(newSettings.captureExits());
        row.setCaptureForward(newSettings.captureForward());
        row.setCaptureShadow(newSettings.captureShadow());
        row.setEpisodeWindowSec(newSettings.episodeWindowSec());
        row.setNotes(newSettings.notes());
        row.setUpdatedAt(Instant.now());
        row.setUpdatedBy(changedBy);

        TuningCaptureConfigEntity saved = configRepo.save(row);
        CaptureSettings snapshot = CaptureSettings.from(saved);

        // Publish immediately so the next read sees the new value.
        Map<StrategyType, CaptureSettings> updated = new EnumMap<>(cache.get());
        updated.put(strategy, snapshot);
        cache.set(updated);

        log.info("[CaptureToggleService] {} updated by={} reason={} captureEnabled={}",
                strategy, changedBy, reason, snapshot.captureEnabled());
        return snapshot;
    }

    /**
     * Idempotent: creates a default-ON row for the strategy if none exists. Called by
     * the {@code TuningCaptureAdapterRegistry} (Phase 2) when a new strategy adapter
     * registers, so the UI capture toggle row appears automatically and starts
     * accumulating data immediately. (As of 4 Jun 2026 capture is on by default.)
     */
    @Transactional
    public CaptureSettings ensureRowExists(StrategyType strategy, int defaultEpisodeWindowSec) {
        return ensureRowExistsInternal(strategy, defaultEpisodeWindowSec);
    }

    /**
     * Bulk flip every existing row's {@code captureEnabled} flag. Used by the
     * {@code POST /tuning/capture/enable-all} (and {@code /disable-all}) admin
     * endpoint so the operator can migrate an existing DB without touching each
     * strategy individually.
     *
     * @param enable true to turn capture ON for every row, false to turn OFF
     * @param changedBy operator identity for the audit trail
     * @param reason free-text reason (e.g. "default-on-migration")
     * @return number of rows actually changed (rows already in target state are skipped)
     */
    @Transactional
    public int setAllCaptureEnabled(boolean enable, String changedBy, String reason) {
        int changed = 0;
        Map<StrategyType, CaptureSettings> updated = new EnumMap<>(cache.get());
        for (TuningCaptureConfigEntity row : configRepo.findAll()) {
            if (row.isCaptureEnabled() == enable) {
                continue;
            }
            recordIfChanged(row.getStrategy(), "captureEnabled", row.isCaptureEnabled(),
                    enable, changedBy, reason);
            row.setCaptureEnabled(enable);
            row.setUpdatedAt(Instant.now());
            row.setUpdatedBy(changedBy);
            TuningCaptureConfigEntity saved = configRepo.save(row);
            updated.put(saved.getStrategy(), CaptureSettings.from(saved));
            changed++;
        }
        if (changed > 0) {
            cache.set(updated);
        }
        log.warn("[CaptureToggleService] bulk capture {} by={} reason={} changedRows={}",
                enable ? "ENABLED" : "DISABLED", changedBy, reason, changed);
        return changed;
    }

    public CaptureSettings ensureRowExists(StrategyType strategy) {
        return ensureRowExistsInternal(strategy, -1);
    }

    private CaptureSettings ensureRowExistsInternal(StrategyType strategy, int episodeWindowSec) {
        Optional<TuningCaptureConfigEntity> existing = configRepo.findById(strategy);
        if (existing.isPresent()) {
            return CaptureSettings.from(existing.get());
        }
        TuningCaptureConfigEntity row = newRowFor(strategy, episodeWindowSec);
        TuningCaptureConfigEntity saved = configRepo.save(row);
        CaptureSettings snapshot = CaptureSettings.from(saved);
        Map<StrategyType, CaptureSettings> updated = new EnumMap<>(cache.get());
        updated.put(strategy, snapshot);
        cache.set(updated);
        log.info("[CaptureToggleService] seeded default OFF row for {}", strategy);
        return snapshot;
    }

    private TuningCaptureConfigEntity newRowFor(StrategyType strategy, int episodeWindowSec) {
        TuningCaptureConfigEntity row = new TuningCaptureConfigEntity();
        row.setStrategy(strategy);
        // All other fields use entity defaults: captureEnabled=false, per-type=true, window=60.
        if (episodeWindowSec > 0) {
            row.setEpisodeWindowSec(episodeWindowSec);
        }
        row.setUpdatedAt(Instant.now());
        return row;
    }

    private void recordIfChanged(StrategyType strategy, String field,
                                  Object oldVal, Object newVal,
                                  String changedBy, String reason) {
        if (Objects.equals(oldVal, newVal)) {
            return;
        }
        auditRepo.save(new TuningCaptureAuditEntity(
                strategy, field,
                oldVal != null ? oldVal.toString() : null,
                newVal != null ? newVal.toString() : null,
                changedBy, reason));
    }
}
