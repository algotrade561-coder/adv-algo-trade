package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * SHADOW-ONLY early-detection module (Workstream D).
 *
 * <p>Goal: detect a directional move <i>before price prints it</i> by reading per-second
 * open-interest velocity (ΔOI faster than the 5-minute {@code ChainSnapshot} floor) plus a
 * microprice/queue-imbalance confirmation, while spot is still coiling. This attacks the
 * documented OI Momentum lag (the May-22 case: chain tilted +619% for 90 min before entry).</p>
 *
 * <p><b>Guardrails — this module never trades.</b></p>
 * <ul>
 *   <li>Disabled by default ({@code oi-momentum.early-detection.enabled=false}).</li>
 *   <li>{@code shadow-only} (default true) is a hard guard: when set, the detector ONLY logs
 *       virtual signals via {@link EarlyDetectionShadowRecorder}. It holds no reference to the
 *       execution engine / order path and is not wired into {@code V3EntryPipeline} or
 *       {@code OIMomentumStrategy}. The live CASE 1–5 entry path is completely untouched.</li>
 *   <li>The whole loop is exception-safe and runs on the Spring scheduler, not the WS thread.</li>
 * </ul>
 *
 * <p>Thresholds here are deliberately coarse placeholders — calibration is the job of the
 * offline event study (Workstream C) over the captured microstructure data. Promotion to live
 * is a separate, future decision gated on validated predictive edge.</p>
 */
@Component
public class OiVelocityEarlyDetector {

    private static final Logger log = LoggerFactory.getLogger(OiVelocityEarlyDetector.class);
    private static final long COIL_WINDOW_MS = 20 * 60 * 1000L;

    private final LiveInstrumentCache cache;
    private final EarlyDetectionShadowRecorder shadowRecorder;

    @Value("${oi-momentum.early-detection.enabled:false}")
    private boolean enabled;

    /** Hard guardrail — must stay true until the signal is validated. Live wiring is out of scope. */
    @Value("${oi-momentum.early-detection.shadow-only:true}")
    private boolean shadowOnly;

    @Value("${oi-momentum.early-detection.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private String underlyingsCsv;

    @Value("${oi-momentum.early-detection.oi-velocity-window-sec:60}")
    private int oiWindowSec;

    @Value("${oi-momentum.early-detection.oi-velocity-threshold-pct:5.0}")
    private double oiVelocityThresholdPct;

    @Value("${oi-momentum.early-detection.coil-max-range-pct:0.10}")
    private double coilMaxRangePct;

    @Value("${oi-momentum.early-detection.imbalance-threshold:0.60}")
    private double imbalanceThreshold;

    private volatile Set<String> underlyings = Set.of("NIFTY", "BANKNIFTY", "SENSEX");

    // token → rolling OI samples
    private final ConcurrentHashMap<Long, Deque<OiSample>> oiHistory = new ConcurrentHashMap<>();
    // index → rolling spot samples (for coil-range detection)
    private final ConcurrentHashMap<IndexType, Deque<SpotSample>> spotHistory = new ConcurrentHashMap<>();
    // index → last virtual-fire epoch ms (per-index throttle)
    private final ConcurrentHashMap<IndexType, Long> lastFireMs = new ConcurrentHashMap<>();
    // index → last gate-diagnostic log epoch ms (throttle for calibration logging)
    private final ConcurrentHashMap<IndexType, Long> lastDiagMs = new ConcurrentHashMap<>();
    private static final long DIAG_THROTTLE_MS = 30_000L;
    // index → latest virtual signal (exposed for future use; NOT consumed by the live path)
    private final ConcurrentHashMap<IndexType, EarlyDetectionShadowRecorder.VirtualSignal> latest =
            new ConcurrentHashMap<>();

    private static final long FIRE_THROTTLE_MS = 20_000L; // was 60s — reduced for faster operator response

    public OiVelocityEarlyDetector(LiveInstrumentCache cache,
                                   EarlyDetectionShadowRecorder shadowRecorder) {
        this.cache = cache;
        this.shadowRecorder = shadowRecorder;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            this.underlyings = Set.of(underlyingsCsv.toUpperCase(Locale.ROOT).replace(" ", "").split(","));
        } catch (Exception ignore) { /* keep default */ }
        if (enabled) {
            log.info("[EarlyDetect] enabled (shadowOnly={}) underlyings={} oiWindow={}s thr={}% coilMax={}% imb={}",
                    shadowOnly, underlyings, oiWindowSec, oiVelocityThresholdPct, coilMaxRangePct, imbalanceThreshold);
        }
    }

    /**
     * Per-second sampler. Computes OI velocity + coil + imbalance and emits a VIRTUAL signal
     * when all align. Never throws; never trades.
     */
    @Scheduled(fixedDelayString = "${oi-momentum.early-detection.sample-interval-ms:1000}",
            initialDelay = 20_000)
    public void sample() {
        if (!enabled) return;
        try {
            long now = System.currentTimeMillis();
            // Group subscribed options by index (configured underlyings only).
            ConcurrentHashMap<IndexType, java.util.List<OptionInstrument>> byIndex = new ConcurrentHashMap<>();
            for (OptionInstrument opt : cache.allOptions()) {
                if (opt == null || opt.getIndexType() == null) continue;
                if (!underlyings.contains(opt.getIndexType().name())) continue;
                byIndex.computeIfAbsent(opt.getIndexType(), k -> new java.util.ArrayList<>()).add(opt);
            }
            for (var e : byIndex.entrySet()) {
                evaluateIndex(e.getKey(), e.getValue(), now);
            }
        } catch (Exception ex) {
            log.debug("[EarlyDetect] sample skipped: {}", ex.toString());
        }
    }

    private void evaluateIndex(IndexType index, java.util.List<OptionInstrument> opts, long now) {
        double spot = cache.getFuturesPrice(index);
        if (spot <= 0) return;

        // Coil range over the trailing 20 minutes.
        Deque<SpotSample> spots = spotHistory.computeIfAbsent(index, k -> new ArrayDeque<>());
        spots.addLast(new SpotSample(now, spot));
        while (!spots.isEmpty() && now - spots.peekFirst().ts > COIL_WINDOW_MS) spots.pollFirst();
        double hi = spot, lo = spot;
        for (SpotSample s : spots) { if (s.spot > hi) hi = s.spot; if (s.spot < lo) lo = s.spot; }
        double coilRangePct = spot > 0 ? (hi - lo) / spot * 100.0 : Double.MAX_VALUE;

        // OI velocity over the configured window.
        long windowMs = oiWindowSec * 1000L;
        int atmStrike = nearestStrike(opts, spot);
        double sumCeDelta = 0, sumPeDelta = 0;
        long baselineOi = 0;
        long maxCeOi = -1, maxPeOi = -1;
        int resistanceStrike = atmStrike, supportStrike = atmStrike;
        OptionInstrument atmCe = null, atmPe = null;

        for (OptionInstrument opt : opts) {
            long token = opt.getInstrumentToken();
            long oiNow = opt.getOpenInterest();
            Deque<OiSample> hist = oiHistory.computeIfAbsent(token, k -> new ArrayDeque<>());
            hist.addLast(new OiSample(now, oiNow));
            while (!hist.isEmpty() && now - hist.peekFirst().ts > windowMs) hist.pollFirst();
            long oiThen = hist.peekFirst() != null ? hist.peekFirst().oi : oiNow;
            long delta = oiNow - oiThen;
            baselineOi += oiThen;

            boolean isCe = "CE".equalsIgnoreCase(opt.getOptionType());
            if (isCe) {
                sumCeDelta += delta;
                if (oiNow > maxCeOi) { maxCeOi = oiNow; resistanceStrike = opt.getStrikePrice(); }
                if (opt.getStrikePrice() == atmStrike) atmCe = opt;
            } else {
                sumPeDelta += delta;
                if (oiNow > maxPeOi) { maxPeOi = oiNow; supportStrike = opt.getStrikePrice(); }
                if (opt.getStrikePrice() == atmStrike) atmPe = opt;
            }
        }

        // Directional scores mirroring OiPattern semantics:
        //   bullish = PE building (support) + CE unwinding (call writers covering)
        //   bearish = CE building (resistance) + PE unwinding (put writers cashing out)
        double bullScore = Math.max(0, sumPeDelta) + Math.max(0, -sumCeDelta);
        double bearScore = Math.max(0, sumCeDelta) + Math.max(0, -sumPeDelta);
        int direction = bullScore > bearScore ? 1 : (bearScore > bullScore ? -1 : 0);
        double dominantDelta = Math.max(bullScore, bearScore);
        double velocityPct = baselineOi > 0 ? dominantDelta / baselineOi * 100.0 : 0.0;

        if (direction == 0) return;

        // Microprice/queue-imbalance confirmation on the ATM leg aligned with the direction.
        OptionInstrument leg = direction > 0 ? atmCe : atmPe;
        double imbalance = legBidImbalance(leg);

        boolean oiFires = velocityPct >= oiVelocityThresholdPct;
        boolean coils = coilRangePct <= coilMaxRangePct;
        boolean imbalanceConfirms = imbalance >= imbalanceThreshold;

        // Per-gate observability: throttled so calibration can see WHICH gate dominates rejections
        // without flooding logs. (Shadow-only module — purely diagnostic.)
        Long lastDiag = lastDiagMs.get(index);
        if (lastDiag == null || now - lastDiag >= DIAG_THROTTLE_MS) {
            lastDiagMs.put(index, now);
            log.debug("[EarlyDetect][gate] {} oiVel={}%/{} coil={}%/{} imb={}/{} -> oi={} coil={} imb={}",
                    index, String.format(Locale.ROOT, "%.2f", velocityPct), oiVelocityThresholdPct,
                    String.format(Locale.ROOT, "%.3f", coilRangePct), coilMaxRangePct,
                    String.format(Locale.ROOT, "%.2f", imbalance), imbalanceThreshold,
                    oiFires, coils, imbalanceConfirms);
        }

        // Gate (relaxed 2026-06-27): OI velocity is the primary trigger; coil + imbalance are
        // CONFIRMATIONS, not all-required. Requiring all three simultaneously (old `&&`) made the
        // joint fire-rate effectively zero. Require the primary OI-velocity build plus at least one
        // confirmation. The full component values are still recorded on the VirtualSignal below, so
        // a stricter rule can be applied offline during calibration. (Shadow-only — no order path.)
        if (!(oiFires && (coils || imbalanceConfirms))) return;

        // Per-index throttle so one buildup doesn't spam virtual signals.
        Long last = lastFireMs.get(index);
        if (last != null && now - last < FIRE_THROTTLE_MS) return;
        lastFireMs.put(index, now);

        EarlyDetectionShadowRecorder.VirtualSignal sig = new EarlyDetectionShadowRecorder.VirtualSignal(
                Instant.ofEpochMilli(now), index.name(), direction, velocityPct,
                direction > 0 ? "CE_BIAS" : "PE_BIAS", supportStrike, resistanceStrike,
                atmStrike, spot, coilRangePct, imbalance, "OI_VELOCITY+MICROPRICE");
        latest.put(index, sig);

        // SHADOW: log only. No order path is ever invoked from here.
        if (shadowOnly) {
            shadowRecorder.record(sig);
            log.info("[EarlyDetect][SHADOW] {} dir={} oiVel={}% coil={}% imb={} atm={} (VIRTUAL — no order)",
                    index, direction, String.format(Locale.ROOT, "%.2f", velocityPct),
                    String.format(Locale.ROOT, "%.3f", coilRangePct),
                    String.format(Locale.ROOT, "%.2f", imbalance), atmStrike);
        } else {
            // Live promotion is intentionally NOT implemented in this session. Record + warn so a
            // misconfiguration (enabled + shadow-only=false) can never silently place an order.
            shadowRecorder.record(sig);
            log.warn("[EarlyDetect] shadow-only=false but live wiring is not implemented — logging only, no order placed.");
        }
    }

    /** Latest virtual signal for an index (for future consumers; not used by the live path). */
    public EarlyDetectionShadowRecorder.VirtualSignal getLatest(IndexType index) {
        return latest.get(index);
    }

    /** Reset rolling state at the start of a new trading day. */
    public void resetForNewDay() {
        oiHistory.clear();
        spotHistory.clear();
        lastFireMs.clear();
        lastDiagMs.clear();
        latest.clear();
    }

    private static int nearestStrike(java.util.List<OptionInstrument> opts, double spot) {
        int best = 0;
        double bestDist = Double.MAX_VALUE;
        Set<Integer> seen = new HashSet<>();
        for (OptionInstrument o : opts) {
            int strike = o.getStrikePrice();
            if (!seen.add(strike)) continue;
            double d = Math.abs(strike - spot);
            if (d < bestDist) { bestDist = d; best = strike; }
        }
        return best;
    }

    private static double legBidImbalance(OptionInstrument leg) {
        if (leg == null) return 0.0;
        long bid = leg.getBestBidQty();
        long ask = leg.getBestAskQty();
        long total = bid + ask;
        return total > 0 ? (double) bid / total : 0.0;
    }

    private record OiSample(long ts, long oi) {}
    private record SpotSample(long ts, double spot) {}
}
