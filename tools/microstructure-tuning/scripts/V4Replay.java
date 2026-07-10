import java.sql.*;
import java.util.*;
import java.nio.file.*;

/**
 * Two validations on the 3-day tick data (07-06/07/08), run LOCALLY:
 *
 * 1) POST-EXIT FORWARD PATH for the bot's REAL exits, by reason: price +2/+5/+15 min after each
 *    exit vs exit price. DODGED = price fell further (exit saved money); COST = price rebounded
 *    (exit was churn). This decides whether OI_WRITER_STOP / OI_FLIP_REVERSE can be eliminated.
 *
 * 2) V4 REPLAY — the proposed single-pipeline entry/exit on the same days, same instruments:
 *    ENTRY (arm/fire/veto):
 *      arm   = |dOI60| >= 0.3%              (tick OI activity — wind OR unwind)
 *      fire  = price crosses >= +3% above rolling 15-min low, while armed
 *      stage veto = fire ignored if already > +6% above the low  (never buy the top of a spike)
 *      vetoes = absorption, spoofActive, volRate >= 6 (blow-off), premium < 15
 *      limits = entry window 09:20-15:05 IST, 1 open/instrument, 60s cooldown, max 4/instrument/day
 *    EXIT priority ladder (first hit wins):
 *      1 WATERFALL  imb <= -0.30 && flow30 < 0 && ltp <= peak*0.99          (held >= 5s)
 *      2 COE        absorption || spoof                                      (held >= 3s)
 *      3 TRAIL      peak gain >= 4% && drawdown from peak >= 8%
 *      4 SL         ltp <= entry * 0.92
 *      5 MAXHOLD    30 min      6 EOD 15:20
 *    Fills: buy at bestAsk, sell at bestBid, -Rs50/round-trip. Lots: NIFTY 65 / SENSEX 20 / BANKNIFTY 30.
 *
 * args: dataDir
 */
public class V4Replay {
    static final int TS=0, LTP=1, BID=2, ASK=3, IMB=4, SV=5, ABS=6, SPF=7, OI=8, DV=9;

    record Trade(String id, String idx, int strike, String type, long exitTs, double exit, String reason) {}
    record Sim(String day, String idx, int strike, String type, long inTs, long outTs,
               double in, double out, int lot, String reason) {}

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "data";
        Class.forName("org.duckdb.DuckDBDriver");
        List<Trade> exits = loadExits(Path.of(dir, "trades-3d.csv"));
        List<Sim> sims = new ArrayList<>();
        // reason -> [n, dodged, cost, flat, sumFwd5mPct]
        Map<String, double[]> fwd = new TreeMap<>();

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
            String files = "['" + dir + "/atm-microstructure-2026-07-06.csv','" + dir + "/atm-0707.csv','" + dir + "/atm-microstructure-2026-07-08.csv']";
            ResultSet r = s.executeQuery(
                "SELECT strftime(to_timestamp(exchangeTsEpochSec + 19800), '%m-%d') dt, index idx, strike, optionType ty, "
                + "exchangeTsEpochSec ts, ltp, bestBid, bestAsk, bookImbalance, signedVolume, isAbsorption, spoofActive, oi, cumVolume "
                + "FROM read_csv_auto(" + files + ", union_by_name=true) "
                + "WHERE ltp>0 AND exchangeTsEpochSec>1700000000 AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 "
                + "ORDER BY dt, idx, strike, ty, ts");

            String curKey = null; String dt = null, idx = null, ty = null; int strike = 0;
            List<double[]> ser = new ArrayList<>(30000);
            long prevCum = -1;
            while (true) {
                boolean has = r.next();
                String key = has ? r.getString(1) + "|" + r.getString(2) + "|" + r.getInt(3) + "|" + r.getString(4) : null;
                if (!has || !key.equals(curKey)) {
                    if (curKey != null && ser.size() > 100) {
                        fwdPaths(idx, strike, ty, ser, exits, fwd);
                        simulate(dt, idx, strike, ty, ser, sims);
                    }
                    if (!has) break;
                    curKey = key; dt = r.getString(1); idx = r.getString(2); strike = r.getInt(3); ty = r.getString(4);
                    ser = new ArrayList<>(30000); prevCum = -1;
                }
                long cum = r.getLong(14);
                long dv = (prevCum < 0 || cum < prevCum) ? 0 : cum - prevCum; prevCum = cum;
                ser.add(new double[]{r.getLong(5), r.getDouble(6), r.getDouble(7), r.getDouble(8), r.getDouble(9),
                        r.getDouble(10), r.getBoolean(11) ? 1 : 0, r.getBoolean(12) ? 1 : 0, r.getDouble(13), dv});
            }
        }

        System.out.println("== 1) POST-EXIT FORWARD PATH of REAL bot exits (was the exit right?) ==");
        System.out.println("   DODGED = price fell >=2% more within 5 min (exit saved money); COST = rebounded >=2% (churn exit)");
        System.out.printf("   %-26s %4s %7s %6s %6s %11s%n", "reason", "n", "DODGED", "COST", "FLAT", "avgFwd5m%");
        for (var e : fwd.entrySet()) {
            double[] v = e.getValue();
            System.out.printf("   %-26s %4.0f %7.0f %6.0f %6.0f %+10.1f%%%n", e.getKey(), v[0], v[1], v[2], v[3], v[0] > 0 ? v[4] / v[0] : 0);
        }

        System.out.println("\n== 2) V4 REPLAY (arm |dOI60|>=0.3 -> fire +3..6% off 15-min low; waterfall/COE/trail/SL exits) ==");
        agg("ALL", sims);
        for (String d : new String[]{"07-06", "07-07", "07-08"}) agg("  " + d, sims.stream().filter(x -> x.day.equals(d)).toList());
        for (String i : new String[]{"NIFTY", "SENSEX", "BANKNIFTY"}) agg("  " + i, sims.stream().filter(x -> x.idx.equals(i)).toList());
        System.out.println("   -- by exit reason --");
        Map<String, List<Sim>> byR = new TreeMap<>();
        for (Sim x : sims) byR.computeIfAbsent(x.reason, k -> new ArrayList<>()).add(x);
        for (var e : byR.entrySet()) agg("  " + e.getKey(), e.getValue());
    }

    static void agg(String label, List<Sim> xs) {
        if (xs.isEmpty()) { System.out.printf("   %-14s n=0%n", label); return; }
        double gross = 0, net = 0; int win = 0; long hold = 0;
        for (Sim x : xs) { double g = (x.out - x.in) * x.lot, nn = g - 50; gross += g; net += nn; if (nn > 0) win++; hold += x.outTs - x.inTs; }
        System.out.printf("   %-14s n=%-4d win%%=%-5.1f grossRs=%-9.0f netRs=%-9.0f avgNet=%-7.1f avgHold=%ds%n",
                label, xs.size(), 100.0 * win / xs.size(), gross, net, net / xs.size(), hold / xs.size());
    }

    // -------- validation 1: forward path after real exits --------
    static void fwdPaths(String idx, int strike, String ty, List<double[]> ser, List<Trade> exits, Map<String, double[]> fwd) {
        for (Trade t : exits) {
            if (!t.idx.equals(idx) || t.strike != strike || !t.type.equals(ty)) continue;
            int i = idxAt(ser, t.exitTs);
            if (i < 0 || i >= ser.size() || Math.abs(ser.get(i)[TS] - t.exitTs) > 90) continue;
            int i5 = idxAt(ser, t.exitTs + 300);
            if (i5 >= ser.size()) i5 = ser.size() - 1;
            double p5 = ser.get(i5)[LTP];
            double chg = t.exit > 0 ? (p5 - t.exit) / t.exit * 100 : 0;
            double[] v = fwd.computeIfAbsent(t.reason, k -> new double[5]);
            v[0]++; if (chg <= -2) v[1]++; else if (chg >= 2) v[2]++; else v[3]++;
            v[4] += chg;
        }
    }

    // -------- validation 2: v4 simulation --------
    static void simulate(String day, String idx, int strike, String ty, List<double[]> ser, List<Sim> sims) {
        int lot = idx.equals("SENSEX") ? 20 : idx.equals("BANKNIFTY") ? 30 : 65;
        boolean in = false; double entry = 0, peak = 0; long inTs = 0, cooldownUntil = 0; int entriesToday = 0;
        ArrayDeque<double[]> lows = new ArrayDeque<>();
        for (int i = 0; i < ser.size(); i++) {
            double[] p = ser.get(i);
            long ts = (long) p[TS];
            long istSec = (ts + 19800) % 86400;
            lows.addLast(new double[]{ts, p[LTP]});
            while (!lows.isEmpty() && lows.peekFirst()[0] < ts - 900) lows.pollFirst();

            if (in) {
                peak = Math.max(peak, p[LTP]);
                long held = ts - inTs; String rsn = null;
                double flow30 = winSum(ser, i, 30, SV);
                if (held >= 10 && p[IMB] <= -0.30 && flow30 <= -2000 && p[LTP] <= peak * 0.97) rsn = "WATERFALL";
                else if (held >= 3 && (p[ABS] == 1 || p[SPF] == 1)) rsn = "COE_REVERSAL";
                else if (peak >= entry * 1.04 && p[LTP] <= peak * 0.92) rsn = "TRAIL";
                else if (p[LTP] <= entry * 0.92) rsn = "SL";
                else if (held >= 1800) rsn = "MAXHOLD";
                else if (istSec >= 55200) rsn = "EOD"; // 15:20 IST
                if (rsn != null) {
                    double bid = p[BID] > 0 ? p[BID] : p[LTP];
                    sims.add(new Sim(day, idx, strike, ty, inTs, ts, entry, bid, lot, rsn));
                    in = false; cooldownUntil = ts + 60;
                }
                continue;
            }
            // entry
            if (istSec < 33600 || istSec > 54300) continue;         // 09:20 - 15:05 IST
            if (ts < cooldownUntil || entriesToday >= 4) continue;
            if (p[LTP] < 15) continue;
            double low = Double.MAX_VALUE;
            for (double[] w : lows) low = Math.min(low, w[1]);
            if (low == Double.MAX_VALUE || low <= 0) continue;
            double stage = (p[LTP] - low) / low * 100;
            if (stage < 3 || stage > 6) continue;                    // fire band: right stage only
            double dOi60 = oiPct(ser, i, 60);
            if (Math.abs(dOi60) < 0.5) continue;                     // arm: STRONG tick OI activity
            if (winSum(ser, i, 30, SV) <= 0) continue;               // fire needs live buy flow
            if (p[IMB] <= -0.25) continue;                           // not into a heavy offer
            if (p[ABS] == 1 || p[SPF] == 1) continue;                // vetoes
            if (volRate(ser, i) >= 6) continue;                      // blow-off veto
            double ask = p[ASK] > 0 ? p[ASK] : p[LTP];
            in = true; entry = ask; peak = ask; inTs = ts; entriesToday++;
        }
        if (in) {
            double[] last = ser.get(ser.size() - 1);
            sims.add(new Sim(day, idx, strike, ty, inTs, (long) last[TS], entry, last[BID] > 0 ? last[BID] : last[LTP], lot, "EOD"));
        }
    }

    static int idxAt(List<double[]> s, long ts) {
        int lo = 0, hi = s.size() - 1;
        while (lo < hi) { int m = (lo + hi) / 2; if (s.get(m)[TS] < ts) lo = m + 1; else hi = m; }
        return lo;
    }
    static double oiPct(List<double[]> s, int i, int sec) {
        int j = idxAt(s, (long) s.get(i)[TS] - sec);
        double o0 = s.get(j)[OI];
        return o0 > 0 ? (s.get(i)[OI] - o0) / o0 * 100 : 0;
    }
    static double winSum(List<double[]> s, int i, int sec, int col) {
        double sum = 0; long t0 = (long) s.get(i)[TS] - sec;
        for (int j = i; j >= 0 && s.get(j)[TS] >= t0; j--) sum += s.get(j)[col];
        return sum;
    }
    static double volRate(List<double[]> s, int i) {
        double v30 = winSum(s, i, 30, DV), v300 = winSum(s, i, 300, DV);
        double base = (v300 - v30) / 9.0;
        return base > 0 ? v30 / base : (v30 > 0 ? 99 : 0);
    }

    static List<Trade> loadExits(Path p) throws Exception {
        List<Trade> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
            if (f.length < 16) continue;
            String id = f[0].replace("\"", "");
            if (id.startsWith("SYNC") || id.startsWith("PAPER")) continue;
            String reason = f[15].replace("\"", "").replaceFirst("^COPY_EXIT: ", "").replaceAll("\\(.*", "");
            if (reason.contains("manually closed") || reason.contains("position-sync")) reason = "MANUAL-RACE";
            String inst = f[2].replace("\"", "").replace("NFO:", "").replace("BFO:", "");
            String type = inst.substring(inst.length() - 2);
            String core = inst.substring(0, inst.length() - 2);
            int di = 0; while (di < core.length() && !Character.isDigit(core.charAt(di))) di++;
            String digits = core.substring(di);
            if (digits.length() < 6) continue;
            long exitTs = ts(f[10]); double exit = num(f[8]);
            if (exitTs == 0 || exit <= 0) continue;
            out.add(new Trade(id, core.substring(0, di), Integer.parseInt(digits.substring(5)), type, exitTs, exit, reason));
        }
        return out;
    }
    static double num(String s) { s = s.replace("\"", "").trim(); return s.isEmpty() ? 0 : Double.parseDouble(s); }
    static long ts(String s) {
        s = s.replace("\"", "").trim(); if (s.isEmpty()) return 0;
        try { return java.time.OffsetDateTime.parse(s.replace(" ", "T").replaceAll("\\+00$", "Z")).toEpochSecond(); }
        catch (Exception e) { try { return java.time.LocalDateTime.parse(s.substring(0, 19).replace(" ", "T")).toEpochSecond(java.time.ZoneOffset.UTC); } catch (Exception e2) { return 0; } }
    }
}
