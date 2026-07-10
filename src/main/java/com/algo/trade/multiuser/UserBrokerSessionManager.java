package com.algo.trade.multiuser;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.*;
import com.algo.trade.broker.BrokerClient;
import com.algo.trade.broker.MarketDataListener;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.FormBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Manages per-user broker sessions — one Kite connection per user.
 *
 * Each user has their own:
 * - API key + secret + access token
 * - Order placement capability
 * - Position tracking
 *
 * Market data (ticks, candles) is SHARED across all users since NIFTY/BANKNIFTY
 * tick data is the same regardless of who's trading. Only broker-specific calls
 * (orders, positions, margins) are per-user.
 *
 * Usage:
 *   BrokerClient client = sessionManager.getClientForUser(userId);
 *   client.placeOrder(request);
 */
@Service
public class UserBrokerSessionManager implements com.algo.trade.broker.zerodha.KiteAccessTokenStore.MultiUserTokenResolver {

    private static final Logger log = LoggerFactory.getLogger(UserBrokerSessionManager.class);

    private final UserBrokerConfigRepository configRepository;
    private final TradingProperties tradingProperties;
    private final ObjectMapper objectMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    /** Per-user broker client instances */
    private final ConcurrentHashMap<Long, UserBrokerSession> sessions = new ConcurrentHashMap<>();

    /** Per-user OkHttp clients bound to user's source IP (for token exchange) */
    private final ConcurrentHashMap<Long, OkHttpClient> perUserHttpClients = new ConcurrentHashMap<>();

    /** Last live token verification per user (valid/message/checkedAt), cached briefly. */
    private final ConcurrentHashMap<Long, TokenVerification> verificationCache = new ConcurrentHashMap<>();
    private static final long VERIFY_TTL_MS = 60_000;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build();

    public UserBrokerSessionManager(UserBrokerConfigRepository configRepository,
                                     TradingProperties tradingProperties,
                                     ObjectMapper objectMapper,
                                     org.springframework.context.ApplicationEventPublisher eventPublisher) {
        this.configRepository = configRepository;
        this.tradingProperties = tradingProperties;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Get or create a broker session for a user.
     */
    public UserBrokerSession getSession(Long userId) {
        return sessions.computeIfAbsent(userId, this::createSession);
    }

    /**
     * Get the broker session for the current thread's user context.
     */
    public UserBrokerSession getCurrentSession() {
        return getSession(UserContext.getUserId());
    }

    /**
     * Check if a user has a valid authenticated session.
     */
    public boolean isAuthenticated(Long userId) {
        // Read the authoritative config from the repository rather than a lazily-created cached
        // session. The previous sessions.get(userId) returned null until some other call had
        // triggered getSession(userId), so a user with a valid stored token showed
        // "not authenticated" (and SignalCopyService skipped their copied orders) until a
        // session happened to be created. Reading the config keeps this consistent with
        // hasValidToken() / getAccessTokenForUser() and with the "Token: valid" UI surface.
        return configRepository.findByUserId(userId)
                .map(UserBrokerConfig::hasValidToken)
                .orElse(false);
    }

    /**
     * Store access token after Kite OAuth callback.
     */
    public void storeAccessToken(Long userId, String accessToken, String requestToken) {
        storeAccessToken(userId, accessToken, requestToken, null);
    }

    public void storeAccessToken(Long userId, String accessToken, String requestToken, String brokerClientId) {
        UserBrokerConfig config = configRepository.findByUserId(userId).orElse(null);
        if (config == null) {
            log.error("[BrokerSession] No broker config for userId={}", userId);
            return;
        }
        if (brokerClientId != null && !brokerClientId.isBlank()) {
            config.setBrokerClientId(brokerClientId);
        }
        config.setAccessToken(accessToken);
        config.setRequestToken(requestToken);
        config.setTokenIssuedAt(Instant.now());
        // Kite tokens expire at midnight IST
        config.setTokenExpiresAt(
                java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
                        .plusDays(1).atStartOfDay(java.time.ZoneId.of("Asia/Kolkata")).toInstant());
        configRepository.save(config);

        // Refresh the in-memory session
        sessions.remove(userId);
        log.info("[BrokerSession] Access token stored for userId={}", userId);

        // If this is the PRIMARY account, the shared market-data WebSocket must reconnect
        // against the freshly stored token. Every per-user login path funnels through here
        // (exchangeToken -> storeAccessToken), but previously only the boot/credentials-change
        // paths pushed the token into KiteAccessTokenStore + fired KiteLoginSuccessEvent — so a
        // routine daily re-login left the WS down even though the token was valid. Reuse the
        // existing PrimaryAccountSwapListener by publishing PrimaryAccountChangedEvent, which
        // pushes the token into the shared store and triggers the (idempotent) WS reconnect.
        if (config.isPrimaryAccount() && eventPublisher != null) {
            log.info("[BrokerSession] Primary account userId={} re-authenticated — triggering shared WS reconnect", userId);
            eventPublisher.publishEvent(new com.algo.trade.auth.MyBrokerController.PrimaryAccountChangedEvent(userId));
        }
    }

    /**
     * Invalidate a user's stored access token — called when Zerodha definitively
     * rejects the credentials (TokenException). Clears the DB token and the cached
     * session so scheduled polls skip this user and the UI shows "login required".
     */
    public void invalidateToken(Long userId, String reason) {
        configRepository.findByUserId(userId).ifPresent(config -> {
            config.setAccessToken(null);
            config.setTokenExpiresAt(Instant.now());
            configRepository.save(config);
        });
        sessions.remove(userId);
        log.warn("[BrokerSession] Access token INVALIDATED for userId={} — {}", userId, reason);
    }

    /**
     * Exchange request token for access token via Kite API.
     * Uses a per-user IP-bound HTTP client when source_ip is configured,
     * ensuring the token exchange originates from the user's whitelisted IP.
     */
    public String exchangeToken(Long userId, String requestToken) {
        UserBrokerConfig config = configRepository.findByUserId(userId).orElse(null);
        if (config == null) return null;

        try {
            String checksum = sha256(config.getApiKey() + requestToken + config.getApiSecret());

            FormBody body = new FormBody.Builder()
                    .add("api_key", config.getApiKey())
                    .add("request_token", requestToken)
                    .add("checksum", checksum)
                    .build();

            Request request = new Request.Builder()
                    .url("https://api.kite.trade/session/token")
                    .post(body)
                    .build();

            OkHttpClient client = resolveHttpClient(userId, config);
            try (Response response = client.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    JsonNode json = objectMapper.readTree(response.body().string());
                    String accessToken = json.path("data").path("access_token").asText();
                    // Zerodha login id (e.g. "SX0602") — stored so trades/orders can be
                    // tied to the exact broker account and exits verified against it.
                    String brokerClientId = json.path("data").path("user_id").asText(null);
                    if (accessToken != null && !accessToken.isBlank()) {
                        storeAccessToken(userId, accessToken, requestToken, brokerClientId);
                        return accessToken;
                    }
                }
            }
        } catch (Exception e) {
            log.error("[BrokerSession] Token exchange failed for userId={}: {}", userId, e.getMessage());
        }
        return null;
    }

    /**
     * Resolve the correct OkHttpClient for a user — bound to their source IP if configured.
     * Falls back to the shared unbound client for users without a source IP.
     */
    private OkHttpClient resolveHttpClient(Long userId, UserBrokerConfig config) {
        if (config.getSourceIp() == null || config.getSourceIp().isBlank()) {
            return httpClient;
        }
        // Cache per-user bound clients to avoid recreating on every call
        return perUserHttpClients.computeIfAbsent(userId, id -> {
            try {
                String sourceIp = config.getSourceIp().trim();
                java.net.InetAddress bindAddress = java.net.InetAddress.getByName(sourceIp);
                log.info("[BrokerSession] Creating source-IP-bound OkHttpClient for userId={}, sourceIp={}", id, sourceIp);
                return new OkHttpClient.Builder()
                        .socketFactory(new BoundSocketFactory(bindAddress))
                        .connectTimeout(10, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build();
            } catch (Exception e) {
                log.error("[BrokerSession] Failed to bind OkHttp to sourceIp={} for userId={}: {}. Using default.",
                        config.getSourceIp(), id, e.getMessage());
                return httpClient;
            }
        });
    }

    /** Invalidate cached per-user HTTP client (call when source_ip changes). */
    public void invalidateHttpClient(Long userId) {
        perUserHttpClients.remove(userId);
    }

    /**
     * Get all active (trading-enabled) user sessions.
     */
    public List<UserBrokerSession> getActiveSessions() {
        List<UserBrokerConfig> activeConfigs = configRepository.findByTradingEnabled(true);
        List<UserBrokerSession> result = new ArrayList<>();
        for (UserBrokerConfig config : activeConfigs) {
            if (config.hasValidToken()) {
                result.add(getSession(config.getUserId()));
            }
        }
        return result;
    }

    /**
     * Get login URL for a specific user.
     */
    public String getLoginUrl(Long userId) {
        UserBrokerConfig config = configRepository.findByUserId(userId).orElse(null);
        if (config == null) return null;
        return "https://kite.zerodha.com/connect/login?api_key=" + config.getApiKey() + "&v=3";
    }

    private UserBrokerSession createSession(Long userId) {
        UserBrokerConfig config = configRepository.findByUserId(userId).orElse(null);
        if (config == null) {
            log.warn("[BrokerSession] No broker config found for userId={}", userId);
            return new UserBrokerSession(userId, null);
        }
        return new UserBrokerSession(userId, config);
    }

    @Override
    public String getAccessTokenForUser(Long userId) {
        UserBrokerConfig config = configRepository.findByUserId(userId).orElse(null);
        if (config == null || !config.hasValidToken()) return null;
        return config.getAccessToken();
    }

    private String sha256(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 failed", e);
        }
    }

    /**
     * LIVE verification: make a real authenticated Zerodha call (GET /user/profile),
     * bound to the user's source IP, to confirm the broker actually ACCEPTS the stored
     * api_key:access_token pair. The diagnostic page's "token present/valid" is only a
     * local check (token string exists, not past expiry) and cannot detect a token that
     * Zerodha rejects (expired, invalidated, or arriving from a non-whitelisted IP).
     */
    public TokenVerification verifyToken(Long userId) {
        UserBrokerConfig config = configRepository.findByUserId(userId).orElse(null);
        if (config == null) {
            return cache(userId, new TokenVerification(false, "No broker config", Instant.now()));
        }
        String apiKey = config.getApiKey();
        String accessToken = config.getAccessToken();
        if (apiKey == null || apiKey.isBlank() || accessToken == null || accessToken.isBlank()) {
            return cache(userId, new TokenVerification(false, "No access token - Kite login required", Instant.now()));
        }
        try {
            Request req = new Request.Builder()
                    .url("https://api.kite.trade/user/profile")
                    .header("X-Kite-Version", "3")
                    .header("Authorization", "token " + apiKey + ":" + accessToken)
                    .get()
                    .build();
            OkHttpClient client = resolveHttpClient(userId, config);
            try (Response resp = client.newCall(req).execute()) {
                String body = resp.body() != null ? resp.body().string() : "";
                if (resp.isSuccessful()) {
                    return cache(userId, new TokenVerification(true, "Verified with Zerodha", Instant.now()));
                }
                String msg = "Zerodha rejected (HTTP " + resp.code() + ")";
                try {
                    JsonNode j = objectMapper.readTree(body);
                    String m = j.path("message").asText("");
                    String et = j.path("error_type").asText("");
                    if (!m.isBlank()) msg = m + (et.isBlank() ? "" : " [" + et + "]");
                    if ("TokenException".equals(et)) {
                        msg += (config.getSourceIp() != null && !config.getSourceIp().isBlank())
                                ? " - token rejected; verify source IP is whitelisted at Zerodha for this api_key"
                                : " - token expired/invalid; re-login to Kite";
                    }
                } catch (Exception ignore) { /* keep generic msg */ }
                log.warn("[BrokerSession] Token verification FAILED for userId={}: {}", userId, msg);
                return cache(userId, new TokenVerification(false, msg, Instant.now()));
            }
        } catch (Exception e) {
            return cache(userId, new TokenVerification(false, "Verification call failed: " + e.getMessage(), Instant.now()));
        }
    }

    /** Cached variant (60s TTL) for status pages so repeated loads don't hammer Zerodha. */
    public TokenVerification verifyTokenCached(Long userId) {
        TokenVerification v = verificationCache.get(userId);
        if (v != null && (System.currentTimeMillis() - v.checkedAt().toEpochMilli()) < VERIFY_TTL_MS) {
            return v;
        }
        return verifyToken(userId);
    }

    private TokenVerification cache(Long userId, TokenVerification v) {
        verificationCache.put(userId, v);
        return v;
    }

    /** Result of a live broker-side token check. */
    public record TokenVerification(boolean valid, String message, Instant checkedAt) {}

    /**
     * Per-user broker session — holds the user's config and provides
     * authenticated API access to their Kite account.
     */
    public static class UserBrokerSession {
        public final Long userId;
        public final UserBrokerConfig config;

        UserBrokerSession(Long userId, UserBrokerConfig config) {
            this.userId = userId;
            this.config = config;
        }

        public boolean isReady() {
            return config != null && config.hasValidToken() && config.isTradingEnabled();
        }

        /**
         * Authentication-only readiness: a valid token, regardless of tradingEnabled.
         * tradingEnabled is a business gate on NEW entries (enforced upstream in
         * MultiUserStrategyLoop / SignalCopyService / UserAwareExecutionService) — it must
         * NOT block authenticated reads (positions) or EXITS. Disabling a user's trading is
         * exactly when the operator most needs to view and flatten their positions.
         */
        public boolean hasAuth() {
            return config != null && config.hasValidToken();
        }

        public String getApiKey() { return config != null ? config.getApiKey() : null; }
        public String getAccessToken() { return config != null ? config.getAccessToken() : null; }
        public String getAuthHeader() {
            return config != null
                    ? "token " + config.getApiKey() + ":" + config.getAccessToken()
                    : null;
        }
    }
}
