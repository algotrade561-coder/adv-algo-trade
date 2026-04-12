package com.kiteapioptions.controller;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.TradingMode;
import com.kiteapioptions.execution.TradingStateService;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TradingControlController {

    private final TradingProperties tradingProperties;
    private final TradingStateService tradingStateService;

    public TradingControlController(TradingProperties tradingProperties, TradingStateService tradingStateService) {
        this.tradingProperties = tradingProperties;
        this.tradingStateService = tradingStateService;
    }

    @GetMapping("/config")
    public TradingProperties config() {
        return tradingProperties;
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> mode(@RequestBody ModeRequest request) {
        if (request.mode() == TradingMode.LIVE && !tradingProperties.liveTradingEnabled()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", "LIVE mode requires trading.live-trading-enabled=true"
            ));
        }
        tradingStateService.setRequestedMode(request.mode());
        return ResponseEntity.ok(status());
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        tradingStateService.start();
        return status();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        tradingStateService.stop();
        return status();
    }

    @PostMapping("/kill-switch")
    public Map<String, Object> killSwitch(@RequestBody KillSwitchRequest request) {
        if (request.enabled()) {
            tradingStateService.enableKillSwitch();
        } else {
            tradingStateService.clearKillSwitch();
        }
        return status();
    }

    private Map<String, Object> status() {
        return Map.of(
                "running", tradingStateService.running(),
                "killSwitch", tradingStateService.killSwitchEnabled(),
                "requestedMode", tradingStateService.requestedMode(),
                "configuredMode", tradingProperties.mode(),
                "liveTradingEnabled", tradingProperties.liveTradingEnabled(),
                "updatedAt", tradingStateService.updatedAt()
        );
    }

    public record ModeRequest(TradingMode mode) {
    }

    public record KillSwitchRequest(boolean enabled) {
    }
}
