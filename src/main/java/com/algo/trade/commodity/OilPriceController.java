package com.algo.trade.commodity;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST API for Brent crude oil price data.
 * Used by dashboard to display current oil price and regime.
 */
@RestController
@RequestMapping("/api/oil-price")
public class OilPriceController {

    private final OilPriceTracker oilPriceTracker;
    private final BrentCrudeService brentCrudeService;

    public OilPriceController(OilPriceTracker oilPriceTracker, BrentCrudeService brentCrudeService) {
        this.oilPriceTracker = oilPriceTracker;
        this.brentCrudeService = brentCrudeService;
    }

    /**
     * Get current Brent crude oil price snapshot.
     * Always returns Brent data (from Yahoo Finance), never MCX.
     */
    @GetMapping("/snapshot")
    public ResponseEntity<Map<String, Object>> getSnapshot() {
        if (!brentCrudeService.isAvailable()) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Brent crude price data not available"
            ));
        }

        double priceUSD = brentCrudeService.getLastPriceUSD();
        double priceINR = priceUSD * 83.5;
        double dailyChangePct = brentCrudeService.getDailyChangePct();
        double dailyChangeINR = priceINR - (brentCrudeService.getPreviousCloseUSD() * 83.5);

        return ResponseEntity.ok(Map.of(
                "priceINR", priceINR,
                "priceUSD", priceUSD,
                "dailyChangeINR", dailyChangeINR,
                "dailyChangePct", dailyChangePct,
                "regime", brentCrudeService.getRegime(),
                "momentum", oilPriceTracker.getMomentum(),
                "tradingSymbol", "BZ=F (Brent Crude)",
                "contractExpiry", "",
                "lastUpdate", brentCrudeService.getLastFetchTime() != null ? brentCrudeService.getLastFetchTime().toString() : null,
                "available", true
        ));
    }
}
