package com.algo.trade.controller;

import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.marketdata.OptionChainCollector;
import com.algo.trade.marketdata.PCRMaxPainTracker;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

/**
 * OI Heatmap Controller — provides Open Interest distribution data for visualization.
 *
 * Exposes:
 * - Full OI chain (call OI + put OI per strike)
 * - OI change data (buildup vs unwinding)
 * - PCR at each strike level
 * - Max pain strike highlighted
 */
@RestController
@RequestMapping("/oi-heatmap")
public class OIHeatmapController {

    private final OptionChainCollector optionChainCollector;
    private final PCRMaxPainTracker pcrMaxPainTracker;

    public OIHeatmapController(OptionChainCollector optionChainCollector, PCRMaxPainTracker pcrMaxPainTracker) {
        this.optionChainCollector = optionChainCollector;
        this.pcrMaxPainTracker = pcrMaxPainTracker;
    }

    /**
     * GET /oi-heatmap/{underlying} — full OI heatmap data for an underlying
     */
    @GetMapping("/{underlying}")
    public ResponseEntity<?> getOIHeatmap(@PathVariable String underlying) {
        try {
            UnderlyingSymbol symbol = UnderlyingSymbol.valueOf(underlying.toUpperCase());
            OptionChainSnapshot chain = optionChainCollector.collectForDisplay(symbol);

            if (chain == null || chain.levels().isEmpty()) {
                return ResponseEntity.ok(Map.of("error", "No option chain data available for " + underlying));
            }

            List<Map<String, Object>> levels = chain.levels().stream().map(level -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("strike", level.strike());
                row.put("callOI", level.callOpenInterest());
                row.put("putOI", level.putOpenInterest());
                row.put("callOIChange", level.callOpenInterestChange());
                row.put("putOIChange", level.putOpenInterestChange());
                row.put("pcr", level.callOpenInterest() > 0 ? (double) level.putOpenInterest() / level.callOpenInterest() : 0);
                return row;
            }).collect(Collectors.toList());

            // Summary
            long totalCallOI = chain.levels().stream().mapToLong(OptionChainLevel::callOpenInterest).sum();
            long totalPutOI = chain.levels().stream().mapToLong(OptionChainLevel::putOpenInterest).sum();
            double overallPCR = totalCallOI > 0 ? (double) totalPutOI / totalCallOI : 1.0;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("underlying", underlying);
            response.put("spotPrice", chain.underlyingPrice());
            response.put("timestamp", chain.timestamp());
            response.put("totalCallOI", totalCallOI);
            response.put("totalPutOI", totalPutOI);
            response.put("overallPCR", Math.round(overallPCR * 100) / 100.0);
            response.put("pcrBias", overallPCR > 1.05 ? "BULLISH" : (overallPCR < 0.95 ? "BEARISH" : "NEUTRAL"));
            response.put("maxPainStrike", pcrMaxPainTracker.getMaxPainStrike(symbol));
            response.put("levels", levels);

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Invalid underlying: " + underlying));
        }
    }

    /**
     * GET /oi-heatmap/summary — PCR summary for all underlyings
     */
    @GetMapping("/summary")
    public ResponseEntity<?> getSummary() {
        return ResponseEntity.ok(pcrMaxPainTracker.getAllSnapshots());
    }
}
