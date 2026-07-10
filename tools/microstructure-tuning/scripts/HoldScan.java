import java.sql.*;
import java.util.*;

/**
 * INSTITUTIONAL-HOLD probe (2026-07-09, review-4 proposal check).
 * Hypothesis under test: "OI flat + premium grinding up + large existing OI" (institutions already
 * positioned, holding) is a tradeable entry the V5 avalanche misses.
 *
 * Same strictly-causal pipeline as V5Memory (time-ordered across instruments, 5s samples, 60s
 * baselines, 25-min warmup, 09:25-15:10, premium 15-700, abs/spoof veto, 120s cooldown, 5/day/inst).
 *
 * Variants (independent books, 1 lot, ask/bid fills, -Rs50/rt):
 *   A  fixed:      |dOI5m| < 0.5%  &&  dP15m >= +2%  &&  dP5m >= 0
 *   B  A + size:   oi >= 0.8 x day's running max oi  (large position EXISTS from earlier)
 *   C  normalized: |dOI5m| < 0.5%  &&  dP15m >= 3 x volUnit  &&  dP5m >= 0
 * Exits per the proposal: wide trail (arm +4%, gap 3%), SL -6%, MAXHOLD 45 min, EOD 15:20.
 *
 * Also: model-free forward returns — mid +15m/+30m after each trigger vs unconditional baseline.
 */
public class HoldScan {

    static class Inst {
        String idx, ty; int strike; int lot;
        ArrayDeque<double[]> samp = new ArrayDeque<>();
        long lastSamp = 0, lastBase = 0, firstTs = 0, lastFwdSamp = 0;
        double madRet = 0.004; long prevCum = -1;
        double dayMaxOi = 0;
        // per-variant position state: entry px, peak, inTs, cooldown, dayCount
        double[] entry = new double[3]; double[] peak = new double[3];
        long[] inTs = new long[3]; long[] cool = new long[3]; int[] cnt = new int[3];
        boolean[] in = new boolean[3];
    }
    record Trade(String day, String variant, String inst, long inTs, long outTs,
                 double in, double out, int lot, String reason) {}
    // forward-return sample: {isTrigger(0=base,1=A,2=B,3=C), fwd15, fwd30 filled later}
    record Fwd(String day, int kind, long ts, double px, String key) {}

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "data";
        Class.forName("org.duckdb.DuckDBDriver");
        List<Trade> trades = new ArrayList<>();
        List<Fwd> fwds = new ArrayList<>();
        Map<String, TreeMap<Long, Double>> mids = new HashMap<>(); // key -> ts -> mid (for fwd lookup)

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
            String files = "['" + dir + "/atm-microstructure-2026-07-06.csv','" + dir + "/atm-0707.csv','" + dir + "/atm-microstructure-2026-07-08.csv']";
            ResultSet r = s.executeQuery(
                "SELECT strftime(to_timestamp(exchangeTsEpochSec + 19800), '%m-%d') dt, index idx, strike, optionType ty, "
                + "exchangeTsEpochSec ts, ltp, bestBid, bestAsk, isAbsorption, spoofActive, oi, cumVolume "
                + "FROM read_csv_auto(" + files + ", union_by_name=true) "
                + "WHERE ltp>0 AND exchangeTsEpochSec>1700000000 AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 "
                + "ORDER BY dt, ts");

            Map<String, Inst> insts = new HashMap<>();
            String curDay = null;
            while (r.next()) {
                String day = r.getString(1);
                if (!day.equals(curDay)) { insts.clear(); curDay = day; }
                String idx = r.getString(2), ty = r.getString(4);
                int strike = r.getInt(3);
                long ts = r.getLong(5);
                double ltp = r.getDouble(6), bid = r.getDouble(7), ask = r.getDouble(8);
                boolean abs = r.getBoolean(9), spf = r.getBoolean(10);
                double oi = r.getDouble(11);

                String key = day + "|" + idx + "|" + strike + "|" + ty;
                Inst in = insts.computeIfAbsent(key, k -> {
                    Inst x = new Inst(); x.idx = idx; x.ty = ty; x.strike = strike; x.firstTs = ts;
                    x.lot = idx.equals("SENSEX") ? 20 : idx.equals("BANKNIFTY") ? 30 : 65;
                    return x;
                });
                in.dayMaxOi = Math.max(in.dayMaxOi, oi);
                double mid = (bid > 0 && ask > 0) ? (bid + ask) / 2 : ltp;
                mids.computeIfAbsent(key, k -> new TreeMap<>()).put(ts, mid);

                if (ts - in.lastSamp >= 5) {
                    in.lastSamp = ts;
                    in.samp.addLast(new double[]{ts, ltp, oi});
                    while (!in.samp.isEmpty() && in.samp.peekFirst()[0] < ts - 5400) in.samp.pollFirst();
                    if (ts - in.lastBase >= 60 && in.samp.size() >= 60) { in.lastBase = ts; baselines(in); }
                }

                double[] p5 = at(in.samp, ts - 300);
                double[] p15 = at(in.samp, ts - 900);
                double dP5 = p5 != null && p5[1] > 0 ? (ltp - p5[1]) / p5[1] * 100 : 0;
                double dOi5 = p5 != null && p5[2] > 0 ? (oi - p5[2]) / p5[2] * 100 : 0;
                double dP15 = p15 != null && p15[1] > 0 ? (ltp - p15[1]) / p15[1] * 100 : 0;
                double volUnit = Math.max(1.5, Math.min(10, 2.2 * in.madRet * 100));
                long istSec = (ts + 19800) % 86400;

                // ---------- EXITS (all variants) ----------
                for (int v = 0; v < 3; v++) {
                    if (!in.in[v]) continue;
                    in.peak[v] = Math.max(in.peak[v], ltp);
                    long held = ts - in.inTs[v];
                    double profit = (ltp - in.entry[v]) / in.entry[v] * 100;
                    String rsn = null;
                    if (profit <= -6) rsn = "SL";
                    else if (in.peak[v] >= in.entry[v] * 1.04 && ltp <= in.peak[v] * 0.97) rsn = "TRAIL";
                    else if (held >= 2700) rsn = "MAXHOLD";
                    else if (istSec >= 55200) rsn = "EOD";
                    if (rsn != null) {
                        double px = bid > 0 ? bid : ltp;
                        trades.add(new Trade(day, "" + (char)('A'+v), idx + " " + strike + " " + ty,
                                in.inTs[v], ts, in.entry[v], px, in.lot, rsn));
                        in.in[v] = false; in.cool[v] = ts + 120;
                    }
                }

                // ---------- TRIGGERS ----------
                if (istSec < 33900 || istSec > 54600) continue;
                if (ts - in.firstTs < 1500 || in.samp.size() < 120) continue;
                boolean holdBase = Math.abs(dOi5) < 0.5 && dP5 >= 0 && p15 != null;
                boolean trigA = holdBase && dP15 >= 2.0;
                boolean trigB = trigA && oi >= 0.8 * in.dayMaxOi && oi > 0;
                boolean trigC = holdBase && dP15 >= 3 * volUnit;
                boolean anyVeto = (abs || spf) || ltp < 15 || ltp > 700;

                // forward-return sampling (independent of the books; 120s per-inst sampling cadence)
                if (ts - in.lastFwdSamp >= 120) {
                    in.lastFwdSamp = ts;
                    fwds.add(new Fwd(day, 0, ts, mid, key));                    // unconditional baseline
                    if (trigA && !anyVeto) fwds.add(new Fwd(day, 1, ts, mid, key));
                    if (trigB && !anyVeto) fwds.add(new Fwd(day, 2, ts, mid, key));
                    if (trigC && !anyVeto) fwds.add(new Fwd(day, 3, ts, mid, key));
                }

                if (anyVeto) continue;
                boolean[] trig = {trigA, trigB, trigC};
                for (int v = 0; v < 3; v++) {
                    if (!trig[v] || in.in[v] || ts < in.cool[v] || in.cnt[v] >= 5) continue;
                    double px = ask > 0 ? ask : ltp;
                    in.in[v] = true; in.entry[v] = px; in.peak[v] = px; in.inTs[v] = ts; in.cnt[v]++;
                }
            }
        }

        // ---------- forward returns ----------
        double[][] sum = new double[4][2]; long[][] n = new long[4][2];
        for (Fwd f : fwds) {
            TreeMap<Long, Double> m = mids.get(f.key());
            for (int h = 0; h < 2; h++) {
                long horizon = h == 0 ? 900 : 1800;
                Map.Entry<Long, Double> e = m.floorEntry(f.ts() + horizon);
                if (e == null || e.getKey() < f.ts() + horizon - 120 || f.px() <= 0) continue;
                sum[f.kind()][h] += (e.getValue() - f.px()) / f.px() * 100;
                n[f.kind()][h]++;
            }
        }
        System.out.println("== INSTITUTIONAL-HOLD probe — forward MID returns (no trading, no costs) ==");
        String[] names = {"BASELINE(all)", "A fixed(dP15>=2,|dOI5|<.5)", "B A+oi>=0.8xdayMax", "C dP15>=3xvolUnit"};
        for (int k = 0; k < 4; k++)
            System.out.printf("  %-28s n=%-7d fwd15m=%+.3f%%   n=%-7d fwd30m=%+.3f%%%n",
                    names[k], n[k][0], n[k][0] > 0 ? sum[k][0] / n[k][0] : 0,
                    n[k][1], n[k][1] > 0 ? sum[k][1] / n[k][1] : 0);

        // ---------- simulated books ----------
        System.out.println("\n== simulated books (1 lot, ask/bid, -Rs50/rt; SL-6% / trail +4%/3% / 45m / EOD) ==");
        for (String v : new String[]{"A", "B", "C"}) {
            Map<String, double[]> byDay = new TreeMap<>();
            int nn = 0, w = 0; double tot = 0;
            for (Trade t : trades) {
                if (!t.variant().equals(v)) continue;
                double net = (t.out() - t.in()) * t.lot() - 50;
                tot += net; nn++; if (net > 0) w++;
                double[] d = byDay.computeIfAbsent(t.day(), k -> new double[2]);
                d[0]++; d[1] += net;
            }
            System.out.printf("  variant %s: trades=%d win%%=%.1f netRs=%.0f  perDay=%s%n",
                    v, nn, nn > 0 ? 100.0 * w / nn : 0, tot,
                    byDay.entrySet().stream().map(e2 -> e2.getKey() + ":" + Math.round(e2.getValue()[1]) + "(" + (int) e2.getValue()[0] + ")").toList());
        }
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
}
