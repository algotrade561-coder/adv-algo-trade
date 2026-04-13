package com.kiteapioptions.broker.zerodha;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.TradingMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Blocks application startup in LIVE mode until Kite auth is ready.
 */
@Component
public class KiteStartupLogin implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KiteStartupLogin.class);

    private final TradingProperties properties;
    private final KiteAccessTokenStore tokenStore;
    private final KiteAuthService kiteAuthService;

    public KiteStartupLogin(
            TradingProperties properties,
            KiteAccessTokenStore tokenStore,
            KiteAuthService kiteAuthService
    ) {
        this.properties = properties;
        this.tokenStore = tokenStore;
        this.kiteAuthService = kiteAuthService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.broker().autoLoginOnStartup()) {
            log.info("Kite startup login skipped: trading.broker.auto-login-on-startup=false");
            return;
        }
        if (properties.mode() != TradingMode.LIVE) {
            log.info("Kite startup login skipped: mode={}", properties.mode());
            return;
        }
        if (tokenStore.authenticated()) {
            log.info("Kite startup login skipped: access token already present");
            return;
        }

        log.info("Kite startup login started. Application startup will wait until the access token is captured.");
        KiteLoginResult result = kiteAuthService.login();
        log.info("Kite startup login completed: userId={}", result.userId());
    }
}
