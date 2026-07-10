package com.algo.trade.indicator;

import com.algo.trade.persistence.IVSampleEntity;
import com.algo.trade.persistence.IVSampleRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Clean India-VIX daily series for the MarketGuard percentile gate — deliberately decoupled from
 * {@link IVRankTracker}, whose live samples are the ATM call's <em>implied volatility</em>
 * (volatile near weekly expiry) rather than India VIX. Mixing those two series is what made the
 * dashboard IV Rank lurch and would have made the dynamic gate inaccurate.
 *
 * <p>This series is pure India VIX, read from the dedicated {@code INDIA_VIX} key only:
 * <ul>
 *   <li><b>Historical backbone</b> — {@code INDIA_VIX} rows seeded from Yahoo {@code ^INDIAVIX}
 *       by {@code HistoricalVixIngestService} (unscaled). The per-index keys (NIFTY/…) are
 *       deliberately NOT read here, because {@code IVRankTracker} overwrites them with live ATM
 *       IV — which is exactly the contamination this series avoids (Gap 2 fix).</li>
 *   <li><b>Live</b> — the actual India-VIX tick ({@code MarketGuard.getCurrentVix()}) recorded
 *       end-of-day under the same {@code INDIA_VIX} key. No ATM-IV ever enters this series.</li>
 * </ul>
 *
 * <p>The gate keys on {@link #percentile} (% of recent sessions below the live VIX), which is
 * robust to extremes — unlike IV Rank, which depends only on the window min/max.</p>
 */
@Component
public class IndiaVixHistory {

    private static final Logger log = LoggerFactory.getLogger(IndiaVixHistory.class);
    static final String KEY = "INDIA_VIX";
    private static final int MAX_SAMPLES = 504;           // ~2 trading years retained
    private static final int DEFAULT_WINDOW = 252;        // 52-week percentile window

    private final IVSampleRepository repository;
    private final Deque<Sample> history = new ConcurrentLinkedDeque<>();

    private record Sample(LocalDate date, double vix) {}

    public IndiaVixHistory(IVSampleRepository repository) {
        this.repository = repository;
    }

    /** (Re)load the clean INDIA_VIX series from the DB. Called at startup and after a seed. */
    @PostConstruct
    public synchronized void load() {
        try {
            // Read the dedicated INDIA_VIX key ONLY — never the per-index keys (ATM-IV polluted).
            Map<LocalDate, Double> byDate = new TreeMap<>();
            for (IVSampleEntity r : repository.findByIndexTypeOrderBySampleDateAsc(KEY)) {
                if (r.getIv() > 0) byDate.put(r.getSampleDate(), r.getIv());
            }
            history.clear();
            byDate.forEach((d, v) -> history.addLast(new Sample(d, v)));
            while (history.size() > MAX_SAMPLES) history.pollFirst();
            log.info("[IndiaVixHistory] loaded {} clean India-VIX sessions ({} → {})",
                    history.size(),
                    history.isEmpty() ? "-" : history.peekFirst().date(),
                    history.isEmpty() ? "-" : history.peekLast().date());
        } catch (Exception e) {
            log.warn("[IndiaVixHistory] load failed (gate will fall back to fixed thresholds): {}", e.getMessage());
        }
    }

    /** Reload after the INDIA_VIX series is (re)seeded, so the gate picks it up without a restart. */
    public void reload() {
        load();
    }

    /** Record the live India-VIX EOD value into the clean series (memory + DB upsert under INDIA_VIX). */
    public synchronized void record(double vix) {
        if (vix <= 0) return;
        LocalDate today = LocalDate.now();
        if (!history.isEmpty() && today.equals(history.peekLast().date())) {
            history.pollLast();   // overwrite today's existing value
        }
        history.addLast(new Sample(today, vix));
        while (history.size() > MAX_SAMPLES) history.pollFirst();
        try {
            repository.findByIndexTypeAndSampleDate(KEY, today).ifPresentOrElse(
                    e -> { e.setIv(vix); repository.save(e); },
                    () -> repository.save(new IVSampleEntity(KEY, today, vix)));
        } catch (Exception e) {
            log.debug("[IndiaVixHistory] persist failed (in-memory series still updated): {}", e.getMessage());
        }
    }

    /**
     * Percentile rank (0–100) of {@code value} — the live India VIX — against the trailing
     * {@code window} clean sessions: % of those sessions whose VIX was below {@code value}.
     * Returns {@code -1} when {@code value <= 0} or there are fewer than 20 sessions, so the
     * caller falls back to fixed thresholds rather than trust a thin sample.
     */
    public double percentile(double value, int window) {
        if (value <= 0) return -1;
        List<Sample> all = new ArrayList<>(history);
        if (all.size() < 20) return -1;
        int from = Math.max(0, all.size() - Math.max(1, window));
        List<Sample> win = all.subList(from, all.size());
        long below = win.stream().filter(s -> s.vix() > 0 && s.vix() < value).count();
        return ((double) below / win.size()) * 100.0;
    }

    /** 52-week (252-session) percentile of {@code value}. */
    public double percentile(double value) {
        return percentile(value, DEFAULT_WINDOW);
    }

    public int sampleCount() {
        return history.size();
    }

    /** Most recent recorded India-VIX value (last EOD/live sample), or -1 if the series is empty.
     *  Used as a cold-start fallback when the live VIX tick is unavailable. */
    public double lastValue() {
        Sample s = history.peekLast();
        return s != null ? s.vix() : -1;
    }
}
