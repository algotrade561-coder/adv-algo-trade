import java.sql.*;
import java.util.*;
import java.io.PrintStream;
import java.nio.file.*;

/**
 * THE DECIDER TEST — 3-day (07-06/07/08) variant matrix, run LOCALLY.
 *
 * 7 ENTRY-CRITERIA GROUPS, each run as the MAIN ENTRY DECIDER over identical data with the
 * IDENTICAL proposed EXIT LADDER, one causal pass (all features look strictly backward):
 *
 *   V0 CURRENT      the live COE/CTO confirm as-is: volSurge>=1.5(prior vol>=500) && thrust60>=2%
 *                   && L5imb>=0.25 && tickFlow>0 && !absorb && !spoof
 *   V1 CURRENT+VETO V0 plus the proposed veto rail: stage<=6% off 15-min low, volRate<6,
 *                   |dOI60|>=0.1 (dead-OI veto)          <-- the testable part of the "veto" proposal
 *   V2 BAND-OIACT   stage 3..6% off 15-min low && |dOI60|>=0.3 && volRate<6 && !absorb && !spoof
 *   V3 BAND-STRICT  V2 && flow30>0 && imb>-0.25 && |dOI60|>=0.5
 *   V4 SQUEEZE      stage 3..6% && dOI60>=+0.3 (writers pressing) && price NOT already running
 *                   (dPrice120<=+1%) && flow30>0 && !absorb && !spoof
 *   V5 UNWIND       stage 3..6% && dOI60<=-0.5 (unwind fuel) && flow30>0 && !absorb && !spoof
 *   V6 THRUST       thrust60>=4% && volRate in [1,6) && stage<=10% && flow30>0 && !absorb && !spoof
 *
 * Common limits for EVERY variant: premium 15..700, entry window 09:20-15:05 IST, 1 open/instrument,
 * 60s cooldown after exit, max 4 entries/instrument/day.
 *
 * EXIT LADDER (identical for all variants — the proposed arbiter):
 *   1 SL         ltp <= entry*0.92
 *   2 WATERFALL  IN-LOSS only: ltp<entry && imb<=-0.30 && flow30<=-5*absorbBar && ltp<=peak*0.97 (held>=10s)
 *   3 SCALP      profit>=3% && weakening (flow30<=0 || imb<=-0.10) (held>=15s)   [mirrors live SCALP_TARGET]
 *   4 TRAIL      peak>=entry*1.04 && ltp<=peak*0.92
 *   5 COE        absorb || spoof (held>=3s)
 *   6 MAXHOLD    30 min          7 EOD >= 15:20 IST
 * Fills: BUY bestAsk / SELL bestBid, -Rs50 per round trip. Lots NIFTY 65 / SENSEX 20 / BANKNIFTY 30.
 *
 * MOVE CAPTURE: zigzag swings (low->high >=20%, 10% reversal, premium>=15) computed once;
 * a variant "captures" a swing if it has an entry inside [tLow..tHigh] at price below the swing midpoint.
 *
 * Output: console summary + FULL per-variant trade lists -> reports/v4-variant-matrix.md
 * args: dataDir
 */
public class VariantMatrix {
    static final int TS=0, LTP=1, BID=2, ASK=3, IMB=4, SV=5, ABS=6, SPF=7, OI=8, DV=9;
    static final String[] VNAME = {"V0-CURRENT","V1-CURRENT+VETO","V2-BAND-OIACT","V3-BAND-STRICT","V4-SQUEEZE","V5-UNWIND","V6-THRUST"};
    static final int NV = VNAME.length;

    record Sim(String day, String inst, long inTs, long outTs, double in, double out, int lot, String reason) {}
    record Swing(long tLow, double pLow, long tHigh, double pHigh) {}

    static double absorbBar(String idx){ return idx.equals("SENSEX")?1500: idx.equals("BANKNIFTY")?1000:15000; }
    static int lotOf(String idx){ return idx.equals("SENSEX")?20: idx.equals("BANKNIFTY")?30:65; }

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "data";
        Class.forName("org.duckdb.DuckDBDriver");
        List<List<Sim>> sims = new ArrayList<>(); for (int v=0; v<NV; v++) sims.add(new ArrayList<>());
        int[] swingTotal = {0};
        int[][] captured = new int[NV][1];

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
                    if (curKey != null && ser.size() > 100)
                        analyzeGroup(dt, idx, strike, ty, ser, sims, swingTotal, captured);
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

        // ---------- report ----------
        PrintStream md = new PrintStream(Files.newOutputStream(Path.of("reports", "v4-variant-matrix.md")));
        md.println("# V4 variant matrix — 3-day decider test (2026-07-06/07/08)\n");
        md.println("All variants: identical exit ladder (SL -> in-loss WATERFALL -> SCALP3% -> TRAIL4/8 -> COE -> MAXHOLD30m -> EOD),");
        md.println("buy ask / sell bid, -Rs50/rt, premium 15-700, window 09:20-15:05 IST, 1 open/instr, 60s cooldown, max 4/instr/day.");
        md.println("Move capture measured against " + swingTotal[0] + " zigzag swings >=20%.\n");
        System.out.println("== VARIANT SUMMARY (details + full trade lists in reports/v4-variant-matrix.md) ==");
        System.out.printf("%-16s %6s %6s %10s %10s %8s %8s | %s%n", "variant", "n", "win%", "grossRs", "netRs", "avgNet", "avgHold", "captured swings (of " + swingTotal[0] + ")");
        for (int v = 0; v < NV; v++) {
            List<Sim> xs = sims.get(v);
            double gross = 0, net = 0; int win = 0; long hold = 0;
            for (Sim x : xs) { double g = (x.out - x.in) * x.lot; gross += g; net += g - 50; if (g - 50 > 0) win++; hold += x.outTs - x.inTs; }
            System.out.printf("%-16s %6d %6.1f %10.0f %10.0f %8.1f %7ds | %d (%.1f%%)%n", VNAME[v], xs.size(),
                    xs.isEmpty() ? 0 : 100.0 * win / xs.size(), gross, net, xs.isEmpty() ? 0 : net / xs.size(),
                    xs.isEmpty() ? 0 : hold / xs.size(), captured[v][0], swingTotal[0] > 0 ? 100.0 * captured[v][0] / swingTotal[0] : 0);

            md.println("\n## " + VNAME[v] + "\n");
            md.printf("trades=%d win%%=%.1f gross=Rs%.0f net=Rs%.0f capturedSwings=%d/%d%n%n", xs.size(),
                    xs.isEmpty() ? 0 : 100.0 * win / xs.size(), gross, net, captured[v][0], swingTotal[0]);
            // per-day + per-reason
            Map<String, double[]> byDay = new TreeMap<>(), byR = new TreeMap<>();
            for (Sim x : xs) {
                double nn = (x.out - x.in) * x.lot - 50;
                byDay.computeIfAbsent(x.day, k -> new double[2]); byDay.get(x.day)[0]++; byDay.get(x.day)[1] += nn;
                byR.computeIfAbsent(x.reason, k -> new double[2]); byR.get(x.reason)[0]++; byR.get(x.reason)[1] += nn;
            }
            md.println("per-day: " + fmtMap(byDay) + "\nper-exit: " + fmtMap(byR) + "\n");
            md.println("| day | instrument | in(IST) | out(IST) | entry | exit | hold_s | pnlRs | exit reason |");
            md.println("|---|---|---|---|---|---|---|---|---|");
            for (Sim x : xs)
                md.printf("| %s | %s | %s | %s | %.2f | %.2f | %d | %.0f | %s |%n",
                        x.day, x.inst, ist(x.inTs), ist(x.outTs), x.in, x.out, x.outTs - x.inTs,
                        (x.out - x.in) * x.lot - 50, x.reason);
        }
        md.close();
    }

    static String fmtMap(Map<String, double[]> m) {
        StringBuilder sb = new StringBuilder();
        for (var e : m.entrySet()) sb.append(String.format("%s n=%.0f net=%.0f | ", e.getKey(), e.getValue()[0], e.getValue()[1]));
        return sb.toString();
    }
    static String ist(long ts) { long t = ts + 19800; return String.format("%02d:%02d:%02d", (t / 3600) % 24, (t % 3600) / 60, t % 60); }

    static void analyzeGroup(String day, String idx, int strike, String ty, List<double[]> ser,
                             List<List<Sim>> sims, int[] swingTotal, int[][] captured) {
        int n = ser.size();
        String inst = idx + " " + strike + " " + ty;
        int lot = lotOf(idx);
        double bar = absorbBar(idx);

        // ---- causal feature precompute (every window looks strictly BACKWARD) ----
        double[] low15 = new double[n], thr60 = new double[n], volR = new double[n], dOi60 = new double[n],
                 flow30 = new double[n], vol30 = new double[n], volP30 = new double[n], dP120 = new double[n];
        ArrayDeque<double[]> lows = new ArrayDeque<>();
        int j60 = 0, j30 = 0, j120 = 0, j300 = 0, jOi = 0;
        double sumDv30 = 0, sumDv300 = 0, sumSv30 = 0, sumDv60 = 0;
        ArrayDeque<double[]> w30 = new ArrayDeque<>(), w300 = new ArrayDeque<>(), w60 = new ArrayDeque<>();
        for (int i = 0; i < n; i++) {
            double[] p = ser.get(i); long ts = (long) p[TS];
            lows.addLast(new double[]{ts, p[LTP]});
            while (!lows.isEmpty() && lows.peekFirst()[0] < ts - 900) lows.pollFirst();
            double lo = Double.MAX_VALUE; for (double[] w : lows) lo = Math.min(lo, w[1]);
            low15[i] = lo;
            w30.addLast(new double[]{ts, p[DV], p[SV]}); sumDv30 += p[DV]; sumSv30 += p[SV];
            while (!w30.isEmpty() && w30.peekFirst()[0] < ts - 30) { double[] o = w30.pollFirst(); sumDv30 -= o[1]; sumSv30 -= o[2]; }
            w300.addLast(new double[]{ts, p[DV]}); sumDv300 += p[DV];
            while (!w300.isEmpty() && w300.peekFirst()[0] < ts - 300) sumDv300 -= w300.pollFirst()[1];
            w60.addLast(new double[]{ts, p[DV]}); sumDv60 += p[DV];
            while (!w60.isEmpty() && w60.peekFirst()[0] < ts - 60) sumDv60 -= w60.pollFirst()[1];
            vol30[i] = sumDv30; volP30[i] = Math.max(0, sumDv60 - sumDv30); flow30[i] = sumSv30;
            double base = (sumDv300 - sumDv30) / 9.0;
            volR[i] = base > 0 ? sumDv30 / base : (sumDv30 > 0 ? 99 : 0);
            while (j60 < i && ser.get(j60)[TS] < ts - 60) j60++;
            double p60 = ser.get(j60)[LTP]; thr60[i] = p60 > 0 ? (p[LTP] - p60) / p60 * 100 : 0;
            while (j120 < i && ser.get(j120)[TS] < ts - 120) j120++;
            double p120 = ser.get(j120)[LTP]; dP120[i] = p120 > 0 ? (p[LTP] - p120) / p120 * 100 : 0;
            while (jOi < i && ser.get(jOi)[TS] < ts - 60) jOi++;
            double o0 = ser.get(jOi)[OI]; dOi60[i] = o0 > 0 ? (p[OI] - o0) / o0 * 100 : 0;
        }

        // ---- swings once (evaluation only — no trading decision uses them) ----
        List<Swing> swings = zigzag(ser);
        swingTotal[0] += swings.size();

        // ---- variant machines ----
        boolean[] in = new boolean[NV]; double[] entry = new double[NV], peak = new double[NV];
        long[] inTs = new long[NV], cool = new long[NV]; int[] cnt = new int[NV];
        List<List<Sim>> local = new ArrayList<>(); for (int v = 0; v < NV; v++) local.add(new ArrayList<>());

        for (int i = 0; i < n; i++) {
            double[] p = ser.get(i); long ts = (long) p[TS]; long istSec = (ts + 19800) % 86400;
            double lo = low15[i];
            double stage = lo > 0 && lo != Double.MAX_VALUE ? (p[LTP] - lo) / lo * 100 : 999;

            for (int v = 0; v < NV; v++) {
                if (in[v]) {
                    peak[v] = Math.max(peak[v], p[LTP]);
                    long held = ts - inTs[v]; String rsn = null;
                    double profit = (p[LTP] - entry[v]) / entry[v] * 100;
                    if (p[LTP] <= entry[v] * 0.92) rsn = "SL";
                    else if (held >= 10 && profit < 0 && p[IMB] <= -0.30 && flow30[i] <= -5 * bar && p[LTP] <= peak[v] * 0.97) rsn = "WATERFALL";
                    else if (held >= 15 && profit >= 3 && (flow30[i] <= 0 || p[IMB] <= -0.10)) rsn = "SCALP";
                    else if (peak[v] >= entry[v] * 1.04 && p[LTP] <= peak[v] * 0.92) rsn = "TRAIL";
                    else if (held >= 3 && (p[ABS] == 1 || p[SPF] == 1)) rsn = "COE";
                    else if (held >= 1800) rsn = "MAXHOLD";
                    else if (istSec >= 55200) rsn = "EOD";
                    if (rsn != null) {
                        double bid = p[BID] > 0 ? p[BID] : p[LTP];
                        local.get(v).add(new Sim(day, inst, inTs[v], ts, entry[v], bid, lot, rsn));
                        in[v] = false; cool[v] = ts + 60;
                    }
                    continue;
                }
                // ---- entry decision, variant v ----
                if (istSec < 33600 || istSec > 54300) continue;      // 09:20-15:05
                if (ts < cool[v] || cnt[v] >= 4) continue;
                if (p[LTP] < 15 || p[LTP] > 700) continue;
                boolean absOrSpoof = p[ABS] == 1 || p[SPF] == 1;
                boolean fire = switch (v) {
                    case 0 -> volP30[i] >= 500 && volP30[i] > 0 && vol30[i] / volP30[i] >= 1.5
                              && thr60[i] >= 2 && p[IMB] >= 0.25 && p[SV] > 0 && !absOrSpoof;
                    case 1 -> volP30[i] >= 500 && volP30[i] > 0 && vol30[i] / volP30[i] >= 1.5
                              && thr60[i] >= 2 && p[IMB] >= 0.25 && p[SV] > 0 && !absOrSpoof
                              && stage <= 6 && volR[i] < 6 && Math.abs(dOi60[i]) >= 0.1;
                    case 2 -> stage >= 3 && stage <= 6 && Math.abs(dOi60[i]) >= 0.3 && volR[i] < 6 && !absOrSpoof;
                    case 3 -> stage >= 3 && stage <= 6 && Math.abs(dOi60[i]) >= 0.5 && volR[i] < 6 && !absOrSpoof
                              && flow30[i] > 0 && p[IMB] > -0.25;
                    case 4 -> stage >= 3 && stage <= 6 && dOi60[i] >= 0.3 && dP120[i] <= 1 && flow30[i] > 0 && !absOrSpoof;
                    case 5 -> stage >= 3 && stage <= 6 && dOi60[i] <= -0.5 && flow30[i] > 0 && !absOrSpoof;
                    case 6 -> thr60[i] >= 4 && volR[i] >= 1 && volR[i] < 6 && stage <= 10 && flow30[i] > 0 && !absOrSpoof;
                    default -> false;
                };
                if (fire) {
                    double ask = p[ASK] > 0 ? p[ASK] : p[LTP];
                    in[v] = true; entry[v] = ask; peak[v] = ask; inTs[v] = ts; cnt[v]++;
                }
            }
        }
        double[] lastP = ser.get(n - 1);
        for (int v = 0; v < NV; v++) {
            if (in[v]) local.get(v).add(new Sim(day, inst, inTs[v], (long) lastP[TS], entry[v],
                    lastP[BID] > 0 ? lastP[BID] : lastP[LTP], lot, "EOD"));
            sims.get(v).addAll(local.get(v));
            // capture join: entry inside swing window below midpoint
            for (Swing sw : swings) {
                for (Sim x : local.get(v)) {
                    if (x.inTs >= sw.tLow && x.inTs <= sw.tHigh && x.in <= (sw.pLow + sw.pHigh) / 2) { captured[v][0]++; break; }
                }
            }
        }
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
                double ext2 = ser.get(iExt)[LTP];
                if (p < ext2) { iExt = i; }
                if (p >= ser.get(iExt)[LTP] * 1.10) { iPiv = iExt; iExt = i; mode = 1; }
            }
        }
        return out;
    }
}
