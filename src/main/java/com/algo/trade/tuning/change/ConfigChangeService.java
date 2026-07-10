package com.algo.trade.tuning.change;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** §10c — records/lists/reverts tuning config-change tags (audit only; never writes live trading config). */
@Service
public class ConfigChangeService {

    private static final Logger log = LoggerFactory.getLogger(ConfigChangeService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ConfigChangeLogRepository repo;

    public ConfigChangeService(ConfigChangeLogRepository repo) {
        this.repo = repo;
    }

    public ConfigChangeLog record(String area, String field, String oldValue, String newValue,
                                  String note, String sourceJobId, String actor) {
        ConfigChangeLog c = new ConfigChangeLog();
        c.setChangedAt(Instant.now());
        c.setChangedBy(actor);
        c.setArea(area);
        c.setField(field);
        c.setOldValue(oldValue);
        c.setNewValue(newValue);
        c.setNote(note);
        c.setSourceJobId(sourceJobId);
        ConfigChangeLog saved = repo.save(c);
        log.info("[ConfigChange] {} recorded: area={} field={} {}→{} (job={})",
                actor, area, field, oldValue, newValue, sourceJobId);
        return saved;
    }

    public List<ConfigChangeLog> recent(int limit) {
        return repo.findAllByOrderByChangedAtDesc(PageRequest.of(0, Math.max(1, limit)));
    }

    /** Changes whose timestamp falls within the IST day-range [from, to] — used by the impact section. */
    public List<ConfigChangeLog> inWindow(LocalDate from, LocalDate to) {
        Instant start = from.atStartOfDay(IST).toInstant();
        Instant end = to.plusDays(1).atStartOfDay(IST).toInstant();
        return repo.findByChangedAtBetweenOrderByChangedAtAsc(start, end);
    }

    public ConfigChangeLog revert(Long id, String actor) {
        ConfigChangeLog c = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("no change with id=" + id));
        c.setReverted(true);
        c.setNote((c.getNote() == null ? "" : c.getNote() + " | ") + "REVERTED by " + actor);
        log.info("[ConfigChange] {} reverted change id={} ({} {})", actor, id, c.getArea(), c.getField());
        return repo.save(c);
    }
}
