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
import java.time.Instant;
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

    private final LiveInstrumentCache liveInstrumentCache;
    private final LiveCandleBuilder candleBuilder;
    private final ApplicationEventPublisher eventPublisher;
    private final KiteAccessTokenStore tokenStore;
    private final KiteCredentialResolver credentialResolver;
    private final com.algo.trade.risk.MarketGuard marketGuard;
    private final com.algo.trade.notification.TelegramAlertService telegramAlertService;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS) // no timeout for WebSocket
            .build();
    private volatile ScheduledExecutorService reconnectExecutor = newReconnectExecutor();

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
    private final AtomicInteger reconnectDelay = new AtomicInteger(5);
    private List<Long> subscribedTokens = List.of();
    private volatile Instant lastTickTime = null;
    private volatile Instant lastConnectTime = null;
    private volatile Instant lastSubscribeTime = null;
    private volatile int subscribeCount = 0;
    private volatile int reconnectCount = 0;
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
                                com.algo.trade.notification.TelegramAlertService telegramAlertService) {
        this.liveInstrumentCache = liveInstrumentCache;
        this.candleBuilder = candleBuilder;
        this.eventPublisher = eventPublisher;
        this.tokenStore = tokenStore;
        this.credentialResolver = credentialResolver;
        this.marketGuard = marketGuard;
        this.telegramAlertService = telegramAlertService;
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
    }

    public void subscribe(List<Long> tokens) {
        this.subscribedTokens = List.copyOf(tokens);
        this.lastSubscribeTime = Instant.now();
        this.subscribeCount++;
        if (!connected) return;
        WebSocket ws = this.webSocket;
        if (ws != null) sendSubscribe(ws, tokens);
    }

    public void disconnect() {
        intentionalClose.set(true);
        reconnectExecutor.shutdownNow();
        WebSocket ws = this.webSocket;
        if (ws != null) ws.close(1000, "Shutdown");
        connected = false;
        log.info("[WS] Disconnected");
    }

    public boolean isConnected() { return connected; }
    public int getSubscribedTokenCount() { return subscribedTokens.size(); }
    public List<Long> getSubscribedTokens() { return subscribedTokens; }
    public Instant getLastTickTime() { return lastTickTime; }
    public Instant getLastConnectTime() { return lastConnectTime; }
    public Instant getLastSubscribeTime() { return lastSubscribeTime; }
    public int getSubscribeCount() { return subscribeCount; }
    public int getReconnectCount() { return reconnectCount; }

    // ── Internal ──────────────────────────────────────────────────────────────

    private void doConnect() {
        if (tokenStore.accessToken().isEmpty()) return;
        // Get apiKey from KiteCredentialResolver via tokenStore context
        // We use the stored access token directly
        String accessToken = tokenStore.accessToken().orElseThrow();
        // apiKey is embedded in the token store context — read from properties via tokenStore
        // For simplicity, we extract it from the Authorization header pattern
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
            alertExecutor.execute(() -> telegramAlertService.systemAlert("\uD83D\uDD34 WebSocket disconnected (failure): " + t.getMessage()));
            scheduleReconnect();
        }

        @Override
        public void onClosed(WebSocket ws, int code, String reason) {
            connected = false;
            log.info("[WS] Closed: {} {}", code, reason);
            if (!intentionalClose.get()) {
                alertExecutor.execute(() -> telegramAlertService.systemAlert("\uD83D\uDFE1 WebSocket closed unexpectedly (code=" + code + ") — reconnecting"));
                scheduleReconnect();
            } else {
                alertExecutor.execute(() -> telegramAlertService.systemAlert("\u26AA WebSocket disconnected"));
            }
        }
    }

    // ── Binary tick parser ────────────────────────────────────────────────────

    private void parseBinaryTicks(byte[] data) {
        if (data.length < 2) return;
        lastTickTime = Instant.now();
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
                pb.getInt(); // oi_day_high (skip)
                oi = pb.getInt() & 0xFFFFFFFFL;
                pb.getInt(); // oi_day_high
                pb.getInt(); // oi_day_low
                pb.getInt(); // exchange_timestamp
                // Best bid (first of 5 bid levels)
                bidQty  = pb.getInt() & 0xFFFFFFFFL;
                bestBid = pb.getInt() / 100.0;
                pb.getShort(); // orders
                for (int d = 1; d < 5; d++) { pb.getInt(); pb.getInt(); pb.getShort(); }
                // Best ask
                askQty  = pb.getInt() & 0xFFFFFFFFL;
                bestAsk = pb.getInt() / 100.0;
                pb.getShort();
                for (int d = 1; d < 5; d++) { pb.getInt(); pb.getInt(); pb.getShort(); }
            }

            Instant now = Instant.now();

            // Route India VIX to MarketGuard and candle builder (needed for detectVixTrend)
            if (token == INDIA_VIX_TOKEN) {
                marketGuard.updateVix(ltp);
                candleBuilder.onTick(token, ltp, 0, 0, now);
                log.debug("[WS] VIX tick: {}", ltp);
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
