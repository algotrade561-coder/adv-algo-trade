package com.algo.trade.risk;

import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * §3.7 (2026-06-27) — rolling realized-quality tracker per signal source, the foundation for
 * MFE/PnL-aware lot sizing.
 *
 * <p>The data showed nearly half of all entries came from the <b>weakest</b> source (oi_momentum,
 * near-zero forward MFE) while the best sources fired rarely. Capital is currently allocated
 * source-agnostically. This tracker records each source's recent realized P&amp;L% and exposes a
 * lot multiplier that down-weights chronically weak sources and up-weights strong ones.</p>
 *
 * <p><b>Default OFF</b> ({@code trading.source-sizing.enabled=false}) and a no-op (multiplier 1.0)
 * until it has {@code min-samples} of data — so it changes no live behaviour until deliberately
 * enabled and backtested. It is intentionally a heuristic v1; calibrate the bounds with real data.</p>
 */
@Component
public class SourcePerformanceTracker {

    private static final Logger log = LoggerFactory.getLogger(SourcePerformanceTracker.class);

    @Value("${trading.source-sizing.enabled:false}")
    private boolean enabled;
    @Value("${trading.source-sizing.min-samples:20}")
    private int minSamples;
    @Value("${trading.source-sizing.window:50}")
    private int window;
    @Value("${trading.source-sizing.min-multiplier:0.5}")
    private double minMultiplier;
    @Value("${trading.source-sizing.max-multiplier:1.5}")
    private double maxMultiplier;

    /** source → rolling realized P&L% of its recent closed trades. */
    private final Map<String, Deque<Double>> pnlBySource = new ConcurrentHashMap<>();

    /** Record a closed trade's realized P&L percent under its signal source. Never throws. */
    public void record(String source, double realizedPnlPct) {
        if (source == null || source.isBlank() || Double.isNaN(realizedPnlPct)) return;
        Deque<Double> dq = pnlBySource.computeIfAbsent(source, k -> new ConcurrentLinkedDeque<>());
        dq.addLast(realizedPnlPct);
        while (dq.size() > Math.max(1, window)) dq.pollFirst();
    }

    /**
     * Lot multiplier in [{@code min}, {@code max}] for a source, scaling its recent average realized
     * P&L% against the cross-source average. Returns 1.0 when disabled or under {@code min-samples}
     * (no behavioural change). A net-losing source floors at {@code min}; a source beating the global
     * average scales toward {@code max}.
     */
    public double lotMultiplier(String source) {
        if (!enabled || source == null) return 1.0;
        Deque<Double> dq = pnlBySource.get(source);
        if (dq == null || dq.size() < minSamples) return 1.0;
        double srcAvg = avg(dq);
        if (srcAvg <= 0) return minMultiplier;             // chronically losing → minimum size
        double globalAvg = globalAvg();
        double ref = globalAvg > 0 ? globalAvg : srcAvg;   // no positive global ref → don't penalise
        double ratio = srcAvg / ref;
        return Math.max(minMultiplier, Math.min(maxMultiplier, ratio));
    }

    private static double avg(Deque<Double> dq) {
        double sum = 0; int n = 0;
        for (double v : dq) { sum += v; n++; }
        return n > 0 ? sum / n : 0.0;
    }

    private double globalAvg() {
        double sum = 0; int n = 0;
        for (Deque<Double> dq : pnlBySource.values()) {
            for (double v : dq) { sum += v; n++; }
        }
        return n > 0 ? sum / n : 0.0;
    }

    /** Diagnostic snapshot for the dashboard / tuning report. */
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("enabled", enabled);
        Map<String, Object> sources = new LinkedHashMap<>();
        pnlBySource.forEach((src, dq) -> {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("samples", dq.size());
            s.put("avgPnlPct", Math.round(avg(dq) * 100.0) / 100.0);
            s.put("lotMultiplier", Math.round(lotMultiplier(src) * 100.0) / 100.0);
            sources.put(src, s);
        });
        out.put("sources", sources);
        return out;
    }
}
