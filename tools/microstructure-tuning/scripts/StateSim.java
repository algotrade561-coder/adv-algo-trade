import java.sql.*;
import java.util.*;
import java.io.PrintStream;
import java.nio.file.*;

/**
 * PROPOSAL OUTCOME TEST (3 days, LOCAL).
 *
 * A) EXIT SIDE ON REAL TRADES: keep every real bot entry exactly as it happened (time+price+qty);
 *    replace only the exit with the PROPOSED state-aware ladder:
 *      SL(-8%) -> WRITER-PRESS accel (in-loss && state=WRITER-PRESS && held>=30s)
 *      -> SCALP(>=3% & weakening & state != SHORT-COVER-RUN)
 *      -> TRAIL(activate 4%, gap 8%; gap 5% in BATTLE) -> COE(absorb|spoof) -> MAXHOLD 30m -> EOD 15:20.
 *    (OI_WRITER_STOP / OI_FLIP_REVERSE do not exist in the ladder = the elimination.)
 *    Output: per-trade actual vs proposed exit/P&L and the total delta.
 *
 * B) STATE AS MAIN ENTRY: enter ONLY on TRANSITION into SHORT-COVER-RUN (dOI5m<=-0.5 && dP5m>=+3),
 *    vetoes: DEAD-neighbour, blow-off volTrend>=4, absorb/spoof at entry tick; same ladder;
 *    limits: premium 15-700, 09:20-15:05, 1 open/instr, 60s cooldown, max 4/instr/day.
 *    Output: trades, P&L, captured swings (>=20%, of the same 520).
 *
 * STATE (per tick, strictly backward): dOI5m, dP5m rolling; absorbCnt5m:
 *   SHORT-COVER-RUN: dOI5m<=-0.5 && dP5m>=+3
 *   WRITER-PRESS:    dOI5m>=+0.5 && dP5m<=-3
 *   DEAD:            |dOI5m|<0.5 && |dP5m|<3
 *   BATTLE:          absorbCnt5m>=3 (overrides NEUTRAL/DEAD label for exit-gap purposes)
 * args: dataDir
 */
public class StateSim {
    static final int TS=0, LTP=1, BID=2, ASK=3, IMB=4, SV=5, ABS=6, SPF=7, OI=8, DV=9;

    record RTrade(String id, long user, String idx, int strike, String type, long entryTs, long exitTs,
                  double entry, double exitActual, int qty, double pnlRecorded, String reasonActual) {}
    record SimX(String day, String inst, long inTs, long outTs, double in, double out, int lot, String reason) {}
    record Swing(long tLow, double pLow, long tHigh, double pHigh) {}

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "data";
        Class.forName("org.duckdb.DuckDBDriver");
        List<RTrade> real = loadTrades(Path.of(dir, "trades-3d.csv"));
        List<String[]> partA = new ArrayList<>();
        double[] totals = new double[2]; // actualGross, proposedGross
        List<SimX> partB = new ArrayList<>();
        int[] swingTotal = {0}, capB = {0};

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
            String files = "['" + dir + "/atm-microstructure-2026-07-06.csv','" + dir + "/atm-0707.csv','" + dir + "/atm-microstructure-2026-07-08.csv']";
            ResultSet r = s.executeQuery(
                "SELECT strftime(to_timestamp(exchangeTsEpochSec + 19800), '%m-%d') dt, index idx, strike, optionType ty, "
                + "exchangeTsEpochSec ts, ltp, bestBid, bestAsk, bookImbalance, signedVolume, isAbsorption, spoofActive, oi, cumVolume "
                + "FROM read_csv_auto(" + files + ", union_by_name=true) "
                + "WHERE ltp>0 AND exchangeTsEpochSec>1700000000 AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 "
                + "ORDER BY dt, idx, strike, ty, ts");
            String curKey = null, dt = null, idx = null, ty = null; int strike = 0;
            List<double[]> ser = new ArrayList<>(30000);
            long prevCum = -1;
            while (true) {
                boolean has = r.next();
                String key = has ? r.getString(1) + "|" + r.getString(2) + "|" + r.getInt(3) + "|" + r.getString(4) : null;
                if (!has || !key.equals(curKey)) {
                    if (curKey != null && ser.size() > 200)
                        analyze(dt, idx, strike, ty, ser, real, partA, totals, partB, swingTotal, capB);
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

        PrintStream md = new PrintStream(Files.newOutputStream(Path.of("reports", "proposal-outcome.md")));
        md.println("# Proposal outcome on 3-day data\n\n## A) Real entries, proposed exits\n");
        md.println("| day | inst | qty | entry(IST@px) | ACTUAL out(IST) px@reason=pnl | PROPOSED out(IST) px@reason=pnl | delta | proposed trigger condition |");
        md.println("|---|---|---|---|---|---|---|---|");
        System.out.println("== A) REAL ENTRIES + PROPOSED STATE-AWARE EXITS (per-trade) ==");
        System.out.printf("%-6s %-19s %4s %-16s | %-36s | %-36s | %7s | %s%n", "day", "inst", "qty", "entry(IST@px)", "ACTUAL out px@reason=pnl", "PROPOSED out px@reason=pnl", "delta", "trigger condition");
        double sumDelta = 0; int better = 0, worse = 0, same = 0;
        for (String[] row : partA) {
            System.out.printf("%-6s %-19s %4s %-16s | %-36s | %-36s | %7s | %s%n", (Object[]) row);
            md.printf("| %s | %s | %s | %s | %s | %s | %s | %s |%n", (Object[]) row);
            double d = Double.parseDouble(row[6]);
            sumDelta += d; if (d > 25) better++; else if (d < -25) worse++; else same++;
        }
        System.out.printf("%nTOTALS: actual gross=Rs%.0f -> proposed gross=Rs%.0f  DELTA=Rs%+.0f  (better/worse/same per trade: %d/%d/%d)%n",
                totals[0], totals[1], totals[1] - totals[0], better, worse, same);

        System.out.println("\n== B) STATE AS MAIN ENTRY (SHORT-COVER-RUN transitions only) ==");
        double g = 0, n2 = 0; int win = 0;
        Map<String, double[]> byR = new TreeMap<>(), byD = new TreeMap<>();
        md.println("\n## B) SHORT-COVER-RUN entry sim\n\n| day | inst | in | out | entry | exit | pnl | reason |\n|---|---|---|---|---|---|---|---|");
        for (SimX x : partB) {
            double nn = (x.out - x.in) * x.lot - 50; g += nn; n2++; if (nn > 0) win++;
            byR.computeIfAbsent(x.reason, k -> new double[2]); byR.get(x.reason)[0]++; byR.get(x.reason)[1] += nn;
            byD.computeIfAbsent(x.day, k -> new double[2]); byD.get(x.day)[0]++; byD.get(x.day)[1] += nn;
            md.printf("| %s | %s | %s | %s | %.2f | %.2f | %.0f | %s |%n", x.day, x.inst, ist(x.inTs), ist(x.outTs), x.in, x.out, nn, x.reason);
        }
        System.out.printf("trades=%d win%%=%.1f netRs=%.0f  capturedSwings=%d/%d%n", partB.size(),
                n2 > 0 ? 100.0 * win / n2 : 0, g, capB[0], swingTotal[0]);
        for (var e : byD.entrySet()) System.out.printf("  %s n=%.0f net=%.0f%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        for (var e : byR.entrySet()) System.out.printf("  exit %s n=%.0f net=%.0f%n", e.getKey(), e.getValue()[0], e.getValue()[1]);
        md.close();
    }

    static String ist(long ts) { long t = ts + 19800; return String.format("%02d:%02d:%02d", (t / 3600) % 24, (t % 3600) / 60, t % 60); }

    // states
    static final int NEUTRAL = 0, SCR = 1, WPRESS = 2, DEAD = 3;
    static int stateOf(double dOi5, double dP5) {
        if (dOi5 <= -0.5 && dP5 >= 3) return SCR;
        if (dOi5 >= 0.5 && dP5 <= -3) return WPRESS;
        if (Math.abs(dOi5) < 0.5 && Math.abs(dP5) < 3) return DEAD;
        return NEUTRAL;
    }

    static void analyze(String day, String idx, int strike, String ty, List<double[]> ser, List<RTrade> real,
                        List<String[]> partA, double[] totals, List<SimX> partB, int[] swingTotal, int[] capB) {
        int n = ser.size();
        String inst = idx + " " + strike + " " + ty;
        int lot = idx.equals("SENSEX") ? 20 : idx.equals("BANKNIFTY") ? 30 : 65;

        // precompute causal: dOI5m, dP5m, absorbCnt5m, volTrend(5v5)
        int[] st = new int[n]; boolean[] battle = new boolean[n]; double[] volT = new double[n];
        double[] dOi5A = new double[n], dP5A = new double[n];
        {
            int j5 = 0, j10 = 0; int absC = 0; double vol5 = 0, vol10 = 0;
            ArrayDeque<double[]> w5 = new ArrayDeque<>(), w10 = new ArrayDeque<>();
            for (int i = 0; i < n; i++) {
                double[] p = ser.get(i); long ts = (long) p[TS];
                w5.addLast(new double[]{ts, p[ABS], p[DV]}); absC += p[ABS]; vol5 += p[DV];
                while (!w5.isEmpty() && w5.peekFirst()[0] < ts - 300) { double[] o = w5.pollFirst(); absC -= o[1]; vol5 -= o[2]; }
                w10.addLast(new double[]{ts, p[DV]}); vol10 += p[DV];
                while (!w10.isEmpty() && w10.peekFirst()[0] < ts - 600) vol10 -= w10.pollFirst()[1];
                while (j5 < i && ser.get(j5)[TS] < ts - 300) j5++;
                double oi0 = ser.get(j5)[OI], p0 = ser.get(j5)[LTP];
                double dOi5 = oi0 > 0 ? (p[OI] - oi0) / oi0 * 100 : 0;
                double dP5 = p0 > 0 ? (p[LTP] - p0) / p0 * 100 : 0;
                dOi5A[i] = dOi5; dP5A[i] = dP5;
                st[i] = stateOf(dOi5, dP5);
                battle[i] = absC >= 3;
                double prior5 = vol10 - vol5;
                volT[i] = prior5 > 0 ? vol5 / prior5 : (vol5 > 0 ? 9 : 0);
            }
        }

        // ---- A) real trades on this instrument: proposed exit from actual entry ----
        for (RTrade t : real) {
            if (!t.idx.equals(idx) || t.strike != strike || !t.type.equals(ty)) continue;
            int i0 = idxAt(ser, t.entryTs);
            if (i0 >= n || Math.abs(ser.get(i0)[TS] - t.entryTs) > 120) continue;
            String dayOf = day; // group day
            double peak = t.entry; String rsn = null; double exitPx = 0; long outTs = 0; String cond = "";
            for (int i = i0; i < n; i++) {
                double[] p = ser.get(i); long ts = (long) p[TS]; long held = ts - t.entryTs;
                long istSec = (ts + 19800) % 86400;
                peak = Math.max(peak, p[LTP]);
                double profit = (p[LTP] - t.entry) / t.entry * 100;
                double flow30 = winSum(ser, i, 30, SV);
                double gap = battle[i] ? 0.95 : 0.92;
                if (p[LTP] <= t.entry * 0.92) { rsn = "SL"; cond = String.format("profit=%.1f%%<=-8%%", profit); }
                else if (held >= 30 && profit < 0 && st[i] == WPRESS) { rsn = "WPRESS-ACCEL";
                    cond = String.format("in-loss %.1f%% + OI build %+.1f%%/5m while price %+.1f%%/5m (writers pressing)", profit, dOi5A[i], dP5A[i]); }
                else if (held >= 15 && profit >= 3 && (flow30 <= 0 || p[IMB] <= -0.10) && st[i] != SCR) { rsn = "SCALP";
                    cond = String.format("profit=%.1f%%>=3%% + weakening (flow30=%.0f, imb=%.2f)", profit, flow30, p[IMB]); }
                else if (peak >= t.entry * 1.04 && p[LTP] <= peak * gap) { rsn = "TRAIL" + (battle[i] ? "-B" : "");
                    cond = String.format("peak %.1f (+%.1f%%), drawdown %.1f%% >= gap %s", peak, (peak - t.entry) / t.entry * 100,
                            (peak - p[LTP]) / peak * 100, battle[i] ? "5%(BATTLE)" : "8%"); }
                else if (held >= 3 && (p[ABS] == 1 || p[SPF] == 1) && st[i] != SCR) { rsn = "COE";
                    cond = p[ABS] == 1 ? "absorption flagged (buy flow into wall)" : "spoof collapse flagged"; }
                else if (held >= 1800) { rsn = "MAXHOLD"; cond = "30 min"; }
                else if (istSec >= 55200) { rsn = "EOD"; cond = "15:20 square-off"; }
                if (rsn != null) { exitPx = p[BID] > 0 ? p[BID] : p[LTP]; outTs = ts; break; }
            }
            if (rsn == null) { double[] lp = ser.get(n - 1); exitPx = lp[BID] > 0 ? lp[BID] : lp[LTP]; outTs = (long) lp[TS]; rsn = "EOD"; cond = "end of data"; }
            double actualG = (t.exitActual - t.entry) * t.qty;
            double propG = (exitPx - t.entry) * t.qty;
            totals[0] += actualG; totals[1] += propG;
            partA.add(new String[]{dayOf, inst, String.valueOf(t.qty),
                    ist(t.entryTs) + "@" + String.format("%.1f", t.entry),
                    String.format("%s %.1f@%s=%.0f", ist(t.exitTs), t.exitActual, shortR(t.reasonActual), actualG),
                    String.format("%s %.1f@%s=%.0f", ist(outTs), exitPx, rsn, propG),
                    String.format("%.0f", propG - actualG), cond});
        }

        // ---- B) SCR-transition entry sim ----
        List<Swing> swings = zigzag(ser); swingTotal[0] += swings.size();
        boolean in = false; double entry = 0, peak = 0; long inTs = 0, cool = 0; int cnt = 0;
        List<SimX> local = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            double[] p = ser.get(i); long ts = (long) p[TS]; long istSec = (ts + 19800) % 86400;
            if (in) {
                peak = Math.max(peak, p[LTP]);
                long held = ts - inTs; String rsn = null;
                double profit = (p[LTP] - entry) / entry * 100;
                double flow30 = winSum(ser, i, 30, SV);
                double gap = battle[i] ? 0.95 : 0.92;
                if (p[LTP] <= entry * 0.92) rsn = "SL";
                else if (held >= 30 && profit < 0 && st[i] == WPRESS) rsn = "WPRESS-ACCEL";
                else if (held >= 15 && profit >= 3 && (flow30 <= 0 || p[IMB] <= -0.10) && st[i] != SCR) rsn = "SCALP";
                else if (peak >= entry * 1.04 && p[LTP] <= peak * gap) rsn = "TRAIL";
                else if (held >= 3 && (p[ABS] == 1 || p[SPF] == 1) && st[i] != SCR) rsn = "COE";
                else if (held >= 1800) rsn = "MAXHOLD";
                else if (istSec >= 55200) rsn = "EOD";
                if (rsn != null) {
                    local.add(new SimX(day, inst, inTs, ts, entry, p[BID] > 0 ? p[BID] : p[LTP], lot, rsn));
                    in = false; cool = ts + 60;
                }
                continue;
            }
            if (istSec < 33600 || istSec > 54300 || ts < cool || cnt >= 4) continue;
            if (p[LTP] < 15 || p[LTP] > 700) continue;
            boolean transition = st[i] == SCR && st[i - 1] != SCR;
            if (!transition) continue;
            if (p[ABS] == 1 || p[SPF] == 1 || volT[i] >= 4) continue;
            double ask = p[ASK] > 0 ? p[ASK] : p[LTP];
            in = true; entry = ask; peak = ask; inTs = ts; cnt++;
        }
        if (in) { double[] lp = ser.get(n - 1); local.add(new SimX(day, inst, inTs, (long) lp[TS], entry, lp[BID] > 0 ? lp[BID] : lp[LTP], lot, "EOD")); }
        partB.addAll(local);
        for (Swing sw : swings)
            for (SimX x : local)
                if (x.inTs >= sw.tLow && x.inTs <= sw.tHigh && x.in <= (sw.pLow + sw.pHigh) / 2) { capB[0]++; break; }
    }

    static String shortR(String r) {
        if (r.contains("WRITER")) return "WRITER";
        if (r.contains("FLIP")) return "FLIP";
        if (r.contains("CONVICTION")) return "COE";
        if (r.contains("SCALP")) return "SCALP";
        if (r.contains("TRAIL")) return "TRAIL";
        if (r.contains("STOP_LOSS")) return "SL";
        if (r.contains("manually") || r.contains("sync")) return "MANUAL";
        return r.length() > 10 ? r.substring(0, 10) : r;
    }
    static int idxAt(List<double[]> s, long ts) {
        int lo = 0, hi = s.size() - 1;
        while (lo < hi) { int m = (lo + hi) / 2; if (s.get(m)[TS] < ts) lo = m + 1; else hi = m; }
        return lo;
    }
    static double winSum(List<double[]> s, int i, int sec, int col) {
        double sum = 0; long t0 = (long) s.get(i)[TS] - sec;
        for (int j = i; j >= 0 && s.get(j)[TS] >= t0; j--) sum += s.get(j)[col];
        return sum;
    }
    static List<Swing> zigzag(List<double[]> ser) {
        List<Swing> out = new ArrayList<>();
        int mode = 0, iPiv = 0, iExt = 0;
        for (int i = 1; i < ser.size(); i++) {
            double p = ser.get(i)[LTP];
            if (mode >= 0) {
                double ext = ser.get(iExt)[LTP];
                if (p > ext) { iExt = i; ext = p; }
                if (p <= ext * 0.90) {
                    double pl = ser.get(iPiv)[LTP];
                    if (ext >= pl * 1.20 && pl >= 15)
                        out.add(new Swing((long) ser.get(iPiv)[TS], pl, (long) ser.get(iExt)[TS], ext));
                    iPiv = iExt; iExt = i; mode = -1;
                }
            }
            if (mode <= 0) {
                if (p < ser.get(iExt)[LTP]) iExt = i;
                if (p >= ser.get(iExt)[LTP] * 1.10) { iPiv = iExt; iExt = i; mode = 1; }
            }
        }
        return out;
    }
    static List<RTrade> loadTrades(Path p) throws Exception {
        List<RTrade> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(p);
        Set<String> seen = new HashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
            if (f.length < 16) continue;
            String id = f[0].replace("\"", "");
            if (id.startsWith("SYNC") || id.startsWith("PAPER")) continue;
            String inst = f[2].replace("\"", "").replace("NFO:", "").replace("BFO:", "");
            String type = inst.substring(inst.length() - 2);
            String core = inst.substring(0, inst.length() - 2);
            int di = 0; while (di < core.length() && !Character.isDigit(core.charAt(di))) di++;
            String digits = core.substring(di);
            if (digits.length() < 6) continue;
            long entryTs = ts(f[9]); long exitTs = ts(f[10]);
            double entry = num(f[7]), exit = num(f[8]);
            if (entryTs == 0 || entry <= 0 || exit <= 0) continue;
            String dedupe = inst + ":" + entryTs / 60;
            if (!seen.add(dedupe)) continue; // fan-out copy
            out.add(new RTrade(id, (long) num(f[1]), core.substring(0, di), Integer.parseInt(digits.substring(5)), type,
                    entryTs, exitTs, entry, exit, (int) num(f[6]), num(f[11]), f[15].replace("\"", "")));
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
