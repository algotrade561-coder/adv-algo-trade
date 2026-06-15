package com.algo.trade.reporting;

import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * End-of-day summary that surfaces the dominant blocker per strategy.
 *
 * <p>Motivation: zero-trade days previously went unexplained until a manual forensic
 * dig through {@code entry-signals.csv}. This service automates that, runs at 15:35
 * IST (5 minutes after market close so 15:30 squareoffs are captured), and emits both
 * an INFO log line per active strategy and a single Telegram digest. It also archives
 * the structured result to {@code reports/daily-blockers/YYYY-MM-DD.json} so trends
 * across days are inspectable later.
 *
 * <p>This is also the verification instrument for the May-25 fix set:
 * <ul>
 *   <li>OI_SHIFT_TRAP top blocker should shift from {@code LOW_VOLUME / underlying_volume}
 *       to {@code TREND_FLAT} (or actual signals) once NIFTY/SENSEX switch to OI_PROXY.</li>
 *   <li>Any blocked strategy will show its dominant filter, making "why no trades?"
 *       a self-answering question without re-running this diagnostic.</li>
 * </ul>
 */
@Service
public class DailyBlockerSummaryService {

    private static final Logger log = LoggerFactory.getLogger(DailyBlockerSummaryService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final int MAX_STRATEGIES_IN_DIGEST = 6;
    private static final int TOP_BLOCKERS_PER_STRATEGY = 3;

    private final StrategyDecisionRepository decisionRepository;
    private final TelegramAlertService telegramAlertService;
    private final ObjectMapper objectMapper;

    @Value("${blocker-summary.enabled:true}")
    private boolean enabled;

    @Value("${blocker-summary.archive-dir:reports/daily-blockers}")
    private String archiveDir;

    public DailyBlockerSummaryService(StrategyDecisionRepository decisionRepository,
                                      TelegramAlertService telegramAlertService,
                                      ObjectMapper objectMapper) {
        this.decisionRepository = decisionRepository;
        this.telegramAlertService = telegramAlertService;
        this.objectMapper = objectMapper;
    }

    /**
     * Runs at 15:35 IST Mon-Fri. Cron is overridable via
     * {@code blocker-summary.cron} so deployments with different session schedules can
     * adjust without code changes.
     */
    @Scheduled(cron = "${blocker-summary.cron:0 35 15 * * MON-FRI}", zone = "Asia/Kolkata")
    public void emitScheduledSummary() {
        if (!enabled) {
            return;
        }
        runSummary(LocalDate.now(IST));
    }

    /**
     * Build and emit the summary for a given trading date. Public so it can be wired
     * to a manual REST endpoint for ad-hoc inspection or replay.
     */
    public DigestPayload runSummary(LocalDate date) {
        Instant startOfDay = date.atStartOfDay(IST).toInstant();

        Map<String, Long> evalsByStrategy = new HashMap<>();
        for (Object[] row : decisionRepository.countByStrategyTypeSince(startOfDay)) {
            evalsByStrategy.put(asString(row[0]), asLong(row[1]));
        }
        Map<String, Long> entriesByStrategy = new HashMap<>();
        for (Object[] row : decisionRepository.countEntriesByStrategySince(startOfDay)) {
            entriesByStrategy.put(asString(row[0]), asLong(row[1]));
        }
        Map<String, List<BlockerEntry>> blockersByStrategy = new HashMap<>();
        for (Object[] row : decisionRepository.countByStrategyAndFirstFailedFilterSince(startOfDay)) {
            String strategy = asString(row[0]);
            String filter = asString(row[1]);
            long cnt = asLong(row[2]);
            blockersByStrategy.computeIfAbsent(strategy, k -> new ArrayList<>())
                    .add(new BlockerEntry(filter, cnt));
        }

        List<StrategyBlocker> rows = new ArrayList<>();
        for (Map.Entry<String, Long> e : evalsByStrategy.entrySet()) {
            String strategy = e.getKey();
            long evals = e.getValue();
            long entries = entriesByStrategy.getOrDefault(strategy, 0L);
            List<BlockerEntry> blockers = blockersByStrategy.getOrDefault(strategy, List.of());
            rows.add(new StrategyBlocker(strategy, evals, entries, blockers));
        }
        rows.sort(Comparator.comparingLong(StrategyBlocker::evals).reversed());

        // INFO log per strategy with non-zero evals
        for (StrategyBlocker sb : rows) {
            if (sb.evals() == 0) continue;
            String topBlockers = formatTopBlockers(sb.blockers(), 2);
            log.info("[DailyBlockers] {}: evals={} entries={} top={}",
                    sb.strategy(), sb.evals(), sb.entries(), topBlockers);
        }

        DigestPayload payload = new DigestPayload(date.toString(), rows);
        sendDigest(payload);
        archive(payload);
        return payload;
    }

    private void sendDigest(DigestPayload payload) {
        long totalEvals = payload.strategies().stream().mapToLong(StrategyBlocker::evals).sum();
        long totalEntries = payload.strategies().stream().mapToLong(StrategyBlocker::entries).sum();

        StringBuilder sb = new StringBuilder();
        sb.append("\uD83D\uDCCA EOD Blocker Report (").append(payload.date()).append(")\n");
        sb.append("Evals=").append(totalEvals).append("  Entries=").append(totalEntries);
        if (totalEvals == 0) {
            sb.append("\n(no evaluations recorded today)");
        } else if (totalEntries == 0) {
            sb.append("\n\u26A0\uFE0F Zero-trade day. Top blockers per strategy:");
        } else {
            sb.append("\nTop blockers per strategy:");
        }
        int shown = 0;
        for (StrategyBlocker s : payload.strategies()) {
            if (shown >= MAX_STRATEGIES_IN_DIGEST) break;
            if (s.evals() == 0) continue;
            String top = s.blockers().isEmpty() ? "-"
                    : s.blockers().get(0).filter() + "(" + s.blockers().get(0).count() + ")";
            sb.append("\n\u2022 ").append(s.strategy())
                    .append(": evals=").append(s.evals())
                    .append(" entries=").append(s.entries())
                    .append(" top=").append(top);
            shown++;
        }
        try {
            telegramAlertService.systemAlert(sb.toString());
        } catch (Exception ex) {
            log.warn("[DailyBlockers] Telegram dispatch failed: {}", ex.getMessage());
        }
    }

    private void archive(DigestPayload payload) {
        try {
            Path dir = Path.of(archiveDir);
            Files.createDirectories(dir);
            Path file = dir.resolve(payload.date() + ".json");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), payload);
            log.info("[DailyBlockers] Archived to {}", file);
        } catch (IOException e) {
            log.warn("[DailyBlockers] Archive failed: {}", e.getMessage());
        }
    }

    private static String formatTopBlockers(List<BlockerEntry> blockers, int limit) {
        if (blockers == null || blockers.isEmpty()) return "-";
        StringBuilder sb = new StringBuilder();
        int n = Math.min(limit, blockers.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append(' ');
            BlockerEntry b = blockers.get(i);
            sb.append(b.filter()).append('(').append(b.count()).append(')');
        }
        return sb.toString();
    }

    private static String asString(Object o) {
        return o == null ? "UNKNOWN" : o.toString();
    }

    private static long asLong(Object o) {
        return o instanceof Number n ? n.longValue() : 0L;
    }

    public record BlockerEntry(String filter, long count) {}

    public record StrategyBlocker(String strategy, long evals, long entries,
                                  List<BlockerEntry> blockers) {
        /** Top-N convenience for downstream consumers (e.g. health endpoint). */
        public List<BlockerEntry> topBlockers() {
            return blockers.subList(0, Math.min(TOP_BLOCKERS_PER_STRATEGY, blockers.size()));
        }
    }

    public record DigestPayload(String date, List<StrategyBlocker> strategies) {}
}
