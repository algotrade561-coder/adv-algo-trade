package com.algo.trade.controller;

import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.indicator.GreeksCalculator;
import com.algo.trade.risk.PortfolioGreeksService;
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

    private final PortfolioGreeksService portfolioGreeksService;

    public GreeksDashboardController(PortfolioGreeksService portfolioGreeksService) {
        this.portfolioGreeksService = portfolioGreeksService;
    }

    /**
     * GET /greeks/portfolio — aggregate portfolio Greeks
     */
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
