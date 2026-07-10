package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Entry over-rejection — the "catch side" of the ledger as a standing report section (the analysis previously
 * done by hand in ENTRY-OVERREJECTION-ANALYSIS.md). For each real tradeable move in the window it shows what
 * the strategy did at the move's ignition: did a signal fire, was the data present, and — if it was missed —
 * <em>which gate rejected it</em> (CASE4/matrix, charges, range-guard, tod/cutoff) or whether the detector
 * simply didn't fire (no_momentum). This is how you see, per move, whether we missed real revenue and why.
 *
 * <p>Moves are detected per INDEX (spot series must be partitioned — NIFTY≈24k, BANKNIFTY≈57k, SENSEX≈77k —
 * or mixed-index ticks fake huge "moves") from the per-second eval {@code spot}. Fail-safe; needs the
 * per-second eval capture on.
 */
@Component
public class EntryMissAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(EntryMissAnalyzerPlugin.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(IST);
    private static final DateTimeFormatter DATE_HHMM = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(IST);

    private static final double MOVE_PCT = 0.40;   // a real tradeable move
    private static final int WINDOW_MIN = 30;
    private static final java.time.LocalTime MKT_OPEN = java.time.LocalTime.of(9, 15);
    private static final java.time.LocalTime MKT_CLOSE = java.time.LocalTime.of(15, 30);

    /** True if the eval-minute is within the 09:15–15:30 IST session (the loop evaluates past close). */
    private static boolean inMarketHours(long epochMin) {
        var t = Instant.ofEpochSecond(epochMin * 60).atZone(IST).toLocalTime();
        return !t.isBefore(MKT_OPEN) && !t.isAfter(MKT_CLOSE);
    }

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    @Override
    public int order() {
        return 75; // next to missed-opportunity (70)
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(section(query));
    }

    /** One per-minute slice for one index. */
    private record Min(double spot, long evals, long ceOk, double rng, long signals,
                       long charges, long matrix, long noMom, long tod, long rangeGuard) {}

    AnalyzerSection section(TuningEventQuery query) {
        final String title = "Entry over-rejection (real moves missed & the gate that blocked them)";
        try {
            if (!EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION)) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No evaluation data — needs the per-second eval capture on.</em></p>");
            }
            String evalSrc = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION);
            String evalSql = ""
                    + "WITH e AS (SELECT index AS idx, "
                    + "  CAST(epoch(date_trunc('minute', TRY_CAST(eventTime AS TIMESTAMP))) AS BIGINT)/60 AS mi, "
                    + "  TRY_CAST(json_extract_string(attr_extra,'$.spot') AS DOUBLE) AS sp, "
                    + "  TRY_CAST(json_extract_string(attr_extra,'$.atmCeLast') AS DOUBLE) AS ce, "
                    + "  TRY_CAST(json_extract_string(attr_extra,'$.rangePct30m') AS DOUBLE) AS rng, blocker "
                    + "  FROM " + evalSrc + ") "
                    + "SELECT idx, mi, AVG(CASE WHEN sp>0 THEN sp END) AS spot, COUNT(*) AS evals, "
                    + "  SUM(CASE WHEN ce>0 THEN 1 ELSE 0 END) AS ce_ok, AVG(rng) AS rng, "
                    + "  SUM(CASE WHEN blocker LIKE 'charges%' THEN 1 ELSE 0 END) AS c_charges, "
                    + "  SUM(CASE WHEN blocker LIKE '%CASE4%' OR blocker LIKE 'matrix_skip%' THEN 1 ELSE 0 END) AS c_matrix, "
                    + "  SUM(CASE WHEN blocker LIKE 'no_momentum%' THEN 1 ELSE 0 END) AS c_nomom, "
                    + "  SUM(CASE WHEN blocker LIKE 'tod_gate%' OR blocker LIKE '%cutoff%' THEN 1 ELSE 0 END) AS c_tod, "
                    + "  SUM(CASE WHEN blocker LIKE 'range_edge%' OR blocker LIKE '%RANGE_LT%' OR blocker LIKE 'regime_standdown%' THEN 1 ELSE 0 END) AS c_range "
                    + "FROM e GROUP BY idx, mi";
            List<Map<String, Object>> evalRows = query.store().query(evalSql);

            // signals per (idx, minute)
            Map<String, Long> sigByKey = new HashMap<>();
            if (EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL)) {
                String sigSrc = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL);
                String sigSql = "SELECT index AS idx, "
                        + "CAST(epoch(date_trunc('minute', TRY_CAST(eventTime AS TIMESTAMP))) AS BIGINT)/60 AS mi, "
                        + "COUNT(*) AS n FROM " + sigSrc + " GROUP BY idx, mi";
                for (Map<String, Object> r : query.store().query(sigSql)) {
                    sigByKey.put(str(r.get("idx")) + "|" + asLong(r.get("mi")), asLong(r.get("n")));
                }
            }

            // index -> (minute -> Min)
            Map<String, TreeMap<Long, Min>> byIndex = new HashMap<>();
            for (Map<String, Object> r : evalRows) {
                String idx = str(r.get("idx"));
                long mi = asLong(r.get("mi"));
                long sigs = sigByKey.getOrDefault(idx + "|" + mi, 0L);
                byIndex.computeIfAbsent(idx, k -> new TreeMap<>()).put(mi, new Min(
                        asDouble(r.get("spot")), asLong(r.get("evals")), asLong(r.get("ce_ok")),
                        asDouble(r.get("rng")), sigs, asLong(r.get("c_charges")), asLong(r.get("c_matrix")),
                        asLong(r.get("c_nomom")), asLong(r.get("c_tod")), asLong(r.get("c_range"))));
            }

            List<String[]> rows = new ArrayList<>();
            for (var idxEntry : byIndex.entrySet()) {
                detectMovesForIndex(idxEntry.getKey(), idxEntry.getValue(), rows);
            }
            return render(title, rows);
        } catch (TuningQueryException ex) {
            log.warn("[EntryMissAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Entry-miss query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    /** Greedy non-overlapping forward-move detection over one index's minute series. */
    private void detectMovesForIndex(String idx, TreeMap<Long, Min> series, List<String[]> out) {
        List<Long> mins = new ArrayList<>(series.keySet());
        int i = 0;
        while (i < mins.size()) {
            long m0 = mins.get(i);
            Min s0 = series.get(m0);
            if (s0.spot() <= 0 || !inMarketHours(m0)) { i++; continue; }
            double best = 0; int bestJ = i;
            for (int j = i + 1; j < mins.size() && mins.get(j) - m0 <= WINDOW_MIN; j++) {
                Min sj = series.get(mins.get(j));
                if (sj.spot() <= 0) continue;
                double mv = Math.abs(sj.spot() - s0.spot()) / s0.spot() * 100.0;
                if (mv > best) { best = mv; bestJ = j; }
            }
            if (best >= MOVE_PCT) {
                // aggregate window i..bestJ
                long ev = 0, ceOk = 0, sig = 0, ch = 0, mx = 0, nm = 0, tod = 0, rg = 0;
                for (int k = i; k <= bestJ; k++) {
                    Min s = series.get(mins.get(k));
                    ev += s.evals(); ceOk += s.ceOk(); sig += s.signals();
                    ch += s.charges(); mx += s.matrix(); nm += s.noMom(); tod += s.tod(); rg += s.rangeGuard();
                }
                int dataPct = ev > 0 ? (int) Math.round(100.0 * ceOk / ev) : 0;
                String verdict = verdict(sig, ev, dataPct, ch, mx, nm, tod, rg);
                out.add(new String[]{
                        idx, DATE_HHMM.format(Instant.ofEpochSecond(m0 * 60)) + "–"
                            + HHMM.format(Instant.ofEpochSecond(mins.get(bestJ) * 60)),
                        String.format(Locale.ROOT, "%.2f%%", best),
                        String.format(Locale.ROOT, "%.2f", s0.rng()),
                        dataPct + "%",
                        "M:" + mx + " ch:" + ch + " noMom:" + nm + " rng:" + rg + " tod:" + tod,
                        String.valueOf(sig), verdict});
                i = bestJ + 1;
            } else {
                i++;
            }
        }
    }

    private static String verdict(long sig, long ev, int dataPct, long ch, long mx, long nm, long tod, long rg) {
        if (sig > 0) return "✅ captured";
        if (ev == 0) return "🔴 BLIND (no evals)";
        if (dataPct < 50) return "🔴 data stale";
        // dominant non-signal cause
        long max = Math.max(Math.max(mx, ch), Math.max(nm, Math.max(tod, rg)));
        if (max == 0) return "🟠 no signal (no blocker)";
        if (max == mx) return "🔴 OVER-REJECTED: matrix/CASE4 (data present)";
        if (max == nm) return "🟠 detector missed (no_momentum)";
        if (max == ch) return "🟠 charges gate";
        if (max == rg) return "🔴 range guard (lagging)";
        return "🟠 tod/cutoff (policy)";
    }

    AnalyzerSection render(String title, List<String[]> rows) {
        if (rows.isEmpty()) {
            return AnalyzerSection.htmlOnly(title,
                    "<p><em>No tradeable moves (≥" + String.format(Locale.ROOT, "%.2f%%", MOVE_PCT)
                    + ") detected in the window — quiet/range-bound session(s).</em></p>");
        }
        StringBuilder h = new StringBuilder(1024);
        h.append("<p>Real moves (≥").append(String.format(Locale.ROOT, "%.2f%%", MOVE_PCT))
                .append(" within ").append(WINDOW_MIN).append(" min) and what the strategy did at ignition. "
                + "<strong>range@start</strong> is the trailing 30-min range when the move began — low values with a "
                + "real move = the lagging-range trap. Blocker counts: M=matrix/CASE4, ch=charges, noMom=no_momentum, "
                + "rng=range/standdown, tod=cutoff.</p>");
        h.append("<table class='entry-miss'><thead><tr><th>Index</th><th>Window (IST)</th><th>move</th>"
                + "<th>range@start</th><th>data present</th><th>blockers in window</th><th>signals</th>"
                + "<th>verdict</th></tr></thead><tbody>");
        // worst first: missed (no signal) before captured, then by move size
        rows.sort((a, b) -> {
            boolean ac = a[7].startsWith("✅"), bc = b[7].startsWith("✅");
            if (ac != bc) return ac ? 1 : -1;
            return Double.compare(parsePct(b[2]), parsePct(a[2]));
        });
        for (String[] r : rows) {
            h.append("<tr><td>").append(escape(r[0])).append("</td><td>").append(r[1]).append("</td>")
                    .append("<td>").append(r[2]).append("</td><td>").append(r[3]).append("%</td>")
                    .append("<td>").append(r[4]).append("</td><td>").append(escape(r[5])).append("</td>")
                    .append("<td>").append(r[6]).append("</td><td>").append(escape(r[7])).append("</td></tr>");
        }
        h.append("</tbody></table>");
        long missed = rows.stream().filter(r -> !r[7].startsWith("✅")).count();
        h.append("<p><strong>").append(missed).append(" of ").append(rows.size())
                .append("</strong> real moves were NOT entered. 🔴 rows with data present = over-rejection "
                + "(real revenue missed); fix the catch side (matrix/CASE4, range guard) not the skip side.</p>");
        return new AnalyzerSection(title, h.toString(), Map.of("moves", rows.size(), "missed", missed));
    }

    private static double parsePct(String s) {
        try { return Double.parseDouble(s.replace("%", "")); } catch (Exception e) { return 0; }
    }
    private static long asLong(Object o) { return (o instanceof Number n) ? n.longValue() : 0L; }
    private static double asDouble(Object o) { return (o instanceof Number n) ? n.doubleValue() : 0.0; }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
