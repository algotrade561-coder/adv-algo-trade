package com.kiteapioptions.broker.zerodha;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.ExecutionMode;
import com.kiteapioptions.domain.MarketDataMode;
import com.kiteapioptions.domain.TradingMode;
import com.kiteapioptions.execution.TradingStateService;
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
    private final TradingStateService tradingStateService;

    public KiteStartupLogin(
            TradingProperties properties,
            KiteAccessTokenStore tokenStore,
            KiteAuthService kiteAuthService,
            TradingStateService tradingStateService
    ) {
        this.properties = properties;
        this.tokenStore = tokenStore;
        this.kiteAuthService = kiteAuthService;
        this.tradingStateService = tradingStateService;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.broker().autoLoginOnStartup()) {
            log.info("Kite startup login skipped: trading.broker.auto-login-on-startup=false");
            return;
        }
        boolean zerodhaRequired = properties.mode() == TradingMode.LIVE
                || properties.marketDataMode() == MarketDataMode.ZERODHA
                || properties.executionMode() == ExecutionMode.ZERODHA;
        if (!zerodhaRequired) {
            log.info("Kite startup login skipped: mode={}, marketDataMode={}, executionMode={}",
                    properties.mode(), properties.marketDataMode(), properties.executionMode());
            return;
        }
        if (tokenStore.authenticated()) {
            if (kiteAuthService.validateCurrentSession()) {
                log.info("Kite startup login skipped: persisted/configured access token is valid");
                startScannerAfterLoginIfConfigured();
                return;
            }
            log.info("Kite startup login will continue: persisted/configured access token is not valid");
        }

        log.info("Kite startup login started. Application startup will wait until the access token is captured.");
        KiteLoginResult result = kiteAuthService.login();
        if (result == null) {
            throw new IllegalStateException("Kite startup login did not return a session");
        }
        log.info("Kite startup login completed: userId={}", result.userId());
        startScannerAfterLoginIfConfigured();
    }

    private void startScannerAfterLoginIfConfigured() {
        if (!properties.algo().autoStartScannerAfterLogin()) {
            log.info("Scanner auto-start skipped: trading.algo.auto-start-scanner-after-login=false");
            return;
        }
        if (!properties.algo().schedulerEnabled()) {
            log.info("Scanner auto-start skipped: scheduler is disabled");
            return;
        }
        if (properties.mode() == TradingMode.BACKTEST) {
            log.info("Scanner auto-start skipped: mode=BACKTEST");
            return;
        }
        if (tradingStateService.killSwitchEnabled()) {
            log.info("Scanner auto-start skipped: kill switch is enabled");
            return;
        }
        tradingStateService.start();
        log.info("Scanner auto-started after Kite access token was validated");
    }
}
