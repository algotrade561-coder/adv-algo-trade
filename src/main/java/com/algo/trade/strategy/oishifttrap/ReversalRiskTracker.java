package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * R1+R2+R3+R4 — Reversal Risk Tracker.
 *
 * <p>Built 2 Jun 2026 after the 12:30 NIFTY reversal trapped OIST in a series
 * of doomed PE BUY signals. Three independent leading indicators were forming
 * in the chain microstructure pre-12:30 that the system didn't compute:</p>
 *
 * <ul>
 *   <li><b>R1 MaxPainDrift</b> — max-pain shifted 23,300 → 23,500 over the
 *       day. Each upward shift = institutional OI cluster migrating up.</li>
 *   <li><b>R2 IvSkewMonitor</b> — peIV(ATM−50) − ceIV(ATM+50) turned strongly
 *       negative (−1.41 at 12:45) = dealers pricing UP-move risk premium.</li>
 *   <li><b>R3 WallMigration</b> — CE wall + PE wall both migrated UP one
 *       strike each between 12:35–12:45 = entire OI cluster rotation.</li>
 *   <li><b>R4 OiFlowInversion</b> — PE 23,250 OI Δ flipped from sustained
 *       +1M/bar to −2.1M at 12:35 = writers giving up the strike.</li>
 * </ul>
 *
 * <p>Combined into a composite 0–100 score per index per direction. At ≥ 50
 * → log WARN + block new entries opposite to drift. At ≥ 75 → close existing
 * positions opposite to drift.</p>
 *
 * <p>Sampled once per minute from {@link com.algo.trade.strategy.oimomentum.v3.V3ContextFeeder}.
 * Each tick reads the latest {@link ChainSnapshot} and computes the four
 * sub-metrics, pushing into per-index ring buffers (30-min retention).</p>
 */
@Service
public class ReversalRiskTracker {

    private static final Logger log = LoggerFactory.getLogger(ReversalRiskTracker.class);

    /** History retained per index — 30 minutes ≥ 30 samples at 1/min. */
    private static final int RETENTION_MIN = 30;

    /** Per-index history of max-pain strike. */
    private final Map<IndexType, Deque<TimedValue>> maxPainHistory = new EnumMap<>(IndexType.class);
    /** Per-index history of IV skew = peIV(ATM−1) − ceIV(ATM+1). */
    private final Map<IndexType, Deque<TimedValue>> skewHistory = new EnumMap<>(IndexType.class);
    /** Per-index history of CE wall strike. */
    private final Map<IndexType, Deque<TimedValue>> ceWallHistory = new EnumMap<>(IndexType.class);
    /** Per-index history of PE wall strike. */
    private final Map<IndexType, Deque<TimedValue>> peWallHistory = new EnumMap<>(IndexType.class);
    /** Per-index per-strike history of PE OI (the strikes OIST might trap). */
    private final Map<IndexType, Map<Integer, Deque<TimedValue>>> peOiByStrike = new EnumMap<>(IndexType.class);
    /** Per-index per-strike history of CE OI. */
    private final Map<IndexType, Map<Integer, Deque<TimedValue>>> ceOiByStrike = new EnumMap<>(IndexType.class);

    public ReversalRiskTracker() {
        for (IndexType ix : IndexType.values()) {
            maxPainHistory.put(ix, new ArrayDeque<>());
            skewHistory.put(ix, new ArrayDeque<>());
            ceWallHistory.put(ix, new ArrayDeque<>());
            peWallHistory.put(ix, new ArrayDeque<>());
            peOiByStrike.put(ix, new HashMap<>());
            ceOiByStrike.put(ix, new HashMap<>());
        }
    }

    /** Composite reversal-risk score and component breakdown. */
    public record Score(
            int total,            // 0–100 against the given direction
            int maxPainComp,      // 0–30
            int skewComp,         // 0–25
            int wallComp,         // 0–25
            int oiFlowComp,       // 0–20
            String detail         // human-readable description
    ) {
        public static Score zero() { return new Score(0, 0, 0, 0, 0, "no_history"); }
    }

    /**
     * Called once per minute (or as fast as the snapshot tape arrives). Computes
     * the four sub-metrics from the latest snapshot and updates the ring buffers.
     */
    public synchronized void recordSnapshot(IndexType ix, ChainSnapshot snap) {
        if (snap == null || ix == null) return;
        Instant now = Instant.now();
        double spot = snap.spot();
        int atm = snap.atmStrike();
        int strikeStep = ix.strikeInterval();

        // Build lookup by strike
        Map<Integer, ChainSnapshot.StrikeData> rows = new HashMap<>();
        for (ChainSnapshot.StrikeData s : snap.strikes()) rows.put(s.strike(), s);

        // R1: max-pain strike
        int maxPain = computeMaxPain(snap);
        push(maxPainHistory.get(ix), now, maxPain);

        // R2: IV skew = peIV(ATM-1) - ceIV(ATM+1)
        ChainSnapshot.StrikeData peBelow = rows.get(atm - strikeStep);
        ChainSnapshot.StrikeData ceAbove = rows.get(atm + strikeStep);
        double skew = (peBelow == null ? 0 : peBelow.peIV()) - (ceAbove == null ? 0 : ceAbove.ceIV());
        push(skewHistory.get(ix), now, skew);

        // R3: wall positions
        int ceWall = 0, peWall = 0;
        long maxCeOi = -1, maxPeOi = -1;
        for (ChainSnapshot.StrikeData s : snap.strikes()) {
            if (s.strike() > spot && s.ceOI() > maxCeOi) { maxCeOi = s.ceOI(); ceWall = s.strike(); }
            if (s.strike() < spot && s.peOI() > maxPeOi) { maxPeOi = s.peOI(); peWall = s.strike(); }
        }
        push(ceWallHistory.get(ix), now, ceWall);
        push(peWallHistory.get(ix), now, peWall);

        // R4: per-strike OI flow for nearby strikes (±3 of ATM is enough for OIST candidates)
        Map<Integer, Deque<TimedValue>> peMap = peOiByStrike.get(ix);
        Map<Integer, Deque<TimedValue>> ceMap = ceOiByStrike.get(ix);
        for (int off = -3; off <= 3; off++) {
            int strike = atm + off * strikeStep;
            ChainSnapshot.StrikeData row = rows.get(strike);
            if (row == null) continue;
            peMap.computeIfAbsent(strike, k -> new ArrayDeque<>());
            ceMap.computeIfAbsent(strike, k -> new ArrayDeque<>());
            push(peMap.get(strike), now, row.peOI());
            push(ceMap.get(strike), now, row.ceOI());
        }

        if (log.isDebugEnabled()) {
            log.debug("[ReversalRisk][{}] sampled — spot={} atm={} maxPain={} skew={} ceWall={} peWall={}",
                    ix, spot, atm, maxPain, String.format("%.2f", skew), ceWall, peWall);
        }
    }

    /**
     * Compute the reversal-risk score for an OIST candidate position.
     *
     * @param ix         index
     * @param trapSide   "PE" if OIST wants to BUY PE (bearish-on-spot bet);
     *                   "CE" if BUY CE (bullish bet)
     * @param trapStrike the candidate strike OIST is buying against
     * @return composite Score 0–100
     */
    public synchronized Score score(IndexType ix, String trapSide, int trapStrike) {
        // direction the OIST position needs the market to go:
        //   PE BUY → wants spot to FALL (or strike to be hit from above)
        //   CE BUY → wants spot to RISE
        // Reversal risk = market moving the OTHER direction.
        boolean wantsDown = "PE".equals(trapSide);

        int mp = scoreMaxPainComp(ix, wantsDown);
        int sk = scoreSkewComp(ix, wantsDown);
        int wl = scoreWallComp(ix, wantsDown);
        int oi = scoreOiFlowComp(ix, trapStrike, wantsDown);
        int total = mp + sk + wl + oi;

        String detail = String.format("MP=%d Skew=%d Wall=%d OiFlow=%d", mp, sk, wl, oi);
        return new Score(total, mp, sk, wl, oi, detail);
    }

    // ── R1: max-pain drift component (0–30) ─────────────────────────────────
    private int scoreMaxPainComp(IndexType ix, boolean wantsDown) {
        Deque<TimedValue> hist = maxPainHistory.get(ix);
        if (hist == null || hist.size() < 3) return 0;
        TimedValue oldest = hist.peekFirst();
        TimedValue latest = hist.peekLast();
        if (oldest == null || latest == null) return 0;
        double drift = latest.value - oldest.value;
        // Position wants down → reversal is drift UP. Position wants up → reversal is drift DOWN.
        double riskDrift = wantsDown ? drift : -drift;
        if (riskDrift <= 0) return 0;
        // 50pt shift = 15 points; 100pt = 30 (cap).
        return (int) Math.min(30, riskDrift / 50.0 * 15.0);
    }

    // ── R2: IV skew component (0–25) ────────────────────────────────────────
    private int scoreSkewComp(IndexType ix, boolean wantsDown) {
        Deque<TimedValue> hist = skewHistory.get(ix);
        if (hist == null || hist.size() < 3) return 0;
        // 3-bar moving average of skew
        double[] last3 = new double[3];
        int i = 0;
        for (java.util.Iterator<TimedValue> it = hist.descendingIterator(); it.hasNext() && i < 3; ) {
            last3[i++] = it.next().value;
        }
        if (i < 3) return 0;
        double avg = (last3[0] + last3[1] + last3[2]) / 3.0;
        // Strongly negative skew = pricing UP-move risk. Bad for PE BUY (wantsDown).
        // Strongly positive skew = pricing DOWN-move risk. Bad for CE BUY.
        double risk = wantsDown ? -avg : avg;
        if (risk <= 0) return 0;
        // skew avg of −1.0 → 12 points; −2.0 → 25 (cap).
        return (int) Math.min(25, risk * 12.5);
    }

    // ── R3: wall migration component (0–25) ─────────────────────────────────
    private int scoreWallComp(IndexType ix, boolean wantsDown) {
        Deque<TimedValue> ce = ceWallHistory.get(ix);
        Deque<TimedValue> pe = peWallHistory.get(ix);
        if (ce == null || pe == null || ce.size() < 3 || pe.size() < 3) return 0;
        TimedValue ceOldest = ce.peekFirst();
        TimedValue ceLatest = ce.peekLast();
        TimedValue peOldest = pe.peekFirst();
        TimedValue peLatest = pe.peekLast();
        double ceDrift = ceLatest.value - ceOldest.value;
        double peDrift = peLatest.value - peOldest.value;
        // Both walls migrating up = bullish (bad for PE BUY).
        // Both walls migrating down = bearish (bad for CE BUY).
        boolean bothUp = ceDrift > 0 && peDrift > 0;
        boolean bothDown = ceDrift < 0 && peDrift < 0;
        if (wantsDown && bothUp) {
            return Math.min(25, (int) ((Math.abs(ceDrift) + Math.abs(peDrift)) / 100.0 * 12.5));
        }
        if (!wantsDown && bothDown) {
            return Math.min(25, (int) ((Math.abs(ceDrift) + Math.abs(peDrift)) / 100.0 * 12.5));
        }
        return 0;
    }

    // ── R4: per-strike OI flow inversion (0–20) ─────────────────────────────
    private int scoreOiFlowComp(IndexType ix, int trapStrike, boolean wantsDown) {
        // If OIST wants down, it's buying PE at trapStrike. Track PE OI at trapStrike.
        // Reversal signal: PE OI Δ (last 5 min) FLIPPED from sustained positive to negative.
        Map<Integer, Deque<TimedValue>> strikeMap = wantsDown
                ? peOiByStrike.get(ix) : ceOiByStrike.get(ix);
        if (strikeMap == null) return 0;
        Deque<TimedValue> hist = strikeMap.get(trapStrike);
        if (hist == null || hist.size() < 5) return 0;
        // Get last 5 values (need 5 samples = 5 minutes minimum to detect inversion)
        TimedValue[] arr = hist.toArray(new TimedValue[0]);
        if (arr.length < 5) return 0;
        double latest = arr[arr.length - 1].value;
        double prev1 = arr[arr.length - 2].value;
        double prev2 = arr[arr.length - 3].value;
        double prev3 = arr[arr.length - 4].value;
        double prev4 = arr[arr.length - 5].value;
        // Last 1-min Δ
        double d1 = latest - prev1;
        // Prior 3-bar average Δ (the "sustained" trend)
        double priorAvg = ((prev1 - prev2) + (prev2 - prev3) + (prev3 - prev4)) / 3.0;
        // Inversion: priorAvg was positive, now d1 is negative AND magnitude ≥ 1M
        if (priorAvg > 100_000 && d1 < -1_000_000) {
            // Scale: −1M = 10 points, −3M = 20 (cap)
            return (int) Math.min(20, Math.abs(d1) / 1_000_000.0 * 10.0);
        }
        return 0;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────
    private static void push(Deque<TimedValue> deque, Instant now, double value) {
        if (deque == null) return;
        deque.addLast(new TimedValue(now, value));
        Instant cutoff = now.minus(Duration.ofMinutes(RETENTION_MIN));
        while (!deque.isEmpty() && deque.peekFirst().time.isBefore(cutoff)) {
            deque.pollFirst();
        }
    }

    private static int computeMaxPain(ChainSnapshot snap) {
        int best = snap.atmStrike();
        double bestPain = Double.MAX_VALUE;
        for (ChainSnapshot.StrikeData candidate : snap.strikes()) {
            double pain = 0;
            for (ChainSnapshot.StrikeData s : snap.strikes()) {
                if (s.strike() < candidate.strike()) {
                    pain += s.ceOI() * (candidate.strike() - s.strike());
                } else if (s.strike() > candidate.strike()) {
                    pain += s.peOI() * (s.strike() - candidate.strike());
                }
            }
            if (pain < bestPain) { bestPain = pain; best = candidate.strike(); }
        }
        return best;
    }

    private record TimedValue(Instant time, double value) {}
}
