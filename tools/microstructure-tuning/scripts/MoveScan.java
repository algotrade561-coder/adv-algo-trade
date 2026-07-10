import java.sql.*;
import java.util.*;
import java.nio.file.*;

/**
 * 3-day leak scan (07-06/07/08, real L5+OI+spoof capture) — run LOCALLY.
 *
 * A) MOVES OFFERED: per instrument, breakout events (ltp crosses +3% above rolling 15-min low)
 *    with entry-gate features AT the breakout tick (thrust, volRatio, L5 imbalance, signed flow,
 *    absorption, spoof, 5-min OI change). Outcome = max gain within the next 45 min.
 * B) CAUGHT vs MISSED: join the bot's real entries (trades-3d.csv) to each big move; for missed
 *    moves, report which COE/CTO gate leg failed at breakout.
 * C) EXIT LAG: for each real OI-momentum trade, time from first post-peak reversal evidence
 *    (imb flip + sell flow / absorption / spoof / writer OI-build) to the actual exit, and the
 *    rupees given back in that lag.
 * D) CORRELATION: forward 15-min max gain bucketed by dOI5m x imbalance and thrust x volRatio.
 *
 * args: dataDir  (expects atm-microstructure-2026-07-06.csv, atm-0707.csv, atm-0708.csv, trades-3d.csv)
 */
public class MoveScan {
    static final double BREAKOUT_PCT = 3.0;      // trigger: +3% above rolling low = "catchable stage"
    static final double BIG_MOVE_PCT = 15.0;     // a move worth catching
    static final int LOW_WIN = 900, FWD_WIN = 2700, EVENT_GAP = 300; // 15m low, 45m forward, 5m dedupe
    static final double MIN_PREMIUM = 15.0;      // ignore deep-OTM dust

    record Ev(String day, String idx, int strike, String type, long t0, double p0,
              double thrust, double volRatio, double imb, double sv, boolean absorb, boolean spoof,
              double dOi5mPct, double maxGain, long tPeak, double pPeak) {}
    record Trade(String id, long user, String idx, int strike, String type, long entryTs, long exitTs,
                 double entry, double exit, int qty, double pnl, String exitReason, String strategy) {}

    public static void main(String[] a) throws Exception {
        String dir = a.length > 0 ? a[0] : "data";
        Class.forName("org.duckdb.DuckDBDriver");
        List<Ev> events = new ArrayList<>();
        Map<String, List<double[]>> series = new HashMap<>(); // key -> sampled [ts,ltp,bid,imb,sv,absorb,spoof,oi] for exit analysis (traded instruments only)
        List<Trade> trades = loadTrades(Path.of(dir, "trades-3d.csv"));
        Set<String> tradedKeys = new HashSet<>();
        for (Trade t : trades) tradedKeys.add(t.idx + ":" + t.strike + ":" + t.type);

        try (Connection c = DriverManager.getConnection("jdbc:duckdb:"); Statement s = c.createStatement()) {
            s.execute("PRAGMA threads=4"); s.execute("SET memory_limit='6GB'");
            String files = "['" + dir + "/atm-microstructure-2026-07-06.csv','" + dir + "/atm-0707.csv','" + dir + "/atm-microstructure-2026-07-08.csv']";
            String sql = "SELECT strftime(to_timestamp(exchangeTsEpochSec + 19800), '%m-%d') dt, index idx, strike, optionType t, "
                + "exchangeTsEpochSec ts, ltp, bestBid, bestAsk, cumVolume, oi, bookImbalance, signedVolume, "
                + "isAbsorption, spoofActive FROM read_csv_auto(" + files + ", union_by_name=true) "
                + "WHERE ltp>0 AND exchangeTsEpochSec>1700000000 AND (exchangeTsEpochSec+19800)%86400 BETWEEN 33300 AND 55800 "
                + "ORDER BY dt, idx, strike, t, ts";
            ResultSet r = s.executeQuery(sql);

            String curKey = null;
            ArrayDeque<double[]> lows = new ArrayDeque<>();   // {ts, ltp} for rolling low
            ArrayDeque<double[]> volw = new ArrayDeque<>();   // {ts, dv, ltp} 60s window
            ArrayDeque<double[]> oiw = new ArrayDeque<>();    // {ts, oi} 5-min window
            long prevCum = -1; double prevLtp = 0;
            List<Ev> open = new ArrayList<>();                // candidates tracking forward window
            String dt = null, idx = null, typ = null; int strike = 0;
            long lastEventT0 = 0;

            while (r.next()) {
                String d = r.getString(1), ix = r.getString(2), ty = r.getString(4);
                int st = r.getInt(3); long ts = r.getLong(5);
                double ltp = r.getDouble(6), bid = r.getDouble(7);
                long cum = r.getLong(9); double oi = r.getDouble(10);
                double imb = r.getDouble(11), sv = r.getDouble(12);
                boolean absorb = r.getBoolean(13), spoof = r.getBoolean(14);
                String key = d + "|" + ix + "|" + st + "|" + ty;
                if (!key.equals(curKey)) {
                    flush(open, events); open.clear();
                    curKey = key; lows.clear(); volw.clear(); oiw.clear(); prevCum = -1; prevLtp = 0; lastEventT0 = 0;
                    dt = d; idx = ix; strike = st; typ = ty;
                }
                long dv = (prevCum < 0 || cum < prevCum) ? 0 : (cum - prevCum);
                lows.addLast(new double[]{ts, ltp}); while (!lows.isEmpty() && lows.peekFirst()[0] < ts - LOW_WIN) lows.pollFirst();
                volw.addLast(new double[]{ts, dv, ltp}); while (!volw.isEmpty() && volw.peekFirst()[0] < ts - 60) volw.pollFirst();
                oiw.addLast(new double[]{ts, oi}); while (!oiw.isEmpty() && oiw.peekFirst()[0] < ts - 60) oiw.pollFirst(); // TICK-level OI velocity (60s)

                // keep a decimated series for traded instruments (exit-lag analysis)
                String ikey = ix + ":" + st + ":" + ty;
                if (tradedKeys.contains(ikey)) {
                    series.computeIfAbsent(ikey + "|" + d, k -> new ArrayList<>())
                          .add(new double[]{ts, ltp, bid, imb, sv, absorb ? 1 : 0, spoof ? 1 : 0, oi});
                }

                // update open candidates with forward path
                for (Ev e : open) { /* replaced at flush; track via arrays */ }
                for (int i = 0; i < open.size(); i++) {
                    Ev e = open.get(i);
                    if (ts - e.t0 <= FWD_WIN) {
                        double g = (ltp - e.p0) / e.p0 * 100.0;
                        if (g > e.maxGain) open.set(i, withGain(e, g, ts, ltp));
                    }
                }
                // close matured candidates
                if (!open.isEmpty() && ts - open.get(0).t0 > FWD_WIN) {
                    Iterator<Ev> it = open.iterator();
                    while (it.hasNext()) { Ev e = it.next(); if (ts - e.t0 > FWD_WIN) { events.add(e); it.remove(); } }
                }

                // breakout detection
                double low = Double.MAX_VALUE; for (double[] w : lows) low = Math.min(low, w[1]);
                if (low > 0 && low != Double.MAX_VALUE && ltp >= MIN_PREMIUM
                        && ltp >= low * (1 + BREAKOUT_PCT / 100.0) && ts - lastEventT0 >= EVENT_GAP) {
                    double ltp60 = volw.peekFirst() != null ? volw.peekFirst()[2] : ltp;
                    double thrust = ltp60 > 0 ? (ltp - ltp60) / ltp60 * 100.0 : 0;
                    double vol30 = 0, volP30 = 0;
                    for (double[] w : volw) { if (w[0] > ts - 30) vol30 += w[1]; else volP30 += w[1]; }
                    double vr = volP30 > 0 ? vol30 / volP30 : (vol30 > 0 ? 99 : 0);
                    double oi0 = oiw.peekFirst() != null ? oiw.peekFirst()[1] : oi;
                    double dOi = oi0 > 0 ? (oi - oi0) / oi0 * 100.0 : 0;
                    open.add(new Ev(dt, idx, strike, typ, ts, ltp, thrust, vr, imb, sv, absorb, spoof, dOi, 0, ts, ltp));
                    lastEventT0 = ts;
                }
                prevCum = cum; prevLtp = ltp;
            }
            flush(open, events);
        }

        report(events, trades, series);
    }

    static Ev withGain(Ev e, double g, long tp, double pp) {
        return new Ev(e.day, e.idx, e.strike, e.type, e.t0, e.p0, e.thrust, e.volRatio, e.imb, e.sv,
                e.absorb, e.spoof, e.dOi5mPct, g, tp, pp);
    }
    static void flush(List<Ev> open, List<Ev> out) { out.addAll(open); }

    static List<Trade> loadTrades(Path p) throws Exception {
        List<Trade> out = new ArrayList<>();
        List<String> lines = Files.readAllLines(p);
        for (int i = 1; i < lines.size(); i++) {
            String[] f = splitCsv(lines.get(i));
            if (f.length < 16) continue;
            String inst = f[2].replace("NFO:", "").replace("BFO:", "").replace("\"", "");
            // e.g. NIFTY2670724350PE -> idx=NIFTY, strike=24350, type=PE
            String type = inst.substring(inst.length() - 2);
            String core = inst.substring(0, inst.length() - 2);
            int di = 0; while (di < core.length() && !Character.isDigit(core.charAt(di))) di++;
            String idx = core.substring(0, di);
            String digits = core.substring(di);
            if (digits.length() < 6) continue;
            int strike = Integer.parseInt(digits.substring(5));
            long entryTs = parseTs(f[9]); long exitTs = parseTs(f[10]);
            double entry = num(f[7]), exit = num(f[8]), pnl = num(f[11]);
            int qty = (int) num(f[6]);
            out.add(new Trade(f[0], (long) num(f[1]), idx, strike, type, entryTs, exitTs, entry, exit, qty, pnl,
                    f[15].replace("\"", ""), f[13].replace("\"", "")));
        }
        return out;
    }
    static String[] splitCsv(String l) { return l.split(",(?=(?:[^\"]*\"[^\"]*\")*[^\"]*$)", -1); }
    static double num(String s) { s = s.replace("\"", "").trim(); return s.isEmpty() ? 0 : Double.parseDouble(s); }
    static long parseTs(String s) {
        s = s.replace("\"", "").trim(); if (s.isEmpty()) return 0;
        // "2026-07-06 03:50:37.883811+00" (UTC)
        try { return java.time.OffsetDateTime.parse(s.replace(" ", "T").replaceAll("\\+00$", "Z")).toEpochSecond(); }
        catch (Exception e) {
            try { return java.time.LocalDateTime.parse(s.substring(0, 19).replace(" ", "T")).toEpochSecond(java.time.ZoneOffset.UTC); }
            catch (Exception e2) { return 0; }
        }
    }
    static String ist(long ts) { long t = ts + 19800; return String.format("%02d:%02d", (t / 3600) % 24, (t % 3600) / 60); }

    static void report(List<Ev> events, List<Trade> trades, Map<String, List<double[]>> series) {
        System.out.println("== A) MOVES OFFERED (breakout +%.0f%% above 15-min low, fwd 45-min max gain) ==".formatted(BREAKOUT_PCT));
        List<Ev> big = events.stream().filter(e -> e.maxGain >= BIG_MOVE_PCT).sorted(Comparator.comparing((Ev e) -> e.day).thenComparing(e -> e.t0)).toList();
        System.out.println("   total breakout events=" + events.size() + "  big moves(>=" + (int) BIG_MOVE_PCT + "%)=" + big.size());

        // real bot entries (dedupe fan-out copies: same instrument+entry-minute)
        List<Trade> oim = trades.stream().filter(t -> !t.id.startsWith("SYNC") && !t.id.startsWith("PAPER"))
                .sorted(Comparator.comparingLong(t -> t.entryTs)).toList();
        Map<String, Trade> dedup = new LinkedHashMap<>();
        for (Trade t : oim) dedup.putIfAbsent(t.idx + ":" + t.strike + ":" + t.type + ":" + (t.entryTs / 60), t);
        List<Trade> uniq = new ArrayList<>(dedup.values());

        System.out.println("\n== B) BIG MOVES: caught vs missed (bot entries: " + uniq.size() + " unique, " + oim.size() + " incl fan-out) ==");
        int caught = 0, late = 0, missed = 0; double missedGain = 0;
        System.out.printf("   %-5s %-9s %-22s %7s %7s %6s | %7s %6s %6s %6s %5s %5s %6s | %s%n",
                "day", "t0(IST)", "instrument", "p0", "maxG%", "mins", "thrust", "volR", "imb", "sv", "abs", "spoof", "dOI5m", "bot");
        for (Ev e : big) {
            Trade hit = null; boolean lateHit = false;
            for (Trade t : uniq) {
                if (!t.idx.equals(e.idx) || t.strike != e.strike || !t.type.equals(e.type)) continue;
                if (t.entryTs >= e.t0 - 120 && t.entryTs <= e.tPeak) { hit = t; break; }
                if (t.entryTs > e.tPeak && t.entryTs <= e.t0 + FWD_WIN) { hit = t; lateHit = true; }
            }
            String status;
            if (hit != null && !lateHit) { caught++; double stage = (hit.entry - e.p0) / (e.pPeak - e.p0) * 100.0;
                status = "CAUGHT@" + (int) stage + "%ofMove pnl=" + (int) hit.pnl; }
            else if (hit != null) { late++; status = "LATE(post-peak) pnl=" + (int) hit.pnl; }
            else { missed++; missedGain += e.maxGain;
                StringBuilder why = new StringBuilder("MISSED[");
                if (e.thrust < 2.0) why.append("thrust<2 ");
                if (e.volRatio < 1.5) why.append("volR<1.5 ");
                if (e.imb < 0.25) why.append("imb<0.25 ");
                if (e.sv <= 0) why.append("sellFlow ");
                if (e.absorb) why.append("ABSORB-veto ");
                if (e.spoof) why.append("SPOOF-veto ");
                if (why.toString().equals("MISSED[")) why.append("GATES-PASS?block/cooldown/cap");
                status = why.toString().trim() + "]"; }
            System.out.printf("   %-5s %-9s %-22s %7.1f %7.1f %6d | %7.1f %6.1f %6.2f %6.0f %5s %5s %6.1f | %s%n",
                    e.day, ist(e.t0), e.idx + " " + e.strike + " " + e.type, e.p0, e.maxGain,
                    (e.tPeak - e.t0) / 60, e.thrust, Math.min(e.volRatio, 99), e.imb, Math.signum(e.sv),
                    e.absorb ? "Y" : "-", e.spoof ? "Y" : "-", e.dOi5mPct, status);
        }
        System.out.printf("   => caught=%d late=%d MISSED=%d (missed avg maxGain=%.0f%%)%n", caught, late, missed, missed > 0 ? missedGain / missed : 0);

        System.out.println("\n== C) EXIT LAG on real trades (reversal evidence -> actual exit) ==");
        System.out.printf("   %-5s %-22s %-6s %8s %8s %8s %8s | %8s %8s %9s | %s%n",
                "day", "instrument", "u", "entry", "peak", "exit", "pnl", "evid(IST)", "exit(IST)", "lag_s", "gaveBack/lot & reason");
        double totalGiveback = 0; int lagged = 0;
        for (Trade t : oim) {
            if (t.exitTs == 0 || t.entryTs == 0) continue;
            String day = java.time.Instant.ofEpochSecond(t.entryTs + 19800).toString().substring(5, 10).replace('-', '-');
            String skey = t.idx + ":" + t.strike + ":" + t.type + "|" + day.substring(0, 5);
            List<double[]> ser = series.get(t.idx + ":" + t.strike + ":" + t.type + "|" + day);
            if (ser == null) { // try both day formats
                for (String k : series.keySet()) if (k.startsWith(t.idx + ":" + t.strike + ":" + t.type + "|")) { ser = series.get(k); break; }
            }
            if (ser == null) continue;
            double peak = t.entry; long tPeak = t.entryTs;
            for (double[] p : ser) { if (p[0] >= t.entryTs && p[0] <= t.exitTs && p[1] > peak) { peak = p[1]; tPeak = (long) p[0]; } }
            long evid = 0; double pEvid = 0;
            for (double[] p : ser) {
                if (p[0] <= tPeak || p[0] > t.exitTs) continue;
                boolean writerBuild = false; // oi rising while price down 1% from peak
                boolean rev = (p[3] < -0.15 && p[4] < 0) || p[5] == 1 || p[6] == 1;
                if (rev && p[1] < peak * 0.99) { evid = (long) p[0]; pEvid = p[1]; break; }
            }
            if (evid == 0) continue;
            long lag = t.exitTs - evid;
            double gb = (pEvid - t.exit) * t.qty;
            if (lag > 30 && gb > 0) { lagged++; totalGiveback += gb; }
            System.out.printf("   %-5s %-22s %-6d %8.1f %8.1f %8.1f %8.0f | %8s %8s %9d | gb=Rs%.0f %s%n",
                    day.substring(0, 5), t.idx + " " + t.strike + " " + t.type, t.user, t.entry, peak, t.exit, t.pnl,
                    ist(evid), ist(t.exitTs), lag, gb, t.exitReason);
        }
        System.out.printf("   => trades with >30s lag after evidence AND positive giveback: %d, total giveback ~Rs%.0f%n", lagged, totalGiveback);

        System.out.println("\n== D) CORRELATION: fwd 45-min max gain by feature bucket (ALL " + events.size() + " breakouts) ==");
        corr(events, "dOI60s", e -> e.dOi5mPct, new double[]{-1, -0.3, 0, 0.3, 1});
        corr(events, "imbalance", e -> e.imb, new double[]{-0.4, -0.1, 0.1, 0.25, 0.5});
        corr(events, "thrust", e -> e.thrust, new double[]{0, 1, 2, 4, 8});
        corr(events, "volRatio", e -> e.volRatio, new double[]{0.5, 1, 1.5, 3, 6});
    }

    static void corr(List<Ev> evs, String name, java.util.function.ToDoubleFunction<Ev> f, double[] cuts) {
        int n = cuts.length + 1;
        double[] sum = new double[n]; int[] cnt = new int[n]; int[] bigN = new int[n];
        for (Ev e : evs) {
            double v = f.applyAsDouble(e); int b = 0; while (b < cuts.length && v >= cuts[b]) b++;
            sum[b] += e.maxGain; cnt[b]++; if (e.maxGain >= BIG_MOVE_PCT) bigN[b]++;
        }
        StringBuilder sb = new StringBuilder(String.format("   %-10s", name));
        for (int i = 0; i < n; i++) {
            String lbl = i == 0 ? "<" + cuts[0] : i == cuts.length ? ">=" + cuts[cuts.length - 1] : "[" + cuts[i - 1] + "," + cuts[i] + ")";
            sb.append(String.format(" | %-12s n=%-6d avg=%5.1f%% P(big)=%4.1f%%", lbl, cnt[i],
                    cnt[i] > 0 ? sum[i] / cnt[i] : 0, cnt[i] > 0 ? 100.0 * bigN[i] / cnt[i] : 0));
        }
        System.out.println(sb);
    }
}
