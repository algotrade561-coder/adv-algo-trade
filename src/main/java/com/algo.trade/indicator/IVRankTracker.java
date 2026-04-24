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

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void recordDailySnapshot() {
        history.forEach((index, samples) -> {
            if (!samples.isEmpty()) {
                double iv = samples.peekLast().iv();
                log.info("[IVRank] {} IV={}% rank={}% pct={}%", index,
                        String.format("%.1f", iv),
                        String.format("%.0f", getIVRank(index)),
                        String.format("%.0f", getIVPercentile(index)));
            }
        });
    }

    private List<IVSample> getSamples(IndexType indexType) {
        Deque<IVSample> d = history.get(indexType);
        return d == null ? List.of() : new ArrayList<>(d);
    }

    private record IVSample(LocalDate date, double iv) {}
}
