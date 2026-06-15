package com.algo.trade.strategy.oishifttrap;

import com.algo.trade.data.ChainSnapshot;
import com.algo.trade.domain.IndexType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
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

    // ── Capture / observability (2026-06-02) ────────────────────────────────
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Path CAPTURE_ROOT = Path.of("reports/tuning/events");

    /** Latest computed metric per index, retained for periodic log dump. */
    private final Map<IndexType, double[]> lastMetrics = new EnumMap<>(IndexType.class);

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

        // Stash latest metrics for the minutely log dump.
        lastMetrics.put(ix, new double[]{spot, atm, maxPain, skew, ceWall, peWall});

        if (log.isDebugEnabled()) {
            log.debug("[ReversalRisk][{}] sampled — spot={} atm={} maxPain={} skew={} ceWall={} peWall={}",
                    ix, spot, atm, maxPain, String.format("%.2f", skew), ceWall, peWall);
        }

        // Append to capture CSV — one row per sample per index. Best-effort; IO
        // failures are swallowed so they never block trading.
        appendCsv("snapshot.csv",
                "eventTime,index,spot,atm,maxPain,skew,ceWall,peWall",
                String.format("%s,%s,%.2f,%d,%d,%.4f,%d,%d",
                        now.toString(), ix, spot, atm, maxPain, skew, ceWall, peWall));
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
        // Capture every score evaluation — even sub-threshold ones — so the tune
        // analyser can study the score distribution + per-component contribution
        // over time.
        appendCsv("score.csv",
                "eventTime,index,trapSide,trapStrike,total,maxPainComp,skewComp,wallComp,oiFlowComp",
                String.format("%s,%s,%s,%d,%d,%d,%d,%d,%d",
                        Instant.now().toString(), ix, trapSide, trapStrike, total, mp, sk, wl, oi));
        return new Score(total, mp, sk, wl, oi, detail);
    }

    /**
     * Periodic INFO log of the current per-index metrics — visible in the
     * application log alongside other strategy ticks. Runs once a minute
     * during market hours.
     */
    @Scheduled(fixedRate = 60_000, initialDelay = 60_000)
    public void logCurrentState() {
        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(LocalTime.of(9, 15)) || now.isAfter(LocalTime.of(15, 30))) return;
        for (Map.Entry<IndexType, double[]> e : lastMetrics.entrySet()) {
            double[] m = e.getValue();
            if (m == null) continue;
            log.info("[ReversalRisk][{}] state: spot={} atm={} maxPain={} skew={} ceWall={} peWall={}",
                    e.getKey(),
                    String.format("%.2f", m[0]),
                    (int) m[1], (int) m[2],
                    String.format("%+.3f", m[3]),
                    (int) m[4], (int) m[5]);
        }
    }

    /** Best-effort CSV append. Creates per-day directory and writes header if first row. */
    private static synchronized void appendCsv(String filename, String header, String row) {
        try {
            LocalDate today = LocalDate.now(IST);
            Path dir = CAPTURE_ROOT.resolve(today.toString()).resolve("reversal_risk");
            Files.createDirectories(dir);
            Path file = dir.resolve(filename);
            boolean newFile = !Files.exists(file);
            StringBuilder sb = new StringBuilder();
            if (newFile) sb.append(header).append('\n');
            sb.append(row).append('\n');
            Files.writeString(file, sb.toString(),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            log.debug("[ReversalRisk] CSV append failed for {}: {}", filename, ex.getMessage());
        }
    }

    // ── R1: max-pain drift component (0–30) ─────────────────────────────────
    private int scoreMaxPainComp(IndexType ix, boolean wantsDown) {
        Deque<TimedValue> hist = maxPainHistory.get(ix);
        if (hist == null || hist.size() < 3) return 0;
        TimedValue oldest = hist.peekFirst();
        TimedValue latest = hist.peekLast();
        if (oldest == null || latest == null) return 0;
        double drift = latest.value - oldest.value;
        double riskDrift = wantsDown ? drift : -drift;
        if (riskDrift <= 0) return 0;
        return (int) Math.min(30, riskDrift / 50.0 * 15.0);
    }

    // ── R2: IV skew component (0–25) — strict trend version ─────────────────
    private int scoreSkewComp(IndexType ix, boolean wantsDown) {
        Deque<TimedValue> hist = skewHistory.get(ix);
        if (hist == null || hist.size() < 5) return 0;
        TimedValue[] arr = hist.toArray(new TimedValue[0]);
        int n = arr.length;
        double avgNow = (arr[n-1].value + arr[n-2].value + arr[n-3].value) / 3.0;
        double avgPrior = (arr[n-3].value + arr[n-4].value + arr[n-5].value) / 3.0;
        double riskNow = wantsDown ? -avgNow : avgNow;
        double riskPrior = wantsDown ? -avgPrior : avgPrior;
        if (riskNow <= 0) return 0;
        if (riskNow <= riskPrior) return 0;
        return (int) Math.min(25, riskNow * 12.5);
    }

    // ── R3: wall migration component (0–25) — strict monotone version ──────
    private int scoreWallComp(IndexType ix, boolean wantsDown) {
        Deque<TimedValue> ce = ceWallHistory.get(ix);
        Deque<TimedValue> pe = peWallHistory.get(ix);
        if (ce == null || pe == null || ce.size() < 4 || pe.size() < 4) return 0;
        TimedValue[] ceArr = ce.toArray(new TimedValue[0]);
        TimedValue[] peArr = pe.toArray(new TimedValue[0]);
        int cn = ceArr.length, pn = peArr.length;
        double ce0 = ceArr[cn-1].value - ceArr[cn-2].value;
        double ce1 = ceArr[cn-2].value - ceArr[cn-3].value;
        double ce2 = ceArr[cn-3].value - ceArr[cn-4].value;
        double pe0 = peArr[pn-1].value - peArr[pn-2].value;
        double pe1 = peArr[pn-2].value - peArr[pn-3].value;
        double pe2 = peArr[pn-3].value - peArr[pn-4].value;
        boolean ceUpMonotone = ce0 >= 0 && ce1 >= 0 && ce2 >= 0 && (ce0 + ce1 + ce2) > 0;
        boolean peUpMonotone = pe0 >= 0 && pe1 >= 0 && pe2 >= 0 && (pe0 + pe1 + pe2) > 0;
        boolean ceDownMonotone = ce0 <= 0 && ce1 <= 0 && ce2 <= 0 && (ce0 + ce1 + ce2) < 0;
        boolean peDownMonotone = pe0 <= 0 && pe1 <= 0 && pe2 <= 0 && (pe0 + pe1 + pe2) < 0;
        boolean bothUp = ceUpMonotone && peUpMonotone;
        boolean bothDown = ceDownMonotone && peDownMonotone;
        double ceDrift = ce0 + ce1 + ce2;
        double peDrift = pe0 + pe1 + pe2;
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
        Map<Integer, Deque<TimedValue>> strikeMap = wantsDown
                ? peOiByStrike.get(ix) : ceOiByStrike.get(ix);
        if (strikeMap == null) return 0;
        Deque<TimedValue> hist = strikeMap.get(trapStrike);
        if (hist == null || hist.size() < 5) return 0;
        TimedValue[] arr = hist.toArray(new TimedValue[0]);
        if (arr.length < 5) return 0;
        double latest = arr[arr.length - 1].value;
        double prev1 = arr[arr.length - 2].value;
        double prev2 = arr[arr.length - 3].value;
        double prev3 = arr[arr.length - 4].value;
        double prev4 = arr[arr.length - 5].value;
        double d1 = latest - prev1;
        double priorAvg = ((prev1 - prev2) + (prev2 - prev3) + (prev3 - prev4)) / 3.0;
        if (priorAvg > 100_000 && d1 < -1_000_000) {
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
