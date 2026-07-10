import java.sql.*;
import java.util.*;
import java.io.PrintStream;
import java.nio.file.*;

/**
 * Per-SWING forensic breakdown of 07-06/07/08 (tick-level, bot-only) — run LOCALLY.
 *
 * Zigzag swing detection per instrument (10% reversal): every up-swing (low->high >= 20%, premium >= 15)
 * is one "move the market offered". CE swings = up-market moves, PE swings = down-market moves, so BOTH
 * directions of the index are covered.
 *
 * For each swing:
 *  - EARLIEST INDICATION: scan [low-180s .. trigger] for the first tick-level precursor:
 *      OI-BUILD  (dOI60 >= +0.3% while price >= low)        — longs building
 *      OI-UNWIND (dOI60 <= -0.3%)                           — short covering fuel
 *      FLOW+     (30s signed flow strongly positive)        — aggressive buyers
 *      IMB+      (L5 imbalance >= 0.20)                     — bid-heavy book
 *    -> lead time before the +3% trigger stage.
 *  - TRIGGER FEATURES at first tick >= low*1.03: thrust60, volRate(30s vs prior 5m avg), L5 imb,
 *    flow30, dOI30/dOI60 (tick OI velocity), OI regime (price x OI quadrant), absorb/spoof.
 *  - LIVE-GATE verdict at trigger: volSurge>=1.5 & thrust>=2 & imb>=0.25 & buyFlow & !absorb/!spoof.
 *  - BOT: CAUGHT (entry stage % of swing, exit efficiency = captured/(peak-entry), bleed after peak,
 *    evidence->exit lag) | LATE | MISSED[failing legs].
 *
 * Output: per-day summaries + full table -> reports/three-day-move-breakdown.md
 * args: dataDir [minGainPct=20]
 */
public class MoveBreakdown {
    static final double REV = 10.0;            // zigzag reversal %
    static double MIN_GAIN = 20.0;             // significant swing
    static final double MIN_PREMIUM = 15.0;
    static final double TRIG_PCT = 3.0;        // catchable stage: +3% off the swing low

    // tick columns in series arrays
    static final int TS=0, LTP=1, BID=2, IMB=3, SV=4, ABS=5, SPF=6, OI=7, DV=8;

    record Trade(String id, long user, String idx, int strike, String type, long entryTs, long exitTs,
                 double entry, double exit, int qty, double pnl, String exitReason) {}
    record Swing(String day, String idx, int strike, String type, int iLow, int iHigh,
                 long tLow, double pLow, long tHigh, double pHigh) {}

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "data";
        if (a.length > 1) MIN_GAIN = Double.parseDouble(a[1]);
        Class.forName("org.duckdb.DuckDBDriver");
        List<Trade> trades = loadTrades(Path.of(dir, "trades-3d.csv"));
        PrintStream md = new PrintStream(Files.newOutputStream(Path.of("reports", "three-day-move-breakdown.md")));
        md.println("# Three-day move breakdown (tick-level OI/flow) — 2026-07-06/07/08\n");
        md.println("Swing = zigzag low->high >= " + (int) MIN_GAIN + "% (10% reversal), premium >= 15. Bot trades only (SYNC/manual excluded).\n");
        md.println("| day | inst | low->high (IST) | pLow->pHigh | gain% | mins | earliest signal (lead) | trigger: thr/volR/imb/flow/dOI60 | regime | live-gate | bot |");
        md.println("|---|---|---|---|---|---|---|---|---|---|---|");

        Map<String, int[]> dayStats = new TreeMap<>();       // day -> [swings, caught, late, missed, gatesPassMissed]
        Map<String, double[]> dayLead = new TreeMap<>();     // day -> [sumLead, nLead]
        Map<String, Map<String, Integer>> failCount = new TreeMap<>(); // day -> failing-leg -> n
        List<String[]> exitRows = new ArrayList<>();
        // tick-OI correlation accumulators: bucket dOI60 at trigger -> outcomes
        double[][] oiCorr = new double[6][3]; // [bucket][n, sumFwdGain, nBig]

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
            String files = "['" + dir + "/atm-microstructure-2026-07-06.csv','" + dir + "/atm-0707.csv','" + dir + "/atm-microstructure-2026-07-08.csv']";
            ResultSet r = s.executeQuery(
                "SELECT strftime(to_timestamp(exchangeTsEpochSec + 19800), '%m-%d') dt, index idx, strike, optionType ty, "
                + "exchangeTsEpochSec ts, ltp, bestBid, bookImbalance, signedVolume, isAbsorption, spoofActive, oi, cumVolume "
                + "FROM read_csv_auto(" + files + ", union_by_name=true) "
                + "WHERE ltp>0 AND exchangeTsEpochSec>1700000000 AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 ORDER BY dt, idx, strike, ty, ts");

            String curKey = null; String dt = null, idx = null, ty = null; int strike = 0;
            List<double[]> ser = new ArrayList<>(30000);
            long prevCum = -1;

            while (true) {
                boolean has = r.next();
                String key = has ? r.getString(1) + "|" + r.getString(2) + "|" + r.getInt(3) + "|" + r.getString(4) : null;
                if (!has || !key.equals(curKey)) {
                    if (curKey != null && ser.size() > 100)
                        analyze(dt, idx, strike, ty, ser, trades, md, dayStats, dayLead, failCount, exitRows, oiCorr);
                    if (!has) break;
                    curKey = key; dt = r.getString(1); idx = r.getString(2); strike = r.getInt(3); ty = r.getString(4);
                    ser = new ArrayList<>(30000); prevCum = -1;
                }
                long cum = r.getLong(13);
                long dv = (prevCum < 0 || cum < prevCum) ? 0 : cum - prevCum; prevCum = cum;
                ser.add(new double[]{r.getLong(5), r.getDouble(6), r.getDouble(7), r.getDouble(8), r.getDouble(9),
                        r.getBoolean(10) ? 1 : 0, r.getBoolean(11) ? 1 : 0, r.getDouble(12), dv});
            }
        }
        md.close();

        // console: per-day summary + correlation
        System.out.println("== PER-DAY SWING SUMMARY (bot-only; full table in reports/three-day-move-breakdown.md) ==");
        for (var e : dayStats.entrySet()) {
            int[] v = e.getValue(); double[] ld = dayLead.getOrDefault(e.getKey(), new double[2]);
            System.out.printf("  %s: swings=%d CAUGHT=%d LATE=%d MISSED=%d (of which gates-PASSED-but-no-entry=%d)  avg earliest-signal lead=%.0fs%n",
                    e.getKey(), v[0], v[1], v[2], v[3], v[4], ld[1] > 0 ? ld[0] / ld[1] : 0);
            Map<String, Integer> fc = failCount.getOrDefault(e.getKey(), Map.of());
            fc.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue()).limit(6)
                    .forEach(x -> System.out.println("      blocked-by " + x.getKey() + ": " + x.getValue()));
        }
        System.out.println("\n== TICK-OI VELOCITY (dOI60 at trigger) vs swing outcome ==");
        String[] lbl = {"<-1.0%", "[-1.0,-0.3)", "[-0.3,0)", "[0,0.3)", "[0.3,1.0)", ">=1.0%"};
        for (int i = 0; i < 6; i++)
            System.out.printf("  dOI60 %-12s n=%-5.0f avgFwdGain=%5.1f%%  P(>=%d%%)=%4.1f%%%n",
                    lbl[i], oiCorr[i][0], oiCorr[i][0] > 0 ? oiCorr[i][1] / oiCorr[i][0] : 0, (int) MIN_GAIN,
                    oiCorr[i][0] > 0 ? 100 * oiCorr[i][2] / oiCorr[i][0] : 0);
        System.out.println("\n== EXIT-BLEED on caught swings (evidence -> exit) ==");
        System.out.printf("  %-5s %-20s %8s %8s %8s %9s %9s %7s %10s %s%n",
                "day", "inst", "entry", "peak", "exit", "capt%", "bleed%", "lag_s", "bleedRs/lot", "exitReason");
        for (String[] row : exitRows) System.out.printf("  %-5s %-20s %8s %8s %8s %9s %9s %7s %10s %s%n", (Object[]) row);
    }

    static void analyze(String day, String idx, int strike, String ty, List<double[]> ser, List<Trade> allTrades,
                        PrintStream md, Map<String, int[]> dayStats, Map<String, double[]> dayLead,
                        Map<String, Map<String, Integer>> failCount, List<String[]> exitRows, double[][] oiCorr) {
        // zigzag
        List<Swing> swings = new ArrayList<>();
        int mode = 0; // 0=undet, 1=up (seeking high), -1=down (seeking low)
        int iPiv = 0, iExt = 0;
        for (int i = 1; i < ser.size(); i++) {
            double p = ser.get(i)[LTP], ext = ser.get(iExt)[LTP];
            if (mode >= 0) { // up or undet: extreme is running max
                if (p > ext) { iExt = i; ext = p; }
                if (p <= ext * (1 - REV / 100)) {
                    if (mode == 1 || mode == 0) {
                        double pl = ser.get(iPiv)[LTP];
                        if (ext >= pl * (1 + MIN_GAIN / 100) && pl >= MIN_PREMIUM)
                            swings.add(new Swing(day, idx, strike, ty, iPiv, iExt,
                                    (long) ser.get(iPiv)[TS], pl, (long) ser.get(iExt)[TS], ext));
                    }
                    iPiv = iExt; iExt = i; mode = -1;
                }
            }
            if (mode <= 0) { // down or undet: extreme is running min
                double ext2 = ser.get(iExt)[LTP];
                if (p < ext2) { iExt = i; ext2 = p; }
                if (p >= ext2 * (1 + REV / 100)) { iPiv = iExt; iExt = i; mode = 1; }
            }
        }
        if (swings.isEmpty()) return;

        List<Trade> myTrades = new ArrayList<>();
        for (Trade t : allTrades)
            if (t.idx.equals(idx) && t.strike == strike && t.type.equals(ty)) myTrades.add(t);

        for (Swing sw : swings) {
            // trigger tick: first >= low*1.03 after iLow
            int iTrig = -1;
            for (int i = sw.iLow; i <= sw.iHigh; i++)
                if (ser.get(i)[LTP] >= sw.pLow * (1 + TRIG_PCT / 100)) { iTrig = i; break; }
            if (iTrig < 0) continue;
            long tTrig = (long) ser.get(iTrig)[TS];
            double pTrig = ser.get(iTrig)[LTP];

            double thr = pct(ser, iTrig, 60), volR = volRate(ser, iTrig), imb = ser.get(iTrig)[IMB];
            double flow = winSum(ser, iTrig, 30, SV);
            double dOi30 = oiPct(ser, iTrig, 30), dOi60 = oiPct(ser, iTrig, 60);
            boolean absorb = ser.get(iTrig)[ABS] == 1, spoof = ser.get(iTrig)[SPF] == 1;
            String regime = regime(pct(ser, iTrig, 120), dOi60);

            // correlation accumulate (fwd gain from trigger to swing high)
            double fwd = (sw.pHigh - pTrig) / pTrig * 100;
            int b = dOi60 < -1 ? 0 : dOi60 < -0.3 ? 1 : dOi60 < 0 ? 2 : dOi60 < 0.3 ? 3 : dOi60 < 1 ? 4 : 5;
            oiCorr[b][0]++; oiCorr[b][1] += fwd; if (fwd >= MIN_GAIN) oiCorr[b][2]++;

            // earliest indication in [low-180s .. trigger]
            String sig = "-"; long lead = -1;
            for (int i = idxAt(ser, sw.tLow - 180); i <= iTrig; i++) {
                if (i < 0) { i = 0; }
                double d60 = oiPct(ser, i, 60), f30 = winSum(ser, i, 30, SV), im = ser.get(i)[IMB];
                String what = d60 >= 0.3 ? "OI-BUILD" : d60 <= -0.3 ? "OI-UNWIND" : f30 > 0 && volRate(ser, i) > 1 && im >= 0.20 ? "IMB+FLOW" : null;
                if (what != null) { sig = what; lead = tTrig - (long) ser.get(i)[TS]; break; }
            }

            // live gate verdict
            List<String> fails = new ArrayList<>();
            if (volR < 1.5) fails.add("volR");
            if (thr < 2.0) fails.add("thrust");
            if (imb < 0.25) fails.add("imb");
            if (flow <= 0) fails.add("sellFlow");
            if (absorb) fails.add("ABSORB");
            if (spoof) fails.add("SPOOF");
            String gate = fails.isEmpty() ? "PASS" : "FAIL:" + String.join("+", fails);

            // bot join
            Trade hit = null; boolean late = false;
            for (Trade t : myTrades) {
                if (t.entryTs >= sw.tLow - 120 && t.entryTs <= sw.tHigh) { hit = t; break; }
                if (hit == null && t.entryTs > sw.tHigh && t.entryTs <= sw.tHigh + 600) { hit = t; late = true; }
            }
            String bot;
            int[] stats = dayStats.computeIfAbsent(day, k -> new int[5]);
            stats[0]++;
            if (hit != null && !late) {
                stats[1]++;
                double stage = (hit.entry - sw.pLow) / (sw.pHigh - sw.pLow) * 100;
                // exit efficiency + bleed
                double peakHeld = hit.entry; long tPeakHeld = hit.entryTs;
                for (double[] p : ser) if (p[TS] >= hit.entryTs && p[TS] <= hit.exitTs && p[LTP] > peakHeld) { peakHeld = p[LTP]; tPeakHeld = (long) p[TS]; }
                double capt = peakHeld > hit.entry ? (hit.exit - hit.entry) / (peakHeld - hit.entry) * 100 : 0;
                double bleed = peakHeld > 0 ? (peakHeld - hit.exit) / peakHeld * 100 : 0;
                long evid = 0; double pEvid = 0;
                for (double[] p : ser) {
                    if (p[TS] <= tPeakHeld || p[TS] > hit.exitTs) continue;
                    double d60 = oiPct(ser, idxAt(ser, (long) p[TS]), 60);
                    boolean rev = (p[IMB] < -0.15 && p[SV] < 0) || p[ABS] == 1 || p[SPF] == 1 || (d60 > 0.3 && p[LTP] < peakHeld * 0.99);
                    if (rev && p[LTP] < peakHeld * 0.99) { evid = (long) p[TS]; pEvid = p[LTP]; break; }
                }
                if (evid > 0 && hit.exitTs > evid)
                    exitRows.add(new String[]{day, idx + " " + strike + " " + ty, f1(hit.entry), f1(peakHeld), f1(hit.exit),
                            f1(capt) + "%", f1(bleed) + "%", String.valueOf(hit.exitTs - evid),
                            f1((pEvid - hit.exit) * 65), hit.exitReason});
                bot = "CAUGHT@" + (int) stage + "% capt=" + (int) capt + "% bleed=" + (int) bleed + "% pnl=" + (int) hit.pnl;
            } else if (hit != null) { stats[2]++; bot = "LATE pnl=" + (int) hit.pnl; }
            else {
                stats[3]++;
                if (fails.isEmpty()) { stats[4]++; bot = "MISSED[gates-PASS: cooldown/cap/not-candidate]"; }
                else bot = "MISSED[" + String.join("+", fails) + "]";
                Map<String, Integer> fc = failCount.computeIfAbsent(day, k -> new HashMap<>());
                for (String f : fails) fc.merge(f, 1, Integer::sum);
                if (fails.isEmpty()) fc.merge("none(cooldown/cap/candidate)", 1, Integer::sum);
            }
            if (lead >= 0) { double[] ld = dayLead.computeIfAbsent(day, k -> new double[2]); ld[0] += lead; ld[1]++; }

            md.printf("| %s | %s %d %s | %s->%s | %.1f->%.1f | %.0f%% | %d | %s(%ss) | %.1f/%.1f/%.2f/%.0f/%.2f%% | %s | %s | %s |%n",
                    day, idx, strike, ty, ist(sw.tLow), ist(sw.tHigh), sw.pLow, sw.pHigh,
                    (sw.pHigh - sw.pLow) / sw.pLow * 100, (sw.tHigh - sw.tLow) / 60,
                    sig, lead >= 0 ? String.valueOf(lead) : "-", thr, Math.min(volR, 99), imb, Math.signum(flow), dOi60, regime, gate, bot);
        }
    }

    static String regime(double dPrice, double dOi) {
        if (dPrice >= 0 && dOi >= 0.1) return "LONG-BUILD";
        if (dPrice >= 0 && dOi <= -0.1) return "SHORT-COVER";
        if (dPrice < 0 && dOi >= 0.1) return "WRITER-BUILD";
        if (dPrice < 0 && dOi <= -0.1) return "LONG-UNWIND";
        return "FLAT-OI";
    }
    static int idxAt(List<double[]> s, long ts) {
        int lo = 0, hi = s.size() - 1;
        while (lo < hi) { int m = (lo + hi) / 2; if (s.get(m)[TS] < ts) lo = m + 1; else hi = m; }
        return lo;
    }
    static double pct(List<double[]> s, int i, int sec) {
        int j = idxAt(s, (long) s.get(i)[TS] - sec);
        double p0 = s.get(j)[LTP];
        return p0 > 0 ? (s.get(i)[LTP] - p0) / p0 * 100 : 0;
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
        double v30 = winSum(s, i, 30, DV);
        double v300 = winSum(s, i, 300, DV);
        double base = (v300 - v30) / 9.0; // avg per-30s over the prior 270s
        return base > 0 ? v30 / base : (v30 > 0 ? 99 : 0);
    }
    static String ist(long ts) { long t = ts + 19800; return String.format("%02d:%02d", (t / 3600) % 24, (t % 3600) / 60); }
    static String f1(double d) { return String.format("%.1f", d); }

    static List<Trade> loadTrades(Path p) throws Exception {
        List<Trade> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = lines.get(i).split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1);
            if (f.length < 16) continue;
            String id = f[0].replace("\"", "");
            if (id.startsWith("SYNC") || id.startsWith("PAPER")) continue; // BOT ONLY
            String inst = f[2].replace("\"", "").replace("NFO:", "").replace("BFO:", "");
            String type = inst.substring(inst.length() - 2);
            String core = inst.substring(0, inst.length() - 2);
            int di = 0; while (di < core.length() && !Character.isDigit(core.charAt(di))) di++;
            String digits = core.substring(di);
            if (digits.length() < 6) continue;
            int strike = Integer.parseInt(digits.substring(5));
            long entryTs = ts(f[9]), exitTs = ts(f[10]);
            out.add(new Trade(id, (long) num(f[1]), core.substring(0, di), strike, type, entryTs, exitTs,
                    num(f[7]), num(f[8]), (int) num(f[6]), num(f[11]), f[15].replace("\"", "")));
        }
        // dedupe fan-out copies (same instrument + entry minute)
        Map<String, Trade> ded = new LinkedHashMap<>();
        for (Trade t : out) ded.putIfAbsent(t.idx + ":" + t.strike + ":" + t.type + ":" + t.entryTs / 60, t);
        return new ArrayList<>(ded.values());
    }
    static double num(String s) { s = s.replace("\"", "").trim(); return s.isEmpty() ? 0 : Double.parseDouble(s); }
    static long ts(String s) {
        s = s.replace("\"", "").trim(); if (s.isEmpty()) return 0;
        try { return java.time.OffsetDateTime.parse(s.replace(" ", "T").replaceAll("\\+00$", "Z")).toEpochSecond(); }
        catch (Exception e) { try { return java.time.LocalDateTime.parse(s.substring(0, 19).replace(" ", "T")).toEpochSecond(java.time.ZoneOffset.UTC); } catch (Exception e2) { return 0; } }
    }
}
