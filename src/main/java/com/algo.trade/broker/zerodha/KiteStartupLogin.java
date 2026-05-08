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
 * Also maintains option WebSocket subscription as the underlying spot price moves.
 */
@Component
public class KiteStartupLogin implements ApplicationRunner, Ordered {

    private static final Logger log = LoggerFactory.getLogger(KiteStartupLogin.class);
    private static final int RESUBSCRIBE_STRIKE_THRESHOLD = 2; // re-subscribe when ATM drifts > 2 strikes

    private final java.util.Map<com.algo.trade.domain.IndexType, Integer> lastSubscribedAtm =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile boolean webSocketConnected = false;

    private final TradingProperties properties;
    private final KiteAccessTokenStore tokenStore;
    private final KiteAuthService kiteAuthService;
    private final TradingStateService tradingStateService;
    private final KiteWebSocketClient webSocketClient;
    private final com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache;
    private final com.algo.trade.marketdata.InstrumentCache instrumentCache;
    private final com.algo.trade.marketdata.MarketDataService marketDataService;
    private final com.algo.trade.marketdata.LiveCandleBuilder candleBuilder;
    private final com.algo.trade.persistence.TradeRepository tradeRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    /** Debounce: minimum 30s between resubscriptions to prevent thrashing. */
    private volatile long lastResubscribeTimeMs = 0;
    private static final long RESUBSCRIBE_DEBOUNCE_MS = 30_000;

    public KiteStartupLogin(
            TradingProperties properties,
            KiteAccessTokenStore tokenStore,
            KiteAuthService kiteAuthService,
            TradingStateService tradingStateService,
            KiteWebSocketClient webSocketClient,
            com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache,
            com.algo.trade.marketdata.InstrumentCache instrumentCache,
            com.algo.trade.marketdata.MarketDataService marketDataService,
            com.algo.trade.marketdata.LiveCandleBuilder candleBuilder,
            com.algo.trade.persistence.TradeRepository tradeRepository
    ) {
        this.properties = properties;
        this.tokenStore = tokenStore;
        this.kiteAuthService = kiteAuthService;
        this.tradingStateService = tradingStateService;
        this.webSocketClient = webSocketClient;
        this.liveInstrumentCache = liveInstrumentCache;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.candleBuilder = candleBuilder;
        this.tradeRepository = tradeRepository;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }

    @jakarta.annotation.PostConstruct
    void registerScheduler() {
        if (schedulerRegistry != null) schedulerRegistry.register("atmResubscribe", "WebSocket option resubscription on ATM drift (5min)", 300_000, this::resubscribeIfAtmMoved);
    }

    /**
     * Event-driven ATM drift check — fires on every 1-min candle close.
     * If spot has moved more than RESUBSCRIBE_STRIKE_THRESHOLD strikes from last subscription,
     * triggers immediate resubscription (with 30s debounce).
     */
    @org.springframework.context.event.EventListener
    public void onCandleCloseAtmCheck(com.algo.trade.domain.CandleClosedEvent event) {
        try {
            if (event.timeframe() != com.algo.trade.domain.Timeframe.ONE_MINUTE) return;
            // Use live connection state — webSocketConnected stays true after startup and
            // doesn't reflect force-reconnect gaps, so check the actual socket state instead.
            if (!webSocketClient.isConnected()) return;
            long now = System.currentTimeMillis();
            if ((now - lastResubscribeTimeMs) < RESUBSCRIBE_DEBOUNCE_MS) return;

            // Quick check: has any underlying's ATM drifted beyond threshold?
            boolean needsResub = false;
            for (var underlying : tradingStateService.enabledUnderlyings()) {
                var indexType = com.algo.trade.domain.IndexType.from(underlying);
                double currentSpot = liveInstrumentCache.getFuturesPrice(indexType);
                if (currentSpot <= 0) continue;
                int currentAtm = indexType.roundToATM(currentSpot);
                Integer prevAtm = lastSubscribedAtm.get(indexType);
                if (prevAtm != null) {
                    int strikeDrift = Math.abs(currentAtm - prevAtm) / indexType.strikeInterval();
                    if (strikeDrift >= RESUBSCRIBE_STRIKE_THRESHOLD) {
                        needsResub = true;
                        break;
                    }
                }
            }
            if (needsResub) {
                lastResubscribeTimeMs = now;
                log.info("[ATM-Drift] Immediate resubscription triggered by candle close");
                resubscribeIfAtmMoved();
            }
        } catch (Exception e) {
            log.warn("[ATM-Drift] onCandleCloseAtmCheck threw — resubscription skipped: {}", e.getMessage(), e);
        }
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
            // Auth timed out (common on EC2 where no browser opens automatically).
            // Do NOT crash — stay alive so the operator can authenticate via the UI or
            // by setting KITE_ACCESS_TOKEN and calling /auth/kite/session.
            log.warn("[KiteStartup] Auth timed out or returned no session. "
                    + "App running in auth-pending mode — scanner will not auto-start. "
                    + "Authenticate via /advalgotrade/auth/kite or set KITE_ACCESS_TOKEN env var.");
            if (errorEventService != null) {
                errorEventService.high("KiteStartup",
                        "Kite login timed out — app is running but scanner is not started. "
                        + "Re-authenticate via the UI auth page.");
            }
            return;
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
            if (errorEventService != null) errorEventService.medium("KiteStartup", "LiveInstrumentCache population failed: " + e.getMessage());
        }

        // Fetch previous day's close for gap detection (GapAndGoStrategy)
        try {
            fetchPreviousDayClose();
        } catch (Exception e) {
            log.warn("Previous day close fetch failed: {}", e.getMessage());
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
                    // Populate instrument cache — refresh from Kite API if not yet loaded
                    var instruments = instrumentCache.all();
                    if (instruments.isEmpty()) {
                        log.info("Instrument cache empty on startup — refreshing from Kite API");
                        instruments = instrumentCache.refresh();
                    }
                    if (!instruments.isEmpty()) {
                        liveInstrumentCache.populate(instruments);
                        log.info("LiveInstrumentCache populated: {} instruments", instruments.size());
                    } else {
                        log.warn("Instrument cache still empty after refresh — option subscriptions will be skipped");
                        if (errorEventService != null) errorEventService.medium("KiteStartup", "Instrument cache empty after refresh — option subscriptions skipped");
                    }

                    // Wait up to 15s for spot ticks to arrive via WebSocket; retry REST fallback each attempt
                    var optionTokens = new java.util.ArrayList<Long>();
                    int attemptMs = 0;
                    while (optionTokens.isEmpty() && attemptMs <= 15000) {
                        Thread.sleep(2000);
                        attemptMs += 2000;
                        optionTokens.clear();
                        for (var underlying : tradingStateService.enabledUnderlyings()) {
                            var indexType = com.algo.trade.domain.IndexType.from(underlying);
                            var expiry = instrumentCache.nearestExpiry(underlying, java.time.LocalDate.now(properties.timezone()))
                                    .orElseGet(() -> new com.algo.trade.marketdata.ExpiryCalendar().getCurrentWeeklyExpiry(indexType));
                            var subscriptionTokens = liveInstrumentCache.getSubscriptionTokens(indexType, expiry, 10);
                            if (subscriptionTokens.isEmpty()) {
                                // Spot not arrived via WS yet — try REST quote
                                try {
                                    String spotKey = properties.symbols().spotQuoteKeys().get(underlying);
                                    var quote = marketDataService.quote(spotKey);
                                    quote.ifPresent(q -> liveInstrumentCache.updateFuturesPrice(indexType, q.lastPrice().doubleValue()));
                                } catch (Exception ignored) {}
                                subscriptionTokens = liveInstrumentCache.getSubscriptionTokens(indexType, expiry, 10);
                            }
                            optionTokens.addAll(subscriptionTokens);
                            double subSpot = liveInstrumentCache.getFuturesPrice(indexType);
                            int subAtm = subSpot > 0 ? indexType.roundToATM(subSpot) : 0;
                            int interval = indexType.strikeInterval();
                            log.info("Option subscription attempt {}ms for {}: spot={} atm={} range=[{},{}] tokens={}",
                                    attemptMs, underlying, subSpot, subAtm,
                                    subAtm - 10 * interval, subAtm + 10 * interval,
                                    subscriptionTokens.size());
                            if (subAtm > 0) lastSubscribedAtm.put(indexType, subAtm);
                        }
                    }

                    if (!optionTokens.isEmpty()) {
                        var allTokens = new java.util.ArrayList<>(tokens);
                        allTokens.addAll(optionTokens);
                        // Include tokens for open trades so exit monitors get live quotes from startup
                        allTokens.addAll(openTradeTokens());
                        webSocketClient.subscribe(allTokens);
                        log.info("WebSocket subscribed with {} option tokens after {}ms", optionTokens.size(), attemptMs);
                    } else {
                        log.warn("Option token subscription failed after {}ms — resubscribeIfAtmMoved() will retry", attemptMs);
                        if (errorEventService != null) errorEventService.medium("KiteStartup", "Option token subscription failed after " + attemptMs + "ms — will retry");
                    }
                    webSocketConnected = true;
                    // Seed candle history from REST if app was restarted during market hours
                    if (isMarketHours()) {
                        seedCandleHistory(optionTokens);
                    }
                } catch (Exception e) {
                    log.warn("Option token subscription failed: {}", e.getMessage());
                    if (errorEventService != null) errorEventService.medium("KiteStartup", "Option token subscription failed: " + e.getMessage());
                    webSocketConnected = true; // allow scheduler to recover
                }
            }, "ws-option-subscribe").start();

        } catch (Exception e) {
            log.warn("WebSocket connection failed on startup (will use REST fallback): {}", e.getMessage());
            if (errorEventService != null) errorEventService.high("KiteStartup", "WebSocket connection failed on startup — using REST fallback: " + e.getMessage(), e);
        }
    }

    /**
     * Seeds candle history from REST historical API for subscribed option tokens, VIX, and underlying indices.
     * Called only during market hours on startup to recover in-memory state after a mid-session restart.
     * Seeds:
     *   - 5-minute candles for underlying spot indices (required by ScalpingStrategy EMA 9/21 warm-up)
     *   - 1-minute candles for options (used by DIRECTIONAL_BUY volume analysis)
     *   - 15-minute candles for VIX (used by detectVixTrend)
     * Limits to 40 option tokens max and throttles at ~2 REST calls/second to respect Zerodha rate limits.
     */
    private void seedCandleHistory(java.util.List<Long> optionTokens) {
        log.info("Market-hours restart detected — seeding candle history from REST for {} option tokens + underlyings + VIX",
                Math.min(optionTokens.size(), 40));

        // Seed VIX 15-minute candles for detectVixTrend()
        long vixToken = 264969L;
        seedTokenCandles(vixToken, "NSE:" + vixToken, com.algo.trade.domain.Timeframe.FIFTEEN_MINUTE);

        // Seed 5-minute candles for underlying spot indices — required by ScalpingStrategy (EMA 9/21 needs 22 candles).
        // Without this, SCALPING cannot fire for 110 minutes after every restart.
        // Uses numeric token format which Zerodha historical API requires; BSE for SENSEX, NSE for all others.
        for (com.algo.trade.domain.IndexType idx : com.algo.trade.domain.IndexType.values()) {
            long token = idx.spotToken();
            String exchange = idx.isBSE() ? "BSE" : "NSE";
            String instrumentKey = exchange + ":" + token;
            seedTokenCandles(token, instrumentKey, com.algo.trade.domain.Timeframe.FIVE_MINUTE);
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }

        // Seed 1-minute candles for up to 40 option tokens (ATM-nearest first)
        int seeded = 0;
        for (Long token : optionTokens) {
            if (seeded >= 40) break;
            var opt = liveInstrumentCache.getByToken(token);
            if (opt.isEmpty()) continue;
            String instrumentKey = opt.get().getExchange() + ":" + token;
            seedTokenCandles(token, instrumentKey, com.algo.trade.domain.Timeframe.ONE_MINUTE);
            seeded++;
            try { Thread.sleep(500); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        log.info("Candle history seeding complete: seeded {} option tokens + {} underlying 5m + VIX",
                seeded, com.algo.trade.domain.IndexType.values().length);
    }

    private void seedTokenCandles(long token, String instrumentKey, com.algo.trade.domain.Timeframe tf) {
        try {
            var candles = marketDataService.historicalCandles(instrumentKey, tf);
            if (!candles.isEmpty()) {
                candleBuilder.seedHistory(token, tf, candles);
                log.info("Seeded {} {} candles: token={} key={}", candles.size(), tf, token, instrumentKey);
            } else {
                log.debug("No REST historical candles available for seed: key={} tf={}", instrumentKey, tf);
            }
        } catch (Exception e) {
            log.debug("Candle seed failed for {}: {}", instrumentKey, e.getMessage());
        }
    }

    /**
     * Fetches previous trading day's closing price for each configured index.
     * Stores in LiveInstrumentCache for use by GapAndGoStrategy gap detection.
     * Uses FIVE_MINUTE candles from yesterday and takes the last candle's close.
     */
    private void fetchPreviousDayClose() {
        var ist = java.time.ZoneId.of("Asia/Kolkata");
        java.time.LocalDate today = java.time.LocalDate.now(ist);
        // Go back to find the previous trading day (skip weekends)
        java.time.LocalDate prevDay = today.minusDays(1);
        while (prevDay.getDayOfWeek() == java.time.DayOfWeek.SATURDAY
                || prevDay.getDayOfWeek() == java.time.DayOfWeek.SUNDAY) {
            prevDay = prevDay.minusDays(1);
        }
        java.time.Instant from = prevDay.atTime(9, 15).atZone(ist).toInstant();
        java.time.Instant to = prevDay.atTime(15, 30).atZone(ist).toInstant();

        for (com.algo.trade.domain.IndexType idx : com.algo.trade.domain.IndexType.values()) {
            try {
                com.algo.trade.domain.UnderlyingSymbol symbol = com.algo.trade.domain.UnderlyingSymbol.valueOf(idx.name());
                String spotKey = properties.symbols().spotHistoricalKeys().get(symbol);
                if (spotKey == null) continue;

                var request = new com.algo.trade.domain.HistoricalDataRequest(
                        spotKey, from, to, com.algo.trade.domain.Timeframe.FIVE_MINUTE, false);
                var candles = marketDataService.historicalCandles(request);
                if (!candles.isEmpty()) {
                    double prevClose = candles.getLast().close().doubleValue();
                    liveInstrumentCache.setPreviousDayClose(idx, prevClose);
                } else {
                    log.debug("No previous day candles for {}: key={}", idx, spotKey);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch previous day close for {}: {}", idx, e.getMessage());
            }
        }
    }

    private boolean isMarketHours() {
        var ist = java.time.ZoneId.of("Asia/Kolkata");
        var now = java.time.LocalTime.now(ist);
        return now.isAfter(java.time.LocalTime.of(9, 15)) && now.isBefore(java.time.LocalTime.of(15, 30));
    }

    /**
     * Re-subscribes option tokens when the underlying spot price moves more than RESUBSCRIBE_STRIKE_THRESHOLD
     * strikes from the ATM used at startup. Runs every 5 minutes during market hours.
     * Ensures that selected options remain within the WebSocket subscription window as NIFTY/BANKNIFTY move.
     */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 5 * 60 * 1000)
    public void resubscribeIfAtmMoved() {
        if (schedulerRegistry != null && !schedulerRegistry.isEnabled("atmResubscribe")) return;
        if (!webSocketConnected) return;

        var baseTokens = new java.util.ArrayList<Long>();
        baseTokens.add(264969L); // India VIX
        for (var idx : com.algo.trade.domain.IndexType.values()) {
            baseTokens.add(idx.spotToken());
        }

        var newOptionTokens = new java.util.ArrayList<Long>();
        // anyMoved=true also when lastSubscribedAtm is empty (startup subscription failed — recover now)
        boolean anyMoved = lastSubscribedAtm.isEmpty();

        for (var underlying : tradingStateService.enabledUnderlyings()) {
            var indexType = com.algo.trade.domain.IndexType.from(underlying);
            double currentSpot = liveInstrumentCache.getFuturesPrice(indexType);
            if (currentSpot <= 0) continue;

            int currentAtm = indexType.roundToATM(currentSpot);
            Integer prevAtm = lastSubscribedAtm.get(indexType);

            if (prevAtm == null) {
                // No previous subscription recorded — subscribe fresh
                anyMoved = true;
                lastSubscribedAtm.put(indexType, currentAtm);
                log.info("ATM first subscription for {} atm={} — subscribing options", underlying, currentAtm);
            } else {
                int strikeDrift = Math.abs(currentAtm - prevAtm) / indexType.strikeInterval();
                if (strikeDrift >= RESUBSCRIBE_STRIKE_THRESHOLD) {
                    anyMoved = true;
                    log.info("ATM moved {} strikes for {} (prev={} curr={}) — re-subscribing options",
                            strikeDrift, underlying, prevAtm, currentAtm);
                    lastSubscribedAtm.put(indexType, currentAtm);
                }
            }

            try {
                var expiry = instrumentCache.nearestExpiry(underlying, java.time.LocalDate.now(properties.timezone()))
                        .orElseGet(() -> new com.algo.trade.marketdata.ExpiryCalendar().getCurrentWeeklyExpiry(indexType));
                var tokens = liveInstrumentCache.getSubscriptionTokens(indexType, expiry, 10);
                newOptionTokens.addAll(tokens);
            } catch (Exception e) {
                log.warn("Re-subscription token fetch failed for {}: {}", underlying, e.getMessage());
                if (errorEventService != null) errorEventService.medium("KiteStartup", "Re-subscription token fetch failed for " + underlying + ": " + e.getMessage());
            }
        }

        if (anyMoved && !newOptionTokens.isEmpty()) {
            var allTokens = new java.util.ArrayList<>(baseTokens);
            allTokens.addAll(newOptionTokens);
            // Always include tokens for open trade instruments so exit monitors get live quotes
            allTokens.addAll(openTradeTokens());
            try {
                webSocketClient.subscribe(allTokens);
                log.info("WebSocket re-subscribed: {} option tokens", newOptionTokens.size());
            } catch (Exception e) {
                log.warn("WebSocket re-subscription failed: {}", e.getMessage());
                if (errorEventService != null) errorEventService.medium("KiteStartup", "WebSocket re-subscription failed: " + e.getMessage());
            }
        }
        if (schedulerRegistry != null) schedulerRegistry.recordRun("atmResubscribe");
    }

    /**
     * Returns instrument tokens for all open trades so their quotes stay live
     * even if the strike drifts outside the ATM ± N subscription window.
     */
    private java.util.List<Long> openTradeTokens() {
        try {
            return tradeRepository.findByStatus(com.algo.trade.domain.TradeStatus.OPEN).stream()
                    .map(trade -> {
                        String key = trade.getInstrumentKey();
                        if (key == null || !key.contains(":")) return null;
                        String symbol = key.split(":", 2)[1];
                        return liveInstrumentCache.getBySymbol(symbol)
                                .map(o -> o.getInstrumentToken())
                                .orElse(null);
                    })
                    .filter(java.util.Objects::nonNull)
                    .distinct()
                    .toList();
        } catch (Exception e) {
            log.debug("Failed to resolve open trade tokens: {}", e.getMessage());
            return java.util.List.of();
        }
    }
}
