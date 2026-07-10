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
 * Top-of-report <b>capture-health</b> panel — the report showing its own data quality, so numbers below
 * are read with the right trust. Per strategy it reports:
 * <ul>
 *   <li><b>eval-minute coverage</b> — distinct market-minutes that had ≥1 evaluation ÷ expected session
 *       minutes; a low value on a DENSE strategy (OI-momentum ≈ 1 Hz) means the bot was blind for part of
 *       the day (restart gaps, feed/auth outages). Naturally low for candle/episode-cadence strategies.</li>
 *   <li><b>reject checkpoints</b> + <b>degraded %</b> — share of reject forward-checkpoints whose forward
 *       path was reconstructed from a coarse/stale snapshot window (rangePoints &lt; 20, maxGapSec &gt; 180,
 *       or anchorDeltaSec &gt; 60 — the provenance stamped by ForwardCheckpointService). High on
 *       restart-heavy days; those rows understate MFE/MAE, so the opportunity-cost table is softer there.</li>
 * </ul>
 * Every cell is individually fail-safe.
 */
public final class CaptureHealthSection {

    /** Regular NSE/BSE session minutes (09:15–15:30 IST = 6h15m). Coverage denominator per eval-day. */
    private static final int SESSION_MINUTES = 375;

    private CaptureHealthSection() {
    }

    public static AnalyzerSection section(TuningEventQuery query, List<StrategyType> withData) {
        StringBuilder h = new StringBuilder(1536);
        h.append("<p>Data-quality self-check for this window — read the numbers below in this light. "
                + "<strong>coverage</strong> = share of session minutes with at least one evaluation (low on a "
                + "dense strategy = the bot was blind for part of the day: restarts, feed/auth gaps). "
                + "<strong>degraded %</strong> = reject forward-checkpoints reconstructed from a coarse/stale "
                + "snapshot window (understate MFE/MAE — common after mid-session restarts).</p>");
        h.append("<table class='capture-health'><thead><tr>"
                + "<th>Strategy</th><th>eval-minutes</th><th>days</th><th>coverage</th>"
                + "<th>spot %</th><th>premium %</th>"
                + "<th>reject checkpoints</th><th>degraded %</th><th>read</th>"
                + "</tr></thead><tbody>");

        for (StrategyType s : withData) {
            long evalMinutes = 0, evalDays = 0, fcRejects = 0;
            double degradedPct = 0;
            // Input coverage (spot/premium present) straight from the pipeline's own validation flags —
            // this is the fail-loud signal: a premium-dependent section (missed-opportunity option moves)
            // must NOT be trusted when premium % is near 0 (NO_LTP). -1 = flags not captured for this strategy.
            double spotCov = -1, premCov = -1;
            if (EventScan.hasData(query, s, TuningEventType.EVALUATION)) {
                List<Map<String, Object>> r = safeQuery(query,
                        "SELECT COUNT(DISTINCT date_trunc('minute', TRY_CAST(eventTime AS TIMESTAMP))) AS eval_minutes, "
                                + "COUNT(DISTINCT CAST(TRY_CAST(eventTime AS TIMESTAMP) AS DATE)) AS eval_days, "
                                + "COUNT(*) AS n, "
                                + "SUM(CASE WHEN json_extract_string(attr_extra,'$.spotAvailable')='true' THEN 1 ELSE 0 END) AS spot_ok, "
                                + "SUM(CASE WHEN json_extract_string(attr_extra,'$.premiumAvailable')='true' THEN 1 ELSE 0 END) AS prem_ok, "
                                + "SUM(CASE WHEN json_extract_string(attr_extra,'$.premiumAvailable') IS NOT NULL THEN 1 ELSE 0 END) AS has_flags "
                                + "FROM " + EventScan.source(query, s, TuningEventType.EVALUATION));
                if (!r.isEmpty()) {
                    evalMinutes = asLong(r.getFirst().get("eval_minutes"));
                    evalDays = asLong(r.getFirst().get("eval_days"));
                    long n = asLong(r.getFirst().get("n"));
                    long hasFlags = asLong(r.getFirst().get("has_flags"));
                    if (hasFlags > 0 && n > 0) {
                        spotCov = 100.0 * asLong(r.getFirst().get("spot_ok")) / n;
                        premCov = 100.0 * asLong(r.getFirst().get("prem_ok")) / n;
                    }
                }
            }
            if (EventScan.hasData(query, s, TuningEventType.FORWARD_CHECKPOINT)) {
                List<Map<String, Object>> r = safeQuery(query,
                        "SELECT COUNT(*) AS n, ROUND(100.0*AVG(CASE WHEN "
                                + "  TRY_CAST(json_extract_string(attr_extra,'$.rangePoints') AS INT) < 20 "
                                + "  OR TRY_CAST(json_extract_string(attr_extra,'$.maxGapSec') AS INT) > 180 "
                                + "  OR TRY_CAST(json_extract_string(attr_extra,'$.anchorDeltaSec') AS INT) > 60 "
                                + "  THEN 1 ELSE 0 END),1) AS degraded_pct "
                                + "FROM " + EventScan.source(query, s, TuningEventType.FORWARD_CHECKPOINT)
                                + " WHERE json_extract_string(attr_extra,'$.source') = 'reject'");
                if (!r.isEmpty()) {
                    fcRejects = asLong(r.getFirst().get("n"));
                    degradedPct = asDouble(r.getFirst().get("degraded_pct"));
                }
            }
            double coverage = evalDays > 0
                    ? Math.min(100.0, 100.0 * evalMinutes / (evalDays * (double) SESSION_MINUTES)) : 0.0;
            boolean dense = evalDays > 0 && (evalMinutes / (double) evalDays) > 200; // ≈per-minute-or-faster
            h.append("<tr>")
                    .append(td(s.displayName()))
                    .append(td(fmt(evalMinutes)))
                    .append(td(fmt(evalDays)))
                    .append(td(evalDays > 0 ? pct1(coverage) : "—"))
                    .append(td(spotCov >= 0 ? pct1(spotCov) : "—"))
                    .append(td(premCov >= 0 ? pct1(premCov) : "—"))
                    .append(td(fmt(fcRejects)))
                    .append(td(fcRejects > 0 ? pct1(degradedPct) : "—"))
                    .append(td(read(dense, coverage, fcRejects, degradedPct, premCov)))
                    .append("</tr>");
        }
        h.append("</tbody></table>");
        h.append("<p class='capture-health-note'><em>Coverage is only a blindness signal for DENSE "
                + "(≈per-second) strategies; candle/episode-cadence strategies are expected to sit low. "
                + "The degraded-% and provenance stamps (rangePoints/maxGapSec/anchorDeltaSec) exist only for "
                + "reject checkpoints written from 2026-07-02 onward.</em></p>");
        return AnalyzerSection.htmlOnly("Capture health (trust this report's numbers?)", h.toString());
    }

    private static String read(boolean dense, double coverage, long fcRejects, double degradedPct, double premCov) {
        // Fail loud: premium-dependent sections (missed-opportunity OPTION moves, give-back) are untrustworthy
        // when the ATM premium isn't being captured. premCov>=0 means the flags exist for this strategy.
        if (premCov >= 0 && premCov < 50.0) {
            return "🔴 premium capture " + pct1(premCov) + " (NO_LTP) — option-move & give-back sections SUPPRESSED";
        }
        if (dense && coverage < 80.0) {
            return "🔴 blind ~" + String.format(Locale.ROOT, "%.0f%%", 100.0 - coverage)
                    + " of the session — restart/feed gaps";
        }
        if (fcRejects >= 10 && degradedPct >= 30.0) {
            return "🟠 " + pct1(degradedPct) + " of reject checkpoints degraded — soften opportunity-cost here";
        }
        if (dense) {
            return "🟢 dense coverage";
        }
        return "⚪ low-cadence (coverage n/a)";
    }

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

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private static double asDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }
}
