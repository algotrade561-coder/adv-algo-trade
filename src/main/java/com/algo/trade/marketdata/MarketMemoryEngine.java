package com.algo.trade.marketdata;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionInstrument;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MARKET-MEMORY ENGINE (docs/MARKET-MEMORY-V5-DESIGN.md §2, §14.1–2, §16–17).
 *
 * <p>DAY memory: per-strike baselines (all signals z-scored vs the strike's OWN day), 6 market
 * states with magnitude tiers, intraday episode memory with suspension. WEEK memory: the
 * mountain/pain ledger — cumulative expiry-cycle OI build + the writers' average sale premium →
 * live pain%, H2-persisted every 5 min so it survives restarts, purged at expiry.</p>
 *
 * <p><b>Observability (mid-market debuggability):</b> ONE-line boot banner of the effective config;
 * a 5-minute [MarketMemory] HEARTBEAT (strike count, state histogram, avalanches/events today,
 * suspensions, top writer-pain); a central {@link #event} API every V5 decision flows through —
 * ring-buffered for the UI (§16) AND appended to data/tuning/market-memory-decisions-&lt;date&gt;.csv
 * (design §17: daily CSV, existing retention cron lifecycle).</p>
 *
 * <p><b>Threading:</b> fed on the WS thread — O(1) amortized; baseline refresh sorts ≤~200 sampled
 * values. Snapshots are immutable records behind volatile refs. CSV writes are buffered in-memory
 * and flushed by a scheduler — the WS thread never touches disk. Never throws into the caller.</p>
 */
@Component
public class MarketMemoryEngine {

    private static final Logger log = LoggerFactory.getLogger(MarketMemoryEngine.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum MarketState { WARMING, DEAD, SHORT_COVER_RUN, WRITER_PRESS, AVALANCHE, NEUTRAL }
    public enum Tier { WEAK, STRONG, DEEP }

    /** Immutable per-strike view; one instance swapped per tick. */
    public record MemorySnapshot(MarketState state, Tier tier, boolean battle, boolean warming,
                                 double zOi, double dOi5mPct, double dP5mPct, double volUnitPct,
                                 double low10m, double low15m, double volRate30s,
                                 long mountainBuild, double avgWritePremium, double painPct, long ts) {
        // Deep = the deep tier OF A CONFIRMED AVALANCHE (state already requires the full §3.1 price
        // turn incl. ltp ≥ low10m×1.01) — never a raw OI-drop without the price confirmation.
        public boolean deepAvalanche() { return state == MarketState.AVALANCHE && dOi5mPct <= DEEP_DOI5M_PCT; }
    }
    /** One observable V5 decision/event — ring-buffered for the UI + persisted to the daily CSV. */
    public record MemoryEvent(long tsMs, String category, String index, String instrument, String detail) {}

    // thresholds (config-tunable; defaults = validated replay values)
    @Value("${market-memory.enabled:true}") private boolean enabled;
    @Value("${market-memory.avalanche-z-min:-3.0}") private double avalancheZMin;
    @Value("${market-memory.avalanche-doi5m-min-pct:-3.0}") private double avalancheDoiMin;
    @Value("${market-memory.state-oi-weak-pct:0.5}") private double oiWeakPct;
    @Value("${market-memory.state-oi-strong-pct:2.0}") private double oiStrongPct;
    @Value("${market-memory.state-price-pct:3.0}") private double statePricePct;
    @Value("${market-memory.battle-absorb-count-5m:3}") private int battleAbsorbCount;
    @Value("${market-memory.warmup-sec:1500}") private long warmupSec;
    @Value("${market-memory.suspension-after-losses:3}") private int suspensionAfterLosses;
    @Value("${market-memory.decisions-csv-dir:data/tuning}") private String decisionsCsvDir;
    /** §14.5 warm-start seed max age. 6d (not 4d) so a Monday-holiday long weekend still warm-starts
     *  Tuesday from Friday's normals; the 60s live baseline refresh overwrites the seed within minutes. */
    @Value("${market-memory.warmstart-max-age-days:6}") private int warmstartMaxAgeDays;
    /** F10-7: deep-by-raw-%% needs zOi<=-3 OR at least this much open interest behind the strike
     *  (0 = off). Replay-validated at 100k (+3.1k/4d, crash day improves); thin new-series strikes
     *  fire -10%% dOI on a handful of contracts otherwise. */
    @Value("${market-memory.deep-min-oi-base:100000}") private double deepMinOiBase;
    /** Adaptive rule-B margin: clamp(THIS x vu, 0.5%%, 1.5%%) off the 10-min low (0 = fixed 1%%).
     *  Sweep-validated at 0.4: +1.6k/4d, trend-day drawdown halved, no day worse. */
    @Value("${market-memory.turn-margin-vu-mult:0.4}") private double turnMarginVuMult;
    public static final double DEEP_DOI5M_PCT = -8.0;

    private final LiveInstrumentCache cache;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ConvictionOverrideEngine convictionOverrideEngine;
    /** Optional — UI-hot override of the suspension threshold (Global Settings page). Null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.config.GlobalConfigService globalConfigService;

    /** Effective suspension threshold: Global-Settings override (0 = DISABLED) → yml default (3).
     *  Read per call so a UI edit applies on the next tick, no restart. Deep tier bypasses regardless. */
    private int effSuspensionAfterLosses() {
        try {
            if (globalConfigService != null) {
                Integer v = globalConfigService.getMemorySuspensionAfterLosses();
                if (v != null) return v <= 0 ? Integer.MAX_VALUE : v;
            }
        } catch (Exception ignore) { /* config hiccup → yml default */ }
        return suspensionAfterLosses;
    }
    /** Optional — week-memory persistence (design §17). Null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.persistence.MountainLedgerRepository mountainRepo;
    /** Optional — month-memory episode store (design §14.4). Null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.persistence.MemoryEpisodeRepository episodeRepo;
    /** Optional — analog-days library (design §14.5). Null in tests. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.persistence.DayFingerprintRepository dayFpRepo;

    public MarketMemoryEngine(LiveInstrumentCache cache) { this.cache = cache; }

    @jakarta.annotation.PostConstruct
    void bootBanner() {
        log.info("[MarketMemory] BOOT enabled={} avalanche(z<={} & dOI5m<={}% | deep<={}%) states(weak={}% strong={}% price={}%) "
                        + "battleAbsorb>={} warmup={}s suspendAfter={} losses csvDir={} mountainRepo={}",
                enabled, avalancheZMin, avalancheDoiMin, DEEP_DOI5M_PCT, oiWeakPct, oiStrongPct, statePricePct,
                battleAbsorbCount, warmupSec, suspensionAfterLosses, decisionsCsvDir, mountainRepo != null);
        // C6 (2026-07-10): intraday episode counters survived only in memory — every mid-market
        // restart reset them to zero (3 resets on 07-09; C2's suspension had nothing to act on all
        // afternoon, and the "#N today" labels reset visibly on 07-10). Episodes are already
        // persisted per close (§14.4); reload TODAY's rows so suspension state survives restarts.
        try {
            if (episodeRepo != null) {
                var rows = episodeRepo.findByTradeDate(LocalDate.now(IST).toString());
                for (var r : rows) {
                    episodes.compute(r.getIndexName() + "|" + r.getSide() + "|" + r.getPattern(), (k, v) -> {
                        if (v == null) v = new double[2];
                        v[0]++; v[1] += r.getNetPct();
                        return v;
                    });
                }
                if (!rows.isEmpty()) log.info("[MarketMemory] C6 reload: {} of today's episodes restored across {} pattern keys",
                        rows.size(), episodes.size());
            }
        } catch (Exception e) {
            log.warn("[MarketMemory] C6 episode reload skipped: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- per-strike state
    private static final class Inst {
        final String idx; final int strike; final String type; final String expiry; // ISO or ""
        final ArrayDeque<double[]> samp = new ArrayDeque<>();   // {ts, ltp, oi, absorb} @>=5s, 90 min
        final ArrayDeque<double[]> vol30 = new ArrayDeque<>();
        final ArrayDeque<double[]> vol300 = new ArrayDeque<>();
        double sumVol30 = 0, sumVol300 = 0;
        long prevCum = -1, lastSampTs = 0, lastBaseTs = 0, firstTs = 0;
        double madRet = 0.004, oiMed = 0, oiMad = 0.2;
        // week memory (mountain + pain, design §14.1-2)
        long mountainLastOi = -1; long cumBuild = 0; double writtenValue = 0; double painPct = 0;
        long sodCumBuild = 0;           // cumBuild at day start (day fingerprint = cumBuild - sod)
        double lastLtp = 0;             // latest premium seen — persisted as the ledger's lastPremium
        boolean baselineSeeded = false; // §14.5 warm-start: yesterday's normals loaded
        volatile MemorySnapshot snap;
        /** Flicker grace: epoch-sec of the last tick classified AVALANCHE / deep AVALANCHE. OI prints
         *  are chunky, so the state can drop between signal detection and a downstream gate check —
         *  these let bypasses honor a just-confirmed avalanche for a few seconds (see recentAvalanche). */
        volatile long lastAvalancheTs = 0; volatile long lastDeepTs = 0;
        Inst(String idx, int strike, String type, String expiry) {
            this.idx = idx; this.strike = strike; this.type = type; this.expiry = expiry;
        }
    }
    private final ConcurrentHashMap<Long, Inst> byToken = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Inst> byKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, double[]> episodes = new ConcurrentHashMap<>(); // idx|ty|pattern -> {n,sumNetPct}
    private volatile LocalDate day = LocalDate.now(IST);

    // observability: event ring (UI) + daily CSV buffer (flushed off-thread)
    private final ArrayDeque<MemoryEvent> events = new ArrayDeque<>();
    private static final int EVENT_RING_MAX = 400;
    private final List<String> csvBuffer = Collections.synchronizedList(new ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger eventsToday = new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger avalanchesSeenToday = new java.util.concurrent.atomic.AtomicInteger();

    public boolean isEnabled() { return enabled; }

    /** WS-thread feed. Same call-site pattern as AtmMicrostructureRecorder — never throws. */
    public void onOptionTick(long token, double ltp, long cumVolume, long oi, Instant recv) {
        if (!enabled || ltp <= 0) return;
        try {
            rolloverIfNewDay();
            Inst in = byToken.get(token);
            if (in == null) {
                OptionInstrument oi2 = cache.getByToken(token).orElse(null);
                if (oi2 == null) return;
                in = byToken.computeIfAbsent(token, t -> {
                    String exp = oi2.getExpiry() != null ? oi2.getExpiry().toString() : "";
                    Inst x = new Inst(oi2.getIndexType().name(), oi2.getStrikePrice(), oi2.getOptionType(), exp);
                    x.firstTs = recv.getEpochSecond();
                    seedFromLedger(x);                               // restart-safe week memory
                    byKey.put(x.idx + ":" + x.strike + ":" + x.type, x);
                    return x;
                });
            }
            long ts = recv.getEpochSecond();
            long dv = (in.prevCum < 0 || cumVolume < in.prevCum) ? 0 : cumVolume - in.prevCum;
            in.prevCum = cumVolume;

            in.vol30.addLast(new double[]{ts, dv}); in.sumVol30 += dv;
            while (!in.vol30.isEmpty() && in.vol30.peekFirst()[0] < ts - 30) in.sumVol30 -= in.vol30.pollFirst()[1];
            in.vol300.addLast(new double[]{ts, dv}); in.sumVol300 += dv;
            while (!in.vol300.isEmpty() && in.vol300.peekFirst()[0] < ts - 300) in.sumVol300 -= in.vol300.pollFirst()[1];

            if (ts - in.lastSampTs >= 5) {
                in.lastSampTs = ts;
                double absorb = 0;
                if (convictionOverrideEngine != null) {
                    try {
                        ConvictionOverrideEngine.OverrideSnapshot os = convictionOverrideEngine.get(
                                IndexType.fromName(in.idx), in.strike, in.type);
                        if (os != null && os.absorption()) absorb = 1;
                    } catch (Exception ignore) { }
                }
                in.samp.addLast(new double[]{ts, ltp, oi, absorb});
                while (!in.samp.isEmpty() && in.samp.peekFirst()[0] < ts - 5400) in.samp.pollFirst();
                if (ts - in.lastBaseTs >= 60 && in.samp.size() >= 60) { in.lastBaseTs = ts; refreshBaselines(in); }
                mountainStep(in, ltp, oi);                          // week memory update (5s cadence)
            }

            MemorySnapshot prev = in.snap;
            in.snap = classify(in, ts, ltp, oi);
            if (in.snap.state() == MarketState.AVALANCHE
                    && (prev == null || prev.state() != MarketState.AVALANCHE)) {
                avalanchesSeenToday.incrementAndGet();
                event("AVALANCHE_STATE", in.idx, in.idx + " " + in.strike + " " + in.type,
                        String.format("dOI5m=%.1f%% zOi=%.1f dP5m=%.1f%% tier=%s pain=%.1f%% mountain=%d",
                                in.snap.dOi5mPct(), in.snap.zOi(), in.snap.dP5mPct(), in.snap.tier(),
                                in.painPct, in.cumBuild));
            }
        } catch (Exception e) {
            log.debug("[MarketMemory] tick skipped: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- week memory (§14.1-2)
    private void mountainStep(Inst in, double ltp, double oi) {
        in.lastLtp = ltp;
        if (in.mountainLastOi < 0) { in.mountainLastOi = (long) oi; return; }
        long d = (long) oi - in.mountainLastOi;
        if (d > 0) { in.cumBuild += d; in.writtenValue += d * ltp; }  // fresh writing at ~this premium
        else if (d < 0 && in.cumBuild > 0) {
            // §14.2 the other half: OI down ≈ buybacks. Retire covered contracts at the CURRENT average
            // write premium (proportional) so cumBuild stays "currently outstanding" — without this it
            // is a gross ever-written counter and avgWrite/painPct go stale the moment writers cover.
            long cover = Math.min(-d, in.cumBuild);
            double avg = in.writtenValue / in.cumBuild;
            in.cumBuild -= cover;
            in.writtenValue = in.cumBuild == 0 ? 0 : Math.max(0, in.writtenValue - cover * avg);
        }
        in.mountainLastOi = (long) oi;
        double avgWrite = in.cumBuild > 0 ? in.writtenValue / in.cumBuild : 0;
        in.painPct = avgWrite > 0 ? (ltp - avgWrite) / avgWrite * 100 : 0; // + = writers underwater
    }

    private void seedFromLedger(Inst in) {
        if (mountainRepo == null || in.expiry.isEmpty()) return;
        try {
            mountainRepo.findById(in.idx + ":" + in.strike + ":" + in.type + ":" + in.expiry).ifPresent(row -> {
                in.cumBuild = row.getCumBuild(); in.writtenValue = row.getWrittenValue();
                in.mountainLastOi = row.getLastOi(); in.painPct = row.getPainPct();
                in.sodCumBuild = row.getCumBuild();
                // §14.5 warm-start: seed yesterday's learned normals (only if recent) so the strike
                // is usable within ~2.5 min of fresh samples instead of the full 25-min warmup.
                if (row.getMadRet() > 0 && row.getUpdatedAt() != null
                        && row.getUpdatedAt().isAfter(Instant.now().minusSeconds(warmstartMaxAgeDays * 86400L))) {
                    in.madRet = row.getMadRet(); in.oiMed = row.getOiMed();
                    in.oiMad = Math.max(row.getOiMad(), 0.10);
                    in.baselineSeeded = true;
                }
            });
        } catch (Exception e) {
            log.debug("[MarketMemory] ledger seed skipped: {}", e.toString());
        }
    }

    /** Persist the week memory every 5 min (design §17: restart-safe, tiny, H2). */
    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    void persistMountains() {
        if (!enabled || mountainRepo == null) return;
        try {
            List<com.algo.trade.persistence.MountainLedgerEntity> rows = new ArrayList<>();
            for (Inst in : byKey.values()) {
                // Persist every FED strike (mountainLastOi >= 0), including ones covered back to zero —
                // skipping cumBuild==0 would leave a stale non-zero mountain in H2 after real buybacks.
                if (in.expiry.isEmpty() || in.mountainLastOi < 0) continue;
                var row = new com.algo.trade.persistence.MountainLedgerEntity(
                        in.idx + ":" + in.strike + ":" + in.type + ":" + in.expiry,
                        in.idx, in.strike, in.type, in.expiry);
                row.update(in.cumBuild, in.writtenValue, in.mountainLastOi,
                        in.lastLtp, in.painPct, in.madRet, in.oiMed, in.oiMad);
                rows.add(row);
            }
            if (!rows.isEmpty()) mountainRepo.saveAll(rows);
            mountainRepo.deleteByExpiryLessThan(LocalDate.now(IST).toString()); // purge dead contracts
        } catch (Exception e) {
            log.warn("[MarketMemory] mountain persist failed (will retry): {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- classification
    private MemorySnapshot classify(Inst in, long ts, double ltp, double oi) {
        double[] p5 = sampleAt(in, ts - 300);
        double dOi5 = p5 != null && p5[2] > 0 ? (oi - p5[2]) / p5[2] * 100 : 0;
        double dP5 = p5 != null && p5[1] > 0 ? (ltp - p5[1]) / p5[1] * 100 : 0;
        double zOi = (dOi5 - in.oiMed) / Math.max(in.oiMad, 0.10);
        double volUnit = clamp(2.2 * in.madRet * 100, 1.5, 10);
        double low10 = Double.MAX_VALUE, low15 = Double.MAX_VALUE;
        int absorbCnt = 0;
        for (double[] q : in.samp) {
            if (q[0] >= ts - 900) { low15 = Math.min(low15, q[1]); if (q[3] == 1 && q[0] >= ts - 300) absorbCnt++; }
            if (q[0] >= ts - 600) low10 = Math.min(low10, q[1]);
        }
        if (low10 == Double.MAX_VALUE) low10 = ltp;
        if (low15 == Double.MAX_VALUE) low15 = ltp;
        double base30 = (in.sumVol300 - in.sumVol30) / 9.0;
        double volRate = base30 > 0 ? in.sumVol30 / base30 : (in.sumVol30 > 0 ? 99 : 0);

        // §14.5 warm-start: a baseline-seeded strike needs only ~2.5 min of fresh samples; an
        // unseeded one keeps the full warmup (its "normal" is unknown until learned today).
        boolean warming = in.baselineSeeded
                ? in.samp.size() < 30
                : (ts - in.firstTs < warmupSec || in.samp.size() < 120);
        boolean battle = absorbCnt >= battleAbsorbCount;
        // §3.1 rule B — the full price-turn is REQUIRED for every tier, deep included: dP5m flat-to-up
        // AND ltp confirmed off the 10-min low. The +₹105k/3d replay (V5Memory) filtered entries on
        // exactly this; OI-avalanche with a merely-flat price is NOT the validated trigger.
        // Adaptive margin (2026-07-10 sweep, from an external suggestion — the one that survived):
        // margin = clamp(mult × vu, 0.5%, 1.5%) instead of fixed 1%. Quiet strikes confirm earlier,
        // violent ones demand more. 4-day tape: +1,646 at mult=0.4 with the trend day's intraday
        // drawdown nearly halved and NO day worse (0.2/0.3 were worse — the curve is real). 0 = fixed 1%.
        double turnMargin = turnMarginVuMult <= 0 ? 0.01
                : Math.max(0.005, Math.min(0.015, turnMarginVuMult * volUnit / 100));
        boolean priceTurn = dP5 >= 0 && ltp >= low10 * (1 + turnMargin);
        // F10-7 (2026-07-10, replay-validated +3.1k/4d at 100k incl. crash day 158.8k→163.1k): the
        // deep-by-raw-% path needs REAL MONEY behind it. On day one of a new weekly series, thin
        // far-OTM strikes swing −10% on a handful of contracts and the z correctly says "nothing
        // unusual" (07-10: two 78200CE entries at z −1.2 → −495; 58 of 174 DEEP events were weak-z).
        // A genuine z-anomaly (≤ −3) always qualifies regardless of size — that keeps 07-09's
        // z −1.6 big winner reachable via its huge base while blocking thin-base noise.
        boolean deepQualified = zOi <= -3 || oi >= deepMinOiBase;
        boolean deep = dOi5 <= DEEP_DOI5M_PCT && priceTurn && deepQualified;
        boolean avalanche = deep || (!warming && zOi <= avalancheZMin && dOi5 <= avalancheDoiMin && priceTurn);
        if (avalanche) { in.lastAvalancheTs = ts; if (deep) in.lastDeepTs = ts; }
        MarketState state;
        if (avalanche) state = MarketState.AVALANCHE;
        else if (warming) state = MarketState.WARMING;
        else if (dOi5 <= -oiWeakPct && dP5 >= statePricePct) state = MarketState.SHORT_COVER_RUN;
        else if (dOi5 >= oiWeakPct && dP5 <= -statePricePct) state = MarketState.WRITER_PRESS;
        else if (Math.abs(dOi5) < oiWeakPct && Math.abs(dP5) < statePricePct) state = MarketState.DEAD;
        else state = MarketState.NEUTRAL;
        Tier tier = Math.abs(dOi5) >= -DEEP_DOI5M_PCT ? Tier.DEEP
                : Math.abs(dOi5) >= oiStrongPct ? Tier.STRONG : Tier.WEAK;
        double avgWrite = in.cumBuild > 0 ? in.writtenValue / in.cumBuild : 0;
        return new MemorySnapshot(state, tier, battle, warming, zOi, dOi5, dP5, volUnit, low10, low15,
                volRate, in.cumBuild, avgWrite, in.painPct, ts);
    }

    private void refreshBaselines(Inst in) {
        double[][] arr = in.samp.toArray(new double[0][]);
        List<Double> rets = new ArrayList<>(), oic = new ArrayList<>();
        for (int i = 12; i < arr.length; i += 6) {
            double p0 = arr[i - 12][1];
            if (p0 > 0) rets.add(Math.abs(arr[i][1] - p0) / p0);
        }
        for (int i = 60; i < arr.length; i += 12) {
            double o0 = arr[i - 60][2];
            if (o0 > 0) oic.add((arr[i][2] - o0) / o0 * 100);
        }
        if (rets.size() >= 10) { Collections.sort(rets); in.madRet = Math.max(rets.get(rets.size() / 2), 0.0015); }
        if (oic.size() >= 10) {
            List<Double> c = new ArrayList<>(oic); Collections.sort(c);
            double med = c.get(c.size() / 2);
            List<Double> dev = new ArrayList<>(oic.size());
            for (double v : oic) dev.add(Math.abs(v - med));
            Collections.sort(dev);
            in.oiMed = med; in.oiMad = Math.max(dev.get(dev.size() / 2) * 1.4826, 0.10);
        }
    }

    private double[] sampleAt(Inst in, long ts) {
        double[] best = null;
        for (double[] q : in.samp) { if (q[0] <= ts) best = q; else break; }
        return best;
    }

    // ---------------------------------------------------------------- observability (§16-17)
    /**
     * Central V5 event sink — EVERY observable decision flows through here: ring buffer for the UI,
     * one INFO log line, and a row in data/tuning/market-memory-decisions-&lt;date&gt;.csv (buffered;
     * flushed off-thread every 5s). Categories: AVALANCHE_STATE / AVALANCHE_ENTRY / AVALANCHE_SKIP /
     * ENTRY_PASS / ENTRY_VETO / EXIT_WPRESS / EXIT_SUPPRESSED / EPISODE.
     */
    public void event(String category, String index, String instrument, String detail) {
        try {
            eventsToday.incrementAndGet();
            MemoryEvent ev = new MemoryEvent(System.currentTimeMillis(), category,
                    index == null ? "" : index, instrument == null ? "" : instrument, detail == null ? "" : detail);
            synchronized (events) {
                events.addLast(ev);
                while (events.size() > EVENT_RING_MAX) events.pollFirst();
            }
            log.info("[MarketMemory][{}] {} {} — {}", category, ev.index(), ev.instrument(), ev.detail());
            csvBuffer.add(ev.tsMs() + "," + category + "," + ev.index() + "," + ev.instrument().replace(',', ' ')
                    + ",\"" + ev.detail().replace('"', '\'') + "\"");
        } catch (Exception e) {
            log.debug("[MarketMemory] event skipped: {}", e.toString());
        }
    }

    /** Flush the decisions CSV buffer (design §17: daily file, retention cron lifecycle). */
    @Scheduled(fixedDelay = 5_000, initialDelay = 10_000)
    void flushCsv() {
        if (csvBuffer.isEmpty()) return;
        List<String> batch;
        synchronized (csvBuffer) { batch = new ArrayList<>(csvBuffer); csvBuffer.clear(); }
        try {
            Path dir = Path.of(decisionsCsvDir);
            Files.createDirectories(dir);
            Path file = dir.resolve("market-memory-decisions-" + LocalDate.now(IST) + ".csv");
            boolean fresh = !Files.exists(file);
            try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                if (fresh) { w.write("tsMs,category,index,instrument,detail"); w.newLine(); }
                for (String line : batch) { w.write(line); w.newLine(); }
            }
        } catch (Exception e) {
            log.warn("[MarketMemory] decisions CSV flush failed ({} rows dropped): {}", batch.size(), e.toString());
        }
    }

    /** 5-minute HEARTBEAT — the one line to watch mid-market for engine health. */
    @Scheduled(fixedDelay = 300_000, initialDelay = 120_000)
    void heartbeat() {
        if (!enabled) return;
        try {
            int total = byKey.size(); int dead = 0, scr = 0, wp = 0, av = 0, battle = 0, warming = 0;
            String topPain = "-"; double topPainVal = 0;
            for (Inst in : byKey.values()) {
                MemorySnapshot s = in.snap;
                if (s == null) continue;
                switch (s.state()) {
                    case DEAD -> dead++;
                    case SHORT_COVER_RUN -> scr++;
                    case WRITER_PRESS -> wp++;
                    case AVALANCHE -> av++;
                    case WARMING -> warming++;
                    default -> { }
                }
                if (s.battle()) battle++;
                if (in.painPct > topPainVal && in.cumBuild > 0) {
                    topPainVal = in.painPct; topPain = in.idx + " " + in.strike + " " + in.type
                            + " pain=" + String.format("%.0f", in.painPct) + "% build=" + in.cumBuild;
                }
            }
            List<String> suspended = new ArrayList<>();
            episodes.forEach((k, v) -> {
                if (v[0] >= effSuspensionAfterLosses() && v[1] / v[0] < 0) suspended.add(k);
            });
            log.info("[MarketMemory] HEARTBEAT strikes={} states[dead={} scr={} wpress={} avalanche={} warming={} battle={}] "
                            + "avalanchesToday={} eventsToday={} suspended={} topWriterPain[{}]",
                    total, dead, scr, wp, av, warming, battle,
                    avalanchesSeenToday.get(), eventsToday.get(), suspended.isEmpty() ? "none" : suspended, topPain);
        } catch (Exception e) {
            log.debug("[MarketMemory] heartbeat skipped: {}", e.toString());
        }
    }

    // ---------------------------------------------------------------- read API (strategy + UI)
    public MemorySnapshot get(IndexType idx, int strike, String optionType) {
        if (!enabled) return null;
        Inst in = byKey.get(idx.name() + ":" + strike + ":" + optionType);
        return in != null ? in.snap : null;
    }

    /** UI summary: one-glance system health for the Market Memory page header. */
    public Map<String, Object> summary(String indexFilter) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("enabled", enabled);
        int total = 0, dead = 0, scr = 0, wp = 0, av = 0, warming = 0, battle = 0, neutral = 0;
        String topPainInstrument = "—"; double topPainVal = 0;
        for (Inst in : byKey.values()) {
            if (indexFilter != null && !indexFilter.isEmpty() && !in.idx.equals(indexFilter)) continue;
            total++;
            if (in.snap == null) continue;
            switch (in.snap.state()) {
                case DEAD -> dead++;
                case SHORT_COVER_RUN -> scr++;
                case WRITER_PRESS -> wp++;
                case AVALANCHE -> av++;
                case WARMING -> warming++;
                default -> neutral++;
            }
            if (in.snap.battle()) battle++;
            if (in.snap.painPct() > topPainVal) {
                topPainVal = in.snap.painPct();
                topPainInstrument = in.idx + " " + in.strike + " " + in.type + " (" + String.format("%.0f%%", topPainVal) + ")";
            }
        }
        out.put("strikesTracked", total);
        out.put("strikesWarmed", total - warming);
        Map<String, Integer> states = new java.util.LinkedHashMap<>();
        states.put("DEAD", dead); states.put("SHORT_COVER_RUN", scr); states.put("WRITER_PRESS", wp);
        states.put("AVALANCHE", av); states.put("NEUTRAL", neutral); states.put("WARMING", warming);
        states.put("BATTLE", battle);
        out.put("states", states);
        out.put("avalanchesToday", avalanchesSeenToday.get());
        out.put("eventsToday", eventsToday.get());
        // Suspensions
        List<String> suspended = new ArrayList<>();
        episodes.forEach((k, v) -> {
            if (v[0] >= effSuspensionAfterLosses() && v[1] / v[0] < 0) suspended.add(k);
        });
        out.put("suspensions", suspended);
        out.put("topWriterPain", topPainInstrument);
        // Episode totals (today's branch P&L)
        int epTotal = 0; double epNetPct = 0;
        for (var v : episodes.values()) { epTotal += (int) v[0]; epNetPct += v[1]; }
        out.put("episodesToday", epTotal);
        out.put("episodesNetPct", round2(epNetPct));
        return out;
    }

    /** UI (§16.1): full state grid for one index — [strike, type, snapshot] rows.
     *  F10-2 (2026-07-10): rows whose snapshot is older than 15 min are DROPPED — after the +700pt
     *  gap-up the abandoned pre-gap window (fed 09:02-09:08 around yesterday's close) sat at the top
     *  of the strike-sorted table looking like "yesterday's list" while the re-centered live window
     *  was further down. 90s-stale rows get a {@code stale} flag so the UI can grey them.
     *  Read-only endpoint — zero trading-path impact. */
    public List<Map<String, Object>> stateGrid(String indexName) {
        long nowMs = System.currentTimeMillis();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inst in : byKey.values()) {
            if (!in.idx.equals(indexName)) continue;
            MemorySnapshot s = in.snap;
            if (s == null) continue;
            long ageMs = nowMs - s.ts() * 1000L;       // snapshot ts is epoch SECONDS (feed exchange ts)
            if (ageMs > 15 * 60_000L) continue;        // subscription window moved on — drop
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("stale", ageMs > 90_000L);
            row.put("strike", in.strike); row.put("type", in.type);
            row.put("state", s.state().name()); row.put("tier", s.tier().name());
            row.put("battle", s.battle()); row.put("warming", s.warming());
            row.put("zOi", round1(s.zOi())); row.put("dOi5mPct", round2(s.dOi5mPct()));
            row.put("dP5mPct", round1(s.dP5mPct())); row.put("volUnitPct", round1(s.volUnitPct()));
            row.put("mountainBuild", s.mountainBuild());
            row.put("avgWritePremium", round2(s.avgWritePremium()));
            row.put("painPct", round1(s.painPct()));
            row.put("ts", s.ts());
            out.add(row);
        }
        out.sort(java.util.Comparator
                .comparing((Map<String, Object> m) -> (Integer) m.get("strike"))
                .thenComparing(m -> (String) m.get("type")));
        return out;
    }

    /** UI (§16.1): recent events, newest first; optional category filter. */
    public List<MemoryEvent> recentEvents(String categoryPrefix, int limit) {
        List<MemoryEvent> out = new ArrayList<>();
        synchronized (events) {
            var it = events.descendingIterator();
            while (it.hasNext() && out.size() < limit) {
                MemoryEvent ev = it.next();
                if (categoryPrefix == null || ev.category().startsWith(categoryPrefix)) out.add(ev);
            }
        }
        return out;
    }

    /** UI (§16.1): episode memory rows. */
    public List<Map<String, Object>> episodeRows() {
        List<Map<String, Object>> out = new ArrayList<>();
        episodes.forEach((k, v) -> {
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            String[] parts = k.split("\\|");
            row.put("index", parts.length > 0 ? parts[0] : k);
            row.put("side", parts.length > 1 ? parts[1] : "");
            row.put("pattern", parts.length > 2 ? parts[2] : "");
            row.put("n", (int) v[0]);
            row.put("avgNetPct", v[0] > 0 ? round2(v[1] / v[0]) : 0);
            row.put("suspended", v[0] >= effSuspensionAfterLosses() && v[1] / v[0] < 0);
            out.add(row);
        });
        return out;
    }

    /** UI (§16.1, tab 5): mountain/pain rows for one index (live view, not the persisted copy). */
    public List<Map<String, Object>> mountainRows(String indexName) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Inst in : byKey.values()) {
            if (!in.idx.equals(indexName) || in.cumBuild <= 0) continue;
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("strike", in.strike); row.put("type", in.type); row.put("expiry", in.expiry);
            row.put("cumBuild", in.cumBuild);
            row.put("avgWritePremium", round2(in.cumBuild > 0 ? in.writtenValue / in.cumBuild : 0));
            row.put("painPct", round1(in.painPct));
            out.add(row);
        }
        out.sort(java.util.Comparator.comparingLong((Map<String, Object> m) -> (Long) m.get("cumBuild")).reversed());
        return out;
    }

    /** Flicker grace: was this strike classified AVALANCHE within the last graceSec? OI prints are
     *  chunky, so the state can drop for a tick between signal detection and a downstream gate
     *  (churn bypass / veto deep-override) — a just-confirmed avalanche stays honored briefly. */
    public boolean recentAvalanche(com.algo.trade.domain.IndexType idx, int strike, String type, long graceSec) {
        Inst in = byKey.get(idx.name() + ":" + strike + ":" + type);
        return in != null && in.lastAvalancheTs > 0
                && (System.currentTimeMillis() / 1000L - in.lastAvalancheTs) <= graceSec;
    }

    /** Flicker grace for the DEEP tier specifically (veto-rail override). */
    public boolean recentDeepAvalanche(com.algo.trade.domain.IndexType idx, int strike, String type, long graceSec) {
        Inst in = byKey.get(idx.name() + ":" + strike + ":" + type);
        return in != null && in.lastDeepTs > 0
                && (System.currentTimeMillis() / 1000L - in.lastDeepTs) <= graceSec;
    }

    /** Latest premium seen for a strike from the 5s feed (0 if unknown) — used by the avalanche
     *  stack exit ladder, which manages positions the singular strategy state doesn't track. */
    public double lastPremium(com.algo.trade.domain.IndexType idx, int strike, String type) {
        Inst in = byKey.get(idx.name() + ":" + strike + ":" + type);
        return in != null ? in.lastLtp : 0;
    }

    public boolean patternSuspended(String idx, String optionType, String pattern) {
        double[] m = episodes.get(idx + "|" + optionType + "|" + pattern);
        return m != null && m[0] >= effSuspensionAfterLosses() && m[1] / m[0] < 0;
    }

    public void recordEpisode(String idx, String optionType, String pattern, double netPct) {
        recordEpisode(idx, optionType, pattern, 0, netPct);
    }

    public void recordEpisode(String idx, String optionType, String pattern, int strike, double netPct) {
        try {
            rolloverIfNewDay();
            episodes.compute(idx + "|" + optionType + "|" + pattern, (k, v) -> {
                if (v == null) v = new double[2];
                v[0]++; v[1] += netPct;
                return v;
            });
            double[] m = episodes.get(idx + "|" + optionType + "|" + pattern);
            event("EPISODE", idx, idx + " " + optionType + " " + pattern,
                    String.format("netPct=%.2f -> n=%d avg=%.2f%s", netPct, (int) m[0], m[1] / m[0],
                            patternSuspended(idx, optionType, pattern) ? " SUSPENDED-for-day" : ""));
            // §14.4 month memory: persist the episode row (the scoreboard learner's raw material).
            if (episodeRepo != null) {
                double pain = 0; long build = 0;
                Inst in = byKey.get(idx + ":" + strike + ":" + optionType);
                if (in != null) { pain = in.painPct; build = in.cumBuild; }
                episodeRepo.save(new com.algo.trade.persistence.MemoryEpisodeEntity(
                        LocalDate.now(IST).toString(), idx, optionType, pattern, strike, netPct, pain, build));
            }
        } catch (Exception e) {
            log.debug("[MarketMemory] recordEpisode skipped: {}", e.toString());
        }
    }

    /**
     * §14.5 analog-days library: ONE fingerprint row per index per day. Written at 15:45 IST and
     * again defensively at rollover (id upsert — idempotent). The analog MATCHER stays gated until
     * weeks of rows exist; this only accumulates the library.
     */
    @Scheduled(cron = "0 45 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void writeDayFingerprints() {
        if (!enabled || dayFpRepo == null) return;
        try {
            Map<String, long[]> agg = new java.util.HashMap<>(); // idx -> [dayBuild, maxPainx100, minDte]
            for (Inst in : byKey.values()) {
                long[] a = agg.computeIfAbsent(in.idx, k -> new long[]{0, 0, 9999});
                a[0] += Math.max(0, in.cumBuild - in.sodCumBuild);
                a[1] = Math.max(a[1], (long) (in.painPct * 100));
                if (!in.expiry.isEmpty()) {
                    try {
                        long dte = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(IST), LocalDate.parse(in.expiry));
                        a[2] = Math.min(a[2], Math.max(0, dte));
                    } catch (Exception ignore) { }
                }
            }
            String today = LocalDate.now(IST).toString();
            for (var e : agg.entrySet()) {
                int epN = 0; double epNet = 0;
                for (var em : episodes.entrySet()) {
                    if (em.getKey().startsWith(e.getKey() + "|")) { epN += (int) em.getValue()[0]; epNet += em.getValue()[1]; }
                }
                dayFpRepo.save(new com.algo.trade.persistence.DayFingerprintEntity(
                        today, e.getKey(), (int) e.getValue()[2], avalanchesSeenToday.get(), epN, epNet,
                        e.getValue()[0], e.getValue()[1] / 100.0, battleMinutes.getOrDefault(e.getKey(), new java.util.concurrent.atomic.AtomicInteger()).get()));
            }
            log.info("[MarketMemory] day fingerprints written for {} indices ({})", agg.size(), today);
        } catch (Exception e) {
            log.warn("[MarketMemory] day fingerprint write failed: {}", e.toString());
        }
    }

    /** Per-index BATTLE minutes for the day fingerprint — sampled once a minute. */
    private final ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicInteger> battleMinutes = new ConcurrentHashMap<>();
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    void sampleBattleMinutes() {
        if (!enabled) return;
        try {
            java.util.Set<String> inBattle = new java.util.HashSet<>();
            for (Inst in : byKey.values()) {
                MemorySnapshot s = in.snap;
                if (s != null && s.battle()) inBattle.add(in.idx);
            }
            for (String idx : inBattle)
                battleMinutes.computeIfAbsent(idx, k -> new java.util.concurrent.atomic.AtomicInteger()).incrementAndGet();
        } catch (Exception ignore) { }
    }

    private void rolloverIfNewDay() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(day)) {
            synchronized (this) {
                if (!today.equals(day)) {
                    writeDayFingerprints();                          // defensive second write (idempotent upsert)
                    persistMountains();                              // save the week memory before reset
                    byToken.clear(); byKey.clear(); episodes.clear();
                    eventsToday.set(0); avalanchesSeenToday.set(0); battleMinutes.clear();
                    day = today;
                    log.info("[MarketMemory] day rollover -> {} (day memory reset; week memory persisted)", today);
                }
            }
        }
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
    private static double round2(double v) { return Math.round(v * 100) / 100.0; }
}
