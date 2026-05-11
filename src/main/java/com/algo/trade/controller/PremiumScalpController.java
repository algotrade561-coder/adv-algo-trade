package com.algo.trade.controller;

import com.algo.trade.strategy.BreakoutReentryService;
import com.algo.trade.strategy.PremiumScalpService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * REST endpoints for monitoring PREMIUM_SCALP and BREAKOUT_REENTRY paper strategies.
 */
@RestController
public class PremiumScalpController {

    private final PremiumScalpService premiumScalpService;
    private final BreakoutReentryService breakoutReentryService;

    public PremiumScalpController(PremiumScalpService premiumScalpService,
                                   BreakoutReentryService breakoutReentryService) {
        this.premiumScalpService = premiumScalpService;
        this.breakoutReentryService = breakoutReentryService;
    }

    @GetMapping("/premium-scalp/status")
    public ResponseEntity<Map<String, Object>> premiumScalpStatus() {
        return ResponseEntity.ok(premiumScalpService.getStatus());
    }

    @GetMapping("/breakout-reentry/status")
    public ResponseEntity<Map<String, Object>> breakoutReentryStatus() {
        return ResponseEntity.ok(breakoutReentryService.getStatus());
    }
}
