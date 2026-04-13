package com.kiteapioptions.controller;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.TradingMode;
import com.kiteapioptions.domain.UnderlyingSymbol;
import com.kiteapioptions.execution.TradingStateService;
import com.kiteapioptions.notification.TelegramAlertService;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TradingControlController {

    private static final Logger log = LoggerFactory.getLogger(TradingControlController.class);

    private final TradingProperties tradingProperties;
    private final TradingStateService tradingStateService;
    private final TelegramAlertService telegramAlertService;

    public TradingControlController(
            TradingProperties tradingProperties,
            TradingStateService tradingStateService,
            TelegramAlertService telegramAlertService
    ) {
        this.tradingProperties = tradingProperties;
        this.tradingStateService = tradingStateService;
        this.telegramAlertService = telegramAlertService;
    }

    @GetMapping("/config")
    public TradingProperties config() {
        log.info("Configuration requested: configuredMode={}, requestedMode={}, liveTradingEnabled={}, running={}, killSwitch={}",
                tradingProperties.mode(),
                tradingStateService.requestedMode(),
                tradingProperties.liveTradingEnabled(),
                tradingStateService.running(),
                tradingStateService.killSwitchEnabled());
        return tradingProperties;
    }

    @PostMapping("/mode")
    public ResponseEntity<Map<String, Object>> mode(@RequestBody ModeRequest request) {
        log.info("Mode change requested: requestedMode={}, configuredMode={}, liveTradingEnabled={}",
                request.mode(), tradingProperties.mode(), tradingProperties.liveTradingEnabled());
        if (request.mode() == TradingMode.LIVE && !tradingProperties.liveTradingEnabled()) {
            log.warn("Mode change rejected: LIVE mode requires trading.live-trading-enabled=true");
            telegramAlertService.tradingStateChanged("Mode change rejected: LIVE requires live trading enabled", status());
            return ResponseEntity.badRequest().body(Map.of(
                    "accepted", false,
                    "reason", "LIVE mode requires trading.live-trading-enabled=true"
            ));
        }
        tradingStateService.setRequestedMode(request.mode());
        log.info("Mode change accepted: {}", status());
        telegramAlertService.tradingStateChanged("Mode changed to " + request.mode(), status());
        return ResponseEntity.ok(status());
    }

    @PostMapping("/start")
    public Map<String, Object> start() {
        log.info("Start requested: configuredMode={}, requestedMode={}, liveTradingEnabled={}, killSwitch={}",
                tradingProperties.mode(),
                tradingStateService.requestedMode(),
                tradingProperties.liveTradingEnabled(),
                tradingStateService.killSwitchEnabled());
        tradingStateService.start();
        log.info("Trading state after start: {}", status());
        telegramAlertService.tradingStateChanged("Scanner started", status());
        return status();
    }

    @PostMapping("/stop")
    public Map<String, Object> stop() {
        log.info("Stop requested: running={}, requestedMode={}",
                tradingStateService.running(), tradingStateService.requestedMode());
        tradingStateService.stop();
        log.info("Trading state after stop: {}", status());
        telegramAlertService.tradingStateChanged("Scanner stopped", status());
        return status();
    }

    @PostMapping("/kill-switch")
    public Map<String, Object> killSwitch(@RequestBody KillSwitchRequest request) {
        log.warn("Kill switch update requested: enabled={}", request.enabled());
        if (request.enabled()) {
            tradingStateService.enableKillSwitch();
        } else {
            tradingStateService.clearKillSwitch();
        }
        log.warn("Trading state after kill switch update: {}", status());
        telegramAlertService.tradingStateChanged("Kill switch set to " + request.enabled(), status());
        return status();
    }

    @GetMapping("/scan/underlyings")
    public Map<String, Object> scanUnderlyings() {
        log.info("Scan underlyings requested: enabledUnderlyings={}", tradingStateService.enabledUnderlyings());
        return status();
    }

    @PostMapping("/scan/underlyings/{underlying}")
    public Map<String, Object> scanUnderlying(
            @PathVariable UnderlyingSymbol underlying,
            @RequestBody ScanUnderlyingRequest request
    ) {
        log.info("Scan underlying update requested: underlying={}, enabled={}", underlying, request.enabled());
        tradingStateService.setUnderlyingScanEnabled(underlying, request.enabled());
        log.info("Trading state after scan underlying update: {}", status());
        telegramAlertService.tradingStateChanged("Scan toggle " + underlying + "=" + request.enabled(), status());
        return status();
    }

    private Map<String, Object> status() {
        return Map.of(
                "running", tradingStateService.running(),
                "killSwitch", tradingStateService.killSwitchEnabled(),
                "requestedMode", tradingStateService.requestedMode(),
                "configuredMode", tradingProperties.mode(),
                "liveTradingEnabled", tradingProperties.liveTradingEnabled(),
                "enabledUnderlyings", tradingStateService.enabledUnderlyings(),
                "updatedAt", tradingStateService.updatedAt()
        );
    }

    public record ModeRequest(TradingMode mode) {
    }

    public record KillSwitchRequest(boolean enabled) {
    }

    public record ScanUnderlyingRequest(boolean enabled) {
    }
}
