package com.algo.trade.config;

import com.algo.trade.multiuser.UserContext;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-user API rate limiting — prevents one user from starving the other.
 *
 * Uses a sliding window counter (1-second granularity):
 * - Max 30 requests per second per user (covers burst UI polling)
 * - Max 300 requests per 10 seconds per user (sustained load cap)
 *
 * Returns HTTP 429 (Too Many Requests) when exceeded.
 * Static assets, health checks, and actuator endpoints are exempt.
 */
@Component
@Order(2) // After UserContextFilter (Order 1)
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** Max requests per second per user */
    private static final int MAX_PER_SECOND = 30;
    /** Max requests per 10-second window per user */
    private static final int MAX_PER_10_SECONDS = 300;

    /** Per-user sliding window counters */
    private final ConcurrentHashMap<Long, UserRateState> userStates = new ConcurrentHashMap<>();

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) request;
        String uri = httpReq.getRequestURI();

        // Exempt static assets, health checks, actuator
        if (isExempt(uri)) {
            chain.doFilter(request, response);
            return;
        }

        Long userId = UserContext.getUserId();
        UserRateState state = userStates.computeIfAbsent(userId, k -> new UserRateState());

        if (!state.tryAcquire()) {
            HttpServletResponse httpResp = (HttpServletResponse) response;
            httpResp.setStatus(429);
            httpResp.setContentType("application/json");
            httpResp.getWriter().write("{\"error\":\"Rate limit exceeded. Max " + MAX_PER_SECOND + " req/s.\"}");
            log.warn("[RateLimit] userId={} exceeded rate limit on {}", userId, uri);
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isExempt(String uri) {
        return uri.endsWith(".js") || uri.endsWith(".css") || uri.endsWith(".ico")
                || uri.endsWith(".html") || uri.endsWith(".json") || uri.endsWith(".txt")
                || uri.contains("/assets/")
                || uri.contains("/actuator/")
                || uri.equals("/advalgotrade/health")
                || uri.equals("/advalgotrade/");
    }

    /**
     * Per-user rate state — dual sliding window (1s + 10s).
     */
    private static class UserRateState {
        private final AtomicInteger currentSecondCount = new AtomicInteger(0);
        private final AtomicLong currentSecondStart = new AtomicLong(0);
        private final AtomicInteger tenSecondCount = new AtomicInteger(0);
        private final AtomicLong tenSecondStart = new AtomicLong(0);

        boolean tryAcquire() {
            long now = System.currentTimeMillis();
            long secondStart = currentSecondStart.get();
            long tenStart = tenSecondStart.get();

            // Reset 1-second window
            if (now - secondStart > 1000) {
                currentSecondCount.set(0);
                currentSecondStart.set(now);
            }

            // Reset 10-second window
            if (now - tenStart > 10_000) {
                tenSecondCount.set(0);
                tenSecondStart.set(now);
            }

            // Check limits
            if (currentSecondCount.get() >= MAX_PER_SECOND) return false;
            if (tenSecondCount.get() >= MAX_PER_10_SECONDS) return false;

            // Acquire
            currentSecondCount.incrementAndGet();
            tenSecondCount.incrementAndGet();
            return true;
        }
    }
}
