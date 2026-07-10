import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * DORMANT-STRATEGY PROBES (2026-07-09) — test the testable strategies on the 3-day tape + spot series.
 *
 * MOMO   — MomentumStrategy replicated on its native inputs (1-min spot bars from momentum-regime CSVs):
 *          ROC5 >= 0.25% (0.45% 11:30-14:30), same-direction acceleration, EMA-21 alignment; volume gate
 *          skipped (index spot has no volume, matches live OI_PROXY path); ATR gate approximated via
 *          close-to-close TR (halved threshold) — score gate ~= roc>0.60 OR accel>=1.5x prev.
 *          Entry: ATM option at ask. Exits: legacy profile (SL -10, target +20, trail 8/4, EOD 15:20).
 *          Also flags OVERLAP: avalanche replay entry same day+index+side within +-10 min.
 * TRAP   — OiShiftTrap CORE THESIS (live class has ~15 extra vetoes; this tests the base edge):
 *          strike within 0.75% of spot, trapped-side OI >= 1.5x opposite AND >= 50k, trapped side
 *          still BUILDING (dOI30m >= +2%), structural gate (CE trap: strike <= spot; PE trap: strike >= spot).
 *          Entry: buy trapped side at ask. Exits: vol-unit (SL 2.2vu clamp 6-12, trail 1.5vu/1.2vu, 40m, EOD).
 * MRFADE — premium mean-reversion cousin (live MR is index-VWAP; untestable as-is): fade a 15-min premium
 *          drop of >= 3 vol-units with FLAT OI (no writer pressing). Target +1.5vu, SL -2vu, 30m cap.
 *
 * Fills ask/bid, -Rs50/rt, 1 lot (N65/S20/B30). Same verdict standard as V5Memory/HoldScan.
 */
public class StratProbes {

    static class Inst {
        String idx, ty; int strike; int lot;
        ArrayDeque<double[]> samp = new ArrayDeque<>();   // {ts, ltp, oi}
        long lastSamp = 0, lastBase = 0, firstTs = 0;
        double madRet = 0.004; long prevCum = -1;
        double lastAsk = 0, lastBid = 0, lastLtp = 0; long lastTick = 0; double lastOi = 0;
        boolean absNow = false, spfNow = false;
        // per-probe position state: 0=MOMO 1=TRAP 2=MRFADE
        boolean[] in = new boolean[3]; double[] entry = new double[3], peak = new double[3];
        long[] inTs = new long[3], cool = new long[3]; int[] cnt = new int[3];
        double[] vuAt = new double[3];
    }
    record Trade(String day, String probe, String inst, long inTs, long outTs,
                 double in, double out, int lot, String reason, boolean overlap) {}

    public static void main(String[] args) throws Exception {
        String dir = args.length > 0 ? args[0] : "data";
        // ---- spot series: day -> idx -> (secOfDayIST -> spot), plus ordered minute closes ----
        Map<String, Map<String, TreeMap<Long, Double>>> spotSeries = new HashMap<>();
        Map<String, String> regimeFiles = Map.of(
                "07-06", dir + "/momentum-regime-2026-07-06.csv",
                "07-07", dir + "/momentum-regime-2026-07-07.csv",
                "07-08", dir + "/momentum-regime-2026-07-08.csv");
        for (var e : regimeFiles.entrySet()) {
            for (String line : Files.readAllLines(Path.of(e.getValue()))) {
                String[] f = line.split(",");
                if (f.length < 3 || f[0].equals("time")) continue;
                String[] hms = f[0].split(":");
                long sec = Long.parseLong(hms[0]) * 3600 + Long.parseLong(hms[1]) * 60 + Long.parseLong(hms[2]);
                double spot = Double.parseDouble(f[2]);
                if (spot <= 0) continue;
                spotSeries.computeIfAbsent(e.getKey(), k -> new HashMap<>())
                        .computeIfAbsent(f[1], k -> new TreeMap<>()).put(sec, spot);
            }
        }
        // ---- avalanche replay entries (overlap flagging): "day|idx|side" -> list of inSec ----
        Map<String, List<Long>> avEntries = new HashMap<>();
        Path v5md = Path.of("reports", "v5-memory.md");
        if (Files.exists(v5md)) {
            for (String line : Files.readAllLines(v5md)) {
                if (!line.startsWith("| 07-")) continue;
                String[] f = line.split("\\|");
                if (f.length < 6) continue;
                String day = f[1].trim();
                String[] instParts = f[2].trim().split(" ");     // "SENSEX 78900 CE"
                if (instParts.length != 3) continue;
                String[] hms = f[4].trim().split(":");
                if (hms.length != 3) continue;
                long sec = Long.parseLong(hms[0]) * 3600 + Long.parseLong(hms[1]) * 60 + Long.parseLong(hms[2]);
                avEntries.computeIfAbsent(day + "|" + instParts[0] + "|" + instParts[2], k -> new ArrayList<>()).add(sec);
            }
        }

        Class.forName("org.duckdb.DuckDBDriver");
        List<Trade> trades = new ArrayList<>();
        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
            String files = "['" + dir + "/atm-microstructure-2026-07-06.csv','" + dir + "/atm-0707.csv','" + dir + "/atm-microstructure-2026-07-08.csv']";
            ResultSet r = s.executeQuery(
                "SELECT strftime(to_timestamp(exchangeTsEpochSec + 19800), '%m-%d') dt, index idx, strike, optionType ty, "
                + "exchangeTsEpochSec ts, ltp, bestBid, bestAsk, isAbsorption, spoofActive, oi "
                + "FROM read_csv_auto(" + files + ", union_by_name=true) "
                + "WHERE ltp>0 AND exchangeTsEpochSec>1700000000 AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 "
                + "ORDER BY dt, ts");

            Map<String, Inst> insts = new HashMap<>();                 // day|idx|strike|ty
            Map<String, TreeMap<Integer, Inst[]>> chain = new HashMap<>(); // day|idx -> strike -> [CE, PE]
            Map<String, Long> lastEvalMin = new HashMap<>();           // day|idx -> minute
            Map<String, long[]> momoCoolByDir = new HashMap<>();       // day|idx -> {ceCoolTs, peCoolTs}
            Map<String, String> momoOpen = new HashMap<>();            // day|idx -> instKey of open MOMO pos
            Map<String, List<Double>> spotCloses = new HashMap<>();    // day|idx -> minute closes so far
            Map<String, Long> lastCloseMin = new HashMap<>();
            String curDay = null;

            while (r.next()) {
                String day = r.getString(1);
                if (!day.equals(curDay)) { insts.clear(); chain.clear(); lastEvalMin.clear();
                    momoCoolByDir.clear(); momoOpen.clear(); spotCloses.clear(); lastCloseMin.clear(); curDay = day; }
                String idx = r.getString(2), ty = r.getString(4);
                int strike = r.getInt(3);
                long ts = r.getLong(5);
                double ltp = r.getDouble(6), bid = r.getDouble(7), ask = r.getDouble(8);
                boolean abs = r.getBoolean(9), spf = r.getBoolean(10);
                double oi = r.getDouble(11);
                long istSec = (ts + 19800) % 86400;

                String key = day + "|" + idx + "|" + strike + "|" + ty;
                Inst in = insts.computeIfAbsent(key, k -> {
                    Inst x = new Inst(); x.idx = idx; x.ty = ty; x.strike = strike; x.firstTs = ts;
                    x.lot = idx.equals("SENSEX") ? 20 : idx.equals("BANKNIFTY") ? 30 : 65;
                    chain.computeIfAbsent(day + "|" + idx, kk -> new TreeMap<>())
                            .computeIfAbsent(strike, kk -> new Inst[2])[ "CE".equals(ty) ? 0 : 1 ] = x;
                    return x;
                });
                in.lastAsk = ask; in.lastBid = bid; in.lastLtp = ltp; in.lastTick = ts; in.lastOi = oi;
                in.absNow = abs; in.spfNow = spf;
                if (ts - in.lastSamp >= 5) {
                    in.lastSamp = ts;
                    in.samp.addLast(new double[]{ts, ltp, oi});
                    while (!in.samp.isEmpty() && in.samp.peekFirst()[0] < ts - 5400) in.samp.pollFirst();
                    if (ts - in.lastBase >= 60 && in.samp.size() >= 60) { in.lastBase = ts; baselines(in); }
                }

                // ---------- EXITS for any open probe position on this instrument ----------
                for (int p = 0; p < 3; p++) {
                    if (!in.in[p]) continue;
                    in.peak[p] = Math.max(in.peak[p], ltp);
                    long held = ts - in.inTs[p];
                    double profit = (ltp - in.entry[p]) / in.entry[p] * 100;
                    double vu = in.vuAt[p];
                    String rsn = null;
                    if (p == 0) { // MOMO legacy exits
                        if (profit <= -10) rsn = "SL";
                        else if (profit >= 20) rsn = "TARGET";
                        else if (in.peak[p] >= in.entry[p] * 1.08 && ltp <= in.peak[p] * 0.96) rsn = "TRAIL";
                        else if (istSec >= 55200) rsn = "EOD";
                    } else if (p == 1) { // TRAP vol-unit exits
                        double sl = Math.min(12, Math.max(6, 2.2 * vu));
                        if (profit <= -sl) rsn = "SL";
                        else if (in.peak[p] >= in.entry[p] * (1 + 1.5 * vu / 100) && ltp <= in.peak[p] * (1 - 1.2 * vu / 100)) rsn = "TRAIL";
                        else if (held >= 2400) rsn = "MAXHOLD";
                        else if (istSec >= 55200) rsn = "EOD";
                    } else { // MRFADE
                        if (profit >= 1.5 * vu) rsn = "TARGET";
                        else if (profit <= -2 * vu) rsn = "SL";
                        else if (held >= 1800) rsn = "MAXHOLD";
                        else if (istSec >= 55200) rsn = "EOD";
                    }
                    if (rsn != null) {
                        double px = bid > 0 ? bid : ltp;
                        boolean ov = false;
                        if (p == 0) momoOpen.remove(day + "|" + idx);
                        List<Long> avs = avEntries.get(day + "|" + idx + "|" + ty);
                        if (avs != null) for (long a2 : avs) if (Math.abs(a2 - ((in.inTs[p] + 19800) % 86400)) <= 600) { ov = true; break; }
                        trades.add(new Trade(day, p == 0 ? "MOMO" : p == 1 ? "TRAP" : "MRFADE",
                                idx + " " + strike + " " + ty, in.inTs[p], ts, in.entry[p], px, in.lot, rsn, ov));
                        in.in[p] = false; in.cool[p] = ts + (p == 0 ? 300 : 600);
                    }
                }

                // ---------- MRFADE entry (per tick, per instrument) ----------
                if (istSec >= 33900 && istSec <= 54600 && ts - in.firstTs >= 1500 && in.samp.size() >= 120
                        && !in.in[2] && ts >= in.cool[2] && in.cnt[2] < 5 && !abs && !spf
                        && ltp >= 15 && ltp <= 700) {
                    double[] p5 = at(in.samp, ts - 300);
                    double[] p15 = at(in.samp, ts - 900);
                    double dOi5 = p5 != null && p5[2] > 0 ? (oi - p5[2]) / p5[2] * 100 : 0;
                    double dP15 = p15 != null && p15[1] > 0 ? (ltp - p15[1]) / p15[1] * 100 : 0;
                    double vu = Math.max(1.5, Math.min(10, 2.2 * in.madRet * 100));
                    if (dP15 <= -3 * vu && Math.abs(dOi5) < 0.5) {
                        double px = ask > 0 ? ask : ltp;
                        in.in[2] = true; in.entry[2] = px; in.peak[2] = px; in.inTs[2] = ts; in.cnt[2]++; in.vuAt[2] = vu;
                    }
                }

                // ---------- once-per-minute evals (MOMO + TRAP) for this index ----------
                String di = day + "|" + idx;
                long minute = istSec / 60;
                Long lastMin = lastEvalMin.get(di);
                if (lastMin != null && minute <= lastMin) continue;
                TreeMap<Long, Double> spots = spotSeries.getOrDefault(day, Map.of()).get(idx);
                if (spots == null) { lastEvalMin.put(di, minute); continue; }
                Map.Entry<Long, Double> spotE = spots.floorEntry(istSec);
                if (spotE == null || istSec - spotE.getKey() > 120) { lastEvalMin.put(di, minute); continue; }
                lastEvalMin.put(di, minute);
                double spot = spotE.getValue();
                // accumulate 1-min closes (fill from series, not tape cadence)
                List<Double> closes = spotCloses.computeIfAbsent(di, k -> new ArrayList<>());
                Long lcm = lastCloseMin.get(di);
                for (var se : spots.headMap(istSec, true).tailMap(lcm == null ? 0L : (lcm + 1) * 60, true).entrySet()) {
                    closes.add(se.getValue());
                    lastCloseMin.put(di, se.getKey() / 60);
                }
                int interval = idx.equals("SENSEX") ? 100 : 50;

                // ----- MOMO eval -----
                if (istSec >= 33600 && istSec <= 54900 && closes.size() >= 22) {
                    int n = closes.size();
                    double cNow = closes.get(n - 1), c5 = closes.get(n - 6), c1 = closes.get(n - 2), c6 = closes.get(n - 7);
                    double roc = (cNow - c5) / c5 * 100, prevRoc = (c1 - c6) / c6 * 100;
                    double thr = (istSec >= 41400 && istSec < 52200) ? 0.45 : 0.25;   // 11:30-14:30
                    boolean bullish = roc > 0;
                    boolean accel = bullish ? (prevRoc >= 0 && roc > prevRoc) : (prevRoc <= 0 && roc < prevRoc);
                    double ema = ema21(closes);
                    boolean aligned = bullish ? cNow > ema : cNow < ema;
                    // args[1]=relaxed → also accept |roc|>=0.40 (approximates live's roc+atr bonus path)
                    boolean relaxed = System.getProperty("relaxed") != null;
                    boolean scoreOk = Math.abs(roc) > 0.60 || (accel && Math.abs(roc) > Math.abs(prevRoc) * 1.5)
                            || (relaxed && Math.abs(roc) >= 0.40);
                    if (Math.abs(roc) >= thr && accel && aligned && scoreOk && !momoOpen.containsKey(di)) {
                        long[] cool = momoCoolByDir.computeIfAbsent(di, k -> new long[2]);
                        int side = bullish ? 0 : 1;
                        if (ts >= cool[side]) {
                            int atm = (int) Math.round(spot / interval) * interval;
                            Inst[] pair = chain.getOrDefault(di, new TreeMap<>()).get(atm);
                            Inst opt = pair != null ? pair[side] : null;
                            if (opt != null && opt.lastAsk > 0 && ts - opt.lastTick <= 120
                                    && opt.lastLtp >= 15 && opt.lastLtp <= 700 && !opt.in[0]) {
                                opt.in[0] = true; opt.entry[0] = opt.lastAsk; opt.peak[0] = opt.lastAsk;
                                opt.inTs[0] = ts; opt.cnt[0]++; opt.vuAt[0] = Math.max(1.5, Math.min(10, 2.2 * opt.madRet * 100));
                                momoOpen.put(di, day + "|" + idx + "|" + atm + "|" + (side == 0 ? "CE" : "PE"));
                                cool[side] = ts + 300;
                            }
                        }
                    }
                }

                // ----- TRAP eval (core thesis) -----
                if (istSec >= 34200 && istSec <= 54600) {
                    TreeMap<Integer, Inst[]> ch = chain.get(di);
                    if (ch != null) {
                        for (var ce2 : ch.subMap((int) (spot * 0.9925), true, (int) (spot * 1.0075), true).entrySet()) {
                            Inst ce = ce2.getValue()[0], pe = ce2.getValue()[1];
                            if (ce == null || pe == null || ce.lastOi <= 0 || pe.lastOi <= 0) continue;
                            boolean ceTrap = ce.lastOi >= pe.lastOi * 1.5 && ce.lastOi >= 50000;
                            boolean peTrap = pe.lastOi >= ce.lastOi * 1.5 && pe.lastOi >= 50000;
                            Inst t = ceTrap ? ce : peTrap ? pe : null;
                            if (t == null) continue;
                            // structural gate: CE trap valid when strike <= spot; PE trap when strike >= spot
                            if (ceTrap && ce2.getKey() > spot) continue;
                            if (peTrap && ce2.getKey() < spot) continue;
                            if (t.in[1] || ts < t.cool[1] || t.cnt[1] >= 3) continue;
                            if (ts - t.firstTs < 1500 || t.samp.size() < 120) continue;
                            if (t.absNow || t.spfNow || t.lastLtp < 15 || t.lastLtp > 700 || t.lastAsk <= 0) continue;
                            double[] p30 = at(t.samp, ts - 1800);
                            double dOi30 = p30 != null && p30[2] > 0 ? (t.lastOi - p30[2]) / p30[2] * 100 : 0;
                            if (dOi30 < 2) continue;                    // trapped side must still be BUILDING
                            t.in[1] = true; t.entry[1] = t.lastAsk; t.peak[1] = t.lastAsk; t.inTs[1] = ts;
                            t.cnt[1]++; t.vuAt[1] = Math.max(1.5, Math.min(10, 2.2 * t.madRet * 100));
                        }
                    }
                }
            }
        }

        // ---------- report ----------
        System.out.println("== DORMANT-STRATEGY PROBES — 3-day tape (fills ask/bid, -Rs50/rt, 1 lot) ==");
        for (String p : new String[]{"MOMO", "TRAP", "MRFADE"}) {
            Map<String, double[]> byDay = new TreeMap<>();
            int n = 0, w = 0, ov = 0; double tot = 0;
            for (Trade t : trades) {
                if (!t.probe().equals(p)) continue;
                double net = (t.out() - t.in()) * t.lot() - 50;
                tot += net; n++; if (net > 0) w++; if (t.overlap()) ov++;
                double[] d = byDay.computeIfAbsent(t.day(), k -> new double[2]);
                d[0]++; d[1] += net;
            }
            System.out.printf("%-7s trades=%-4d win%%=%-5.1f netRs=%-8.0f avOverlap=%-3d perDay=%s%n",
                    p, n, n > 0 ? 100.0 * w / n : 0, tot, ov,
                    byDay.entrySet().stream().map(e -> e.getKey() + ":" + Math.round(e.getValue()[1]) + "(" + (int) e.getValue()[0] + ")").toList());
        }
        System.out.println("-- MOMO trade list --");
        for (Trade t : trades) if (t.probe().equals("MOMO"))
            System.out.printf("  %s %s in=%s out=%s %.2f->%.2f net=%.0f %s%s%n", t.day(), t.inst(),
                    ist(t.inTs()), ist(t.outTs()), t.in(), t.out(), (t.out() - t.in()) * t.lot() - 50,
                    t.reason(), t.overlap() ? " [OVERLAPS-AVALANCHE]" : "");
    }

    static double ema21(List<Double> closes) {
        int period = 21, n = closes.size();
        int start = Math.max(0, n - period * 2);
        double ema = 0; int smaEnd = start + period;
        for (int i = start; i < smaEnd && i < n; i++) ema += closes.get(i);
        ema /= period;
        double mult = 2.0 / (period + 1);
        for (int i = smaEnd; i < n; i++) ema = (closes.get(i) - ema) * mult + ema;
        return ema;
    }
    static double[] at(ArrayDeque<double[]> samp, long ts) {
        double[] best = null;
        for (double[] q : samp) { if (q[0] <= ts) best = q; else break; }
        return best;
    }
    static void baselines(Inst in) {
        List<Double> rets = new ArrayList<>();
        double[][] arr = in.samp.toArray(new double[0][]);
        for (int i = 12; i < arr.length; i += 6) {
            double p0 = arr[i - 12][1];
            if (p0 > 0) rets.add(Math.abs(arr[i][1] - p0) / p0);
        }
        if (rets.size() >= 10) { Collections.sort(rets); in.madRet = Math.max(rets.get(rets.size() / 2), 0.0015); }
    }
    static String ist(long ts) { long t = ts + 19800; return String.format("%02d:%02d:%02d", (t / 3600) % 24, (t % 3600) / 60, t % 60); }
}
