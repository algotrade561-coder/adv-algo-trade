package com.algo.trade.tuning.analyzer.plugins;

import com.algo.trade.persistence.ErrorEventEntity;
import com.algo.trade.persistence.ErrorEventRepository;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.tuning.TuningEventType;
import com.algo.trade.tuning.analyzer.AnalyzerSection;
import com.algo.trade.tuning.analyzer.EventScan;
import com.algo.trade.tuning.analyzer.TuningAnalyzerPlugin;
import com.algo.trade.tuning.analyzer.TuningEventQuery;
import com.algo.trade.tuning.store.TuningQueryException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Session-health section — per-trading-day <b>WebSocket churn</b> and <b>in-session feed staleness</b>, so you
 * can watch whether an infra change (e.g. the Sydney → Mumbai region migration) actually reduced connection
 * churn rather than guessing.
 *
 * <p>Two independent measures, both per IST trading day:
 * <ul>
 *   <li><b>WS events</b> — count of {@code WebSocket}-component rows in {@code error_event} (force-reconnects,
 *       failures, session resets). A churn proxy; lower = a steadier feed.</li>
 *   <li><b>Stale minutes</b> — distinct minutes whose OI-Momentum evaluations were dominated by a
 *       {@code data_stale}/{@code not yet live} blocker (counted as <i>minutes</i>, not rows, so a dead-feed
 *       burst doesn't inflate it). Scoped to market hours by {@link EventScan} already.</li>
 * </ul>
 *
 * <p>Fail-safe: if the eval store or the error-event repo is unavailable, the section renders an inline notice
 * rather than breaking the report.</p>
 */
@Component
public class SessionHealthAnalyzerPlugin implements TuningAnalyzerPlugin {

    private static final Logger log = LoggerFactory.getLogger(SessionHealthAnalyzerPlugin.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** Optional — when absent (e.g. unit context) the WS-churn column shows n/a. */
    @Autowired(required = false)
    private ErrorEventRepository errorEventRepository;

    @Override
    public StrategyType strategy() {
        return StrategyType.OI_MOMENTUM;
    }

    /** Render near the top, beside data-health. */
    @Override
    public int order() {
        return -90;
    }

    @Override
    public List<AnalyzerSection> customSections(TuningEventQuery query) {
        if (!query.includesStrategy(StrategyType.OI_MOMENTUM)) {
            return List.of();
        }
        return List.of(section(query));
    }

    AnalyzerSection section(TuningEventQuery query) {
        final String title = "Session health (WS churn & in-session staleness)";
        try {
            Map<String, long[]> byDate = new LinkedHashMap<>(); // date → [captured_min, stale_min, ws_events]

            // 1) Stale + captured minutes per IST day, from the (market-hours-scoped) eval store.
            if (EventScan.hasData(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION)) {
                String src = EventScan.source(query, StrategyType.OI_MOMENTUM, TuningEventType.EVALUATION);
                String sql = ""
                        + "WITH m AS ("
                        + "  SELECT CAST((TRY_CAST(eventTime AS TIMESTAMP) + INTERVAL '5 hours 30 minutes') AS DATE) AS d, "
                        + "    date_trunc('minute', TRY_CAST(eventTime AS TIMESTAMP) + INTERVAL '5 hours 30 minutes') AS mn, "
                        + "    MAX(CASE WHEN lower(COALESCE(blocker,'')) LIKE '%stale%' "
                        + "             OR lower(COALESCE(blocker,'')) LIKE '%not yet live%' THEN 1 ELSE 0 END) AS st "
                        + "  FROM " + src + " GROUP BY 1, 2"
                        + ") SELECT CAST(d AS VARCHAR) AS d, COUNT(*) AS captured_min, SUM(st) AS stale_min "
                        + "FROM m GROUP BY 1 ORDER BY 1";
                for (Map<String, Object> r : query.store().query(sql)) {
                    String d = str(r.get("d"));
                    byDate.computeIfAbsent(d, k -> new long[3]);
                    byDate.get(d)[0] = asLong(r.get("captured_min"));
                    byDate.get(d)[1] = asLong(r.get("stale_min"));
                }
            }

            // 2) WebSocket churn per IST day, from the error_event table.
            if (errorEventRepository != null && query.fromDate() != null && query.toDate() != null) {
                Instant from = query.fromDate().atStartOfDay(IST).toInstant();
                Instant toEnd = query.toDate().plusDays(1).atStartOfDay(IST).toInstant();
                try {
                    List<ErrorEventEntity> events = errorEventRepository
                            .findByComponentAndTimestampAfterOrderByTimestampDesc("WebSocket", from);
                    for (ErrorEventEntity e : events) {
                        Instant ts = e.getTimestamp();
                        if (ts == null || ts.isAfter(toEnd)) {
                            continue;
                        }
                        String d = ts.atZone(IST).toLocalDate().toString();
                        byDate.computeIfAbsent(d, k -> new long[3]);
                        byDate.get(d)[2]++;
                    }
                } catch (Exception ex) {
                    log.debug("[SessionHealth] WS event query failed: {}", ex.getMessage());
                }
            }

            if (byDate.isEmpty()) {
                return AnalyzerSection.htmlOnly(title,
                        "<p><em>No session-health data in this window.</em></p>");
            }
            return render(title, byDate, errorEventRepository != null);
        } catch (TuningQueryException ex) {
            log.warn("[SessionHealth] query failed: {}", ex.getMessage());
            return AnalyzerSection.htmlOnly(title, "<p class='error'>Query failed: " + escape(ex.getMessage()) + "</p>");
        } catch (Exception ex) {
            log.warn("[SessionHealth] section failed: {}", ex.toString());
            return AnalyzerSection.htmlOnly(title, "<p class='error'>Section failed: " + escape(String.valueOf(ex.getMessage())) + "</p>");
        }
    }

    private AnalyzerSection render(String title, Map<String, long[]> byDate, boolean wsAvailable) {
        StringBuilder h = new StringBuilder(512);
        h.append("<p>Per trading day: WebSocket churn (force-reconnects/failures from <code>error_event</code>) "
                + "and in-session stale minutes (market-hours only; counted as distinct minutes, not rows). "
                + "Use this to confirm whether an infra change (e.g. the Mumbai migration) lowered churn — "
                + "lower WS events + near-zero stale minutes on a normal day = a steadier feed.</p>");
        h.append("<table><thead><tr><th>date (IST)</th><th>WS events</th><th>stale min</th>"
                + "<th>captured min</th><th>stale %</th></tr></thead><tbody>");
        for (String d : new TreeSet<>(byDate.keySet())) {
            long[] v = byDate.get(d);
            long captured = v[0], stale = v[1], ws = v[2];
            double pct = captured > 0 ? 100.0 * stale / captured : 0.0;
            h.append("<tr><td>").append(escape(d)).append("</td>")
                    .append("<td>").append(wsAvailable ? Long.toString(ws) : "n/a").append("</td>")
                    .append("<td>").append(stale).append("</td>")
                    .append("<td>").append(captured).append("</td>")
                    .append("<td>").append(String.format(Locale.ROOT, "%.1f%%", pct)).append("</td></tr>");
        }
        h.append("</tbody></table>");
        if (!wsAvailable) {
            h.append("<p class='muted'><em>WS-event counts unavailable (error-event repo not present).</em></p>");
        }
        return AnalyzerSection.htmlOnly(title, h.toString());
    }

    private static long asLong(Object o) {
        return (o instanceof Number n) ? n.longValue() : 0L;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
