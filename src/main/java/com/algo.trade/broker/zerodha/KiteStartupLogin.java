package com.algo.trade.broker.zerodha;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.ExecutionMode;
import com.algo.trade.domain.MarketDataMode;
import com.algo.trade.domain.TradingMode;
import com.algo.trade.execution.TradingStateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/**
 * Blocks application startup in LIVE mode until Kite auth is ready.
 */
@Component
public class KiteStartupLogin implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(KiteStartupLogin.class);

    private final TradingProperties properties;
    private final KiteAccessTokenStore tokenStore;
    private final KiteAuthService kiteAuthService;
    private final TradingStateService tradingStateService;
    private final KiteWebSocketClient webSocketClient;
    private final com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.marketdata.InstrumentCache instrumentCache;
    private final com.algo.trade.marketdata.MarketDataService marketDataService;

    public KiteStartupLogin(
            TradingProperties properties,
            KiteAccessTokenStore tokenStore,
            KiteAuthService kiteAuthService,
            TradingStateService tradingStateService,
            KiteWebSocketClient webSocketClient,
            com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache,
            com.algo.trade.marketdata.InstrumentCache instrumentCache,
            com.algo.trade.marketdata.MarketDataService marketDataService
    ) {
        this.properties = properties;
        this.tokenStore = tokenStore;
        this.kiteAuthService = kiteAuthService;
        this.tradingStateService = tradingStateService;
        this.webSocketClient = webSocketClient;
        this.liveInstrumentCache = liveInstrumentCache;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
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
        if (!kiteAuthService.apiKeyConfigured()) {
            kiteAuthService.firstRunSetup();
            log.warn("Kite startup login skipped: API credentials are not configured. "
                    + "Use the UI Kite Auth page after adding credentials to data/trading-secrets.properties.");
            return;
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
        if (properties.mode() == TradingMode.BACKTEST) {
            log.info("Scanner auto-start skipped: mode=BACKTEST");
            return;
        }
        if (tradingStateService.killSwitchEnabled()) {
            log.info("Scanner auto-start skipped: kill switch is enabled");
            return;
        }

        // Populate LiveInstrumentCache from the instrument master
        try {
            var instruments = instrumentCache.all();
            if (!instruments.isEmpty()) {
                liveInstrumentCache.populate(instruments);
                log.info("LiveInstrumentCache populated: {} instruments", instruments.size());
            }
        } catch (Exception e) {
            log.warn("LiveInstrumentCache population failed: {}", e.getMessage());
        }

        // Always connect WebSocket for real-time tick data (primary trigger)
        connectWebSocket();

        // Start scanner if auto-start is configured
        if (!properties.algo().autoStartScannerAfterLogin()) {
            log.info("Scanner auto-start skipped: trading.algo.auto-start-scanner-after-login=false");
            return;
        }

        tradingStateService.start();
        log.info("Scanner auto-started after Kite access token was validated");
    }

    private void connectWebSocket() {
        try {
            webSocketClient.connect();
            log.info("Kite WebSocket connection initiated on startup");

            var tokens = new java.util.ArrayList<Long>();
            tokens.add(264969L); // India VIX
            // Always subscribe index spot tokens first
            for (var idx : com.algo.trade.domain.IndexType.values()) {
                tokens.add(idx.spotToken());
            }
            webSocketClient.subscribe(tokens);
            log.info("WebSocket subscribed to {} index + VIX tokens on startup", tokens.size());

            // Populate LiveInstrumentCache from instrument master
            // then subscribe option tokens after a short delay to allow spot price to arrive
            new Thread(() -> {
                try {
                    Thread.sleep(3000); // wait 3s for spot price ticks to arrive
                    var instruments = instrumentCache.all();
                    if (!instruments.isEmpty()) {
                        liveInstrumentCache.populate(instruments);
                        log.info("LiveInstrumentCache populated: {} instruments", instruments.size());
                    }
                    var optionTokens = new java.util.ArrayList<Long>();
                    for (var underlying : properties.symbols().underlyings()) {
                        var indexType = com.algo.trade.domain.IndexType.from(underlying);
                        var expiryCal = new com.algo.trade.marketdata.ExpiryCalendar();
                        var expiry = expiryCal.getCurrentWeeklyExpiry(indexType);
                        var subscriptionTokens = liveInstrumentCache.getSubscriptionTokens(indexType, expiry, 10);
                        if (subscriptionTokens.isEmpty()) {
                            // Fallback: subscribe ATM ± 10 strikes using spot price from REST
                            double spot = liveInstrumentCache.getFuturesPrice(indexType);
                            if (spot <= 0) {
                                // Try REST quote
                                try {
                                    String spotKey = properties.symbols().spotQuoteKeys().get(underlying);
                                    var quote = marketDataService.quote(spotKey);
                                    quote.ifPresent(q -> liveInstrumentCache.updateFuturesPrice(indexType, q.lastPrice().doubleValue()));
                                } catch (Exception ignored) {}
                            }
                            subscriptionTokens = liveInstrumentCache.getSubscriptionTokens(indexType, expiry, 10);
                        }
                        optionTokens.addAll(subscriptionTokens);
                        log.info("Option tokens for {}: {}", underlying, subscriptionTokens.size());
                    }
                    if (!optionTokens.isEmpty()) {
                        var allTokens = new java.util.ArrayList<>(tokens);
                        allTokens.addAll(optionTokens);
                        webSocketClient.subscribe(allTokens);
                        log.info("WebSocket re-subscribed with {} option tokens", optionTokens.size());
                    }
                } catch (Exception e) {
                    log.warn("Option token subscription failed: {}", e.getMessage());
                }
            }, "ws-option-subscribe").start();

        } catch (Exception e) {
            log.warn("WebSocket connection failed on startup (will use REST fallback): {}", e.getMessage());
        }
    }
}
