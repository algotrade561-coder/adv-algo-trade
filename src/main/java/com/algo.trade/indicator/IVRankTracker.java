package com.algo.trade.indicator;

import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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

    /** Record ATM IV for an index. Called when ATM option IV is calculated. */
    public void recordIV(IndexType indexType, double iv) {
        if (iv <= 0) return;
        Deque<IVSample> deque = history.computeIfAbsent(indexType, k -> new ArrayDeque<>());
        deque.addLast(new IVSample(LocalDate.now(), iv));
        while (deque.size() > MAX_SAMPLES) deque.pollFirst();
    }

    /** IV Rank 0–100. Returns 50 (neutral) if < 5 samples. */
    public double getIVRank(IndexType indexType) {
        List<IVSample> samples = getSamples(indexType);
        if (samples.size() < 5) return 50.0;
        double current = samples.getLast().iv();
        double low  = samples.stream().mapToDouble(IVSample::iv).min().orElse(current);
        double high = samples.stream().mapToDouble(IVSample::iv).max().orElse(current);
        if (high == low) return 50.0;
        return ((current - low) / (high - low)) * 100.0;
    }

    /** IV Percentile — % of days IV was below current. */
    public double getIVPercentile(IndexType indexType) {
        List<IVSample> samples = getSamples(indexType);
        if (samples.size() < 5) return 50.0;
        double current = samples.getLast().iv();
        long below = samples.stream().filter(s -> s.iv() < current).count();
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

    /** Persist current IV to DB every hour during market hours so restarts recover at most 1 hour of data. */
    @Scheduled(cron = "0 0 10,11,12,13,14 * * MON-FRI", zone = "Asia/Kolkata")
    public void recordIntradaySnapshot() {
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
                if (cleanup) {
                    ivSampleRepository.deleteByIndexTypeAndSampleDateBefore(
                            index.name(), LocalDate.now().minusDays(365));
                    greeksSampleRepository.deleteByIndexTypeAndCapturedAtBefore(
                            index.name(), java.time.Instant.now().minus(
                                    java.time.Duration.ofDays(GREEKS_RETENTION_DAYS)));
                }

            } catch (Exception e) {
                log.debug("[IVRank] Failed to persist IV sample for {}: {}", index, e.getMessage());
            }
        });

        if (cleanup) {
            try {
                java.time.Instant decisionCutoff = java.time.Instant.now()
                        .minus(java.time.Duration.ofDays(DECISIONS_RETENTION_DAYS));
                strategyDecisionRepository.deleteByTimestampBefore(decisionCutoff);
                log.info("[EOD cleanup] Deleted strategy decisions older than {} days", DECISIONS_RETENTION_DAYS);
            } catch (Exception e) {
                log.warn("[EOD cleanup] Strategy decision purge failed: {}", e.getMessage());
            }
        }
    }

    private List<IVSample> getSamples(IndexType indexType) {
        Deque<IVSample> d = history.get(indexType);
        return d == null ? List.of() : new ArrayList<>(d);
    }

    private record IVSample(LocalDate date, double iv) {}
}
