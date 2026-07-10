package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.GenericStrategyAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P2 / §3c-bis — <b>Missed-opportunity attribution</b>: which of the day's real tradeable moves did we miss,
 * and <em>why</em>. Unlike the blocker section (which only covers evaluated-then-rejected setups), this
 * starts from a ground-truth of moves derived from the spot path itself, so it also catches moves the bot was
 * blind to (the 06/25 auth-dead case).
 *
 * <p>Method: reconstruct a per-minute spot series from the per-second evaluation events (the {@code spot}
 * feature), detect the largest clean directional runs (≥ {@link #MOVE_PCT}% within {@link #WINDOW_MIN} min),
 * and for each classify the cause by what the bot was doing in that window:
 * <ul>
 *   <li><b>BLIND (feed/auth dead)</b> — a gap in the eval series during market hours (no evals at all).</li>
 *   <li><b>CAPTURED</b> — a signal fired in the window.</li>
 *   <li><b>DATA STALE</b> — evals present but dominated by stale/zero-spot blocks.</li>
 *   <li><b>ENTRY CUTOFF (policy)</b> — blocked by the post-cutoff no-new-entries rule (not a fault).</li>
 *   <li><b>NO SIGNAL (coverage gap)</b> — feed healthy, evaluated, but no candidate produced.</li>
 *   <li><b>BLOCKED BY GATE</b> — a gate rejected it (cross-ref the opportunity-cost section).</li>
 * </ul>
 * Each cause points at its fix. Fail-safe; needs the per-second eval capture to be on (P0.2/P0.3).
 */
@Component
public class MissedOpportunityAnalyzerPlugin implements GenericStrategyAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(MissedOpportunityAnalyzerPlugin.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm").withZone(IST);

    /** A clean directional run of at least this % within the window counts as a tradeable move. */
    private static final double MOVE_PCT = 0.40;
    /** Forward look-ahead window for a move, in minutes. */
    private static final int WINDOW_MIN = 30;
    /** A gap of at least this many minutes with no evaluation during market hours = a blind window. */
    private static final int BLIND_GAP_MIN = 5;

    @Override
    public int order() {
        return 70;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query, StrategyType strategy) {
        return List.of(section(query, strategy));
    }

    AnalyzerSection section(TuningEventQuery query, StrategyType strategy) {
        final String title = "Missed-opportunity attribution (which moves we missed & why)";
        try {
            if (!EventScan.hasData(query, strategy, TuningEventType.EVALUATION)) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No evaluation data — needs the per-second eval capture on.</em></p>");
            }
            // Per-minute spot series + blocker mix, reconstructed from eval events.
            // Per (index, minute): spot must be partitioned BY INDEX — NIFTY≈24k, BANKNIFTY≈57k, SENSEX≈77k,
            // so a single mixed series fakes huge "moves". Group by idx + minute and detect per index.
            // NOTE: needs the `spot` attribute on eval rows — OI-momentum captures it inline; the pipeline
            // strategies get it centrally via TuningCaptureBridge. If a strategy has no spot yet, its spot
            // series is empty and this section simply reports no reconstructable moves (never a false move).
            String evalSql = ""
                    + "WITH e AS ("
                    + "  SELECT index AS idx, CAST(epoch(date_trunc('minute', TRY_CAST(eventTime AS TIMESTAMP))) AS BIGINT)/60 AS me, "
                    + "    TRY_CAST(json_extract_string(attr_extra,'$.spot') AS DOUBLE) AS sp, "
                    // ATM option premium + strike — the OPTION path is what the bot actually trades; spot barely
                    // moves on low-vol days while the ATM premium swings 10-20% (gamma). Needs the atmCeLast/PeLast
                    // capture fix (expiry-keyed atmOptionPremiums) — before it, these are 0 (NO_LTP) and the
                    // premium-move table self-suppresses via the coverage guard below.
                    + "    TRY_CAST(json_extract_string(attr_extra,'$.atmCeLast') AS DOUBLE) AS ce_raw, "
                    + "    TRY_CAST(json_extract_string(attr_extra,'$.atmPeLast') AS DOUBLE) AS pe_raw, "
                    + "    TRY_CAST(json_extract_string(attr_extra,'$.atm') AS INT) AS atm_raw, "
                    + "    blocker, outcome "
                    + "  FROM " + EventScan.source(query, strategy, TuningEventType.EVALUATION)
                    + ") SELECT idx, me, "
                    + "  AVG(CASE WHEN sp > 0 THEN sp END) AS spot, COUNT(*) AS evals, "
                    + "  AVG(CASE WHEN ce_raw > 0 THEN ce_raw END) AS ce, "
                    + "  AVG(CASE WHEN pe_raw > 0 THEN pe_raw END) AS pe, "
                    + "  MAX(atm_raw) AS atm, "
                    + "  SUM(CASE WHEN ce_raw > 0 THEN 1 ELSE 0 END) AS ce_present, "
                    + "  SUM(CASE WHEN sp IS NULL OR sp = 0 OR blocker LIKE '%data_stale%' THEN 1 ELSE 0 END) AS stale, "
                    + "  SUM(CASE WHEN blocker = 'entry_cutoff' THEN 1 ELSE 0 END) AS cutoff, "
                    + "  SUM(CASE WHEN blocker = 'no_momentum_detected' THEN 1 ELSE 0 END) AS no_mom, "
                    // Generic outcome mix — works for ALL strategies (the entry_cutoff/no_momentum literals
                    // above are OI-momentum-only). Lets classify() reach NO-SIGNAL for non-OI too. (2026-07-02)
                    + "  SUM(CASE WHEN outcome = 'SKIPPED' THEN 1 ELSE 0 END) AS skipped, "
                    + "  SUM(CASE WHEN outcome = 'BLOCKED' THEN 1 ELSE 0 END) AS blocked "
                    + "FROM e GROUP BY idx, me ORDER BY idx, me";
            List<Map<String, Object>> evalRows = query.store().query(evalSql);

            // Per (index, minute) signal counts, keyed "idx|me".
            Map<String, Long> signalByMin = new HashMap<>();
            if (EventScan.hasData(query, strategy, TuningEventType.SIGNAL)) {
                String sigSql = "SELECT index AS idx, CAST(epoch(date_trunc('minute', TRY_CAST(eventTime AS TIMESTAMP))) AS BIGINT)/60 AS me, "
                        + "COUNT(*) AS n FROM " + EventScan.source(query, strategy, TuningEventType.SIGNAL)
                        + " GROUP BY idx, me";
                for (Map<String, Object> r : query.store().query(sigSql)) {
                    signalByMin.put(str(r.get("idx")) + "|" + asLong(r.get("me")), asLong(r.get("n")));
                }
            }
            return render(title, evalRows, signalByMin);
        } catch (TuningQueryException ex) {
            log.warn("[MissedOpportunityAnalyzerPlugin] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title,
                    "<p class='error'>Missed-opportunity query failed: " + escape(ex.getMessage()) + "</p>");
        }
    }

    /** Holds one per-minute slice of one index's reconstructed timeline. */
    private record Minute(String idx, long epochMin, double spot, long evals, long stale, long cutoff, long noMom,
                          long signals, long skipped, long blocked,
                          double ce, double pe, int atm, long cePresent) {}

    /** A clean directional run of at least this % on the ATM OPTION premium within the window = a tradeable
     *  option move (the thing the bot actually buys). Higher than the spot bar because option premium is
     *  leveraged and must clear round-trip cost to be worth taking. */
    private static final double PREM_MOVE_PCT = 8.0;

    AnalyzerSection render(String title, List<Map<String, Object>> evalRows, Map<String, Long> signalByMin) {
        // Group minutes BY INDEX — each index has its own spot series (mixing them fakes moves).
        Map<String, List<Minute>> byIdx = new LinkedHashMap<>();
        for (Map<String, Object> r : evalRows) {
            String idx = str(r.get("idx"));
            long me = asLong(r.get("me"));
            byIdx.computeIfAbsent(idx, k -> new ArrayList<>()).add(new Minute(idx, me,
                    asDouble(r.get("spot")), asLong(r.get("evals")), asLong(r.get("stale")),
                    asLong(r.get("cutoff")), asLong(r.get("no_mom")),
                    signalByMin.getOrDefault(idx + "|" + me, 0L),
                    asLong(r.get("skipped")), asLong(r.get("blocked")),
                    asDouble(r.get("ce")), asDouble(r.get("pe")), (int) asLong(r.get("atm")),
                    asLong(r.get("ce_present"))));
        }
        int totalMinutes = byIdx.values().stream().mapToInt(List::size).sum();
        if (totalMinutes < 5) {
            return AnalyzerSection.htmlOnly(title, "<p><em>Not enough captured minutes to reconstruct the day.</em></p>");
        }

        Map<String, Integer> causeCount = new LinkedHashMap<>();
        Map<String, Double> causeCost = new LinkedHashMap<>();
        List<String> blind = new ArrayList<>();
        List<String[]> moveRows = new ArrayList<>();

        for (var indexEntry : byIdx.entrySet()) {
            String idx = indexEntry.getKey();
            List<Minute> mins = indexEntry.getValue(); // ORDER BY me from SQL
            // 1) Blind windows = gaps in THIS index's eval timeline — ONLY meaningful for DENSE
            // (≈per-second) capture like OI Momentum, where a multi-minute gap really means the bot
            // stopped seeing the market. Candle/episode-cadence strategies (Momentum, Scalping, the
            // spreads, …) naturally emit an eval row only every few minutes, so a >BLIND_GAP_MIN gap
            // between rows is NORMAL, not blindness — flagging it produces hundreds of false positives.
            // Gate on eval density: require this index to cover most minutes of its active span.
            if (isDense(mins)) {
                for (int k = 1; k < mins.size(); k++) {
                    long gap = mins.get(k).epochMin - mins.get(k - 1).epochMin;
                    // Only flag a gap as BLIND if it's an INTRADAY, same-session gap — overnight/weekend/
                    // inter-day gaps (market closed) are normal, not blindness.
                    if (gap >= BLIND_GAP_MIN && isIntradayGap(mins.get(k - 1).epochMin, mins.get(k).epochMin)) {
                        blind.add(idx + " " + dateHhmm(mins.get(k - 1).epochMin) + "–" + hhmm(mins.get(k).epochMin)
                                + " (" + gap + " min, no evals — feed/auth blind)");
                    }
                }
            }
            // 2) Detect moves over THIS index's spot series; greedy non-overlapping.
            int i = 0;
            while (i < mins.size()) {
                Minute start = mins.get(i);
                if (start.spot <= 0 || !inMarketHours(start.epochMin)) { i++; continue; }
                double best = 0;
                int bestJ = i;
                for (int j = i + 1; j < mins.size() && mins.get(j).epochMin - start.epochMin <= WINDOW_MIN; j++) {
                    if (mins.get(j).spot <= 0) continue;
                    double movePct = Math.abs(mins.get(j).spot - start.spot) / start.spot * 100.0;
                    if (movePct > best) { best = movePct; bestJ = j; }
                }
                if (best >= MOVE_PCT) {
                    String cause = classify(mins, i, bestJ);
                    causeCount.merge(cause, 1, Integer::sum);
                    causeCost.merge(cause, best, Double::sum);
                    if (!"CAPTURED".equals(cause) && !"ENTRY CUTOFF (policy)".equals(cause)) {
                        moveRows.add(new String[]{
                                idx + " " + dateHhmm(start.epochMin) + "–" + hhmm(mins.get(bestJ).epochMin),
                                String.format(Locale.ROOT, "%.2f%%", best), cause});
                    }
                    i = bestJ + 1; // skip past this move
                } else {
                    i++;
                }
            }
        }

        // 3) OPTION-premium moves — what the bot ACTUALLY trades. Spot barely moves on low-vol days, but the
        // ATM premium swings via gamma/theta/vega. Detect rideable UP moves on the ATM CE and PE premium series
        // (the bot BUYS premium, so only up-runs are the opportunity), grouped by FIXED strike so a strike-hop
        // isn't mistaken for a price move. Needs the atmOptionPremiums expiry-key capture fix; before it, ce/pe
        // are 0 (NO_LTP) and the coverage guard below self-suppresses the table instead of faking "no moves".
        Map<String, Integer> premCauseCount = new LinkedHashMap<>();
        Map<String, Double> premCauseCost = new LinkedHashMap<>();
        List<String[]> premMoveRows = new ArrayList<>();
        long totalEvals = 0, totalCePresent = 0;
        for (var indexEntry : byIdx.entrySet()) {
            String idx = indexEntry.getKey();
            List<Minute> mins = indexEntry.getValue();
            for (Minute m : mins) { totalEvals += m.evals(); totalCePresent += m.cePresent(); }
            Map<Integer, List<Minute>> byStrike = new LinkedHashMap<>();
            for (Minute m : mins) {
                if (m.atm() <= 0) continue;
                byStrike.computeIfAbsent(m.atm(), k -> new ArrayList<>()).add(m);
            }
            for (var se : byStrike.entrySet()) {
                detectPremiumLeg(idx, se.getKey(), se.getValue(), true, premCauseCount, premCauseCost, premMoveRows);
                detectPremiumLeg(idx, se.getKey(), se.getValue(), false, premCauseCount, premCauseCost, premMoveRows);
            }
        }
        double premCoverage = totalEvals > 0 ? 100.0 * totalCePresent / totalEvals : 0.0;

        StringBuilder h = new StringBuilder(2048);

        // ── PRIMARY: OPTION-premium moves (the instrument the bot trades) ──
        h.append("<h4>Missed OPTION moves — ATM premium ≥ ").append(fmtPct(PREM_MOVE_PCT))
                .append(" within ").append(WINDOW_MIN).append(" min (what the bot actually buys)</h4>");
        if (premCoverage < 50.0) {
            h.append("<p class='miss-suppressed'>🔴 <strong>SUPPRESSED</strong> — ATM premium captured on only ")
                    .append(fmtPct(premCoverage)).append(" of eval rows (NO_LTP). Option-move detection needs the "
                    + "<code>atmOptionPremiums</code> expiry-key capture fix live; until then it cannot be computed "
                    + "(silently reporting “no moves” here would be false). Index/spot moves below are unaffected.</p>");
        } else {
            appendCauseSummary(h, premCauseCount, premCauseCost);
            if (premCauseCount.isEmpty()) {
                h.append("<p><em>Premium captured (").append(fmtPct(premCoverage))
                        .append("), but no ≥").append(fmtPct(PREM_MOVE_PCT))
                        .append(" ATM option move in any ").append(WINDOW_MIN).append("-min window — genuinely quiet.</em></p>");
            }
            appendMoveList(h, "Missed option moves (excl. captured & entry-cutoff)", premMoveRows);
        }

        // ── SECONDARY: index (spot) moves — context only ──
        h.append("<h4>Index (spot) moves — context</h4>");
        h.append("<p>Spot moves ≥ ").append(String.format(Locale.ROOT, "%.2f%%", MOVE_PCT))
                .append(" within ").append(WINDOW_MIN).append(" min, reconstructed from the captured spot path. On "
                + "low-vol days the index barely moves even when option premiums swing, so read this as background — "
                + "the OPTION table above is the real opportunity measure. Entry-cutoff misses are policy, not a fault.</p>");
        appendCauseSummary(h, causeCount, causeCost);

        // Blind windows (intraday only; capped defensively)
        if (!blind.isEmpty()) {
            h.append("<h4>⚠ Blind windows (intraday gaps with no data — possible unseen moves)</h4><ul>");
            int shown = 0;
            for (String b : blind) {
                if (shown++ >= 50) break;
                h.append("<li>").append(escape(b)).append("</li>");
            }
            if (blind.size() > 50) {
                h.append("<li><em>… and ").append(blind.size() - 50).append(" more</em></li>");
            }
            h.append("</ul>");
        }
        appendMoveList(h, "Missed index moves (excl. captured & entry-cutoff)", moveRows);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("optionCauses", new LinkedHashMap<>(premCauseCount));
        metadata.put("premiumCoveragePct", premCoverage);
        metadata.put("causes", new LinkedHashMap<>(causeCount));
        metadata.put("blindWindows", blind.size());
        return new AnalyzerSection(title, h.toString(), metadata);
    }

    /** Greedy non-overlapping UP-run detection on one ATM leg's premium series (CE if {@code ceLeg}, else PE). */
    private void detectPremiumLeg(String idx, int strike, List<Minute> sm, boolean ceLeg,
                                  Map<String, Integer> causeCount, Map<String, Double> causeCost,
                                  List<String[]> moveRows) {
        int i = 0;
        while (i < sm.size()) {
            Minute start = sm.get(i);
            double p0 = ceLeg ? start.ce() : start.pe();
            if (p0 <= 0 || !inMarketHours(start.epochMin())) { i++; continue; }
            double best = 0;
            int bestJ = i;
            for (int j = i + 1; j < sm.size() && sm.get(j).epochMin() - start.epochMin() <= WINDOW_MIN; j++) {
                double pj = ceLeg ? sm.get(j).ce() : sm.get(j).pe();
                if (pj <= 0) continue;
                double up = (pj - p0) / p0 * 100.0; // UP only — the bot buys premium
                if (up > best) { best = up; bestJ = j; }
            }
            if (best >= PREM_MOVE_PCT) {
                String cause = classify(sm, i, bestJ);
                causeCount.merge(cause, 1, Integer::sum);
                causeCost.merge(cause, best, Double::sum);
                if (!"CAPTURED".equals(cause) && !"ENTRY CUTOFF (policy)".equals(cause)) {
                    moveRows.add(new String[]{
                            idx + " " + (ceLeg ? "CE" : "PE") + " " + strike + " "
                                    + dateHhmm(start.epochMin()) + "–" + hhmm(sm.get(bestJ).epochMin()),
                            String.format(Locale.ROOT, "%.1f%%", best), cause});
                }
                i = bestJ + 1;
            } else {
                i++;
            }
        }
    }

    private static String fmtPct(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v);
    }

    private static void appendCauseSummary(StringBuilder h, Map<String, Integer> causeCount, Map<String, Double> causeCost) {
        h.append("<table class='miss-sum'><thead><tr><th>Cause</th><th>moves</th><th>Σ move %</th><th>fix</th>"
                + "</tr></thead><tbody>");
        if (causeCount.isEmpty()) {
            h.append("<tr><td colspan='4'><em>No qualifying moves detected in the window.</em></td></tr>");
        }
        for (var e : causeCount.entrySet()) {
            h.append("<tr><td>").append(escape(e.getKey())).append("</td><td>").append(e.getValue())
                    .append("</td><td>").append(String.format(Locale.ROOT, "%.1f%%", causeCost.getOrDefault(e.getKey(), 0.0)))
                    .append("</td><td>").append(fixFor(e.getKey())).append("</td></tr>");
        }
        h.append("</tbody></table>");
    }

    private static void appendMoveList(StringBuilder h, String heading, List<String[]> moveRows) {
        if (moveRows.isEmpty()) {
            return;
        }
        h.append("<h5>").append(escape(heading)).append("</h5>");
        h.append("<table class='miss-list'><thead><tr><th>Window (IST)</th><th>move</th><th>cause</th></tr></thead><tbody>");
        moveRows.stream()
                .sorted((a, b) -> Double.compare(parsePct(b[1]), parsePct(a[1])))
                .limit(15)
                .forEach(m -> h.append("<tr><td>").append(m[0]).append("</td><td>").append(m[1])
                        .append("</td><td>").append(escape(m[2])).append("</td></tr>"));
        h.append("</tbody></table>");
    }

    /**
     * True when this index's evals are dense enough (≈per-minute or faster) that a {@link #BLIND_GAP_MIN}
     * gap is genuinely anomalous — i.e. eval-minutes cover at least half the active span. OI Momentum
     * (1 Hz) sits near 1.0; candle/episode-cadence strategies sit well below 0.5, so their normal
     * multi-minute spacing is not mislabeled "blind". Needs ≥ ~a session of minutes to judge.
     */
    private static boolean isDense(List<Minute> mins) {
        if (mins.size() < 10) {
            return false;
        }
        long span = mins.get(mins.size() - 1).epochMin - mins.getFirst().epochMin + 1;
        return span > 0 && (double) mins.size() / span >= 0.5;
    }

    private String classify(List<Minute> mins, int from, int to) {
        long evals = 0, stale = 0, cutoff = 0, noMom = 0, signals = 0, skipped = 0, blocked = 0;
        for (int k = from; k <= to; k++) {
            Minute m = mins.get(k);
            evals += m.evals; stale += m.stale; cutoff += m.cutoff; noMom += m.noMom;
            signals += m.signals; skipped += m.skipped; blocked += m.blocked;
        }
        if (signals > 0) return "CAPTURED";
        if (evals == 0) return "BLIND (feed/auth dead)";
        double staleFrac = (double) stale / Math.max(1, evals);
        if (staleFrac >= 0.5) return "DATA STALE";
        // OI-momentum-specific policy blocker (entry_cutoff) — 0 for every other strategy, so no-op there.
        long gated = Math.max(0, evals - stale - cutoff - noMom);
        if (cutoff > gated && cutoff > noMom) return "ENTRY CUTOFF (policy)";
        // GENERIC (all strategies) — distinguish an ACTIVE gate rejection from a coverage gap by the eval
        // OUTCOME mix rather than OI-only blocker literals (which made non-OI misses ALWAYS read BLOCKED BY
        // GATE): a burst of distinct BLOCKED episodes = a gate actively rejecting (tune it, see the
        // opportunity-cost table); a burst of SKIPPED (episode-deduped, nothing new) or OI's
        // no_momentum_detected = the detector produced no candidate = a coverage/sensitivity gap. (2026-07-02)
        if (blocked > skipped && blocked >= noMom) return "BLOCKED BY GATE";
        return "NO SIGNAL (coverage gap)";
    }

    private static String fixFor(String cause) {
        return switch (cause) {
            case "BLIND (feed/auth dead)" -> "auth alarm + pre-open token check (6/25 P0)";
            case "DATA STALE" -> "never-trade-on-stale + REST fallback";
            case "NO SIGNAL (coverage gap)" -> "detector sensitivity / OI-velocity early detector";
            case "BLOCKED BY GATE" -> "tune the gate — see blocker opportunity-cost";
            case "ENTRY CUTOFF (policy)" -> "policy choice (extend cutoff only if intended)";
            case "CAPTURED" -> "✅ taken";
            default -> "—";
        };
    }

    private static String hhmm(long epochMinutes) {
        return HHMM.format(Instant.ofEpochSecond(epochMinutes * 60));
    }

    private static final java.time.LocalTime MKT_OPEN = java.time.LocalTime.of(9, 15);
    private static final java.time.LocalTime MKT_CLOSE = java.time.LocalTime.of(15, 30);
    private static final DateTimeFormatter DATE_HHMM = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(IST);

    private static String dateHhmm(long epochMinutes) {
        return DATE_HHMM.format(Instant.ofEpochSecond(epochMinutes * 60));
    }

    /** True if the eval-minute falls within the 09:15–15:30 IST session (so we don't detect post-close moves;
     *  the strategy loop keeps evaluating past close). */
    private static boolean inMarketHours(long epochMin) {
        var t = Instant.ofEpochSecond(epochMin * 60).atZone(IST).toLocalTime();
        return !t.isBefore(MKT_OPEN) && !t.isAfter(MKT_CLOSE);
    }

    /** True only if the gap between two eval-minutes lies WITHIN one trading session (same IST date and both
     *  inside market hours) — so overnight/weekend/inter-day gaps aren't mistaken for feed blindness. */
    private static boolean isIntradayGap(long prevEpochMin, long currEpochMin) {
        var p = Instant.ofEpochSecond(prevEpochMin * 60).atZone(IST);
        var c = Instant.ofEpochSecond(currEpochMin * 60).atZone(IST);
        return p.toLocalDate().equals(c.toLocalDate())
                && !p.toLocalTime().isBefore(MKT_OPEN) && !c.toLocalTime().isAfter(MKT_CLOSE);
    }

    private static double parsePct(String s) {
        try { return Double.parseDouble(s.replace("%", "")); } catch (Exception e) { return 0; }
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
