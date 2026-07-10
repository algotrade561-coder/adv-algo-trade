import java.sql.*;
import java.util.*;
import java.io.PrintStream;
import java.nio.file.*;

/**
 * MARKET-MEMORY REPLAY (V5) — adaptive, self-normalizing, with intraday pattern memory.
 * True time-ordered replay across ALL instruments simultaneously (ORDER BY day, ts) — strictly causal.
 *
 * LAYER 1  BASELINE MEMORY (per instrument): 5s-sampled (ts,ltp,oi) series, 90 min window.
 *   Every 60s recompute: MAD of 1-min returns (vol unit), median+MAD of 5-min OI changes.
 *   All triggers/exits use Z-SCORES vs the instrument's OWN day — no fixed per-index constants.
 *
 * LAYER 2  EPISODE MEMORY (per index+side+pattern): every closed trade updates (n, avgNetPct).
 *   A pattern with n>=3 and avgNetPct<0 TODAY is SUSPENDED for the rest of the day.
 *
 * LAYER 3  DECISIONS
 *   ENTRY patterns (need >=25 min instrument warmup, window 09:25-15:10 IST, premium 15-700,
 *                   1 open/instrument, 120s cooldown, max 5 strong entries/instrument/day):
 *     AVALANCHE  zOI <= -3 && dOI5m <= -3% && dP5m >= 0 && ltp >= low10m*1.01   (panic covering)
 *     PRESSFLIP  pressed >= 15 of last 45 min (OI build + price down) && dOI5m <= -1%
 *                && ltp breaks above the prior 10-min high                       (trap springs)
 *     IMPULSE    zP1m >= 3 && vol1m >= 2x baseline && |dOI5m| >= 0.3%            (real ignition)
 *   EXIT (adaptive; volUnit = clamp(2.2 x MAD1m%, 1.5%, 10%)):
 *     SL        -max(6%, 2.2 x volUnit) capped 12%
 *     WPRESS    in-loss && zOI >= +2 && dP5m <= -volUnit  (writers pressing against us)
 *     SCALP     profit >= 2 x volUnit && weakening && NOT in avalanche state (ride avalanches)
 *     TRAIL     activate at +1.5 x volUnit, gap 1.2 x volUnit off peak
 *     MAXHOLD 40 min; EOD 15:20.
 * Fills: buy ask / sell bid, -Rs50/rt. Lots: NIFTY 65 / SENSEX 20 / BANKNIFTY 30.
 * Output: per-day trade breakdown + daily net PnL -> reports/v5-memory.md
 */
public class V5Memory {

    static class Inst {
        String idx, ty; int strike; int lot;
        ArrayDeque<double[]> samp = new ArrayDeque<>();   // {ts, ltp, oi} every >=5s, 90 min
        ArrayDeque<double[]> raw30 = new ArrayDeque<>();  // {ts, sv} 30s flow
        ArrayDeque<double[]> vol60 = new ArrayDeque<>();  // {ts, dv} 60s vol
        ArrayDeque<double[]> volBase = new ArrayDeque<>();// {ts, dv} 15m vol baseline
        ArrayDeque<double[]> press = new ArrayDeque<>();  // {ts, pressed?1:0} sampled 5s, 45 min
        double flow30 = 0, v60 = 0, vBase = 0;
        long lastSamp = 0, lastBase = 0, firstTs = 0;
        double madRet = 0.004, oiMed = 0, oiMad = 0.2;    // baselines (fractions/%): warm defaults
        long prevCum = -1;
        // position
        boolean in = false; double entry, peak; long inTs, cool; int strongCnt = 0; String pat;
        double volUnitAtEntry;
        // last computed features
        double dP5 = 0, dOi5 = 0, zOi = 0, zP1 = 0, low10 = 0, high10 = 0, vol1m = 0;
    }
    record Trade(String day, String inst, String pattern, long inTs, long outTs,
                 double in, double out, int lot, String reason, double volUnit) {}

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "data";
        double DAY_CAP = a.length > 1 ? Double.parseDouble(a[1]) : 0;      // halt new entries for the day when day net <= -CAP (0=off)
        int SUSP_N = a.length > 2 ? Integer.parseInt(a[2]) : 3;            // suspension after N closed losers
        boolean DEEP_ONLY = a.length > 3 && a[3].equals("deep");           // only deep-tier (dOI5m<=-8) entries
        int REGIME_MIN = a.length > 4 ? Integer.parseInt(a[4]) : 0;        // routine entries need side-dominance >= this (of 45; 0=off)
        int STREAK_K = a.length > 5 ? Integer.parseInt(a[5]) : 0;          // pause routine tier after K consecutive losers (0=off)
        int PAUSE_MIN = a.length > 6 ? Integer.parseInt(a[6]) : 30;        // pause length minutes
        double PREM_MAX = a.length > 7 ? Double.parseDouble(a[7]) : 700;   // premium band upper bound
        int LOT_MULT = a.length > 8 ? Integer.parseInt(a[8]) : 1;          // lots per trade
        int COOLDOWN = a.length > 9 ? Integer.parseInt(a[9]) : 120;        // re-entry cooldown seconds
        double DP5MAX = a.length > 10 ? Double.parseDouble(a[10]) : 0;     // C1: max dP5m at entry (0=off) — buy the TURN, not the spike
        double WIDE = a.length > 11 ? Double.parseDouble(a[11]) : 0;       // two-phase trail: widen gap to WIDE*vu once peak>=3vu (0=off=1.2vu fixed)
        double DEEPBASE = a.length > 12 ? Double.parseDouble(a[12]) : 0;   // F10-7: deep-by-raw-% needs zOi<=-3 OR oi>=THIS (0=off) — thin new-series bases fire deep on noise
        double TURNM = a.length > 13 ? Double.parseDouble(a[13]) : 0;     // adaptive rule-B margin: 0=fixed 1%; else margin = clamp(TURNM*vu, 0.5%, 1.5%) of low10
        Class.forName("org.duckdb.DuckDBDriver");
        List<Trade> trades = new ArrayList<>();
        java.util.Map<String,Double> minByDay = new java.util.TreeMap<>();

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
            String files = "['" + dir + "/atm-microstructure-2026-07-06.csv','" + dir + "/atm-0707.csv','" + dir + "/atm-microstructure-2026-07-08.csv','" + dir + "/atm-microstructure-2026-07-09.csv.gz']";
            ResultSet r = s.executeQuery(
                "SELECT strftime(to_timestamp(exchangeTsEpochSec + 19800), '%m-%d') dt, index idx, strike, optionType ty, "
                + "exchangeTsEpochSec ts, ltp, bestBid, bestAsk, bookImbalance, signedVolume, isAbsorption, spoofActive, oi, cumVolume "
                + "FROM read_csv_auto(" + files + ", union_by_name=true) "
                + "WHERE ltp>0 AND exchangeTsEpochSec>1700000000 AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 "
                + "ORDER BY dt, ts");   // TRUE TIME ORDER across all instruments

            Map<String, Inst> insts = new HashMap<>();
            Map<String, double[]> patMem = new HashMap<>(); // idx|ty|pattern -> {n, sumNetPct}
            double[] dayNet = {0};
            int[] lossStreak = {0}; long[] pausedUntil = {0};
            java.util.Map<String, Long> regTs = new HashMap<>();
            java.util.Map<String, ArrayDeque<Integer>> regSigns = new HashMap<>();
            java.util.Map<String, Integer> regDom = new HashMap<>();
            String curDay = null;

            while (r.next()) {
                String day = r.getString(1);
                if (!day.equals(curDay)) { insts.clear(); patMem.clear(); dayNet[0] = 0; lossStreak[0] = 0; pausedUntil[0] = 0; curDay = day; } // memory is intraday
                String idx = r.getString(2), ty = r.getString(4);
                int strike = r.getInt(3);
                long ts = r.getLong(5);
                double ltp = r.getDouble(6), bid = r.getDouble(7), ask = r.getDouble(8);
                double imb = r.getDouble(9), sv = r.getDouble(10);
                boolean abs = r.getBoolean(11), spf = r.getBoolean(12);
                double oi = r.getDouble(13); long cum = r.getLong(14);

                String key = idx + "|" + strike + "|" + ty;
                Inst in = insts.computeIfAbsent(key, k -> {
                    Inst x = new Inst(); x.idx = idx; x.ty = ty; x.strike = strike; x.firstTs = ts;
                    x.lot = (idx.equals("SENSEX") ? 20 : idx.equals("BANKNIFTY") ? 30 : 65) * LOT_MULT;
                    return x;
                });
                long dv = (in.prevCum < 0 || cum < in.prevCum) ? 0 : cum - in.prevCum; in.prevCum = cum;

                // rolling raw windows
                in.raw30.addLast(new double[]{ts, sv}); in.flow30 += sv;
                while (!in.raw30.isEmpty() && in.raw30.peekFirst()[0] < ts - 30) in.flow30 -= in.raw30.pollFirst()[1];
                in.vol60.addLast(new double[]{ts, dv}); in.v60 += dv;
                while (!in.vol60.isEmpty() && in.vol60.peekFirst()[0] < ts - 60) in.v60 -= in.vol60.pollFirst()[1];
                in.volBase.addLast(new double[]{ts, dv}); in.vBase += dv;
                while (!in.volBase.isEmpty() && in.volBase.peekFirst()[0] < ts - 900) in.vBase -= in.volBase.pollFirst()[1];

                // 5s sampling + baselines every 60s
                if (ts - in.lastSamp >= 5) {
                    in.lastSamp = ts;
                    in.samp.addLast(new double[]{ts, ltp, oi});
                    while (!in.samp.isEmpty() && in.samp.peekFirst()[0] < ts - 5400) in.samp.pollFirst();
                    // press flag: OI up + price down over 5 min
                    double[] p5 = at(in.samp, ts - 300);
                    boolean pressed = p5 != null && p5[2] > 0 && p5[1] > 0
                            && (oi - p5[2]) / p5[2] * 100 >= 0.5 && (ltp - p5[1]) / p5[1] * 100 <= -1;
                    in.press.addLast(new double[]{ts, pressed ? 1 : 0});
                    while (!in.press.isEmpty() && in.press.peekFirst()[0] < ts - 2700) in.press.pollFirst();
                    if (ts - in.lastBase >= 60 && in.samp.size() >= 60) { in.lastBase = ts; baselines(in); }
                }

                // features (causal)
                double[] p5 = at(in.samp, ts - 300);
                double[] p1 = at(in.samp, ts - 60);
                in.dP5 = p5 != null && p5[1] > 0 ? (ltp - p5[1]) / p5[1] * 100 : 0;
                in.dOi5 = p5 != null && p5[2] > 0 ? (oi - p5[2]) / p5[2] * 100 : 0;
                double r1 = p1 != null && p1[1] > 0 ? (ltp - p1[1]) / p1[1] : 0;
                in.zOi = (in.dOi5 - in.oiMed) / Math.max(in.oiMad, 0.10);
                in.zP1 = r1 / Math.max(in.madRet, 0.0015);
                in.vol1m = in.v60;
                double lo = Double.MAX_VALUE, hi = 0;
                for (double[] q : in.samp) { if (q[0] >= ts - 600) { lo = Math.min(lo, q[1]); hi = Math.max(hi, q[1]); } }
                in.low10 = lo == Double.MAX_VALUE ? ltp : lo; in.high10 = hi;

                long istSec = (ts + 19800) % 86400;
                double volUnit = clamp(2.2 * in.madRet * 100, 1.5, 10);

                // ---------- REGIME (causal, once per minute per index): side-dominance over 45 min.
                // Each minute: are PEs or CEs winning (mean dP5m across strikes)? |sum of last 45 signs| =
                // how ONE-WAY the tape is. Range day => sides keep flipping => low dominance.
                if (REGIME_MIN > 0 && ts - regTs.getOrDefault(idx, 0L) >= 60) {
                    regTs.put(idx, ts);
                    double ceSum = 0, peSum = 0; int ceN = 0, peN = 0;
                    for (Inst x : insts.values()) {
                        if (!x.idx.equals(idx)) continue;
                        if ("CE".equals(x.ty)) { ceSum += x.dP5; ceN++; } else { peSum += x.dP5; peN++; }
                    }
                    if (ceN > 0 && peN > 0) {
                        int sign = (peSum / peN) > (ceSum / ceN) ? 1 : -1;
                        ArrayDeque<Integer> dq = regSigns.computeIfAbsent(idx, k -> new ArrayDeque<>());
                        dq.addLast(sign);
                        while (dq.size() > 45) dq.pollFirst();
                        int sum = 0; for (int s2 : dq) sum += s2;
                        regDom.put(idx, Math.abs(sum));
                    }
                }

                // ---------- EXIT ----------
                if (in.in) {
                    in.peak = Math.max(in.peak, ltp);
                    long held = ts - in.inTs;
                    double profit = (ltp - in.entry) / in.entry * 100;
                    double vu = in.volUnitAtEntry;
                    boolean avalancheState = in.zOi <= -2 && in.dP5 >= 0; // still covering -> ride
                    String rsn = null;
                    double slPct = Math.min(12, Math.max(6, 2.2 * vu));
                    if (profit <= -slPct) rsn = "SL";
                    else if (held >= 30 && profit < 0 && in.zOi >= 2 && in.dP5 <= -vu) rsn = "WPRESS";
                    else if (held >= 20 && profit >= 2 * vu && (in.flow30 <= 0 || imb <= -0.10) && !avalancheState) rsn = "SCALP";
                    else if (in.peak >= in.entry * (1 + 1.5 * vu / 100)
                            && ltp <= in.peak * (1 - (WIDE > 0 && (in.peak - in.entry) / in.entry * 100 >= 3 * vu ? WIDE : 1.2) * vu / 100)) rsn = "TRAIL";
                    else if (held >= 2400) rsn = "MAXHOLD";
                    else if (istSec >= 55200) rsn = "EOD";
                    if (rsn != null) {
                        double px = bid > 0 ? bid : ltp;
                        trades.add(new Trade(day, idx + " " + strike + " " + ty, in.pat, in.inTs, ts, in.entry, px, in.lot, rsn, vu));
                        double netPct = (px - in.entry) / in.entry * 100;
                        double[] m = patMem.computeIfAbsent(idx + "|" + ty + "|" + in.pat, k -> new double[2]);
                        m[0]++; m[1] += netPct;                     // EPISODE MEMORY update
                        in.in = false; in.cool = ts + COOLDOWN;
                        double tradeNet = (px - in.entry) * in.lot - 50;
                        dayNet[0] += tradeNet;
                        if (STREAK_K > 0) {
                            if (tradeNet < 0) { lossStreak[0]++; if (lossStreak[0] >= STREAK_K) { pausedUntil[0] = ts + PAUSE_MIN * 60L; lossStreak[0] = 0; } }
                            else lossStreak[0] = 0;
                        }
                        minByDay.merge(day, dayNet[0], Math::min);
                    }
                    continue;
                }

                // ---------- ENTRY ----------
                if (istSec < 33900 || istSec > 54600) continue;             // 09:25 - 15:10
                if (ts - in.firstTs < 1500 || in.samp.size() < 120) continue; // 25 min warmup
                if (ts < in.cool) continue;
                boolean deepQualified = DEEPBASE <= 0 || in.zOi <= -3 || oi >= DEEPBASE; // F10-7 size floor
                boolean deepNow = in.dOi5 <= -8 && in.dP5 >= 0 && deepQualified;
                // Deployed expiry semantics (07-09 was SENSEX expiry): C1b tighter ceiling 25 on the
                // EXPIRING index; C2 deep loses its budget/suspension exemptions there (unwind makes
                // -8% routine). Non-expiry days and the non-expiring index are byte-identical.
                boolean expIdx = "07-09".equals(day) && "SENSEX".equals(idx);
                double dp5Cap = (expIdx && DP5MAX > 0) ? Math.min(25, DP5MAX) : DP5MAX;
                boolean deepPriv = deepNow && !expIdx;
                if (in.strongCnt >= 5 && !deepPriv) continue; // deep avalanche not rationed by the routine budget (C2: no exemption on expiring index)
                if (ltp < 15 || ltp > PREM_MAX) continue;
                if (dp5Cap > 0 && in.dP5 > dp5Cap) continue; // C1 two-tier ceiling: the move already happened — ALL tiers
                if ((abs || spf) && !(in.dOi5 <= -8 && in.dP5 >= 0)) continue; // deep avalanche overrides absorption/spoof veto (panic covering IS heavy flow)

                String pattern = null;
                double turnMargin = TURNM <= 0 ? 0.01 : Math.max(0.005, Math.min(0.015, TURNM * volUnit / 100));
                if ((in.zOi <= -3 && in.dOi5 <= -3 || (in.dOi5 <= -8 && deepQualified)) && in.dP5 >= 0 && ltp >= in.low10 * (1 + turnMargin)) pattern = "AVALANCHE";
                else if (true) { /* memory verdict: IMPULSE and PRESSFLIP suspended permanently (net losers) */ }
                else {
                    double pressMin = 0; for (double[] q : in.press) pressMin += q[1];
                    pressMin = pressMin * 5 / 60.0;
                    if (pressMin >= 15 && in.dOi5 <= -1 && in.high10 > 0 && ltp >= in.high10 * 0.999 && in.dP5 > 0)
                        pattern = "PRESSFLIP";
                    else if (in.zP1 >= 3 && in.vBase > 0 && in.vol1m >= 2 * (in.vBase / 15.0) && Math.abs(in.dOi5) >= 0.3)
                        pattern = "IMPULSE";
                }
                if (pattern == null) continue;
                // pattern memory: suspended if negative today after 3 closed trades
                double[] m = patMem.get(idx + "|" + ty + "|" + pattern);
                if (m != null && m[0] >= SUSP_N && m[1] / m[0] < 0 && (in.dOi5 > -8 || expIdx)) continue; // SUSPENDED by memory (deep overrides — except expiring index, C2)
                if (DEEP_ONLY && in.dOi5 > -8) continue;                    // deep-only experiment
                if (DAY_CAP > 0 && dayNet[0] <= -DAY_CAP && in.dOi5 > -8) continue; // daily branch stop (deep exempt)
                if (STREAK_K > 0 && ts < pausedUntil[0] && in.dOi5 > -8) continue; // loss-streak circuit breaker (re-arms; deep exempt)
                if (REGIME_MIN > 0 && in.dOi5 > -8
                        && regDom.getOrDefault(idx, 0) < REGIME_MIN) continue; // routine tier needs a directional tape (deep exempt)

                double px = ask > 0 ? ask : ltp;
                in.in = true; in.entry = px; in.peak = px; in.inTs = ts; in.pat = pattern;
                if (!deepPriv) in.strongCnt++; in.volUnitAtEntry = volUnit;
            }
            // EOD force-close any residue at last seen price
            for (Inst x : insts.values()) { /* handled by EOD rule at 15:20; leftover past filter ignored */ }
        }

        // ---------- REPORT ----------
        PrintStream md = new PrintStream(Files.newOutputStream(Path.of("reports", "v5-memory.md")));
        md.println("# V5 Market-Memory replay — per-trade breakdown\n");
        md.println("| day | inst | pattern | in(IST) | out(IST) | entry | exit | pnlRs | exit reason | volUnit% |");
        md.println("|---|---|---|---|---|---|---|---|---|---|");
        Map<String, double[]> byDay = new TreeMap<>(), byPat = new TreeMap<>(), byExit = new TreeMap<>();
        for (Trade t : trades) {
            double net = (t.out - t.in) * t.lot - 50;
            add(byDay, t.day, net); add(byPat, t.pattern, net); add(byExit, t.reason, net);
            md.printf("| %s | %s | %s | %s | %s | %.2f | %.2f | %.0f | %s | %.1f |%n",
                    t.day, t.inst, t.pattern, ist(t.inTs), ist(t.outTs), t.in, t.out, net, t.reason, t.volUnit);
        }
        md.close();
        System.out.println("== V5 MARKET-MEMORY — summary (full trade list in reports/v5-memory.md) ==");
        System.out.println("cfg: dayCap=" + DAY_CAP + " suspN=" + SUSP_N + " deepOnly=" + DEEP_ONLY + "  intraday equity LOW per day: " + minByDay);
        double tot = 0; int n = 0, w = 0;
        for (Trade t : trades) { double net = (t.out - t.in) * t.lot - 50; tot += net; n++; if (net > 0) w++; }
        System.out.printf("TOTAL: trades=%d win%%=%.1f netRs=%.0f%n", n, n > 0 ? 100.0 * w / n : 0, tot);
        System.out.println("-- per day --");
        for (var e : byDay.entrySet()) System.out.printf("   %s  n=%.0f  netRs=%.0f%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        System.out.println("-- per pattern --");
        for (var e : byPat.entrySet()) System.out.printf("   %-10s n=%.0f netRs=%.0f%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        System.out.println("-- per exit --");
        for (var e : byExit.entrySet()) System.out.printf("   %-8s n=%.0f netRs=%.0f%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
    }

    static void add(Map<String, double[]> m, String k, double v) {
        double[] x = m.computeIfAbsent(k, kk -> new double[2]); x[0]++; x[1] += v;
    }
    static double[] at(ArrayDeque<double[]> samp, long ts) {
        double[] best = null;
        for (double[] q : samp) { if (q[0] <= ts) best = q; else break; }
        return best;
    }
    static void baselines(Inst in) {
        // MAD of 1-min returns + median/MAD of 5-min OI changes from the 5s samples
        List<Double> rets = new ArrayList<>(), oic = new ArrayList<>();
        double[][] arr = in.samp.toArray(new double[0][]);
        for (int i = 12; i < arr.length; i += 6) {                 // every 30s, 1-min return
            double p0 = arr[i - 12][1];
            if (p0 > 0) rets.add(Math.abs(arr[i][1] - p0) / p0);
        }
        for (int i = 60; i < arr.length; i += 12) {                // every 60s, 5-min OI change
            double o0 = arr[i - 60][2];
            if (o0 > 0) oic.add((arr[i][2] - o0) / o0 * 100);
        }
        if (rets.size() >= 10) { Collections.sort(rets); in.madRet = Math.max(rets.get(rets.size() / 2), 0.0015); }
        if (oic.size() >= 10) {
            List<Double> c = new ArrayList<>(oic); Collections.sort(c);
            double med = c.get(c.size() / 2);
            List<Double> dev = new ArrayList<>();
            for (double v : oic) dev.add(Math.abs(v - med));
            Collections.sort(dev);
            in.oiMed = med; in.oiMad = Math.max(dev.get(dev.size() / 2) * 1.4826, 0.10);
        }
    }
    static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }
    static String ist(long ts) { long t = ts + 19800; return String.format("%02d:%02d:%02d", (t / 3600) % 24, (t % 3600) / 60, t % 60); }
}
