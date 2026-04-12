package com.kiteapioptions.controller;

import com.kiteapioptions.broker.zerodha.KiteAuthService;
import com.kiteapioptions.broker.zerodha.KiteLoginResult;
import java.net.URI;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class KiteAuthController {

    private final KiteAuthService kiteAuthService;

    public KiteAuthController(KiteAuthService kiteAuthService) {
        this.kiteAuthService = kiteAuthService;
    }

    @GetMapping("/auth/kite/login")
    public Map<String, Object> login() {
        URI loginUrl = kiteAuthService.loginUrl();
        return Map.of(
                "loginUrl", loginUrl.toString(),
                "diagnostics", kiteAuthService.diagnostics()
        );
    }

    @GetMapping(value = "/auth/kite/callback", produces = MediaType.TEXT_PLAIN_VALUE)
    public String callback(@RequestParam(name = "status", required = false) String status,
                           @RequestParam(name = "request_token", required = false) String requestToken) {
        if (!"success".equalsIgnoreCase(status) || requestToken == null || requestToken.isBlank()) {
            return "Kite login failed. Close this tab and check the application logs.";
        }
        KiteLoginResult result = kiteAuthService.exchangeRequestToken(requestToken);
        return "Kite login completed for user " + result.userId()
                + ". Access token captured in memory for this application run. You can close this tab.";
    }
}
