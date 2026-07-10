package com.algo.trade.controller;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.indicator.GreeksCalculator;
import com.algo.trade.risk.PortfolioGreeksService;
import com.algo.trade.strategy.oimomentum.GammaExposureService;
import com.algo.trade.strategy.oimomentum.OiPriceMatrixService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Greeks Dashboard Controller — exposes live option Greeks for the Angular dashboard.
 *
 * Provides:
 * - Portfolio-level Greeks (net delta, gamma, theta, vega)
 * - Per-position Greeks breakdown
 * - Greeks risk alerts (e.g., delta too high)
 */
@RestController
@RequestMapping("/greeks")
public class GreeksDashboardController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GreeksDashboardController.class);

    private final PortfolioGreeksService portfolioGreeksService;
    private final GammaExposureService gexService;
    private final OiPriceMatrixService oiMatrixService;

    public GreeksDashboardController(PortfolioGreeksService portfolioGreeksService,
                                      GammaExposureService gexService,
                                      OiPriceMatrixService oiMatrixService) {
        this.portfolioGreeksService = portfolioGreeksService;
        this.gexService = gexService;
        this.oiMatrixService = oiMatrixService;
    }

    /**
     * GET /greeks/gex/{index} — GEX profile for an index (flip point, walls, per-strike)
     */
    @GetMapping("/gex/{index}")
    public ResponseEntity<?> getGex(@PathVariable String index) {
        if (index == null || index.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Index parameter is required"));
        }
        try {
            IndexType indexType = IndexType.fromName(index);
            if (indexType == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown index: " + index));
            }
            GammaExposureService.GexSnapshot snap = gexService.evaluate(indexType);
            if (!snap.isValid()) {
                return ResponseEntity.ok(Map.of("status", "no_data", "index", index));
            }
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("index", indexType.name());
            resp.put("spot", snap.spot());
            resp.put("totalGex", snap.totalGex());
            resp.put("atmGex", snap.atmGex());
            resp.put("flipStrike", snap.flipStrike());
            resp.put("wallAbove", snap.wallAbove());
            resp.put("wallBelow", snap.wallBelow());
            int regime = gexService.dealerRegime(indexType);
            resp.put("dealerRegime", regime > 0 ? "DAMPENING" : regime < 0 ? "AMPLIFYING" : "NEUTRAL");
            resp.put("strikes", snap.strikes());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("[GreeksDashboard] GEX request failed for index={}: {}", index, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error",
                    e.getMessage() != null ? e.getMessage() : "Unexpected error computing GEX"));
        }
    }

    /**
     * GET /greeks/oi-matrix/{index} — OI+Price 4-cell matrix for ATM ±5 strikes
     */
    @GetMapping("/oi-matrix/{index}")
    public ResponseEntity<?> getOiMatrix(@PathVariable String index) {
        if (index == null || index.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Index parameter is required"));
        }
        try {
            IndexType indexType = IndexType.fromName(index);
            if (indexType == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown index: " + index));
            }
            OiPriceMatrixService.MatrixSnapshot snap = oiMatrixService.evaluate(indexType, 5);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("index", indexType.name());
            resp.put("longBuildupCount", snap.longBuildupCount());
            resp.put("shortBuildupCount", snap.shortBuildupCount());
            resp.put("shortCoveringCount", snap.shortCoveringCount());
            resp.put("longUnwindingCount", snap.longUnwindingCount());
            resp.put("netBullishCells", snap.netBullishCells());
            resp.put("dominantCell", snap.dominantCell().name());
            resp.put("directionSignal", snap.directionSignal());
            resp.put("cells", snap.cells());
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("[GreeksDashboard] OI-matrix request failed for index={}: {}", index, e.getMessage(), e);
            return ResponseEntity.badRequest().body(Map.of("error",
                    e.getMessage() != null ? e.getMessage() : "Unexpected error computing OI matrix"));
        }
    }

    @GetMapping("/portfolio")
    public ResponseEntity<?> getPortfolioGreeks() {
        return ResponseEntity.ok(portfolioGreeksService.compute());
    }

    /**
     * GET /greeks/positions — per-position Greeks breakdown
     */
    @GetMapping("/positions")
    public ResponseEntity<?> getPositionGreeks() {
        return ResponseEntity.ok(portfolioGreeksService.compute().byUnderlying());
    }

    /**
     * GET /greeks/risk — Greeks-based risk alerts
     */
    @GetMapping("/risk")
    public ResponseEntity<?> getGreeksRisk() {
        Map<String, Object> risk = new LinkedHashMap<>();
        var portfolio = portfolioGreeksService.compute();
        double netDelta = portfolio.netDelta();
        double netGamma = portfolio.netGamma();
        double netTheta = portfolio.netTheta();

        risk.put("deltaExposure", Math.abs(netDelta) > 50 ? "HIGH" : "NORMAL");
        risk.put("gammaRisk", Math.abs(netGamma) > 10 ? "ELEVATED" : "NORMAL");
        risk.put("thetaDecay", netTheta < -500 ? "SIGNIFICANT" : "MODERATE");
        risk.put("netDelta", netDelta);
        risk.put("netGamma", netGamma);
        risk.put("netTheta", netTheta);

        return ResponseEntity.ok(risk);
    }
}
