package com.algo.trade.broker.zerodha;

import com.algo.trade.auth.PrimaryAccountSelector;
import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.broker.PositionSynchronizer;
import com.algo.trade.multiuser.LocalBindSocketFactory;
import com.algo.trade.multiuser.UserContext;
import okhttp3.*;
import okio.ByteString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-user order-update WebSocket manager (2026-06-29).
 *
 * <p>The shared {@link KiteWebSocketClient} feed is authed with the PRIMARY account's token, so its
 * {@code order_update} frames only cover the primary's orders. Secondary users' fills were therefore
 * only detected by the {@link com.algo.trade.execution.OrderFillWatchdog} 2s poll (or the 60s
 * {@link PositionSynchronizer}). This manager closes that gap: for every NON-primary trading-enabled user
 * with a valid token, it opens a lightweight Kite WebSocket — that user's own api_key + access token, bound
 * to that user's whitelisted source IP — which subscribes to NOTHING (no instruments) and listens ONLY for
 * {@code order_update} text frames. On a COMPLETE fill it triggers an immediate {@link PositionSynchronizer}
 * reconcile in that user's context, mirroring the primary's instant path.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Order updates are sparse, so there is no tick-based zombie detection — just simple
 *       market-hours-aware reconnect backoff.</li>
 *   <li>Each secondary uses its OWN api_key WebSocket quota (Kite allows 3 per api_key), independent of the
 *       primary feed.</li>
 *   <li>A 60s reconcile loop opens sockets for newly-eligible users, reconnects on token change, and closes
 *       sockets for users who lost their token / disabled trading. Off-hours the token expires →
 *       {@code hasValidToken()} is false → the socket is closed, so there is no overnight churn.</li>
 *   <li>Gated by {@code trading.multiuser.per-user-order-ws.enabled} (default true) — an ops safety valve
 *       that can be flipped in the external {@code config/application-live.yml} without a redeploy.</li>
 * </ul>
 */
@Component
public class UserOrderWebSocketManager {

    private static final Logger log = LoggerFactory.getLogger(UserOrderWebSocketManager.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    @Value("${trading.multiuser.per-user-order-ws.enabled:true}")
    private boolean enabled;

    private final UserBrokerConfigRepository configRepository;
    private final PrimaryAccountSelector primaryAccountSelector;
    private final PositionSynchronizer positionSynchronizer;
    private final com.algo.trade.notification.TelegramAlertService telegramAlertService;

    @Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    /** Materialize filled entry orders immediately (SL/target/trail arming), not only on the 2s poll. */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.execution.OrderFillWatchdog orderFillWatchdog;

    /** One socket per non-primary user, keyed by userId. */
    private final ConcurrentHashMap<Long, UserOrderSocket> sockets = new ConcurrentHashMap<>();

    /** Shared scheduler for all per-user reconnects (reconnects are rare). */
    private final ScheduledExecutorService reconnectExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "user-order-ws");
                t.setDaemon(true);
                return t;
            });

    /** Async dispatch for Telegram/error alerts so a slow send never blocks a WS callback. */
    private final java.util.concurrent.ExecutorService alertExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "user-order-ws-alert");
                t.setDaemon(true);
                return t;
            });

    public UserOrderWebSocketManager(UserBrokerConfigRepository configRepository,
                                     PrimaryAccountSelector primaryAccountSelector,
                                     PositionSynchronizer positionSynchronizer,
                                     com.algo.trade.notification.TelegramAlertService telegramAlertService) {
        this.configRepository = configRepository;
        this.primaryAccountSelector = primaryAccountSelector;
        this.positionSynchronizer = positionSynchronizer;
        this.telegramAlertService = telegramAlertService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) {
            log.info("[UserOrderWS] disabled (trading.multiuser.per-user-order-ws.enabled=false)");
            return;
        }
        log.info("[UserOrderWS] enabled — per-user order-update sockets will be managed every 60s");
        reconcile();
    }

    /**
     * Open sockets for newly-eligible users, reconnect on token change, and close sockets for users who are
     * no longer eligible. Idempotent and cheap; the per-socket reconnect logic handles transient drops.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    public void reconcile() {
        try {
            if (!enabled) {
                if (!sockets.isEmpty()) closeAll("feature disabled");
                return;
            }
            Long primaryId = primaryAccountSelector != null
                    ? primaryAccountSelector.userId().orElse(null) : null;

            var active = configRepository.findByTradingEnabled(true);
            Set<Long> eligible = ConcurrentHashMap.newKeySet();
            if (active != null) {
                for (UserBrokerConfig cfg : active) {
                    Long uid = cfg.getUserId();
                    if (uid == null) continue;
                    if (primaryId != null && primaryId.equals(uid)) continue; // primary handled by KiteWebSocketClient
                    if (!cfg.hasValidToken()) continue;
                    String apiKey = cfg.getApiKey();
                    String token = cfg.getAccessToken();
                    if (apiKey == null || apiKey.isBlank() || token == null || token.isBlank()) continue;

                    eligible.add(uid);
                    UserOrderSocket existing = sockets.get(uid);
                    if (existing == null) {
                        open(uid, apiKey, token, cfg.getSourceIp());
                    } else if (!token.equals(existing.token)) {
                        log.info("[UserOrderWS] u:{} token changed — reconnecting", uid);
                        existing.close("token changed");
                        sockets.remove(uid);
                        open(uid, apiKey, token, cfg.getSourceIp());
                    }
                    // else: socket present; its own reconnect handles transient drops.
                }
            }

            // Close sockets for users no longer eligible (token expired / trading disabled / became primary).
            for (Long uid : Set.copyOf(sockets.keySet())) {
                if (!eligible.contains(uid)) {
                    UserOrderSocket s = sockets.remove(uid);
                    if (s != null) s.close("no longer eligible");
                }
            }
        } catch (Exception e) {
            log.warn("[UserOrderWS] reconcile failed: {}", e.getMessage());
        }
    }

    private void open(Long userId, String apiKey, String token, String sourceIp) {
        try {
            UserOrderSocket s = new UserOrderSocket(userId, apiKey, token, sourceIp);
            sockets.put(userId, s);
            s.connect();
        } catch (Exception e) {
            log.warn("[UserOrderWS] u:{} open failed: {}", userId, e.getMessage());
        }
    }

    private void closeAll(String reason) {
        for (Long uid : Set.copyOf(sockets.keySet())) {
            UserOrderSocket s = sockets.remove(uid);
            if (s != null) s.close(reason);
        }
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        closeAll("shutdown");
        reconnectExecutor.shutdownNow();
        alertExecutor.shutdownNow();
    }

    /** True during NSE market hours (IST) — reconnect aggressively then, back off hard otherwise. */
    private static boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return !now.isBefore(LocalTime.of(9, 0)) && !now.isAfter(LocalTime.of(15, 30));
    }

    private static OkHttpClient buildClient(String sourceIp) {
        OkHttpClient.Builder b = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS); // no read timeout for a long-lived WebSocket
        if (sourceIp != null && !sourceIp.isBlank()) {
            try {
                b.socketFactory(new LocalBindSocketFactory(InetAddress.getByName(sourceIp.trim())));
            } catch (Exception e) {
                log.warn("[UserOrderWS] cannot bind to source IP {} — using default interface: {}",
                        sourceIp, e.getMessage());
            }
        }
        return b.build();
    }

    // ── Per-user socket ─────────────────────────────────────────────────────────

    private final class UserOrderSocket {
        private final Long userId;
        private final String apiKey;
        private final String token;
        private final String sourceIp;
        private final OkHttpClient client;
        private volatile WebSocket ws;
        private volatile boolean connected = false;
        private final AtomicBoolean intentionalClose = new AtomicBoolean(false);
        private final AtomicBoolean alertedAuthFailure = new AtomicBoolean(false);
        private final AtomicInteger reconnectDelay = new AtomicInteger(5);

        UserOrderSocket(Long userId, String apiKey, String token, String sourceIp) {
            this.userId = userId;
            this.apiKey = apiKey;
            this.token = token;
            this.sourceIp = sourceIp;
            this.client = buildClient(sourceIp);
        }

        void connect() {
            String url = "wss://ws.kite.trade?api_key=" + apiKey + "&access_token=" + token;
            Request req = new Request.Builder().url(url).build();
            ws = client.newWebSocket(req, new Listener());
            log.info("[UserOrderWS] u:{} connecting (order-update only, sourceIp={})", userId,
                    sourceIp == null || sourceIp.isBlank() ? "default" : sourceIp);
        }

        void close(String reason) {
            intentionalClose.set(true);
            WebSocket w = this.ws;
            if (w != null) {
                try { w.close(1000, "manager: " + reason); } catch (Exception ignored) {}
            }
            connected = false;
            try {
                client.dispatcher().executorService().shutdown();
                client.connectionPool().evictAll();
            } catch (Exception ignored) {}
            log.info("[UserOrderWS] u:{} closed ({})", userId, reason);
        }

        private void scheduleReconnect() {
            if (intentionalClose.get() || !enabled) return;
            // Only keep this socket if the user is still eligible — reconcile() owns lifecycle, but guard here
            // too so a dropped socket for a now-ineligible user doesn't reconnect forever.
            if (!sockets.containsKey(userId) || sockets.get(userId) != this) return;
            int delay = isMarketHours() ? reconnectDelay.get() : 300;
            reconnectExecutor.schedule(() -> {
                if (intentionalClose.get() || !enabled) return;
                if (sockets.get(userId) != this) return; // superseded by a newer socket (e.g. token change)
                log.info("[UserOrderWS] u:{} reconnecting (delay={}s)", userId, delay);
                connect();
                reconnectDelay.set(Math.min(delay * 2, 60));
            }, delay, TimeUnit.SECONDS);
        }

        private final class Listener extends WebSocketListener {
            @Override
            public void onOpen(WebSocket socket, Response response) {
                connected = true;
                reconnectDelay.set(5);
                alertedAuthFailure.set(false);
                log.info("[UserOrderWS] u:{} connected (order-update stream live)", userId);
                alertExecutor.execute(() -> telegramAlertService.systemAlert(
                        "🔗 Per-user order WS connected for user " + userId));
                // NOTE: deliberately NO subscribe/mode frame — Kite pushes order_update text frames for the
                // authed account without any instrument subscription. We never receive binary tick frames.
            }

            @Override
            public void onMessage(WebSocket socket, String text) {
                try {
                    com.fasterxml.jackson.databind.JsonNode root =
                            new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
                    if (!"order_update".equals(root.path("type").asText(""))) {
                        return; // ignore non-order frames (e.g. errors / messages)
                    }
                    com.fasterxml.jackson.databind.JsonNode data = root.path("data");
                    String orderId = data.path("order_id").asText("");
                    String status  = data.path("status").asText("");
                    String symbol  = data.path("tradingsymbol").asText("");
                    int filledQty  = data.path("filled_quantity").asInt(0);
                    log.info("[UserOrderWS] u:{} order update: orderId={} status={} symbol={} filledQty={}",
                            userId, orderId, status, symbol, filledQty);
                    if ("COMPLETE".equalsIgnoreCase(status)) {
                        // Instant per-user materialize + reconcile — scoped to THIS user so a secondary fill
                        // gets SL/target/trail within milliseconds, not blocked by another user's trade on the
                        // same strike (OrderFillWatchdog duplicate guard is per user).
                        if (orderFillWatchdog != null && !orderId.isBlank()) {
                            orderFillWatchdog.promptMaterializeByBrokerOrderId(orderId, userId);
                        }
                        try {
                            UserContext.runAs(userId, positionSynchronizer::syncPositions);
                        } catch (Exception ex) {
                            log.warn("[UserOrderWS] u:{} immediate sync failed: {}", userId, ex.getMessage());
                        }
                    }
                } catch (Exception e) {
                    log.debug("[UserOrderWS] u:{} text-frame parse failed: {}", userId, e.getMessage());
                }
            }

            @Override
            public void onMessage(WebSocket socket, ByteString bytes) {
                // We never subscribe to instruments, so binary tick frames should not arrive. Kite may send a
                // tiny binary heartbeat — ignore it (no tick parsing needed for the order-update stream).
            }

            @Override
            public void onFailure(WebSocket socket, Throwable t, Response response) {
                connected = false;
                int code = response != null ? response.code() : -1;
                String tm = t.getMessage() != null ? t.getMessage() : "";
                boolean authFailure = code == 403 || tm.contains("403") || tm.toLowerCase().contains("forbidden");
                if (authFailure) {
                    reconnectDelay.set(60); // dead/whitelist-mismatched token — back off, don't hammer
                    log.error("[UserOrderWS] u:{} auth failure (code={}) — token invalid or source IP not whitelisted: {}",
                            userId, code, tm);
                    if (alertedAuthFailure.compareAndSet(false, true)) {
                        alertExecutor.execute(() -> {
                            telegramAlertService.systemAlert("🔴 Per-user order WS auth failure for user "
                                    + userId + " (token invalid / source IP not whitelisted)");
                            if (errorEventService != null) {
                                errorEventService.high("UserOrderWS",
                                        "Order WS auth failure for user " + userId + ": " + tm, t);
                            }
                        });
                    }
                } else {
                    log.warn("[UserOrderWS] u:{} failure (code={}): {}", userId, code, tm);
                }
                scheduleReconnect();
            }

            @Override
            public void onClosed(WebSocket socket, int code, String reason) {
                connected = false;
                log.info("[UserOrderWS] u:{} closed: {} {}", userId, code, reason);
                if (!intentionalClose.get()) scheduleReconnect();
            }
        }
    }
}
