package com.algo.trade.commodity;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API for MCX crude oil price data.
 * Used by dashboard to display current oil price and regime.
 */
@RestController
@RequestMapping("/api/oil-price")
public class OilPriceController {

    private final OilPriceTracker oilPriceTracker;

    public OilPriceController(OilPriceTracker oilPriceTracker) {
        this.oilPriceTracker = oilPriceTracker;
    }

    /**
     * Get current oil price snapshot.
     * 
     * GET /api/oil-price/snapshot
     * 
     * Response:
     * {
     *   "priceINR": 8719.0,
     *   "priceUSD": 104.42,
     *   "dailyChangeINR": 282.0,
     *   "dailyChangePct": 3.34,
     *   "regime": "CRISIS",
     *   "momentum": "SPIKING",
     *   "tradingSymbol": "CRUDEOIL26JUNFUT",
     *   "contractExpiry": "2026-06-19",
     *   "lastUpdate": "2026-05-09T10:35:00Z",
     *   "available": true
     * }
     */
    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> getSnapshot() {
        if (!oilPriceTracker.isDataAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Oil price data not available"
            ));
        }

        OilPriceTracker.OilPriceSnapshot snapshot = oilPriceTracker.getSnapshot();
        
        return ResponseEntity.ok(Map.of(
                "priceINR", snapshot.priceINR(),
                "priceUSD", snapshot.priceUSD(),
                "dailyChangeINR", snapshot.dailyChangeINR(),
                "dailyChangePct", snapshot.dailyChangePct(),
                "regime", snapshot.regime(),
                "momentum", snapshot.momentum(),
                "tradingSymbol", snapshot.tradingSymbol(),
                "contractExpiry", snapshot.contractExpiry() != null ? snapshot.contractExpiry().toString() : null,
                "lastUpdate", snapshot.lastUpdate() != null ? snapshot.lastUpdate().toString() : null,
                "available", true
        ));
    }
}
