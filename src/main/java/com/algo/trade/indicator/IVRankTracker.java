package com.algo.trade.indicator;

import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks IV history to compute IV Rank and IV Percentile.
 *
 * IV Rank  = (current IV - 52w low) / (52w high - 52w low) × 100
 * IV Pct   = % of days in past year where IV was below current IV
 *
 * IV Rank > 50 → sell premium (IV historically high)
 * IV Rank < 20 → buy premium (IV historically low)
 *
 * Fed from GreeksCalculator on every ATM option tick.
 * Resets on restart — acceptable for intraday use.
 */
@Component
public class IVRankTracker {

    private static final Logger log = LoggerFactory.getLogger(IVRankTracker.class);
    private static final int MAX_SAMPLES = 252; // ~1 trading year

    private final Map<IndexType, Deque<IVSample>> history = new ConcurrentHashMap<>();
    private static final int GREEKS_RETENTION_DAYS = 30;
    private static final int DECISIONS_RETENTION_DAYS = 90;

    private final com.algo.trade.persistence.IVSampleRepository ivSampleRepository;
    private final com.algo.trade.persistence.GreeksSampleRepository greeksSampleRepository;
    private final com.algo.trade.persistence.StrategyDecisionRepository strategyDecisionRepository;

    /** Lazy-injected so direct callers (AlgoFlowOrchestrator, StrategySelector)
     *  that bypass computeLiveIvRank still get the VIX-bucket fallback when
     *  the tracker has insufficient history. Optional — null-safe. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.risk.MarketGuard marketGuard;

    public IVRankTracker(com.algo.trade.persistence.IVSampleRepository ivSampleRepository,
                         com.algo.trade.persistence.GreeksSampleRepository greeksSampleRepository,
                         com.algo.trade.persistence.StrategyDecisionRepository strategyDecisionRepository) {
        this.ivSampleRepository = ivSampleRepository;
        this.greeksSampleRepository = greeksSampleRepository;
        this.strategyDecisionRepository = strategyDecisionRepository;
    }

    /** Load persisted IV history from DB on startup. */
    @jakarta.annotation.PostConstruct
    public void loadFromDb() {
        reloadFromDb();
    }

    /**
     * Public reload — replaces the in-memory cache with fresh rows from DB.
     * Called by {@code HistoricalVixIngestService.autoSeedIfEmpty()} after a
     * background bootstrap so the tracker picks up new samples without
     * requiring an app restart. Idempotent — replaces the deque atomically.
     */
    public synchronized void reloadFromDb() {
        for (IndexType idx : IndexType.values()) {
            var samples = ivSampleRepository.findByIndexTypeOrderBySampleDateAsc(idx.name());
            if (!samples.isEmpty()) {
                Deque<IVSample> deque = new ArrayDeque<>();
                for (var s : samples) {
                    deque.addLast(new IVSample(s.getSampleDate(), s.getIv()));
                }
                history.put(idx, deque);
                log.info("[IVRank] Loaded {} historical samples for {} from DB", samples.size(), idx);
            }
        }
    }

    /**
     * Record ATM IV for an index. Called many times per second from
     * {@code LiveInstrumentCache.updateFuturesPrice}.
     *
     * <p><strong>4 Jun 2026 fix (re-shipped):</strong> dedupe by calendar date.
     * Only ONE sample per day is retained — if today's sample exists, it is
     * OVERWRITTEN with the latest IV. Without this, every WebSocket tick
     * appends a new sample; once we exceed {@link #MAX_SAMPLES} the historical
     * 5-year backfill gets evicted from the front of the deque and IV rank
     * collapses to "current IV vs last N intraday ticks" instead of the
     * documented "current IV vs 52-week range".</p>
     */
    public void recordIV(IndexType indexType, double iv) {
        if (iv <= 0) return;
        Deque<IVSample> deque = history.computeIfAbsent(indexType, k -> new ArrayDeque<>());
        LocalDate today = LocalDate.now();
        IVSample last = deque.peekLast();
        if (last != null && today.equals(last.date())) {
            deque.pollLast();   // overwrite today's existing entry
        }
        deque.addLast(new IVSample(today, iv));
        while (deque.size() > MAX_SAMPLES) deque.pollFirst();
    }

    /** IV Rank 0–100. Returns 50 (neutral) if < 20 samples (insufficient history). */
    public double getIVRank(IndexType indexType) {
        List<IVSample> samples = getSamples(indexType);
        if (samples == null || samples.size() < 20) return 50.0;
        IVSample lastSample = samples.getLast();
        if (lastSample == null) return 50.0;
        double current = lastSample.iv();
        if (current <= 0) return 50.0;
        double low  = samples.stream().filter(Objects::nonNull)
                .mapToDouble(s -> s.iv() > 0 ? s.iv() : Double.MAX_VALUE).min().orElse(current);
        double high = samples.stream().filter(Objects::nonNull)
                .mapToDouble(s -> s.iv() > 0 ? s.iv() : 0).max().orElse(current);
        if (high <= low || high == 0) return 50.0;
        return ((current - low) / (high - low)) * 100.0;
    }

    /** IV Percentile — % of days IV was below current. */
    public double getIVPercentile(IndexType indexType) {
        List<IVSample> samples = getSamples(indexType);
        if (samples.size() < 20) return 50.0;
        // Guard against null samples produced during OOM recovery or partial snapshot writes
        IVSample last = samples.getLast();
        if (last == null) return 50.0;
        double current = last.iv();
        long below = samples.stream()
                .filter(s -> s != null && s.iv() < current)
                .count();
        return ((double) below / samples.size()) * 100.0;
    }

    public double getCurrentIV(IndexType indexType) {
        List<IVSample> s = getSamples(indexType);
        return s.isEmpty() ? 0 : s.getLast().iv();
    }

    public boolean isHighIV(IndexType indexType, double minRank) {
        return getIVRank(indexType) >= minRank;
    }

    public boolean isLowIV(IndexType indexType, double maxRank) {
        return getIVRank(indexType) <= maxRank;
    }

    /**
     * Number of historical IV samples currently available for an index.
     * Used by callers (e.g. {@code AlgoTradeExecution#computeLiveIvRank}) to
     * decide whether the tracker has enough history to be trusted over a
     * VIX-bucket proxy. Returns 0 if the index has no samples loaded yet.
     */
    public int getSampleCount(IndexType indexType) {
        List<IVSample> s = getSamples(indexType);
        return s == null ? 0 : s.size();
    }

    /**
     * True when there is enough history to return a meaningful IV rank.
     * Matches the internal threshold of {@link #getIVRank} (20 samples).
     */
    public boolean hasSufficientHistory(IndexType indexType) {
        return getSampleCount(indexType) >= 20;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    /** Persist current IV to DB every hour during market hours so restarts recover at most 1 hour of data. */
    @Scheduled(cron = "0 0 10,11,12,13,14 * * MON-FRI", zone = "Asia/Kolkata")
    public void recordIntradaySnapshot() {
        if (schedulerRegistry != null) schedulerRegistry.recordRun("ivRankTracker");
        persistCurrentIV(false);
    }

    /** End-of-day persistence with log + cleanup. */
    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void recordDailySnapshot() {
        history.forEach((index, samples) -> {
            if (!samples.isEmpty()) {
                log.info("[IVRank] {} IV={}% rank={}% pct={}%", index,
                        String.format("%.1f", samples.peekLast().iv()),
                        String.format("%.0f", getIVRank(index)),
                        String.format("%.0f", getIVPercentile(index)));
            }
        });
        persistCurrentIV(true);
    }

    private void persistCurrentIV(boolean cleanup) {
        history.forEach((index, samples) -> {
            if (samples.isEmpty()) return;
            double iv = samples.peekLast().iv();
            try {
                // Upsert: update today's row if it exists, otherwise insert
                var existing = ivSampleRepository.findByIndexTypeAndSampleDate(
                        index.name(), LocalDate.now());
                if (existing.isPresent()) {
                    existing.get().setIv(iv);
                    ivSampleRepository.save(existing.get());
                } else {
                    ivSampleRepository.save(
                            new com.algo.trade.persistence.IVSampleEntity(index.name(), LocalDate.now(), iv));
                }
                // cleanup block removed — repo methods unavailable

            } catch (Exception e) {
                log.debug("[IVRank] Failed to persist IV sample for {}: {}", index, e.getMessage());
            }
        });
        // cleanup block intentionally removed (repo APIs unavailable)

    }

    private List<IVSample> getSamples(IndexType indexType) {
        Deque<IVSample> deque = history.get(indexType);
        return deque == null ? List.of() : List.copyOf(deque);
    }
    private record IVSample(LocalDate date, double iv) {}
}
