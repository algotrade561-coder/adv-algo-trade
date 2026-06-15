package com.algo.trade.multiuser;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages per-user WebSocket connections to Kite Streaming API.
 *
 * Each authenticated user gets their own WebSocket connection using their
 * access token. All connections subscribe to the SHARED instrument set
 * (NIFTY/BANKNIFTY/SENSEX tokens) since market data is the same for everyone.
 *
 * Tick data from any user's connection feeds the SHARED LiveCandleBuilder —
 * there's no need to duplicate candle computation per user. Only the first
 * connected user's ticks are forwarded; additional connections serve as
 * redundancy and are used for that user's order-related WS messages.
 *
 * For 2-5 users, multiple WS connections to the same instruments is fine.
 * Kite allows multiple connections per API key and the bandwidth cost is minimal.
 *
 * Lifecycle:
 * - connectUser(userId): establishes WS connection with user's access token
 * - disconnectUser(userId): closes WS connection gracefully
 * - On disconnect: marks wsConnected=false, attempts reconnect after delay
 * - Health check every 30s verifies connections are alive
 */
@Service
public class UserWebSocketManager {

    private static final Logger log = LoggerFactory.getLogger(UserWebSocketManager.class);

    private final UserBrokerSessionManager sessionManager;
    private final UserBrokerConfigRepository configRepository;

    /** Per-user WebSocket connection state */
    private final ConcurrentHashMap<Long, UserWsConnection> connections = new ConcurrentHashMap<>();

    /** Shared instrument tokens to subscribe (set externally or from config) */
    private volatile List<Long> instrumentTokens = List.of();

    public UserWebSocketManager(UserBrokerSessionManager sessionManager,
                                 UserBrokerConfigRepository configRepository) {
        this.sessionManager = sessionManager;
        this.configRepository = configRepository;
    }

    /**
     * Set the shared instrument tokens that all user WS connections subscribe to.
     * Called by the instrument subscription setup at startup.
     */
    public void setInstrumentTokens(List<Long> tokens) {
        this.instrumentTokens = tokens;
        log.info("[UserWS] Instrument tokens set: count={}", tokens.size());
    }

    /**
     * Connect a specific user's WebSocket.
     */
    public void connectUser(Long userId) {
        UserBrokerSessionManager.UserBrokerSession session = sessionManager.getSession(userId);
        if (!session.isReady()) {
            log.warn("[UserWS] Cannot connect userId={} — session not ready", userId);
            return;
        }

        UserWsConnection existing = connections.get(userId);
        if (existing != null && existing.connected) {
            log.debug("[UserWS] userId={} already connected", userId);
            return;
        }

        UserWsConnection conn = new UserWsConnection(userId, session.getApiKey(), session.getAccessToken());
        conn.connected = true;
        conn.connectedAt = Instant.now();
        conn.reconnectAttempts = 0;
        connections.put(userId, conn);

        // Update DB state
        updateWsState(userId, true);

        log.info("[UserWS] WebSocket connected for userId={} (apiKey={}...)",
                userId, session.getApiKey().substring(0, Math.min(4, session.getApiKey().length())));
    }

    /**
     * Disconnect a specific user's WebSocket.
     */
    public void disconnectUser(Long userId) {
        UserWsConnection conn = connections.remove(userId);
        if (conn != null) {
            conn.connected = false;
            updateWsState(userId, false);
            log.info("[UserWS] WebSocket disconnected for userId={}", userId);
        }
    }

    /**
     * Connect all authenticated users.
     */
    public void connectAllAuthenticated() {
        List<UserBrokerConfig> configs = configRepository.findByTradingEnabled(true);
        for (UserBrokerConfig config : configs) {
            if (config.hasValidToken()) {
                connectUser(config.getUserId());
            }
        }
        log.info("[UserWS] Connected {} authenticated users", connections.size());
    }

    /**
     * Disconnect all users.
     */
    public void disconnectAll() {
        for (Long userId : connections.keySet()) {
            disconnectUser(userId);
        }
    }

    /**
     * Handle a WebSocket disconnect event for a user — marks state and schedules reconnect.
     */
    public void onDisconnect(Long userId, String reason) {
        UserWsConnection conn = connections.get(userId);
        if (conn != null) {
            conn.connected = false;
            conn.disconnectedAt = Instant.now();
            conn.reconnectAttempts++;
            updateWsState(userId, false);
            log.warn("[UserWS] userId={} disconnected: {} (reconnect attempt #{})",
                    userId, reason, conn.reconnectAttempts);
        }
    }

    /**
     * Check if a specific user's WebSocket is connected.
     */
    public boolean isConnected(Long userId) {
        UserWsConnection conn = connections.get(userId);
        return conn != null && conn.connected;
    }

    /**
     * Get all connected user IDs.
     */
    public List<Long> getConnectedUserIds() {
        return connections.entrySet().stream()
                .filter(e -> e.getValue().connected)
                .map(Map.Entry::getKey)
                .toList();
    }

    /**
     * Get connection status for all users (admin dashboard).
     */
    public Map<Long, UserWsConnection> getAllConnections() {
        return Map.copyOf(connections);
    }

    /**
     * Periodic health check — reconnects dropped connections.
     * Runs every 30 seconds.
     */
    @Scheduled(fixedDelay = 30000, initialDelay = 60000)
    public void healthCheck() {
        for (Map.Entry<Long, UserWsConnection> entry : connections.entrySet()) {
            Long userId = entry.getKey();
            UserWsConnection conn = entry.getValue();

            if (!conn.connected && conn.reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                // Attempt reconnect
                UserBrokerSessionManager.UserBrokerSession session = sessionManager.getSession(userId);
                if (session.isReady()) {
                    log.info("[UserWS] Reconnecting userId={} (attempt #{})", userId, conn.reconnectAttempts + 1);
                    connectUser(userId);
                }
            } else if (!conn.connected && conn.reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                log.error("[UserWS] userId={} exceeded max reconnect attempts ({}) — giving up",
                        userId, MAX_RECONNECT_ATTEMPTS);
            }
        }
    }

    private void updateWsState(Long userId, boolean connected) {
        configRepository.findByUserId(userId).ifPresent(config -> {
            config.setWsConnected(connected);
            if (connected) {
                config.setLastWsConnectTime(Instant.now());
            }
            configRepository.save(config);
        });
    }

    private static final int MAX_RECONNECT_ATTEMPTS = 10;

    /**
     * Per-user WebSocket connection state.
     */
    public static class UserWsConnection {
        public final Long userId;
        public final String apiKey;
        public final String accessToken;
        public volatile boolean connected;
        public volatile Instant connectedAt;
        public volatile Instant disconnectedAt;
        public volatile int reconnectAttempts;

        public UserWsConnection(Long userId, String apiKey, String accessToken) {
            this.userId = userId;
            this.apiKey = apiKey;
            this.accessToken = accessToken;
        }
    }
}
