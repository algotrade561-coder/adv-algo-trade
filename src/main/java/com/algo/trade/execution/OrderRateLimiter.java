package com.algo.trade.execution;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Token-bucket rate limiter for broker order placement.
 * Kite allows ~10 orders/sec; we cap at 8/sec to leave headroom.
 *
 * <p>Call {@link #acquire()} before every {@code brokerClient.placeOrder()} call.
 * Tokens refill every second.</p>
 */
@Component
public class OrderRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(OrderRateLimiter.class);
    private static final int TOKENS_PER_SECOND = 8;
    private static final long ACQUIRE_TIMEOUT_MS = 5000;

    private final Semaphore tokens = new Semaphore(TOKENS_PER_SECOND);

    /**
     * Acquire a token before placing an order. Blocks up to 5 seconds.
     *
     * @return true if token acquired, false if timed out (caller should back off)
     */
    public boolean acquire() {
        try {
            boolean acquired = tokens.tryAcquire(ACQUIRE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!acquired) {
                log.warn("[RateLimiter] Order rate limit exceeded — waited {}ms without token", ACQUIRE_TIMEOUT_MS);
            }
            return acquired;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Refill tokens every second (up to max capacity).
     */
    @Scheduled(fixedRate = 1000)
    public void refill() {
        int available = tokens.availablePermits();
        int toRelease = TOKENS_PER_SECOND - available;
        if (toRelease > 0) {
            tokens.release(toRelease);
        }
    }

    /** Current available tokens (for monitoring). */
    public int availableTokens() {
        return tokens.availablePermits();
    }
}
