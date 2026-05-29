package com.algo.trade.broker.zerodha;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.LiveInstrumentCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import okhttp3.*;
import okio.ByteString;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Kite WebSocket client — receives live tick stream and order updates.
 *
 * On each tick:
 *   1. Updates LiveInstrumentCache (latest price/OI/depth/Greeks per token)
 *   2. Feeds LiveCandleBuilder (aggregates ticks → OHLCV candles)
 *   3. LiveCandleBuilder publishes CandleClosedEvent → strategies react
 *
 * This replaces the 60-second REST quote poll for real-time price data.
 * REST is still used for: historical candles (startup), order placement, instruments.
 *
 * Binary packet format:
 *   https://kite.trade/docs/connect/v3/websocket/#message-structure
 *
 * India VIX token: 264969 — subscribe to get live VIX for MarketGuard.
 */
@Component
public class KiteWebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(KiteWebSocketClient.class);
    private static final long INDIA_VIX_TOKEN = 264969L;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    /** How often the zombie heartbeat runs (seconds). */
    private static final int HEARTBEAT_INTERVAL_SEC = 5;
    /** If no tick received for this many seconds during market hours, force-reconnect. */
    private static final int ZOMBIE_THRESHOLD_SEC = 30;
    /** Max consecutive zombie reconnects before backing off to avoid thrashing. */
    private static final int MAX_ZOMBIE_RECONNECTS_BEFORE_BACKOFF = 5;
    /** Backoff period after too many zombie reconnects (minutes). */
    private static final int ZOMBIE_BACKOFF_MINUTES = 5;
    /**
     * Bug #4 fix (29 May 2026): when this many zombie reconnects accumulate
     * without sustained recovery, recreate the OkHttpClient itself. Re-login
     * is NOT an option mid-day (Kite OAuth requires manual TOTP), but a fresh
     * HTTP client with a fresh connection pool effectively achieves the same
     * thing — Kite's server-side session tracker treats it as a new client.
     * This is what JVM restart does and is why "only restart helps" today.
     */
    private static final int ZOMBIE_HTTP_CLIENT_RECREATE_THRESHOLD = 10;
    /** Extended backoff (minutes) after recreating the HTTP client — let Kite's session state age out. */
    private static final int EXTENDED_BACKOFF_MINUTES = 5;

    private final LiveInstrumentCache liveInstrumentCache;
    private final LiveCandleBuilder candleBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final KiteAccessTokenStore tokenStore;
    private final KiteCredentialResolver credentialResolver;
    private final com.algo.trade.risk.MarketGuard marketGuard;
    private final com.algo.trade.notification.TelegramAlertService telegramAlertService;
    private final com.algo.trade.marketdata.TickVolumeProfileService tickVolumeProfileService;
    
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.commodity.OilPriceTracker oilPriceTracker;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    /**
     * Bug #4 fix (29 May 2026): NOT final anymore. When Kite poisons a session
     * (rapid reconnects → backend marks the session as suspicious), fresh TCP
     * connects from the SAME OkHttpClient keep getting killed because they
     * reuse the same connection pool state. The only known fix without
     * re-login (which requires manual TOTP — not available mid-day) is to
     * destroy the OkHttpClient and create a fresh one. This is what a JVM
     * restart effectively does and explains why "only restart helps".
     */
    private volatile OkHttpClient httpClient = newOkHttpClient();
    private volatile ScheduledExecutorService reconnectExecutor = newReconnectExecutor();

    private static OkHttpClient newOkHttpClient() {
        return new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
                .build();
    }

    private static ScheduledExecutorService newReconnectExecutor() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kite-ws-reconnect");
            t.setDaemon(true);
            return t;
        });
    }

    private volatile WebSocket webSocket;
    private volatile boolean connected = false;
    private final AtomicBoolean intentionalClose = new AtomicBoolean(false);
    /** Suppresses onFailure reconnect when force-reconnect already scheduled one. */
    private final AtomicBoolean forceReconnectInProgress = new AtomicBoolean(false);
    private final AtomicInteger reconnectDelay = new AtomicInteger(5);
    private List<Long> subscribedTokens = List.of();
    private volatile Instant lastTickTime = null;
    private volatile Instant lastConnectTime = null;
    private volatile Instant lastSubscribeTime = null;
    private volatile int subscribeCount = 0;
    private volatile int reconnectCount = 0;
    private volatile int zombieReconnectCount = 0;
    private volatile Instant lastZombieReconnectTime = null;
    /**
     * Counts tick frames received since the last zombie reconnect. Bug #3 fix
     * (29 May 2026): the previous code reset {@link #zombieReconnectCount} to 0
     * on the FIRST tick after a reconnect. But Kite sometimes sends a single tick
     * burst right after subscribe and then nothing — that single tick was enough
     * to reset the backoff counter, so MAX_ZOMBIE_RECONNECTS_BEFORE_BACKOFF was
     * never reached and the strategy thrashed forever. We now require at least
     * {@link #SUSTAINED_TICKS_TO_RESET} consecutive ticks before declaring the
     * reconnect a real success.
     */
    private volatile int sustainedTicksSinceReconnect = 0;
    private static final int SUSTAINED_TICKS_TO_RESET = 100;
    private volatile ScheduledExecutorService heartbeatExecutor;
    private final java.util.concurrent.ExecutorService alertExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ws-telegram-alert");
                t.setDaemon(true);
                return t;
            });

    public KiteWebSocketClient(LiveInstrumentCache liveInstrumentCache,
                                LiveCandleBuilder candleBuilder,
                                ApplicationEventPublisher eventPublisher,
                                KiteAccessTokenStore tokenStore,
                                KiteCredentialResolver credentialResolver,
                                com.algo.trade.risk.MarketGuard marketGuard,
                                com.algo.trade.notification.TelegramAlertService telegramAlertService,
                                com.algo.trade.marketdata.TickVolumeProfileService tickVolumeProfileService) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.candleBuilder = candleBuilder;
        this.eventPublisher = eventPublisher;
        this.tokenStore = tokenStore;
        this.credentialResolver = credentialResolver;
        this.marketGuard = marketGuard;
        this.telegramAlertService = telegramAlertService;
        this.tickVolumeProfileService = tickVolumeProfileService;
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void connect() {
        if (tokenStore.accessToken().isEmpty()) {
            log.warn("[WS] Cannot connect: no access token");
            return;
        }
        intentionalClose.set(false);
        // Recreate executor if shut down by a previous disconnect()
        if (reconnectExecutor.isShutdown()) {
            reconnectExecutor = newReconnectExecutor();
        }
        doConnect();
        startHeartbeat();
    }

    public void subscribe(List<Long> tokens) {
        this.subscribedTokens = List.copyOf(tokens);
        this.lastSubscribeTime = Instant.now();
        this.subscribeCount++;
        if (!connected) return;
        WebSocket ws = this.webSocket;
        if (ws != null) sendSubscribe(ws, tokens);
    }

    /**
     * Append additional tokens to the live subscription without resending the existing ones.
     * Kite's WS protocol treats `subscribe` as additive, so we only send the delta to the socket
     * but keep `subscribedTokens` complete so reconnects restore everything.
     */
    public synchronized void addSubscriptions(List<Long> additionalTokens) {
        if (additionalTokens == null || additionalTokens.isEmpty()) return;
        var existing = new java.util.LinkedHashSet<>(this.subscribedTokens);
        var delta = additionalTokens.stream().filter(t -> t != null && t > 0 && !existing.contains(t)).toList();
        if (delta.isEmpty()) return;
        existing.addAll(delta);
        this.subscribedTokens = List.copyOf(existing);
        this.lastSubscribeTime = Instant.now();
        this.subscribeCount++;
        WebSocket ws = this.webSocket;
        if (connected && ws != null) sendSubscribe(ws, delta);
    }

    public void disconnect() {
        intentionalClose.set(true);
        stopHeartbeat();
        reconnectExecutor.shutdownNow();
        WebSocket ws = this.webSocket;
        if (ws != null) ws.close(1000, "Shutdown");
        connected = false;
        log.info("[WS] Disconnected");
    }

    /**
     * Force-reconnect: tears down the existing WebSocket and creates a fresh connection.
     * Called by the zombie heartbeat when ticks stop flowing, or externally by SchedulerWatchdog.
     * Safe to call from any thread — idempotent within a short window.
     */
    public void forceReconnect(String reason) {
        if (intentionalClose.get()) return;
        // Prevent concurrent force-reconnects and suppress onFailure's scheduleReconnect
        if (!forceReconnectInProgress.compareAndSet(false, true)) {
            log.debug("[WS] Force-reconnect already in progress — skipping: {}", reason);
            return;
        }
        log.warn("[WS] Force-reconnect triggered: {}", reason);
        zombieReconnectCount++;
        lastZombieReconnectTime = Instant.now();
        // Bug #3 fix: reset sustained-tick counter so each reconnect attempt
        // must independently prove the connection is healthy.
        sustainedTicksSinceReconnect = 0;

        // Bug #4 fix (29 May 2026): persistent zombies indicate Kite has poisoned
        // the session — fresh TCP connects from the SAME OkHttpClient keep being
        // killed. Re-login isn't available mid-day (TOTP required). The only known
        // automatic recovery is to recreate the OkHttpClient itself, dropping all
        // connection-pool state and HTTP/2 session cookies. This is effectively
        // what a JVM restart does, which is why "only restart helps" today.
        // We recreate the client + back off for an extended period to let Kite's
        // server-side state clear before the next attempt.
        if (zombieReconnectCount >= ZOMBIE_HTTP_CLIENT_RECREATE_THRESHOLD) {
            log.error("[WS] {} consecutive zombie reconnects — recreating OkHttpClient "
                    + "to drop poisoned session state (mimicking restart behaviour)",
                    zombieReconnectCount);
            OkHttpClient oldClient = this.httpClient;
            try {
                // Build fresh client first so the new connect uses it
                this.httpClient = newOkHttpClient();
                // Tear down the old client's connection pool + dispatcher
                if (oldClient != null) {
                    try {
                        oldClient.dispatcher().executorService().shutdown();
                        oldClient.connectionPool().evictAll();
                    } catch (Exception evictEx) {
                        log.debug("[WS] Old client teardown ignored: {}", evictEx.getMessage());
                    }
                }
                log.info("[WS] OkHttpClient recreated — fresh connection pool ready");
            } catch (Exception ex) {
                log.error("[WS] OkHttpClient recreation failed: {}", ex.getMessage(), ex);
            }
            alertExecutor.execute(() -> {
                telegramAlertService.systemAlert(
                        "🔄 WebSocket session reset: " + zombieReconnectCount
                        + " consecutive zombies. Recreated HTTP client to drop poisoned "
                        + "session state. Backing off " + EXTENDED_BACKOFF_MINUTES
                        + " min before next attempt.");
                if (errorEventService != null) {
                    errorEventService.high("WebSocket",
                            "Session reset after " + zombieReconnectCount
                            + " zombies — fresh HTTP client created", null);
                }
            });
            // Reset zombie counter — the recreated client is a fresh start
            zombieReconnectCount = 0;
            sustainedTicksSinceReconnect = 0;
            // Extended backoff: 5 min before next connect attempt. Gives Kite's
            // server-side session tracker time to age out any rate-limit state.
            if (reconnectExecutor.isShutdown()) {
                reconnectExecutor = newReconnectExecutor();
            }
            reconnectExecutor.schedule(() -> {
                log.info("[WS] Extended backoff over — attempting connect with fresh client");
                forceReconnectInProgress.set(false);
                doConnect();
            }, EXTENDED_BACKOFF_MINUTES, TimeUnit.MINUTES);
            return;
        }

        // Tear down existing socket — onFailure will fire but won't schedule a second reconnect
        WebSocket ws = this.webSocket;
        if (ws != null) {
            try {
                ws.cancel(); // hard close — don't wait for graceful shutdown
            } catch (Exception e) {
                log.debug("[WS] Error cancelling old socket: {}", e.getMessage());
            }
        }
        connected = false;

        // Small delay before reconnecting to let the old socket clean up
        if (reconnectExecutor.isShutdown()) {
            reconnectExecutor = newReconnectExecutor();
        }
        reconnectDelay.set(3); // short delay for zombie recovery
        reconnectExecutor.schedule(() -> {
            log.info("[WS] Zombie recovery: reconnecting...");
            reconnectCount++;
            // Fix (29 May 2026 — Bug #1): do NOT clear forceReconnectInProgress here.
            // The OLD socket's onFailure callback fires AFTER ws.cancel() above —
            // often a few hundred ms after we get into this scheduled task.
            // If we cleared the flag here, that onFailure would see flag=false and
            // call scheduleReconnect() → DUPLICATE connection (3 connections in 10s).
            // The flag is now cleared in onOpen() of the new socket — by then,
            // the OLD socket's onFailure has already been suppressed correctly.
            doConnect();
        }, 3, TimeUnit.SECONDS);

        alertExecutor.execute(() -> {
            telegramAlertService.systemAlert("🔄 WebSocket force-reconnect: " + reason
                    + " (zombie #" + zombieReconnectCount + ")");
            if (errorEventService != null) {
                errorEventService.high("WebSocket", "Force-reconnect: " + reason
                        + " (zombie #" + zombieReconnectCount + ")");
            }
        });
    }

    // ── Zombie Heartbeat ──────────────────────────────────────────────────────

    /**
     * Starts the zombie detection heartbeat. Runs every 30s during market hours.
     * If connected but no tick received for 60s, forces a reconnect.
     */
    private void startHeartbeat() {
        stopHeartbeat(); // ensure no duplicate
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "kite-ws-heartbeat");
            t.setDaemon(true);
            return t;
        });
        heartbeatExecutor.scheduleAtFixedRate(this::zombieCheck,
                HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("[WS] Zombie heartbeat started (interval={}s, threshold={}s)",
                HEARTBEAT_INTERVAL_SEC, ZOMBIE_THRESHOLD_SEC);
    }

    private void stopHeartbeat() {
        ScheduledExecutorService hb = this.heartbeatExecutor;
        if (hb != null && !hb.isShutdown()) {
            hb.shutdownNow();
        }
    }

    /**
     * Core zombie detection logic. Runs every 30 seconds.
     * During market hours, if we're "connected" but haven't received a tick
     * in ZOMBIE_THRESHOLD_SEC seconds, force-reconnect.
     */
    private void zombieCheck() {
        try {
            if (intentionalClose.get()) return;
            if (!connected) return;

            // Only check during market hours (9:15 - 15:30 IST)
            LocalTime now = LocalTime.now(IST);
            if (now.isBefore(LocalTime.of(9, 16)) || now.isAfter(LocalTime.of(15, 29))) return;

            Instant lastTick = this.lastTickTime;
            if (lastTick == null) {
                // No tick ever received — if connected for > 30s, that's a problem
                Instant connectTime = this.lastConnectTime;
                if (connectTime != null && Duration.between(connectTime, Instant.now()).getSeconds() > ZOMBIE_THRESHOLD_SEC) {
                    forceReconnect("No ticks ever received after " + Duration.between(connectTime, Instant.now()).getSeconds() + "s");
                }
                return;
            }

            long tickAgeSec = Duration.between(lastTick, Instant.now()).getSeconds();
            if (tickAgeSec < ZOMBIE_THRESHOLD_SEC) return; // healthy

            // Zombie detected — but check backoff to prevent thrashing
            if (zombieReconnectCount >= MAX_ZOMBIE_RECONNECTS_BEFORE_BACKOFF && lastZombieReconnectTime != null) {
                long sinceLastZombie = Duration.between(lastZombieReconnectTime, Instant.now()).toMinutes();
                if (sinceLastZombie < ZOMBIE_BACKOFF_MINUTES) {
                    log.warn("[WS] Zombie detected ({}s stale) but in backoff period ({}/{} reconnects, {}min since last). "
                                    + "Will retry after {}min backoff.",
                            tickAgeSec, zombieReconnectCount, MAX_ZOMBIE_RECONNECTS_BEFORE_BACKOFF,
                            sinceLastZombie, ZOMBIE_BACKOFF_MINUTES);
                    return;
                }
                // Backoff period passed — reset counter and try again
                log.info("[WS] Zombie backoff period passed — resetting counter and retrying");
                zombieReconnectCount = 0;
            }

            forceReconnect("No ticks for " + tickAgeSec + "s (zombie connection)");

        } catch (Exception e) {
            log.debug("[WS] Heartbeat check error: {}", e.getMessage());
        }
    }

    public boolean isConnected() { return connected; }
    public int getSubscribedTokenCount() { return subscribedTokens.size(); }
    public List<Long> getSubscribedTokens() { return subscribedTokens; }
    public Instant getLastTickTime() { return lastTickTime; }
    public Instant getLastConnectTime() { return lastConnectTime; }
    public Instant getLastSubscribeTime() { return lastSubscribeTime; }
    public int getSubscribeCount() { return subscribeCount; }
    public int getReconnectCount() { return reconnectCount; }
    public int getZombieReconnectCount() { return zombieReconnectCount; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void doConnect() {
        // Single atomic read — prevents race between isEmpty() check and orElseThrow()
        java.util.Optional<String> tokenOpt = tokenStore.accessToken();
        if (tokenOpt.isEmpty()) {
            log.warn("[WS] Cannot connect: no access token");
            return;
        }
        String accessToken = tokenOpt.get();
        String url = "wss://ws.kite.trade?api_key=" + getApiKey() + "&access_token=" + accessToken;
        Request req = new Request.Builder().url(url).build();
        webSocket = httpClient.newWebSocket(req, new TickListener());
        log.info("[WS] Connecting to Kite WebSocket...");
    }

    private String getApiKey() {
        return credentialResolver.apiKey();
    }

    private void sendSubscribe(WebSocket ws, List<Long> tokens) {
        if (tokens.isEmpty()) return;
        ws.send("{\"a\":\"subscribe\",\"v\":" + tokens + "}");
        ws.send("{\"a\":\"mode\",\"v\":[\"full\"," + tokens + "]}");
        log.info("[WS] Subscribed to {} instruments in full mode", tokens.size());
    }

    private void scheduleReconnect() {
        if (intentionalClose.get()) return;
        // Recreate executor if it was shut down by a previous disconnect()
        if (reconnectExecutor.isShutdown()) {
            reconnectExecutor = newReconnectExecutor();
        }
        int delay = reconnectDelay.get();
        reconnectExecutor.schedule(() -> {
            log.info("[WS] Reconnecting (delay={}s)...", delay);
            reconnectCount++;
            doConnect();
            reconnectDelay.set(Math.min(delay * 2, 60));
        }, delay, TimeUnit.SECONDS);
    }

    // ── WebSocket listener ────────────────────────────────────────────────────

    private class TickListener extends WebSocketListener {

        @Override
        public void onOpen(WebSocket ws, Response response) {
            connected = true;
            reconnectDelay.set(5);
            lastConnectTime = Instant.now();
            // Fix (29 May 2026 \u2014 Bug #2): set lastTickTime = null, NOT Instant.now().
            // The previous code set it to "now" on connect, which faked a healthy state
            // for 30s after every reconnect even if zero real ticks arrived. Combined
            // with Bug #3 (counter reset on any single tick burst), that masked the
            // dead connection and prevented backoff from triggering.
            // Setting it to null routes the heartbeat through the "no tick ever
            // received" branch in zombieCheck(), which uses lastConnectTime instead.
            // That branch correctly detects "30s elapsed without a tick" without the
            // false-positive caused by stale lastTickTime from a previous session.
            lastTickTime = null;
            // Fix (29 May 2026 \u2014 Bug #1 follow-up): clear forceReconnectInProgress here,
            // after the new socket is genuinely open. By now any OLD onFailure has fired
            // and been correctly suppressed.
            forceReconnectInProgress.set(false);
            candleBuilder.clearOpenCandles();
            tickVolumeProfileService.reset(); // cumulative volumes restart after reconnect
            log.info("[WS] Connected to Kite WebSocket");
            if (!subscribedTokens.isEmpty()) {
                sendSubscribe(ws, subscribedTokens);
                lastSubscribeTime = Instant.now();
                subscribeCount++;
                log.info("[WS] Resubscribed to {} tokens after connect", subscribedTokens.size());
            }
            alertExecutor.execute(() -> telegramAlertService.systemAlert("\uD83D\uDFE2 WebSocket connected to Kite"));
        }

        @Override
        public void onMessage(WebSocket ws, ByteString bytes) {
            parseBinaryTicks(bytes.toByteArray());
        }

        @Override
        public void onMessage(WebSocket ws, String text) {
            try {
                com.fasterxml.jackson.databind.JsonNode root =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
                String type = root.path("type").asText("");
                if ("order_update".equals(type)) {
                    com.fasterxml.jackson.databind.JsonNode data = root.path("data");
                    String orderId  = data.path("order_id").asText("");
                    String status   = data.path("status").asText("");
                    String symbol   = data.path("tradingsymbol").asText("");
                    int filledQty   = data.path("filled_quantity").asInt(0);
                    double avgPrice = data.path("average_price").asDouble(0);
                    String msg      = data.path("status_message").asText("");
                    log.info("[WS] Order update: orderId={} status={} symbol={} filledQty={} avgPrice={} msg={}",
                            orderId, status, symbol, filledQty, avgPrice, msg);
                    alertExecutor.execute(() -> telegramAlertService.systemAlert(
                            String.format("📋 Order Update: %s | %s | Filled: %d @ ₹%.2f%s",
                                    symbol, status, filledQty, avgPrice,
                                    msg.isBlank() ? "" : " | " + msg)));
                    // Trigger immediate position sync on any COMPLETE order
                    // This catches manual closes from the broker app within seconds
                    if ("COMPLETE".equalsIgnoreCase(status)) {
                        alertExecutor.execute(() -> eventPublisher.publishEvent(
                                new com.algo.trade.domain.OrderCompletedEvent(orderId, symbol, status, filledQty, avgPrice)));
                    }
                } else {
                    log.debug("[WS] Text frame type={}: {}", type, text);
                }
            } catch (Exception e) {
                log.debug("[WS] Text frame parse failed: {}", e.getMessage());
            }
        }

        @Override
        public void onFailure(WebSocket ws, Throwable t, Response response) {
            connected = false;
            log.error("[WS] Failure: {}", t.getMessage());
            alertExecutor.execute(() -> {
                telegramAlertService.systemAlert("\uD83D\uDD34 WebSocket disconnected (failure): " + t.getMessage());
                if (errorEventService != null) errorEventService.critical("WebSocket", "WebSocket failure: " + t.getMessage(), t);
            });
            // Skip reconnect if forceReconnect() already scheduled one
            if (forceReconnectInProgress.get()) {
                log.debug("[WS] onFailure: skipping scheduleReconnect — force-reconnect in progress");
                return;
            }
            scheduleReconnect();
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            connected = false;
            log.info("[WS] Closed: {} {}", code, reason);
            if (!intentionalClose.get() && !forceReconnectInProgress.get()) {
                alertExecutor.execute(() -> telegramAlertService.systemAlert("\uD83D\uDFE1 WebSocket closed unexpectedly (code=" + code + ") — reconnecting"));
                scheduleReconnect();
            } else if (intentionalClose.get()) {
                alertExecutor.execute(() -> telegramAlertService.systemAlert("\u26AA WebSocket disconnected"));
            }
            // else: forceReconnect in progress — it handles reconnection
        }
    }

    // ── Binary tick parser ────────────────────────────────────────────────────

    private void parseBinaryTicks(byte[] data) {
        if (data.length < 2) return;
        lastTickTime = Instant.now();
        // Bug #3 fix (29 May 2026): require SUSTAINED ticks before declaring
        // the reconnect a real success. Kite sometimes sends a single tick burst
        // after subscribe and then nothing — previously that one tick reset
        // zombieReconnectCount to 0, so MAX_ZOMBIE_RECONNECTS_BEFORE_BACKOFF
        // was never reached and the strategy thrashed forever.
        if (zombieReconnectCount > 0) {
            sustainedTicksSinceReconnect++;
            if (sustainedTicksSinceReconnect >= SUSTAINED_TICKS_TO_RESET) {
                log.info("[WS] Sustained ticks ({}) after {} zombie reconnect(s) — resetting counter",
                        sustainedTicksSinceReconnect, zombieReconnectCount);
                zombieReconnectCount = 0;
                sustainedTicksSinceReconnect = 0;
            }
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        int numPackets = buf.getShort();

        for (int i = 0; i < numPackets; i++) {
            if (buf.remaining() < 2) break;
            int len = buf.getShort();
            if (buf.remaining() < len) break;
            byte[] packet = new byte[len];
            buf.get(packet);
            parsePacket(packet, len);
        }
    }

    private void parsePacket(byte[] packet, int len) {
        try {
            if (len < 8) {
                // LTP packets are minimum 8 bytes (4 token + 4 ltp).
                // Shorter packets are heartbeats or subscription confirmations — skip silently.
                return;
            }
            ByteBuffer pb = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);
            long token = pb.getInt() & 0xFFFFFFFFL;
            double ltp = pb.getInt() / 100.0;

            double open = 0, high = 0, low = 0, close = 0;
            long volume = 0, oi = 0;
            double bestBid = 0, bestAsk = 0;
            long bidQty = 0, askQty = 0;

            if (len >= 44) {
                pb.getInt(); // last_qty
                pb.getInt(); // avg_price
                volume = pb.getInt() & 0xFFFFFFFFL;
                pb.getInt(); // buy_qty
                pb.getInt(); // sell_qty
                open  = pb.getInt() / 100.0;
                high  = pb.getInt() / 100.0;
                low   = pb.getInt() / 100.0;
                close = pb.getInt() / 100.0;
            }

            if (len >= 184) {
                pb.getInt(); // last_trade_time
                oi = pb.getInt() & 0xFFFFFFFFL; // OI
                pb.getInt(); // oi_day_high
                pb.getInt(); // oi_day_low
                pb.getInt(); // exchange_timestamp
                // Best bid (first of 5 bid levels)
                bidQty  = pb.getInt() & 0xFFFFFFFFL;
                bestBid = pb.getInt() / 100.0;
                pb.getShort(); // orders
                pb.getShort(); // padding (2 bytes per depth level)
                for (int d = 1; d < 5; d++) { pb.getInt(); pb.getInt(); pb.getShort(); pb.getShort(); }
                // Best ask
                askQty  = pb.getInt() & 0xFFFFFFFFL;
                bestAsk = pb.getInt() / 100.0;
                pb.getShort(); // orders
                pb.getShort(); // padding
                for (int d = 1; d < 5; d++) { pb.getInt(); pb.getInt(); pb.getShort(); pb.getShort(); }
            }

            Instant now = Instant.now();

            // Route India VIX to MarketGuard and candle builder (needed for detectVixTrend)
            if (token == INDIA_VIX_TOKEN) {
                marketGuard.updateVix(ltp);
                candleBuilder.onTick(token, ltp, 0, 0, now);
                //log.debug("[WS] VIX tick: {}", ltp);
                return;
            }
            
            // Route MCX Crude Oil to OilPriceTracker
            if (oilPriceTracker != null && token == oilPriceTracker.getInstrumentToken()) {
                oilPriceTracker.updatePrice(ltp);
                log.debug("[WS] MCX Crude Oil tick: ltp={}", ltp);
                return;
            }

            // Route index spot tokens to LiveInstrumentCache for ATM price
            for (IndexType idx : IndexType.values()) {
                if (token == idx.spotToken()) {
                    liveInstrumentCache.updateFuturesPrice(idx, ltp);
                    // Feed circuit breaker: open price from packet, current = ltp
                    if (open > 0) {
                        marketGuard.updateIndexPrice(open, ltp);
                    }
                    // Feed tick-based VPVR — volume from Kite is cumulative daily, use as-is
                    // (TickVolumeProfileService handles delta internally via bucket accumulation)
                    if (volume > 0) {
                        tickVolumeProfileService.onTick(token, ltp, volume);
                    }
                    // Also feed to candle builder for underlying candles
                    candleBuilder.onTick(token, ltp, volume, oi, now);
                    return;
                }
            }

            // Option tick — update live data and feed candle builder
            liveInstrumentCache.updateOptionMarketData(token, ltp, volume, oi,
                    bestBid, bestAsk, bidQty, askQty);
            candleBuilder.onTick(token, ltp, volume, oi, now);

        } catch (Exception e) {
            log.debug("[WS] Failed to parse tick packet: {}", e.getMessage());
        }
    }
}
