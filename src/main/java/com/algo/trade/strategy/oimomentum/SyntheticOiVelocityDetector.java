package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import com.algo.trade.marketdata.LiveInstrumentCache;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
 * Synthetic OI-velocity detector — a tick-resolution build/unwind <em>proxy</em> from volume +
 * premium direction, usable in the sub-minute gaps between actual OI updates.
 *
 * <h2>OI cadence — EMPIRICALLY MEASURED (the old "~3-minute" folklore is WRONG for our feed)</h2>
 * Earlier comments (and TrueData/Zerodha lore) claimed NSE OI refreshes "only every ~3 minutes".
 * The bot's OWN per-tick capture disproves that for the Zerodha WebSocket full-mode feed we consume
 * ({@code KiteWebSocketClient.parsePacket} carries OI on every tick). On 2026-06-24, liquid ATM
 * NIFTY strikes showed ~400 distinct OI changes/session: mean gap ≈ 55s, ~54% of changes within
 * 60s, and up to <strong>9 distinct OI values in the busiest minute (~every 6-7s)</strong>, fastest
 * sub-second — impossible on a 3-minute cadence. So real OI updates ≈ once a minute, and every few
 * seconds during active bursts. (The "3 min" figure is the NSE website / a slower vendor feed, NOT
 * the live tick feed.) True per-tick OI is already captured by {@code AtmMicrostructureRecorder} →
 * {@code data/tuning/atm-microstructure-*.csv} (29-strike band); derive OI velocity/acceleration
 * from THAT stream, not from the coarse 5-min chain snapshots.
 *
 * <h2>Why this detector still exists</h2>
 * Even at ≈1/min, OI still lags the fastest ignition bars. Cumulative volume, premium direction and
 * the bid/ask queue advance on EVERY tick, so this proxy can flag a build/unwind a few seconds
 * ahead of the next OI change. It SUPPLEMENTS the directly-measured OI — it does not replace it.
 *
 * <h2>How it differs from {@link OiVelocityEarlyDetector}</h2>
 * {@code OiVelocityEarlyDetector} samples {@code getOpenInterest()} directly (which genuinely moves
 * ≈1/min, not once per 3 min). This detector instead derives flow from <em>volume</em>, which
 * advances on every tick, so it can react inside the sub-minute OI gaps.
 *
 * <h2>Classification (volume-weighted, per ATM-band leg, over a rolling window)</h2>
 * <pre>
 *   CE premium ↑ on volume  → CE demand   → BULLISH
 *   CE premium ↓ on volume  → CE writing   → BEARISH
 *   PE premium ↑ on volume  → PE demand   → BEARISH
 *   PE premium ↓ on volume  → PE writing   → BULLISH
 * </pre>
 * Each leg's contribution is weighted by its interval volume; the band is summed into bull/bear
 * flow, and the dominant side yields a direction + a 0..100 confidence.
 *
 * <h2>Honest limitations</h2>
 * Volume mixes opening and closing trades, and premium direction is partly driven by the
 * underlying's own move — so this is a <em>probabilistic proxy</em>, not a measurement of OI. It
 * will occasionally disagree with the eventual OI print. It is therefore feature-flagged
 * (default OFF); validate it against the per-tick OI (atm-microstructure capture) before trusting
 * it live — the OI cadence is ≈1/min, so the disagreement window is small.
 *
 * <p>Exception-safe; runs on the Spring scheduler (never the WS thread); holds no order path.</p>
 */
@Component
public class SyntheticOiVelocityDetector {

    private static final Logger log = LoggerFactory.getLogger(SyntheticOiVelocityDetector.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final LiveInstrumentCache cache;

    @Value("${oi-momentum.synthetic-oi-velocity.enabled:false}")
    private boolean enabled;

    @Value("${oi-momentum.synthetic-oi-velocity.underlyings:NIFTY,BANKNIFTY,SENSEX}")
    private String underlyingsCsv;

    /** Rolling flow window in seconds (how far back interval-volume / premium-change are measured). */
    @Value("${oi-momentum.synthetic-oi-velocity.window-sec:90}")
    private int windowSec;

    /** ATM ± this many strikes are considered (band width). */
    @Value("${oi-momentum.synthetic-oi-velocity.strikes-around:3}")
    private int strikesAround;

    /** Minimum total band interval-volume before a signal is considered meaningful. */
    @Value("${oi-momentum.synthetic-oi-velocity.min-interval-volume:0}")
    private long minIntervalVolume;

    /** Minimum confidence (0..100) before the signal reports a non-zero direction. */
    @Value("${oi-momentum.synthetic-oi-velocity.min-confidence:55}")
    private int minConfidence;

    private volatile Set<String> underlyings = Set.of("NIFTY", "BANKNIFTY", "SENSEX");

    /** token → rolling (ts, cumVolume, ltp) samples. */
    private final ConcurrentHashMap<Long, Deque<Sample>> history = new ConcurrentHashMap<>();
    /** index → latest computed signal. */
    private final ConcurrentHashMap<IndexType, Signal> latest = new ConcurrentHashMap<>();
    private volatile LocalDate currentDay = LocalDate.now(IST);

    public SyntheticOiVelocityDetector(LiveInstrumentCache cache) {
        this.cache = cache;
    }

    @jakarta.annotation.PostConstruct
    void init() {
        try {
            this.underlyings = Set.of(underlyingsCsv.toUpperCase(Locale.ROOT).replace(" ", "").split(","));
        } catch (Exception ignore) { /* keep default */ }
        if (enabled) {
            log.info("[SynthOI] enabled underlyings={} window={}s band=±{} minVol={} minConf={}",
                    underlyings, windowSec, strikesAround, minIntervalVolume, minConfidence);
        }
    }

    /** True when sampling is active (lets the strategy gate its bonus on the detector running). */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Per-2s sampler. Reads cumulative volume (tick-resolution) + premium direction for each
     * subscribed option and recomputes the per-index synthetic flow signal. Never throws.
     */
    @Scheduled(fixedDelayString = "${oi-momentum.synthetic-oi-velocity.sample-interval-ms:2000}",
            initialDelay = 20_000)
    public void sample() {
        if (!enabled) return;
        try {
            // Day rollover — drop stale rolling state so a new session starts clean.
            LocalDate today = LocalDate.now(IST);
            if (!today.equals(currentDay)) {
                resetForNewDay();
                currentDay = today;
            }
            long now = System.currentTimeMillis();
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
            log.debug("[SynthOI] sample skipped: {}", ex.toString());
        }
    }

    private void evaluateIndex(IndexType index, java.util.List<OptionInstrument> opts, long now) {
        double spot = cache.getFuturesPrice(index);
        if (spot <= 0) return;
        int atmStrike = nearestStrike(opts, spot);
        int interval = index.strikeInterval();
        int bandLo = atmStrike - strikesAround * interval;
        int bandHi = atmStrike + strikesAround * interval;
        long windowMs = windowSec * 1000L;

        double bullFlow = 0, bearFlow = 0;
        long totalIntervalVol = 0;
        for (OptionInstrument opt : opts) {
            int strike = opt.getStrikePrice();
            if (strike < bandLo || strike > bandHi) continue;

            long token = opt.getInstrumentToken();
            long cumVol = opt.getVolume();
            double ltp = opt.getLastPrice();
            Deque<Sample> hist = history.computeIfAbsent(token, k -> new ArrayDeque<>());
            hist.addLast(new Sample(now, cumVol, ltp));
            while (!hist.isEmpty() && now - hist.peekFirst().ts > windowMs) hist.pollFirst();

            Sample base = hist.peekFirst();
            if (base == null) continue;
            long intervalVol = cumVol - base.cumVol;
            if (intervalVol <= 0) continue;               // no fresh trading on this leg
            if (base.ltp <= 0) continue;
            double premChangePct = (ltp - base.ltp) / base.ltp * 100.0;
            if (Math.abs(premChangePct) < 1e-6) continue;  // flat premium → no directional read

            totalIntervalVol += intervalVol;
            boolean isCe = "CE".equalsIgnoreCase(opt.getOptionType());
            boolean premUp = premChangePct > 0;
            // Volume-weighted flow → underlying direction (see class javadoc matrix).
            if (isCe) {
                if (premUp) bullFlow += intervalVol;       // CE demand
                else        bearFlow += intervalVol;        // CE writing
            } else {
                if (premUp) bearFlow += intervalVol;        // PE demand
                else        bullFlow += intervalVol;        // PE writing
            }
        }

        double dominant = Math.max(bullFlow, bearFlow);
        double sum = bullFlow + bearFlow;
        int direction = bullFlow > bearFlow ? 1 : (bearFlow > bullFlow ? -1 : 0);
        int confidence = sum > 0 ? (int) Math.round(dominant / sum * 100.0) : 0;

        boolean fires = direction != 0
                && totalIntervalVol >= minIntervalVolume
                && confidence >= minConfidence;

        Signal sig = new Signal(Instant.ofEpochMilli(now), index.name(),
                fires ? direction : 0, fires ? confidence : 0, totalIntervalVol, atmStrike);
        latest.put(index, sig);
    }

    /**
     * Latest synthetic flow signal for an index, or a neutral signal if none computed yet.
     * Safe to call from the strategy thread.
     */
    public Signal getLatest(IndexType index) {
        Signal s = latest.get(index);
        return s != null ? s : Signal.neutral(index);
    }

    /**
     * Convenience for bias scoring: a non-negative bonus magnitude when the synthetic flow agrees
     * with {@code momentumDir}, else 0. The caller scales/labels it. Returns 0 when disabled or no
     * confident signal exists.
     */
    public int alignmentBonus(IndexType index, int momentumDir, int maxBonus) {
        if (!enabled || momentumDir == 0 || maxBonus <= 0) return 0;
        Signal s = getLatest(index);
        if (s.direction() != momentumDir || s.confidence() <= 0) return 0;
        // Scale the bonus by how far confidence sits above the firing floor.
        double span = Math.max(1, 100 - minConfidence);
        double frac = Math.min(1.0, Math.max(0.0, (s.confidence() - minConfidence) / span));
        return (int) Math.round(maxBonus * (0.5 + 0.5 * frac)); // half the bonus at the floor, full at 100%
    }

    public void resetForNewDay() {
        history.clear();
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

    /** Rolling per-token sample: exchange-driven cumulative volume + last price at a wall-clock ts. */
    private record Sample(long ts, long cumVol, double ltp) {}

    /**
     * Synthetic flow signal for one index.
     *
     * @param direction  +1 bullish / -1 bearish / 0 none (0 unless it cleared the confidence + volume floors)
     * @param confidence 0..100 — share of band flow on the dominant side
     * @param intervalVolume total ATM-band volume traded in the window (the freshness/strength gauge)
     * @param atmStrike  the ATM strike used for the band
     */
    public record Signal(Instant at, String index, int direction, int confidence,
                         long intervalVolume, int atmStrike) {
        static Signal neutral(IndexType idx) {
            return new Signal(Instant.now(), idx.name(), 0, 0, 0L, 0);
        }
    }
}
