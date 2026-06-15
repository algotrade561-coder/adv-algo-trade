package com.algo.trade.execution.exit;

import com.algo.trade.persistence.ExitEvaluationEntity;
import com.algo.trade.persistence.ExitEvaluationRepository;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * In-memory store of the latest exit evaluation per trade or spread group,
 * with async persistence for post-mortem audit.
 */
@Component
public class ExitEvaluationRegistry {

    private final Map<String, ExitEvaluationSnapshot> snapshots = new ConcurrentHashMap<>();
    private final ExitEvaluationRepository repository;

    public ExitEvaluationRegistry(ExitEvaluationRepository repository) {
        this.repository = repository;
    }

    public void put(ExitEvaluationSnapshot snapshot) {
        snapshots.put(snapshot.positionId(), snapshot);
        // Persist non-HOLD decisions for audit trail
        if (snapshot.decision() != null && !snapshot.decision().startsWith("HOLD")) {
            try {
                repository.save(ExitEvaluationEntity.from(snapshot));
            } catch (Exception e) {
                // Don't let persistence failure block exit evaluation — but log it
                org.slf4j.LoggerFactory.getLogger(ExitEvaluationRegistry.class)
                        .warn("Failed to persist exit evaluation for {}: {}", snapshot.positionId(), e.getMessage());
            }
        }
    }

    public ExitEvaluationSnapshot get(String positionId) {
        return snapshots.get(positionId);
    }

    public Collection<ExitEvaluationSnapshot> all() {
        return snapshots.values();
    }

    public void remove(String positionId) {
        snapshots.remove(positionId);
    }
}
