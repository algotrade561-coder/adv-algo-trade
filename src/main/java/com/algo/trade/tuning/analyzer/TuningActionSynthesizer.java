package com.algo.trade.tuning.analyzer;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * P2 / §3d + §10c — turns the analyzer sections into a ranked, machine-readable list of <b>tuning actions</b>
 * (the {@code actions.json} that accompanies the HTML report). Each action carries the evidence (n, the
 * metric) and a concrete suggestion, so a superuser can review-and-apply rather than eyeball prose.
 *
 * <p>Reads the structured {@code data} maps the analyzers expose (no re-querying): blocker opportunity-cost
 * → loosen-gate actions; exit attribution → tighten-exit actions; missed-opportunity → cause-driven actions.
 * Per §10b, sample-size thresholds gate every suggestion so nothing is recommended off noise. Best-effort:
 * any shape it doesn't recognise is skipped.
 */
@Component
public class TuningActionSynthesizer {

    private static final Logger log = LoggerFactory.getLogger(TuningActionSynthesizer.class);
    private final ObjectMapper json = new ObjectMapper();

    // §10b sample-size guards.
    private static final int MIN_REJECT_N = 10;
    private static final int MIN_EXIT_N = 5;
    private static final double WOULD_PROFIT_FLAG = 30.0; // %
    private static final double GIVEBACK_FLAG = 5.0;      // %

    public String toJson(TuningReport report) {
        List<Map<String, Object>> actions = synthesize(report);
        try {
            return json.writerWithDefaultPrettyPrinter().writeValueAsString(Map.of(
                    "window", report.fromDate() + " → " + report.toDate(),
                    "actionCount", actions.size(),
                    "actions", actions));
        } catch (Exception ex) {
            log.warn("[TuningActionSynthesizer] serialise failed: {}", ex.getMessage());
            return "{\"actions\":[]}";
        }
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> synthesize(TuningReport report) {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (report == null) {
            return actions;
        }
        for (AnalyzerSection s : report.sections()) {
            if (s == null || s.data() == null) {
                continue;
            }
            String title = s.title() == null ? "" : s.title();
            // The coordinator prefixes each section title with "<Strategy display name> — <section>";
            // recover the strategy so every action is attributed (#144). Empty when there's no prefix.
            String strategyName = title.contains(" — ") ? title.substring(0, title.indexOf(" — ")).trim() : "";
            Object rowsObj = s.data().get("rows");

            // NOTE: the coordinator prefixes every section title with the strategy display name
            // ("OI Momentum — …"), so match with contains(), not startsWith(), or no action ever fires.
            // Blocker opportunity-cost → loosen-gate
            if (title.contains("Blocker opportunity-cost") && rowsObj instanceof List<?> rows) {
                for (Object ro : rows) {
                    if (!(ro instanceof Map<?, ?> r)) continue;
                    long n = asLong(((Map<String, Object>) r).get("n"));
                    double wp = asDouble(((Map<String, Object>) r).get("pct_would_profit"));
                    String blocker = str(((Map<String, Object>) r).get("blocker"));
                    if (n >= MIN_REJECT_N && wp >= WOULD_PROFIT_FLAG) {
                        actions.add(action(strategyName, "loosen_gate", blocker, priorityFor(wp, n),
                                String.format("%.0f%% of %d sampled rejects had a ≥0.30%% forward move", wp, n),
                                "Loosen / recalibrate '" + blocker + "' — it is rejecting setups that moved."));
                    }
                }
            }

            // Exit attribution → tighten exits
            if (title.contains("Exit attribution") && rowsObj instanceof List<?> rows) {
                for (Object ro : rows) {
                    if (!(ro instanceof Map<?, ?> r)) continue;
                    long n = asLong(((Map<String, Object>) r).get("n"));
                    double giveback = asDouble(((Map<String, Object>) r).get("avg_giveback"));
                    double avgPnl = asDouble(((Map<String, Object>) r).get("avg_pnl"));
                    String reason = str(((Map<String, Object>) r).get("exitReason"));
                    if (n >= MIN_EXIT_N && giveback >= GIVEBACK_FLAG) {
                        actions.add(action(strategyName, "tighten_exit", reason, 2,
                                String.format("avg give-back %.2f%% over %d exits (avg realized %.2f%%)", giveback, n, avgPnl),
                                "Trail tighter / book sooner on '" + reason + "' — it hands back peak profit."));
                    }
                }
            }

            // Missed-opportunity → cause-driven
            if (title.contains("Missed-opportunity")) {
                Object causes = s.data().get("causes");
                if (causes instanceof Map<?, ?> cm) {
                    for (var e : ((Map<String, Object>) cm).entrySet()) {
                        int count = (int) asLong(e.getValue());
                        String cause = e.getKey();
                        if (count <= 0 || "CAPTURED".equals(cause) || cause.startsWith("ENTRY CUTOFF")) continue;
                        actions.add(action(strategyName, "fix_miss_cause", cause, priorityForCause(cause),
                                count + " tradeable move(s) missed with this cause",
                                fixForCause(cause)));
                    }
                }
            }
        }
        actions.sort((a, b) -> Integer.compare(asInt(a.get("priority")), asInt(b.get("priority"))));
        return actions;
    }

    private static Map<String, Object> action(String strategy, String type, String target, int priority,
                                              String evidence, String suggest) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("strategy", strategy);   // #144: which strategy this action is for (two strategies can flag the
        a.put("priority", priority);   //       same blocker, e.g. algoFlow:dte — actions were indistinguishable)
        a.put("type", type);
        a.put("target", target);
        a.put("evidence", evidence);
        a.put("suggest", suggest);
        return a;
    }

    private static int priorityFor(double wouldProfit, long n) {
        return (wouldProfit >= 45 && n >= 50) ? 1 : 2;
    }

    private static int priorityForCause(String cause) {
        if (cause.startsWith("BLIND")) return 1;          // infra outage — highest
        if (cause.startsWith("DATA STALE")) return 1;
        return 2;
    }

    private static String fixForCause(String cause) {
        if (cause.startsWith("BLIND")) return "Feed/auth alarm + pre-open token validation (P0).";
        if (cause.startsWith("DATA STALE")) return "Never-trade-on-stale + REST fallback for signals.";
        if (cause.startsWith("NO SIGNAL")) return "Increase detector sensitivity / enable OI-velocity early detector.";
        if (cause.startsWith("BLOCKED")) return "Tune the offending gate (see blocker opportunity-cost).";
        return "Review.";
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private static int asInt(Object o) {
        return (o instanceof Number n) ? n.intValue() : 9;
    }

    private static double asDouble(Object o) {
        return (o instanceof Number n) ? n.doubleValue() : 0.0;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
