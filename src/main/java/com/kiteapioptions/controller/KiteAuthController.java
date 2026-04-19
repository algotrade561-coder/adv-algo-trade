package com.kiteapioptions.controller;

import com.kiteapioptions.broker.zerodha.KiteAuthService;
import com.kiteapioptions.broker.zerodha.KiteLoginResult;

import java.io.IOException;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KiteAuthController {

    private static final Logger log = LoggerFactory.getLogger(KiteAuthController.class);

    private final KiteAuthService kiteAuthService;

    public KiteAuthController(KiteAuthService kiteAuthService) {
        this.kiteAuthService = kiteAuthService;
    }

    @GetMapping("/auth/kite/login")
    public Map<String, Object> login() throws IOException {
        log.info("Kite login endpoint called");
        if (!kiteAuthService.apiKeyConfigured()) {
            log.info("Kite login endpoint returning first-run setup details");
            return kiteAuthService.firstRunSetup();
        }
        Map<String, Object> response = kiteAuthService.initiateLoginFlow();
        log.info("Kite login endpoint initiated login flow");
        return response;
    }

    @GetMapping("/auth/kite/session")
    public Map<String, Object> session() {
        log.info("Kite session endpoint called");
        if (!kiteAuthService.apiKeyConfigured()) {
            log.info("Kite session endpoint returning first-run setup details");
            return kiteAuthService.firstRunSessionSetup();
        }
        KiteLoginResult result = kiteAuthService.currentSession();
        log.info("Kite session endpoint completed: authenticated={}, userId={}", result.success(), result.userId());
        return Map.of(
                "authenticated", result.success(),
                "userId", result.userId() == null ? "" : result.userId(),
                "authenticatedAt", result.authenticatedAt(),
                "diagnostics", kiteAuthService.diagnostics()
        );
    }

    @GetMapping(value = "/auth/kite/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String callback(@RequestParam(name = "status", required = false) String status,
                           @RequestParam(name = "request_token", required = false) String requestToken) {
        log.info("Kite callback received: status={}, requestTokenPresent={}",
                status, requestToken != null && !requestToken.isBlank());
        if (!"success".equalsIgnoreCase(status) || requestToken == null || requestToken.isBlank()) {
            log.warn("Kite callback rejected: status={}, requestTokenPresent={}",
                    status, requestToken != null && !requestToken.isBlank());
            return "Kite login failed. Close this tab and check the application logs.";
        }
        KiteLoginResult result = kiteAuthService.exchangeRequestToken(requestToken);
        log.info("Kite callback completed: userId={}", result.userId());
        return "Kite login completed for user " + result.userId()
                + ". Access token captured for this run and persisted to local token storage. You can close this tab.";
    }
}
