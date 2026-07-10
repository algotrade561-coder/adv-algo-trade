package com.algo.trade.persistence;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Bounds H2 growth by purging high-volume telemetry tables on a daily schedule.
 *
 * <h2>What it purges (and why it's safe)</h2>
 * <ul>
 *   <li>{@code STRATEGY_DECISION_ENTITY} — the runaway (~19k rows/day). Live code only ever reads
 *       <em>today</em> or the <em>top-N recent</em> rows (RiskManager top-200, AiAnalysisService
 *       today); the rest is UI/reporting. Purging &gt;N days breaks no calculation — it only limits
 *       how far back the UI can browse. Default 7 days.</li>
 *   <li>{@code GREEKS_SAMPLES} — write-only (repository read methods have no callers) AND redundant:
 *       per-strike greeks are already captured, richer, in the tuning chain snapshots. Default 7 days.</li>
 *   <li>{@code EXIT_EVALUATIONS} — DB rows are post-mortem audit only; live exit logic reads an
 *       in-memory registry, not the table. Default 30 days.</li>
 *   <li>{@code ERROR_EVENT_ENTITY} — error log surfaced in the admin UI. Default 30 days.</li>
 * </ul>
 *
 * <h2>What it deliberately does NOT touch</h2>
 * {@code IV_SAMPLES} feeds the MarketGuard VIX percentile gate ({@link com.algo.trade.indicator.IndiaVixHistory},
 * 5-yr / 252-session window) and IV Rank ({@link com.algo.trade.indicator.IVRankTracker}); it is tiny
 * and must be retained. {@code TRADE_ENTITY}, {@code ORDER_ENTITY}, all config and summary tables are
 * P&amp;L / audit / configuration and are never purged here.
 *
 * <h2>Triggers</h2>
 * Runs automatically pre-market (09:05 IST Mon–Fri — EC2 is up 08:45–15:45). Also registered with
 * {@link com.algo.trade.monitoring.SchedulerRegistry} as {@code dbRetention} so it can be force-run
 * from the diagnostics page (Schedulers → trigger), and exposed at
 * {@code POST /diagnostics/db-retention/run} for a run that returns per-table counts.
 *
 * <p>Each table is deleted independently (auto-commit) so one failure can't roll back the others.
 * Deleting rows frees space for reuse but does NOT shrink the .mv.db high-water mark; run a one-time
 * {@code SHUTDOWN COMPACT} (app stopped) to reclaim the file size. {@code db-retention.dry-run: true}
 * (or {@code ?dryRun=true} on the endpoint) logs/returns what would be deleted without deleting.
 */
@Service
public class DatabaseRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseRetentionService.class);

    private final JdbcTemplate jdbc;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    @Value("${db-retention.enabled:true}")
    private boolean enabled;

    @Value("${db-retention.dry-run:false}")
    private boolean dryRun;

    @Value("${db-retention.strategy-decision-days:7}")
    private int strategyDecisionDays;

    @Value("${db-retention.greeks-days:7}")
    private int greeksDays;

    @Value("${db-retention.exit-eval-days:30}")
    private int exitEvalDays;

    @Value("${db-retention.error-event-days:30}")
    private int errorEventDays;

    public DatabaseRetentionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @jakarta.annotation.PostConstruct
    void registerTrigger() {
        if (schedulerRegistry != null) {
            // 24h interval so the diagnostics health view treats a once-daily run as healthy.
            schedulerRegistry.register("dbRetention",
                    "DB retention purge — decisions/greeks(7d), exit/error(30d); IV_SAMPLES kept",
                    Duration.ofHours(24).toMillis(), this::purge);
        }
    }

    /** A purge rule: physical table, its timestamp column (quoted — TIMESTAMP is reserved), retention days. */
    private record Rule(String table, String tsColumn, int days) {}

    private List<Rule> rules() {
        return List.of(
                new Rule("STRATEGY_DECISION_ENTITY", "\"TIMESTAMP\"", strategyDecisionDays),
                new Rule("GREEKS_SAMPLES", "CAPTURED_AT", greeksDays),
                new Rule("EXIT_EVALUATIONS", "EVALUATED_AT", exitEvalDays),
                new Rule("ERROR_EVENT_ENTITY", "\"TIMESTAMP\"", errorEventDays));
    }

    /** Scheduled + SchedulerRegistry trigger entry point. Uses the configured dry-run flag. */
    @Scheduled(cron = "0 5 9 * * MON-FRI", zone = "Asia/Kolkata")
    public void purge() {
        Map<String, Object> result = runNow(dryRun);
        if (schedulerRegistry != null) schedulerRegistry.recordRun("dbRetention");
        log.info("[DbRetention] scheduled run complete — {}", result);
    }

    /**
     * Run the purge now and return a per-table summary. Used by the diagnostics endpoint so a manual
     * run reports exactly how many rows each table shed. Verbose at INFO so the EC2 logs show, per
     * table: total before, how many were older than the cutoff, and the count after the delete.
     *
     * @param dryRunMode when true, counts rows that <em>would</em> be deleted without deleting.
     */
    public Map<String, Object> runNow(boolean dryRunMode) {
        Map<String, Object> summary = new LinkedHashMap<>();
        Map<String, Object> perTable = new LinkedHashMap<>();
        long total = 0;

        log.info("[DbRetention] runNow START — enabled={} dryRunArg={} configDryRun={} "
                        + "retention(decisions={}d greeks={}d exit={}d error={}d)",
                enabled, dryRunMode, dryRun, strategyDecisionDays, greeksDays, exitEvalDays, errorEventDays);

        if (!enabled) {
            log.warn("[DbRetention] SKIPPED — db-retention.enabled=false. Set 'db-retention.enabled: true' "
                    + "(and confirm the deployed application.yml has it) to actually purge.");
            summary.put("enabled", false);
            summary.put("note", "db-retention.enabled is false — nothing was deleted");
            return summary;
        }

        for (Rule r : rules()) {
            if (r.days() <= 0) {
                log.info("[DbRetention] {} — retentionDays={} (<=0) → kept, skipped", r.table(), r.days());
                perTable.put(r.table(), "kept (retention<=0)");
                continue;
            }
            try {
                Long before = jdbc.queryForObject("SELECT COUNT(*) FROM " + r.table(), Long.class);
                // Cutoff computed IN the DB (DATEADD) — avoids any JDBC timestamp-binding ambiguity.
                Long older = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + r.table() + " WHERE " + r.tsColumn()
                                + " < DATEADD('DAY', ?, CURRENT_TIMESTAMP)",
                        Long.class, -r.days());
                before = before == null ? 0 : before;
                older = older == null ? 0 : older;

                if (dryRunMode) {
                    log.info("[DbRetention] {} — total={} olderThan{}d={} (DRY-RUN, nothing deleted)",
                            r.table(), before, r.days(), older);
                    perTable.put(r.table(), Map.of("retentionDays", r.days(),
                            "total", before, "wouldDelete", older));
                    total += older;
                } else {
                    int deleted = jdbc.update(
                            "DELETE FROM " + r.table() + " WHERE " + r.tsColumn()
                                    + " < DATEADD('DAY', ?, CURRENT_TIMESTAMP)", -r.days());
                    Long after = jdbc.queryForObject("SELECT COUNT(*) FROM " + r.table(), Long.class);
                    after = after == null ? 0 : after;
                    log.info("[DbRetention] {} — PURGED {} rows older than {}d; count {} → {} (olderEligible={})",
                            r.table(), deleted, r.days(), before, after, older);
                    perTable.put(r.table(), Map.of("retentionDays", r.days(),
                            "before", before, "deleted", deleted, "after", after));
                    total += deleted;
                }
            } catch (Exception e) {
                // Full stack trace so a binding/identifier problem is visible in the EC2 logs.
                log.error("[DbRetention] purge FAILED for {} (continuing with other tables): {}",
                        r.table(), e.toString(), e);
                perTable.put(r.table(), "error: " + e.getMessage());
            }
        }

        summary.put("enabled", true);
        summary.put("dryRun", dryRunMode);
        summary.put("tables", perTable);
        summary.put(dryRunMode ? "totalWouldDelete" : "totalDeleted", total);
        summary.put("note", "IV_SAMPLES, TRADE/ORDER/configs are never purged. Row counts drop immediately; "
                + "the .mv.db FILE SIZE only shrinks after a one-time SHUTDOWN COMPACT (deletes free space for reuse).");
        log.info("[DbRetention] runNow DONE — {}{}", dryRunMode ? "wouldDelete=" : "deleted=", total);
        return summary;
    }
}
