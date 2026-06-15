package com.algo.trade.controller;

import com.algo.trade.execution.exit.ExitEvaluationRegistry;
import com.algo.trade.execution.exit.ExitEvaluationSnapshot;
import java.util.Collection;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Debug API: last computed exit parameters per open trade or spread group.
 */
@RestController
public class ExitEvaluationController {

    private final ExitEvaluationRegistry registry;

    public ExitEvaluationController(ExitEvaluationRegistry registry) {
        this.registry = registry;
    }

    @GetMapping("/exit-evaluations")
    public Collection<ExitEvaluationSnapshot> all() {
        return registry.all();
    }

    @GetMapping("/exit-evaluations/{positionId}")
    public Map<String, Object> one(@PathVariable String positionId) {
        ExitEvaluationSnapshot snap = registry.get(positionId);
        if (snap == null) {
            return Map.of("found", false, "positionId", positionId);
        }
        return Map.of("found", true, "snapshot", snap);
    }
}
