package com.algo.trade.auth;

import com.algo.trade.multiuser.UserBrokerSessionManager;
import com.algo.trade.notification.TelegramLinkService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import com.algo.trade.notification.TelegramLinkService.LinkChallenge;
import com.algo.trade.notification.TelegramLinkService.LinkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

/**
 * Self-service endpoints for the CURRENT logged-in user.
 *
 *   GET    /me/broker                      → current config (secrets masked)
 *   PUT    /me/broker                      → save Kite apiKey/apiSecret + risk limits
 *   POST   /me/broker/telegram/start-link  → issue OTP + deep-link to shared bot
 *   GET    /me/broker/telegram/link-status → has the user clicked & opened the bot?
 *   POST   /me/broker/telegram/unlink      → forget chat_id, stop alerts
 *   POST   /me/broker/test-telegram        → send a test alert to verify the link
 *
 * Kite secrets are encrypted at rest by EncryptedStringConverter. Telegram now
 * uses a single shared bot — per-user link is established by OTP, not by the
 * user pasting a bot token.
 */
@RestController
@RequestMapping("/me/broker")
public class MyBrokerController {

    private static final Logger log = LoggerFactory.getLogger(MyBrokerController.class);

    private final AppUserRepository userRepository;
    private final UserBrokerConfigRepository configRepository;
    private final TelegramLinkService telegram;
    private final ApplicationEventPublisher events;
    private final UserBrokerSessionManager sessionManager;

    @Value("${auth.google.enabled:false}")
    private String googleAuthEnabledRaw;

    public MyBrokerController(AppUserRepository userRepository,
                               UserBrokerConfigRepository configRepository,
                               TelegramLinkService telegram,
                               ApplicationEventPublisher events,
                               UserBrokerSessionManager sessionManager) {
        this.userRepository = userRepository;
        this.configRepository = configRepository;
        this.telegram = telegram;
        this.events = events;
        this.sessionManager = sessionManager;
    }

    @GetMapping
    public ResponseEntity<?> get(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        UserBrokerConfig c = configRepository.findByUserId(u.getId()).orElse(null);
        return ResponseEntity.ok(view(u, c));
    }

    @PutMapping
    public ResponseEntity<?> save(@RequestBody SaveRequest req, Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));

        boolean wasPresent = configRepository.existsByUserId(u.getId());
        UserBrokerConfig c = configRepository.findByUserId(u.getId())
                .orElseGet(() -> new UserBrokerConfig(u.getId(),
                        nullSafe(req.apiKey()), nullSafe(req.apiSecret())));

        boolean apiCredsChanged = false;
        if (req.apiKey() != null && !req.apiKey().isBlank()) {
            apiCredsChanged |= !req.apiKey().trim().equals(c.getApiKey());
            c.setApiKey(req.apiKey().trim());
        }
        if (req.apiSecret() != null && !req.apiSecret().isBlank()) {
            apiCredsChanged = true;
            c.setApiSecret(req.apiSecret().trim());
        }
        if (req.webhookUrl() != null) c.setWebhookUrl(blankToNull(req.webhookUrl().trim()));
        if (req.totalCapital() != null) c.setTotalCapital(req.totalCapital());
        if (req.dailyMaxLoss() != null) c.setDailyMaxLoss(req.dailyMaxLoss());
        if (req.maxOpenPositions() != null) c.setMaxOpenPositions(req.maxOpenPositions());
        if (req.maxLotsPerTrade() != null) c.setMaxLotsPerTrade(req.maxLotsPerTrade());
        if (req.tradingEnabled() != null) c.setTradingEnabled(req.tradingEnabled());
        if (req.sourceIp() != null) {
            String ip = blankToNull(req.sourceIp().trim());
            if (ip != null) {
                // Guard (incident 2026-06-08): a wrong sourceIp — a public/Elastic IP, an
                // address not on this box, or another user's egress IP — silently routes
                // this user's orders out the wrong interface and Zerodha 403s a valid token.
                String reason = com.algo.trade.multiuser.SourceIpValidator.reasonIfInvalid(ip, c.isPrimaryAccount());
                if (reason != null) {
                    log.warn("[MyBroker] user={} rejected sourceIp='{}': {}", u.getEmail(), ip, reason);
                    return ResponseEntity.badRequest().body(Map.of("error", reason));
                }
                Long clash = configRepository.findAll().stream()
                        .filter(other -> !other.getUserId().equals(u.getId()))
                        .filter(other -> ip.equalsIgnoreCase(other.getSourceIp()))
                        .map(UserBrokerConfig::getUserId)
                        .findFirst().orElse(null);
                if (clash != null) {
                    String msg = "Source IP " + ip + " is already assigned to another user (userId="
                            + clash + "). Each account must egress from its own whitelisted IP.";
                    log.warn("[MyBroker] user={} rejected sourceIp='{}': {}", u.getEmail(), ip, msg);
                    return ResponseEntity.badRequest().body(Map.of("error", msg));
                }
            }
            // Only churn clients/WS when the IP actually moves (this block is entered
            // on any save that includes the field, even if unchanged).
            String oldIp = c.getSourceIp();
            boolean ipChanged = !java.util.Objects.equals(oldIp, ip);
            c.setSourceIp(ip);
            if (ipChanged) {
                // Full invalidate chain (2026-06-14 fix): REST factory + per-user OkHttp
                // client (token exchange) + WebSocket. Previously only the REST factory was
                // invalidated, so the cached OkHttp/WS client kept the OLD bind address until
                // restart and could egress token-exchange from the wrong IP (Zerodha 403).
                if (sourceIpFactory != null) sourceIpFactory.invalidate(u.getId());
                sessionManager.invalidateHttpClient(u.getId());
                if (userWebSocketManager != null) userWebSocketManager.disconnectUser(u.getId());
                log.info("[MyBroker] user={} sourceIp changed '{}' -> '{}' — invalidated REST+OkHttp clients and bounced WS for rebind",
                        u.getEmail(), oldIp, ip);
            }
        }

        configRepository.save(c);
        log.info("[MyBroker] user={} saved broker config (apiKey={}, new={})",
                u.getEmail(), mask(c.getApiKey()), !wasPresent);

        // If this user is the primary account and they just (re)configured credentials,
        // tell the broker connector to hot-swap immediately for market analysis.
        if (c.isPrimaryAccount() && apiCredsChanged) {
            events.publishEvent(new PrimaryAccountChangedEvent(u.getId()));
        }
        return ResponseEntity.ok(view(u, c));
    }

    // ── Telegram link (OTP flow against a single shared bot) ──

    @PostMapping("/telegram/start-link")
    public ResponseEntity<?> startTelegramLink(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        try {
            LinkChallenge ch = telegram.startLink(u.getId());
            return ResponseEntity.ok(ch);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(503).body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/telegram/link-status")
    public ResponseEntity<LinkStatus> telegramStatus(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(telegram.status(u.getId()));
    }

    @PostMapping("/telegram/unlink")
    public ResponseEntity<?> unlinkTelegram(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        telegram.unlink(u.getId());
        return ResponseEntity.ok(Map.of("unlinked", true));
    }

    // ── Per-user Kite OAuth (each user gets their OWN access token in DB) ──

    /**
     * Returns the Kite login URL built with THIS user's apiKey. We also drop a
     * short-lived cookie capturing the user's CURRENT browser origin (scheme+host)
     * so the post-callback redirect can bounce them back to where they came from
     * — important when the app is accessed via ngrok / a dev proxy on a different
     * port than the registered Kite redirect URL.
     */
    @GetMapping("/kite/login-url")
    public ResponseEntity<?> kiteLoginUrl(Authentication auth,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        String url = sessionManager.getLoginUrl(u.getId());
        if (url == null) return ResponseEntity.badRequest().body(Map.of("error", "save your Kite API key first"));

        String origin = resolveBrowserOrigin(request);
        Cookie c = new Cookie("kite-return-origin", java.net.URLEncoder.encode(origin, java.nio.charset.StandardCharsets.UTF_8));
        c.setPath("/");
        c.setMaxAge(10 * 60); // 10 minutes
        c.setHttpOnly(true);
        response.addCookie(c);

        return ResponseEntity.ok(Map.of("loginUrl", url));
    }

    /**
     * Kite OAuth callback — Zerodha redirects here after the user completes login.
     * We identify the user via their authenticated app session (Google) and store
     * the resulting access token on THEIR UserBrokerConfig row in the DB.
     *
     * Requires this exact URL to be configured as the Redirect URL in Zerodha's
     * Kite Connect developer app for the user's API key:
     *   http://<host>/advalgotrade/me/broker/kite/callback
     */
    @GetMapping("/kite/callback")
    public ResponseEntity<?> kiteCallback(@RequestParam("request_token") String requestToken,
                                           @RequestParam(value = "status", required = false) String status,
                                           Authentication auth,
                                           HttpServletRequest request) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        if (status != null && !"success".equalsIgnoreCase(status)) {
            return ResponseEntity.status(400).body(Map.of("error", "Kite reported status=" + status));
        }
        String accessToken = sessionManager.exchangeToken(u.getId(), requestToken);
        if (accessToken == null || accessToken.isBlank()) {
            return ResponseEntity.status(502).body(Map.of("error", "Kite token exchange failed"));
        }
        log.info("[MyBroker] user={} Kite access token stored in DB", u.getEmail());

        // Live verification: confirm Zerodha actually ACCEPTS the new token (from the
        // user's source IP) — not just that a token string was stored. Surfaced in the UI.
        var verify = sessionManager.verifyToken(u.getId());
        log.info("[MyBroker] user={} token verification after login: valid={} ({})",
                u.getEmail(), verify.valid(), verify.message());

        // If this user is the primary account, hot-swap the shared WS feed too.
        UserBrokerConfig c = configRepository.findByUserId(u.getId()).orElse(null);
        if (c != null && c.isPrimaryAccount()) {
            events.publishEvent(new PrimaryAccountChangedEvent(u.getId()));
        }

        // Bounce back to the host the browser originally came from (captured at
        // login-url time). Falls back to forwarded-header host, then to current
        // request host. This handles ngrok / Angular-dev-proxy / direct-port cases.
        String returnOrigin = readReturnOriginCookie(request);
        if (returnOrigin == null) returnOrigin = resolveBrowserOrigin(request);
        String location = returnOrigin + "/advalgotrade/auth?kite=linked";
        return ResponseEntity.status(org.springframework.http.HttpStatus.FOUND)
                .location(java.net.URI.create(location)).build();
    }

    /** Reads scheme+host(+port) from X-Forwarded-* or the request itself. */
    private String resolveBrowserOrigin(HttpServletRequest request) {
        String scheme = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getHeader("Host"));
        if (host == null || host.isBlank()) {
            host = request.getServerName() + ":" + request.getServerPort();
        }
        // X-Forwarded-Host can carry "host:port" already
        return scheme + "://" + host;
    }

    private String readReturnOriginCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        for (Cookie c : request.getCookies()) {
            if ("kite-return-origin".equals(c.getName())) {
                try { return java.net.URLDecoder.decode(c.getValue(), java.nio.charset.StandardCharsets.UTF_8); }
                catch (Exception e) { return null; }
            }
        }
        return null;
    }

    private String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    @PostMapping("/test-telegram")
    public ResponseEntity<?> testTelegram(Authentication auth) {
        AppUser u = currentUser(auth);
        if (u == null) return ResponseEntity.status(401).body(Map.of("error", "not authenticated"));
        boolean ok = telegram.sendToUser(u.getId(),
                "✅ Test alert — your Telegram link is working for " + u.getEmail());
        if (!ok) return ResponseEntity.status(400).body(Map.of("sent", false, "error", "Telegram not linked yet"));
        return ResponseEntity.ok(Map.of("sent", true));
    }

    // ── helpers ──

    private AppUser currentUser(Authentication auth) {
        if (!"true".equalsIgnoreCase(googleAuthEnabledRaw)) {
            return userRepository.findByEmail("local").orElseGet(() -> {
                AppUser u = new AppUser("local", "SUPERUSER");
                u.setName("Local User");
                return userRepository.save(u);
            });
        }
        if (auth == null || auth.getPrincipal() == null) return null;
        Object p = auth.getPrincipal();
        String email = null;
        if (p instanceof org.springframework.security.oauth2.core.user.OAuth2User o) {
            email = o.getAttribute("email");
        }
        if (email == null) return null;
        return userRepository.findByEmail(email.toLowerCase()).orElse(null);
    }

    private Map<String, Object> view(AppUser u, UserBrokerConfig c) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("userId", u.getId());
        m.put("email", u.getEmail());
        if (c == null) {
            m.put("configured", false);
            return m;
        }
        m.put("configured", true);
        m.put("apiKey", mask(c.getApiKey()));
        m.put("apiSecretMasked", c.getApiSecret() != null);
        m.put("accessTokenPresent", c.getAccessToken() != null && !c.getAccessToken().isBlank());
        // Live broker-side check (cached 60s). "accessTokenPresent" only means a string
        // exists; "tokenValid" means Zerodha accepted it on a real call from the user's IP.
        var tv = sessionManager.verifyTokenCached(u.getId());
        m.put("tokenValid", tv.valid());
        m.put("tokenVerifyMessage", tv.message());
        m.put("tokenVerifiedAt", tv.checkedAt());
        m.put("telegramChatId", c.getTelegramChatId());
        m.put("telegramLinked", c.getTelegramChatId() != null && !c.getTelegramChatId().isBlank());
        m.put("webhookConfigured", c.getWebhookUrl() != null);
        m.put("tradingEnabled", c.isTradingEnabled());
        m.put("totalCapital", c.getTotalCapital());
        m.put("dailyMaxLoss", c.getDailyMaxLoss());
        m.put("maxOpenPositions", c.getMaxOpenPositions());
        m.put("maxLotsPerTrade", c.getMaxLotsPerTrade());
        m.put("primaryAccount", c.isPrimaryAccount());
        m.put("sourceIp", c.getSourceIp());
        m.put("tokenExpiresAt", c.getTokenExpiresAt());
        m.put("updatedAt", c.getUpdatedAt() == null ? Instant.now() : c.getUpdatedAt());
        return m;
    }

    private String mask(String s) {
        if (s == null || s.isBlank()) return null;
        if (s.length() <= 4) return "****";
        return "****" + s.substring(s.length() - 4);
    }

    private String nullSafe(String s) { return s == null ? "" : s; }
    private String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    public record SaveRequest(String apiKey, String apiSecret,
                              String webhookUrl,
                              Double totalCapital, Double dailyMaxLoss,
                              Integer maxOpenPositions, Integer maxLotsPerTrade,
                              Boolean tradingEnabled,
                              String sourceIp) {}

    /** Optional — present when source-IP routing is active. Cache invalidation on save. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.multiuser.SourceIpRoutingRequestFactory sourceIpFactory;

    /** Optional — bounce the user's market-data WebSocket so it reconnects from the new source IP. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.multiuser.UserWebSocketManager userWebSocketManager;

    /** Published when a primary user changes their API key / secret — triggers WS reconnect. */
    public record PrimaryAccountChangedEvent(Long userId) {}
}
