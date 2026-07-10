package com.algo.trade.tuning.analyzer;

import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;

/**
 * §3b — the canonical <b>decision record</b>: the per-decision join that downstream analysis and offline
 * ML read instead of re-joining event streams ad hoc.
 *
 * <p>The captured data has <b>two grains</b> (verified against real data — eval and signal correlationKeys
 * do not overlap), so there are two canonical records rather than one:
 * <ul>
 *   <li>{@link #evalRecordSql} — one row per <b>evaluation</b> (the "what we considered / mostly rejected"
 *       grain, ~18k/day), joined to its <b>reject</b> forward-checkpoint by the {@code EVAL-…} key. This is
 *       the grain the blocker opportunity-cost analysis lives on.</li>
 *   <li>{@link #tradeRecordSql} — one row per <b>signal/trade</b> (~50/day), joined to its execution, exit,
 *       and <b>signal</b> forward-checkpoint by the trade key. The "what we traded and how it turned out"
 *       grain.</li>
 * </ul>
 *
 * <p>Both build their FROM clauses through {@link EventScan} so they span the recent CSVs and the rolled
 * Parquet archive transparently. Each returns a complete {@code SELECT} usable as a subquery or for COPY/export.
 *
 * <p><b>Known data-quality caveat (surfaced by building this):</b> exit-event correlationKeys only partially
 * match signal keys (~6/11 on 06-22) because some close paths key the exit by {@code tradeId} rather than the
 * entry/signal key. So {@code tradeRecordSql}'s exit columns are LEFT-joined and may be null even for a real
 * exit until the exit-key is unified (a P1.3 follow-up).
 */
public final class DecisionRecord {

    private DecisionRecord() {}

    /** Canonical evaluation-grain record: eval + (sampled) reject forward outcome. */
    public static String evalRecordSql(TuningEventQuery q) {
        String eval = EventScan.source(q, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION);
        String fwd = EventScan.source(q, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT);
        return ""
                + "WITH ev AS ("
                + "  SELECT correlationKey AS k, eventTime, index, outcome, blocker, "
                + "    json_extract_string(attr_extra,'$.dataQuality') AS dataQuality, "
                + "    json_extract_string(attr_extra,'$.feedFresh')    AS feedFresh, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.spot') AS DOUBLE)            AS spot, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.vix') AS DOUBLE)             AS vix, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.rangePct30m') AS DOUBLE)     AS rangePct30m, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.biasScore') AS DOUBLE)       AS biasScore, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.operatorScore') AS DOUBLE)   AS operatorScore, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.pcr') AS DOUBLE)             AS pcr, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.sustainedDriftPct') AS DOUBLE) AS sustainedDriftPct "
                + "  FROM " + eval + "), "
                + "rfwd AS ("
                + "  SELECT correlationKey AS k, "
                + "    TRY_CAST(fwdMfe30mPct AS DOUBLE) AS rejFwdMfe30m, "
                + "    TRY_CAST(fwdMae30mPct AS DOUBLE) AS rejFwdMae30m "
                + "  FROM " + fwd + " WHERE json_extract_string(attr_extra,'$.source') = 'reject') "
                + "SELECT ev.*, rfwd.rejFwdMfe30m, rfwd.rejFwdMae30m "
                + "FROM ev LEFT JOIN rfwd USING (k)";
    }

    /** Canonical trade-grain record: signal + execution + exit + signal forward outcome. */
    public static String tradeRecordSql(TuningEventQuery q) {
        String sig = EventScan.source(q, StrategyType.OI_MOMENTUM, TuningEventType.SIGNAL);
        String exe = EventScan.source(q, StrategyType.OI_MOMENTUM, TuningEventType.EXECUTION);
        String exit = EventScan.source(q, StrategyType.OI_MOMENTUM, TuningEventType.EXIT);
        String fwd = EventScan.source(q, StrategyType.OI_MOMENTUM, TuningEventType.FORWARD_CHECKPOINT);
        return ""
                + "WITH sig AS ("
                + "  SELECT correlationKey AS k, eventTime, index, "
                + "    json_extract_string(attr_extra,'$.entryCase')   AS entryCase, "
                + "    json_extract_string(attr_extra,'$.matrixCase')  AS matrixCase, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.operatorScore') AS DOUBLE) AS operatorScore, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.biasScore') AS DOUBLE)     AS biasScore, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.vix') AS DOUBLE)           AS vix, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.rangePct30m') AS DOUBLE)   AS rangePct30m, "
                + "    TRY_CAST(json_extract_string(attr_extra,'$.sustainedDriftPct') AS DOUBLE) AS sustainedDriftPct "
                + "  FROM " + sig + "), "
                // "executed" = actually FILLED, not merely placed/rejected. The execution stream carries a row
                // per stage (ORDER_OPEN placeholder, RISK_REJECTED, ORDER_GUARD_REJECTED, …), so counting any
                // execution row massively overstated "executed" (e.g. 221 vs ~40 real fills) and made
                // exits-vs-executed look permanently broken. Filter to the fill stage on BOTH paths
                // (sync immediate-fill + async watchdog both emit ORDER_FILLED).
                + "exe AS (SELECT DISTINCT correlationKey AS k, 1 AS executed FROM " + exe
                + "        WHERE stage = 'ORDER_FILLED'), "
                + "ex AS ("
                + "  SELECT correlationKey AS k, exitReason, "
                + "    TRY_CAST(realizedPnlPct AS DOUBLE) AS realizedPnlPct, "
                + "    TRY_CAST(mfePct AS DOUBLE) AS mfePct, TRY_CAST(maePct AS DOUBLE) AS maePct "
                + "  FROM " + exit + "), "
                + "sfwd AS ("
                + "  SELECT correlationKey AS k, "
                + "    TRY_CAST(fwdMfe30mPct AS DOUBLE) AS fwdMfe30m, TRY_CAST(fwdMae30mPct AS DOUBLE) AS fwdMae30m "
                + "  FROM " + fwd + " WHERE json_extract_string(attr_extra,'$.source') IS NULL) "
                + "SELECT sig.*, COALESCE(exe.executed,0) AS executed, "
                + "  ex.exitReason, ex.realizedPnlPct, ex.mfePct, ex.maePct, "
                + "  sfwd.fwdMfe30m, sfwd.fwdMae30m "
                + "FROM sig "
                + "LEFT JOIN exe USING (k) LEFT JOIN ex USING (k) LEFT JOIN sfwd USING (k)";
    }
}
