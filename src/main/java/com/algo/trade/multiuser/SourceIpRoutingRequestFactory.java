package com.algo.trade.multiuser;

import com.algo.trade.auth.UserBrokerConfig;
import com.algo.trade.auth.UserBrokerConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ClientHttpRequestFactory that binds each outbound Zerodha REST call to the
 * CURRENT USER's registered source IP (UserBrokerConfig.sourceIp).
 *
 * Why: SEBI's retail algo framework (effective Apr 2026) requires every API
 * user to whitelist ONE static IP, unique to them. With several users on one
 * EC2 instance, each user gets a secondary private IP + Elastic IP pair, and
 * their API calls must leave the box from THEIR IP — otherwise Zerodha
 * rejects the order as coming from an unregistered address.
 *
 * Resolution order per request:
 *   1. UserContext user's sourceIp (cached 30s to avoid a DB hit per call)
 *   2. null / blank / unparseable → default factory (primary interface)
 *
 * One JDK HttpClient is created per distinct local IP and reused.
 */
@Component
public class SourceIpRoutingRequestFactory implements ClientHttpRequestFactory {

    private static final Logger log = LoggerFactory.getLogger(SourceIpRoutingRequestFactory.class);
    private static final long CACHE_TTL_MS = 30_000;

    private final UserBrokerConfigRepository configRepository;

    /** localIp → request factory bound to that address */
    private final Map<String, JdkClientHttpRequestFactory> boundFactories = new ConcurrentHashMap<>();
    /** userId → (sourceIp or "" if none, cachedAt) */
    private final Map<Long, CachedIp> ipCache = new ConcurrentHashMap<>();

    private final JdkClientHttpRequestFactory defaultFactory;

    private record CachedIp(String ip, long cachedAtMs) {}

    public SourceIpRoutingRequestFactory(UserBrokerConfigRepository configRepository) {
        this.configRepository = configRepository;
        this.defaultFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    @Override
    public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
        String sourceIp = resolveCurrentUserSourceIp();
        if (sourceIp == null || sourceIp.isBlank()) {
            return defaultFactory.createRequest(uri, httpMethod);
        }
        JdkClientHttpRequestFactory bound = boundFactories.computeIfAbsent(sourceIp, ip -> {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .localAddress(InetAddress.getByName(ip))
                        .build();
                log.info("[SourceIP] Created HTTP client bound to local address {}", ip);
                return new JdkClientHttpRequestFactory(client);
            } catch (Exception e) {
                log.error("[SourceIP] Cannot bind to {} — falling back to default interface: {}", ip, e.getMessage());
                return null;
            }
        });
        return (bound != null ? bound : defaultFactory).createRequest(uri, httpMethod);
    }

    private String resolveCurrentUserSourceIp() {
        if (!UserContext.isSet()) return null; // scheduler thread without owner context — default
        Long userId = UserContext.getUserId();
        long now = System.currentTimeMillis();
        CachedIp cached = ipCache.get(userId);
        if (cached != null && (now - cached.cachedAtMs) < CACHE_TTL_MS) {
            return cached.ip.isEmpty() ? null : cached.ip;
        }
        String ip = "";
        try {
            ip = configRepository.findByUserId(userId)
                    .map(UserBrokerConfig::getSourceIp)
                    .filter(s -> s != null && !s.isBlank())
                    .map(String::trim)
                    .orElse("");
        } catch (Exception e) {
            log.debug("[SourceIP] lookup failed for userId={}: {}", userId, e.getMessage());
        }
        ipCache.put(userId, new CachedIp(ip, now));
        return ip.isEmpty() ? null : ip;
    }

    /** Drop the cached IP for a user — call after their broker config is saved. */
    public void invalidate(Long userId) {
        ipCache.remove(userId);
    }
}
