package com.algo.trade.tuning.analyzer.core;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Top-of-report <b>per-strategy scorecard</b> — one row per strategy so the whole book can be
 * read at a glance and each strategy tuned from there. Answers, per strategy: how much did it
 * evaluate, how often did it actually fire / fill / exit, what did those exits make, which gate
 * blocked it most, and — crucially — is that dominant gate <em>throwing away winners</em>
 * (from the reject forward-checkpoints). The last column is a plain-English verdict:
 * <em>firing</em> vs <em>dormant (config-strangled)</em> vs <em>legitimately quiet</em>.
 *
 * <p>All queries are per-strategy and individually fail-safe: a bad query for one strategy
 * degrades that cell to "—", never the whole section.</p>
 */
public final class CrossStrategyScorecard {

    /** A 30-min forward MFE at/above this (%) means a long option would likely have profited. */
    private static final double PROFIT_MFE_PCT = 0.30;
    /** Don't call a gate "over-blocking" off a handful of sampled rejects. */
    private static final int MIN_OPP_SAMPLE = 10;
    /** Don't show a win%/avg-P&L off a handful of exits — a single 100% win reads as a real edge otherwise. */
    private static final int MIN_EXITS_FOR_STATS = 3;

    private CrossStrategyScorecard() {
    }

    public static AnalyzerSection section(TuningEventQuery query, List<StrategyType> withData) {
        StringBuilder h = new StringBuilder(2048);
        h.append("<p>One row per strategy that captured events in this window. <strong>fired</strong> = "
                + "signals that passed every gate; <strong>filled</strong> = orders actually filled; "
                + "<strong>exits</strong>/<strong>win%</strong>/<strong>avg P&L%</strong> = realized outcomes. "
                + "<strong>top blocker</strong> is the most frequent reject reason; <strong>gate throwing "
                + "winners?</strong> is the reject reason whose blocked setups most often went on to a "
                + "profitable 30-min move (from the reject forward-checkpoints) — that's the one to loosen "
                + "first. Read the <strong>verdict</strong> to know whether a strategy is genuinely quiet or "
                + "config-strangled.</p>");
        h.append("<table class='scorecard'><thead><tr>"
                + "<th>Strategy</th><th>evals</th><th>fired</th><th>filled</th><th>exits</th>"
                + "<th>win%</th><th>avg P&L%</th><th>top blocker</th>"
                + "<th>gate throwing winners? (would-profit%, n)</th><th>verdict</th>"
                + "</tr></thead><tbody>");

        for (StrategyType s : withData) {
            Row r = buildRow(query, s);
            h.append("<tr>")
                    .append(td(s.displayName()))
                    .append(td(fmt(r.evals)))
                    .append(td(fmt(r.fired)))
                    .append(td(fmt(r.filled)))
                    .append(td(fmt(r.exits)))
                    .append(td(r.exits >= MIN_EXITS_FOR_STATS ? pct1(r.winPct)
                            : r.exits > 0 ? "n=" + r.exits : "—"))
                    .append(td(r.exits >= MIN_EXITS_FOR_STATS ? signedPct(r.avgPnlPct)
                            : r.exits > 0 ? "n=" + r.exits : "—"))
                    .append(td(r.topBlocker == null ? "—" : escape(r.topBlocker)))
                    .append(td(r.oppGate == null ? "—"
                            : escape(r.oppGate) + " (" + pct1(r.oppWouldProfit) + ", n=" + r.oppSample + ")"))
                    .append(td(verdictHtml(r)))
                    .append("</tr>");
        }
        h.append("</tbody></table>");
        h.append("<p class='scorecard-note'><em>The reject-checkpoint columns fill in over a session or two "
                + "once <code>tuning.forward.checkpoint-rejects-enabled</code> has swept each strategy's "
                + "evaluations; until then \"gate throwing winners?\" reads \"—\". <strong>filled</strong> is "
                + "only trustworthy from 2026-07-02 onward — the watchdog ORDER_FILLED emit was added then, so "
                + "any window including earlier dates undercounts fills (they were never written).</em></p>");
        return AnalyzerSection.htmlOnly("Per-strategy scorecard (tune each strategy from here)", h.toString());
    }

    private record Row(StrategyType strategy, long evals, long fired, long blocked, long skipped,
                       long filled, long exits, double winPct, double avgPnlPct,
                       String topBlocker, String oppGate, double oppWouldProfit, long oppSample,
                       boolean hasExec) {
    }

    private static Row buildRow(TuningEventQuery query, StrategyType s) {
        long evals = 0, fired = 0, blocked = 0, skipped = 0, filled = 0, exits = 0;
        double winPct = 0, avgPnl = 0;
        String topBlocker = null, oppGate = null;
        double oppWouldProfit = 0;
        long oppSample = 0;

        // Eval funnel: FIRED / BLOCKED / SKIPPED episode counts.
        if (EventScan.hasData(query, s, TuningEventType.EVALUATION)) {
            String src = EventScan.source(query, s, TuningEventType.EVALUATION);
            for (Map<String, Object> row : safeQuery(query,
                    "SELECT outcome, COUNT(*) AS n FROM " + src + " GROUP BY outcome")) {
                long n = asLong(row.get("n"));
                evals += n;
                switch (str(row.get("outcome"))) {
                    case "FIRED" -> fired += n;
                    case "BLOCKED" -> blocked += n;
                    case "SKIPPED" -> skipped += n;
                    default -> { /* unknown outcome — counted in evals only */ }
                }
            }
            // Top blocker by episode count. Include SKIPPED as well as BLOCKED: some strategies (the
            // spreads) gate via a SKIPPED outcome (e.g. ivRankTooHigh), which carries its reason in
            // firstFailedFilter rather than the blocker column — without this they'd show "—".
            // Normalize the firstFailedFilter fallback: strip a 'spreadEntryBlocked:'-style prefix and any
            // parenthesized params/timers so the strangle variants insufficientTrendCandlesForSqueeze(n=8)/
            // (n=7)/(n=6) collapse into ONE named group instead of fragmenting. (2026-07-02)
            List<Map<String, Object>> tb = safeQuery(query,
                    "SELECT COALESCE(NULLIF(blocker,''), "
                            + "  regexp_replace(regexp_replace(json_extract_string(attr_extra,'$.firstFailedFilter'), "
                            + "    '^[A-Za-z]*Blocked:', ''), '\\(.*$', ''), "
                            + "  '(skipped)') AS blocker, SUM(episodeTickCount) AS ticks FROM " + src
                            + " WHERE outcome IN ('BLOCKED','SKIPPED') GROUP BY 1 ORDER BY ticks DESC LIMIT 1");
            if (!tb.isEmpty()) {
                topBlocker = str(tb.getFirst().get("blocker"));
            }
        }

        // Fired signals may also come from the SIGNAL stream (fills reference these). Prefer the eval FIRED
        // count above; fall back to SIGNAL rows if evals didn't capture fires (LOW-cadence strategies).
        if (fired == 0 && EventScan.hasData(query, s, TuningEventType.SIGNAL)) {
            List<Map<String, Object>> sig = safeQuery(query,
                    "SELECT COUNT(*) AS n FROM " + EventScan.source(query, s, TuningEventType.SIGNAL));
            if (!sig.isEmpty()) {
                fired = asLong(sig.getFirst().get("n"));
            }
        }

        // Fills — ORDER_FILLED execution rows. Spread strategies execute via SpreadOrderExecutor and emit
        // NO execution stream, so filled is structurally 0 for them — track hasExec so the verdict doesn't
        // wrongly say "fires but never fills" for a strategy that simply doesn't capture fills. (#132)
        boolean hasExec = EventScan.hasData(query, s, TuningEventType.EXECUTION);
        if (hasExec) {
            List<Map<String, Object>> f = safeQuery(query,
                    "SELECT COUNT(*) AS n FROM " + EventScan.source(query, s, TuningEventType.EXECUTION)
                            + " WHERE stage = 'ORDER_FILLED'");
            if (!f.isEmpty()) {
                filled = asLong(f.getFirst().get("n"));
            }
        }

        // Realized exits — collapse per-tick exit rows to one terminal row per position.
        if (EventScan.hasData(query, s, TuningEventType.EXIT)) {
            String exitSrc = EventScan.source(query, s, TuningEventType.EXIT);
            List<Map<String, Object>> ex = safeQuery(query,
                    "WITH x AS (SELECT * FROM " + exitSrc + " "
                            + "QUALIFY ROW_NUMBER() OVER (PARTITION BY correlationKey ORDER BY eventTime DESC) = 1) "
                            + "SELECT COUNT(*) AS n, "
                            + "ROUND(AVG(TRY_CAST(realizedPnlPct AS DOUBLE)),2) AS avg_pnl, "
                            + "ROUND(100.0*AVG(CASE WHEN TRY_CAST(realizedPnlPct AS DOUBLE) > 0 THEN 1 ELSE 0 END),1) AS win_pct "
                            + "FROM x");
            if (!ex.isEmpty()) {
                exits = asLong(ex.getFirst().get("n"));
                avgPnl = asDouble(ex.getFirst().get("avg_pnl"));
                winPct = asDouble(ex.getFirst().get("win_pct"));
            }
        }

        // Opportunity cost — the reject reason whose blocked setups most often went on to profit.
        if (EventScan.hasData(query, s, TuningEventType.FORWARD_CHECKPOINT)) {
            List<Map<String, Object>> opp = safeQuery(query,
                    "SELECT json_extract_string(attr_extra,'$.blocker') AS blocker, COUNT(*) AS n, "
                            + "ROUND(100.0*AVG(CASE WHEN GREATEST(TRY_CAST(fwdMfe30mPct AS DOUBLE), "
                            + "-1.0*TRY_CAST(fwdMae30mPct AS DOUBLE)) >= " + PROFIT_MFE_PCT
                            + " THEN 1 ELSE 0 END),1) AS would_profit "
                            + "FROM " + EventScan.source(query, s, TuningEventType.FORWARD_CHECKPOINT) + " "
                            + "WHERE json_extract_string(attr_extra,'$.source') = 'reject' AND fwdMfe30mPct IS NOT NULL "
                            + "GROUP BY 1 HAVING COUNT(*) >= " + MIN_OPP_SAMPLE + " "
                            + "ORDER BY would_profit DESC, n DESC LIMIT 1");
            if (!opp.isEmpty()) {
                oppGate = str(opp.getFirst().get("blocker"));
                oppWouldProfit = asDouble(opp.getFirst().get("would_profit"));
                oppSample = asLong(opp.getFirst().get("n"));
            }
        }

        return new Row(s, evals, fired, blocked, skipped, filled, exits, winPct, avgPnl,
                topBlocker, oppGate, oppWouldProfit, oppSample, hasExec);
    }

    private static String verdictHtml(Row r) {
        // A flagged over-blocking gate is the loudest signal — surface it first regardless of firing.
        if (r.oppGate != null && r.oppWouldProfit >= 30.0) {
            return "🔴 over-blocking <code>" + escape(r.oppGate) + "</code> — loosen it";
        }
        if (r.exits >= MIN_EXITS_FOR_STATS) {
            String tone = r.avgPnlPct > 0 ? "🟢" : "🔴";
            return tone + " trading — " + r.exits + " exits, " + pct1(r.winPct) + " win, " + signedPct(r.avgPnlPct) + " avg";
        }
        if (r.exits > 0) {
            return "🟢 trading — " + r.exits + " exit(s) (sample too small for win%/avg)";
        }
        if (r.fired > 0 && r.filled == 0) {
            if (!r.hasExec) {
                // No execution stream at all (e.g. the spread strategies execute via SpreadOrderExecutor) —
                // we simply can't see fills for this strategy, so don't imply an execution problem.
                return "⚪ fired — fills not captured for this strategy (see exits for outcomes)";
            }
            return "🟠 fires but never fills — check execution gates / premium range";
        }
        if (r.fired == 0 && r.evals > 0) {
            String blk = r.topBlocker == null ? "gates" : "<code>" + escape(r.topBlocker) + "</code>";
            String read = r.oppWouldProfit >= 15.0 ? " (review " + blk + ")" : "";
            return "⚪ dormant — 0 fires, mostly " + blk + read;
        }
        return "—";
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> safeQuery(TuningEventQuery query, String sql) {
        try {
            return query.store().query(sql);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private static String td(String inner) {
        return "<td>" + inner + "</td>";
    }

    private static String fmt(long v) {
        return String.format(Locale.ROOT, "%,d", v);
    }

    private static String pct1(double v) {
        return String.format(Locale.ROOT, "%.1f%%", v);
    }

    private static String signedPct(double v) {
        return String.format(Locale.ROOT, "%+.2f%%", v);
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
