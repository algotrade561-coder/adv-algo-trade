package com.algo.trade.controller;

import com.algo.trade.marketdata.MarketMemoryEngine;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Market-Memory V5 UI backend (docs/MARKET-MEMORY-V5-DESIGN.md §16.1) — READ-ONLY views of state
 * the engine already computes (ring buffers + live snapshots). Zero impact on trading paths.
 */
@RestController
public class MarketMemoryController {

    private final MarketMemoryEngine engine;

    public MarketMemoryController(MarketMemoryEngine engine) {
        this.engine = engine;
    }

    /** Per-strike live state grid for one index (tab 1: heat-grid). */
    @GetMapping("/market-memory/state")
    public Map<String, Object> state(@RequestParam(value = "index", defaultValue = "NIFTY") String index) {
        return Map.of("enabled", engine.isEnabled(), "index", index, "rows", engine.stateGrid(index));
    }

    /** System health summary — one-glance header for the Market Memory page. */
    @GetMapping("/market-memory/summary")
    public Map<String, Object> summary(@RequestParam(value = "index", defaultValue = "NIFTY") String index) {
        return engine.summary(index);
    }

    /** Recent avalanche detections + entry outcomes (tab 2: feed). */
    @GetMapping("/market-memory/avalanches")
    public List<MarketMemoryEngine.MemoryEvent> avalanches(
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        return engine.recentEvents("AVALANCHE", Math.min(limit, 400));
    }

    /** All recent decision events (tab 3: EntryPipeline verdicts + ExitArbiter audit). */
    @GetMapping("/market-memory/decisions")
    public List<MarketMemoryEngine.MemoryEvent> decisions(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "limit", defaultValue = "150") int limit) {
        return engine.recentEvents(category, Math.min(limit, 400));
    }

    /** Episode memory / suspension scoreboard (tab 4). */
    @GetMapping("/market-memory/episodes")
    public List<Map<String, Object>> episodes() {
        return engine.episodeRows();
    }

    /** Week memory: mountain build + writers' pain per strike (tab 5). */
    @GetMapping("/market-memory/mountain")
    public List<Map<String, Object>> mountain(@RequestParam(value = "index", defaultValue = "NIFTY") String index) {
        return engine.mountainRows(index);
    }
}
