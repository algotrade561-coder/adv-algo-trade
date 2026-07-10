import java.sql.*;
import java.util.*;

/**
 * PRE-MOVE STATE STUDY — does the microstructure carry ADVANCE knowledge of moves?
 *
 * Method (no cherry-picking, no look-ahead):
 *   ANCHORS: every 60s per instrument (deterministic stride), premium 15..700, anchored 09:30-15:00 IST
 *   FINGERPRINT (strictly BACKWARD from anchor):
 *     dOI 2m/5m/10m %, price drift 2m/5m/10m %, signed flow 5m (in units of the index absorb bar),
 *     volume 5m vs prior 10m ratio, absorption count 5m, spoof count 5m, mean L5 imbalance 2m
 *   OUTCOME (strictly FORWARD): max rally % and max drop % within next 30 min
 *   REPORT: P(rally>=15%) and P(drop>=15%) per pre-state bucket vs BASE RATE -> lift.
 *   Key tables:
 *     T1  OI-quadrant x price-context (the wind/unwind intelligence):
 *         rows = dOI5m: UNWIND<=-0.5 | FLAT | BUILD>=+0.5, cols = dP5m: DOWN<=-3 | FLAT | UP>=+3
 *     T2  flow5m buckets;  T3 absorption/spoof counts;  T4 volume-trend buckets
 *     T5  named hypotheses incl. "OI unwound in prior 5m -> collapse ahead" and event frequency/day
 *
 * args: dataDir
 */
public class PreMoveStudy2 {
    static final int TS=0, LTP=1, IMB=2, SV=3, ABS=4, SPF=5, OI=6, DV=7;
    static double absorbBar(String idx){ return idx.equals("SENSEX")?1500: idx.equals("BANKNIFTY")?1000:15000; }

    // one anchor observation
    record Obs(String day, String idx, double dOi2, double dOi5, double dOi10, double dP2, double dP5, double dP10,
               double flow5bar, double volTrend, int absCnt, int spfCnt, double imb2,
               double fwdRally, double fwdDrop) {}

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "data";
        Class.forName("org.duckdb.DuckDBDriver");
        List<Obs> obs = new ArrayList<>(60000);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
            String files = "['" + dir + "/atm-microstructure-2026-07-06.csv','" + dir + "/atm-0707.csv','" + dir + "/atm-microstructure-2026-07-08.csv']";
            ResultSet r = s.executeQuery(
                "SELECT strftime(to_timestamp(exchangeTsEpochSec + 19800), '%m-%d') dt, index idx, strike, optionType ty, "
                + "exchangeTsEpochSec ts, ltp, bookImbalance, signedVolume, isAbsorption, spoofActive, oi, cumVolume "
                + "FROM read_csv_auto(" + files + ", union_by_name=true) "
                + "WHERE ltp>0 AND exchangeTsEpochSec>1700000000 AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 "
                + "ORDER BY dt, idx, strike, ty, ts");

            String curKey = null, dt = null, idx = null;
            List<double[]> ser = new ArrayList<>(30000);
            long prevCum = -1;
            while (true) {
                boolean has = r.next();
                String key = has ? r.getString(1) + "|" + r.getString(2) + "|" + r.getInt(3) + "|" + r.getString(4) : null;
                if (!has || !key.equals(curKey)) {
                    if (curKey != null && ser.size() > 200) harvest(dt, idx, ser, obs);
                    if (!has) break;
                    curKey = key; dt = r.getString(1); idx = r.getString(2);
                    ser = new ArrayList<>(30000); prevCum = -1;
                }
                long cum = r.getLong(12);
                long dv = (prevCum < 0 || cum < prevCum) ? 0 : cum - prevCum; prevCum = cum;
                ser.add(new double[]{r.getLong(5), r.getDouble(6), r.getDouble(7), r.getDouble(8),
                        r.getBoolean(9) ? 1 : 0, r.getBoolean(10) ? 1 : 0, r.getDouble(11), dv});
            }
        }

        System.out.println("anchors=" + obs.size());
        double baseR = 100.0 * obs.stream().filter(o -> o.fwdRally >= 15).count() / obs.size();
        double baseD = 100.0 * obs.stream().filter(o -> o.fwdDrop >= 15).count() / obs.size();
        System.out.printf("BASE RATES (next 30 min): P(rally>=15%%)=%.1f%%   P(drop>=15%%)=%.1f%%%n%n", baseR, baseD);

        // T1: OI quadrant x price context
        System.out.println("== T1: dOI5m x dP5m — P(rally) / P(drop) [lift vs base]  n ==");
        String[] oiL = {"UNWIND<=-0.5", "OI-FLAT", "BUILD>=+0.5"};
        String[] prL = {"PRICE-DOWN<=-3", "PRICE-FLAT", "PRICE-UP>=+3"};
        for (int oi = 0; oi < 3; oi++) {
            StringBuilder sb = new StringBuilder(String.format("  %-13s", oiL[oi]));
            for (int pr = 0; pr < 3; pr++) {
                final int foi = oi, fpr = pr;
                List<Obs> cell = obs.stream().filter(o -> oiB(o.dOi5) == foi && prB(o.dP5) == fpr).toList();
                sb.append(cellStr(cell, baseR, baseD));
            }
            System.out.println(sb);
        }
        System.out.println("  cols: " + String.join(" | ", prL));

        // T2 flow
        System.out.println("\n== T2: signed flow 5m (in absorb-bar units) ==");
        bucketTable(obs, baseR, baseD, o -> o.flow5bar, new double[]{-20, -5, 0, 5, 20},
                new String[]{"<-20", "-20..-5", "-5..0", "0..5", "5..20", ">=20"});

        // T3 absorption / spoof counts
        System.out.println("\n== T3: absorption events in prior 5m ==");
        bucketTable(obs, baseR, baseD, o -> (double) o.absCnt, new double[]{1, 3, 8},
                new String[]{"0", "1-2", "3-7", ">=8"});
        System.out.println("\n== T3b: spoof events in prior 5m ==");
        bucketTable(obs, baseR, baseD, o -> (double) o.spfCnt, new double[]{1, 3},
                new String[]{"0", "1-2", ">=3"});

        // T4 volume trend
        System.out.println("\n== T4: volume 5m vs prior 10m ==");
        bucketTable(obs, baseR, baseD, o -> o.volTrend, new double[]{0.5, 1, 2, 4},
                new String[]{"<0.5", "0.5-1", "1-2", "2-4", ">=4"});

        // T5 named hypotheses
        System.out.println("\n== T5: NAMED PRE-STATE HYPOTHESES (events/day = frequency across all instruments) ==");
        hypo(obs, baseR, baseD, "H1 SHORT-COVER FUEL: OI unwind<=-0.5 5m + price flat(|dP5|<3)",
                o -> o.dOi5 <= -0.5 && Math.abs(o.dP5) < 3);
        hypo(obs, baseR, baseD, "H2 SQUEEZE SETUP: OI build>=+0.5 5m + price down(dP5<=-3)",
                o -> o.dOi5 >= 0.5 && o.dP5 <= -3);
        hypo(obs, baseR, baseD, "H3 USER: OI unwound 5m AFTER run-up (dP10>=+5) -> collapse?",
                o -> o.dOi5 <= -0.5 && o.dP10 >= 5);
        hypo(obs, baseR, baseD, "H4 TOP-ABSORPTION: price up 5m>=+5 + absorb>=3 in 5m -> collapse?",
                o -> o.dP5 >= 5 && o.absCnt >= 3);
        hypo(obs, baseR, baseD, "H5 FLOW DIVERGENCE: price up 5m>=+5 + flow5m negative -> collapse?",
                o -> o.dP5 >= 5 && o.flow5bar < 0);
        hypo(obs, baseR, baseD, "H6 QUIET COIL: |dP10|<2 + volTrend<0.7 + OI build>=+0.3 -> rally?",
                o -> Math.abs(o.dP10) < 2 && o.volTrend < 0.7 && o.dOi5 >= 0.3);
        hypo(obs, baseR, baseD, "H7 DOUBLE-BUILD: dOI2>=+0.3 AND dOI10>=+1 (sustained build) ",
                o -> o.dOi2 >= 0.3 && o.dOi10 >= 1);
        hypo(obs, baseR, baseD, "H8 CAPITULATION: price down 10m<=-8 + OI unwind<=-1 10m -> rally (V-bottom)?",
                o -> o.dP10 <= -8 && o.dOi10 <= -1);

        // per-day robustness of the strongest cells (does the edge survive outside the crash day?)
        System.out.println("\n== ROBUSTNESS: strongest signals per day ==");
        for (String d : new String[]{"07-06", "07-07", "07-08"}) {
            List<Obs> dayObs = obs.stream().filter(o -> o.day.equals(d)).toList();
            if (dayObs.isEmpty()) continue;
            double bR = 100.0 * dayObs.stream().filter(o -> o.fwdRally >= 15).count() / dayObs.size();
            double bD = 100.0 * dayObs.stream().filter(o -> o.fwdDrop >= 15).count() / dayObs.size();
            System.out.printf("  -- %s (n=%d, base R=%.1f%% D=%.1f%%) --%n", d, dayObs.size(), bR, bD);
            hypo(dayObs, bR, bD, "  UNWIND+PRICE-UP (short-cover run)", o -> o.dOi5 <= -0.5 && o.dP5 >= 3);
            hypo(dayObs, bR, bD, "  H3 unwind after run-up", o -> o.dOi5 <= -0.5 && o.dP10 >= 5);
            hypo(dayObs, bR, bD, "  H4 top-absorption", o -> o.dP5 >= 5 && o.absCnt >= 3);
            hypo(dayObs, bR, bD, "  H5 flow divergence", o -> o.dP5 >= 5 && o.flow5bar < 0);
            hypo(dayObs, bR, bD, "  DEAD (OI flat + price flat)", o -> Math.abs(o.dOi5) < 0.5 && Math.abs(o.dP5) < 3);
            hypo(dayObs, bR, bD, "  BUILD+PRICE-DOWN (writers pressing)", o -> o.dOi5 >= 0.5 && o.dP5 <= -3);
        }
    }

    static int oiB(double d) { return d <= -0.5 ? 0 : d >= 0.5 ? 2 : 1; }
    static int prB(double d) { return d <= -3 ? 0 : d >= 3 ? 2 : 1; }

    static String cellStr(List<Obs> cell, double baseR, double baseD) {
        if (cell.size() < 30) return String.format(" | %28s", "n=" + cell.size() + " (thin)");
        double pr = 100.0 * cell.stream().filter(o -> o.fwdRally >= 15).count() / cell.size();
        double pd = 100.0 * cell.stream().filter(o -> o.fwdDrop >= 15).count() / cell.size();
        return String.format(" | R%5.1f%%[x%.1f] D%5.1f%%[x%.1f] n=%-6d", pr, pr / baseR, pd, pd / baseD, cell.size());
    }

    static void bucketTable(List<Obs> obs, double baseR, double baseD,
                            java.util.function.ToDoubleFunction<Obs> f, double[] cuts, String[] lbl) {
        for (int b = 0; b < cuts.length + 1; b++) {
            final int fb = b;
            List<Obs> cell = obs.stream().filter(o -> {
                double v = f.applyAsDouble(o); int x = 0; while (x < cuts.length && v >= cuts[x]) x++;
                return x == fb;
            }).toList();
            if (cell.isEmpty()) continue;
            double pr = 100.0 * cell.stream().filter(o -> o.fwdRally >= 15).count() / cell.size();
            double pd = 100.0 * cell.stream().filter(o -> o.fwdDrop >= 15).count() / cell.size();
            System.out.printf("  %-9s n=%-6d P(rally)=%5.1f%% [x%.2f]   P(drop)=%5.1f%% [x%.2f]%n",
                    lbl[b], cell.size(), pr, pr / baseR, pd, pd / baseD);
        }
    }

    static void hypo(List<Obs> obs, double baseR, double baseD, String name, java.util.function.Predicate<Obs> p) {
        List<Obs> cell = obs.stream().filter(p).toList();
        if (cell.size() < 20) { System.out.printf("  %-72s n=%d (too thin)%n", name, cell.size()); return; }
        double pr = 100.0 * cell.stream().filter(o -> o.fwdRally >= 15).count() / cell.size();
        double pd = 100.0 * cell.stream().filter(o -> o.fwdDrop >= 15).count() / cell.size();
        double avgR = cell.stream().mapToDouble(o -> o.fwdRally).average().orElse(0);
        double avgD = cell.stream().mapToDouble(o -> o.fwdDrop).average().orElse(0);
        System.out.printf("  %-72s n=%-5d (%.0f/day)  P(rally)=%5.1f%% [x%.2f] avgRally=%.1f%%  P(drop)=%5.1f%% [x%.2f] avgDrop=%.1f%%%n",
                name, cell.size(), cell.size() / 3.0, pr, pr / baseR, avgR, pd, pd / baseD, avgD);
    }

    static void harvest(String day, String idx, List<double[]> ser, List<Obs> out) {
        int n = ser.size();
        double bar = absorbBar(idx);
        long nextAnchor = 0;
        int j2 = 0, j5 = 0, j10 = 0;
        // rolling sums for flow5m and vol windows
        for (int i = 0; i < n; i++) {
            double[] p = ser.get(i); long ts = (long) p[TS];
            long istSec = (ts + 19800) % 86400;
            if (istSec < 34200 || istSec > 54000) continue;         // anchors 09:30-15:00 (30m fwd room)
            if (ts < nextAnchor) continue;
            if (p[LTP] < 15 || p[LTP] > 700) continue;
            nextAnchor = ts + 60;

            while (j2 < i && ser.get(j2)[TS] < ts - 120) j2++;
            while (j5 < i && ser.get(j5)[TS] < ts - 300) j5++;
            while (j10 < i && ser.get(j10)[TS] < ts - 600) j10++;
            if (ser.get(j10)[TS] > ts - 540) continue;              // need >=9 min of history

            double oi0_2 = ser.get(j2)[OI], oi0_5 = ser.get(j5)[OI], oi0_10 = ser.get(j10)[OI];
            double p0_2 = ser.get(j2)[LTP], p0_5 = ser.get(j5)[LTP], p0_10 = ser.get(j10)[LTP];
            if (oi0_5 <= 0 || p0_5 <= 0 || oi0_10 <= 0 || p0_10 <= 0 || oi0_2 <= 0 || p0_2 <= 0) continue;

            double flow5 = 0, vol5 = 0, vol10 = 0; int absC = 0, spfC = 0; double imbSum = 0; int imbN = 0;
            for (int k = j5; k <= i; k++) {
                double[] q = ser.get(k);
                flow5 += q[SV]; vol5 += q[DV];
                if (q[ABS] == 1) absC++; if (q[SPF] == 1) spfC++;
                if (q[TS] >= ts - 120) { imbSum += q[IMB]; imbN++; }
            }
            for (int k = j10; k < j5; k++) vol10 += ser.get(k)[DV];
            double volTrend = vol10 > 0 ? vol5 / (vol10 * 300.0 / 300.0) : (vol5 > 0 ? 9 : 0); // 5m vs prior 5m (j10..j5 is 5 min)

            // forward outcome 30 min
            double maxUp = 0, maxDn = 0;
            for (int k = i + 1; k < n && ser.get(k)[TS] <= ts + 1800; k++) {
                double g = (ser.get(k)[LTP] - p[LTP]) / p[LTP] * 100;
                if (g > maxUp) maxUp = g; if (-g > maxDn) maxDn = -g;
            }

            out.add(new Obs(day, idx,
                    (p[OI] - oi0_2) / oi0_2 * 100, (p[OI] - oi0_5) / oi0_5 * 100, (p[OI] - oi0_10) / oi0_10 * 100,
                    (p[LTP] - p0_2) / p0_2 * 100, (p[LTP] - p0_5) / p0_5 * 100, (p[LTP] - p0_10) / p0_10 * 100,
                    flow5 / bar, volTrend, absC, spfC, imbN > 0 ? imbSum / imbN : 0, maxUp, maxDn));
        }
    }
}
