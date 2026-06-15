package com.algo.trade.broker;

import com.algo.trade.domain.*;
import com.algo.trade.multiuser.UserContext;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.RetryRegistry;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Resilient Broker Client — wraps the RoutingBrokerClient with Resilience4j
 * circuit breaker and retry for production resilience.
 *
 * <h2>Order placement is isolated three ways (incident 2026-06-08)</h2>
 *
 * On 2026-06-08 a single shared {@code brokerApi} circuit breaker covered every
 * operation for every user, and its fallback shouted "CIRCUIT OPEN" on ANY failure,
 * masking the real broker error. Fixes:
 * <ol>
 *   <li><b>Per-account order breakers</b> — entry placement uses a breaker keyed by
 *       the acting userId ({@code brokerOrder-<userId>}); one user's bad token can no
 *       longer trip placement for everyone else.</li>
 *   <li><b>Exit orders bypass the breaker entirely</b> — square-off / close orders
 *       ({@code EXIT-*}) are never short-circuited, so a saturated breaker can never
 *       strand an open position. They still get transient IOException retry.</li>
 *   <li><b>Reads use a separate breaker</b> ({@code brokerRead}) — a failing
 *       positions()/quote() poll can no longer poison order placement.</li>
 * </ol>
 * The order path is programmatic (not annotation-driven) so it can report the REAL
 * broker error and only say "CIRCUIT OPEN" on a genuine {@link CallNotPermittedException}.
 */
@Component
@Primary
public class ResilientBrokerClient implements BrokerClient {

    private static final Logger log = LoggerFactory.getLogger(ResilientBrokerClient.class);

    /** Named config (see application*.yml) applied to every per-user order breaker. */
    private static final String ORDER_CB_CONFIG = "brokerOrder";

    private final BrokerClient delegate;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final RetryRegistry retryRegistry;

    public ResilientBrokerClient(@Qualifier("routingBrokerClient") BrokerClient delegate,
                                 CircuitBreakerRegistry circuitBreakerRegistry,
                                 RetryRegistry retryRegistry) {
        this.delegate = delegate;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.retryRegistry = retryRegistry;
    }

    @Override
    public BrokerSession session() {
        return delegate.session();
    }

    @Override
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerRead", fallbackMethod = "downloadInstrumentsFallback")
    public List<Instrument> downloadInstruments() {
        return delegate.downloadInstruments();
    }

    @Override
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerRead", fallbackMethod = "quoteFallback")
    public Optional<Quote> quote(String instrumentKey) {
        return delegate.quote(instrumentKey);
    }

    @Override
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerRead", fallbackMethod = "quotesFallback")
    public Map<String, Quote> quotes(Collection<String> instrumentKeys) {
        return delegate.quotes(instrumentKeys);
    }

    @Override
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerRead", fallbackMethod = "historicalCandlesFallback")
    public List<Candle> historicalCandles(HistoricalDataRequest request) {
        return delegate.historicalCandles(request);
    }

    /**
     * Order placement — programmatic resilience so the breaker can be keyed per user
     * and exits exempted. Fully-qualified resilience4j types avoid clashing with the
     * @Retry/@CircuitBreaker annotation imports used by the read methods above.
     */
    @Override
    public OrderResponse placeOrder(OrderRequest request) {
        io.github.resilience4j.retry.Retry retry = retryRegistry.retry("brokerApi");
        Supplier<OrderResponse> call = () -> delegate.placeOrder(request);

        // 2) Exit / square-off orders are NEVER short-circuited. A stuck breaker must
        //    never be able to strand an open position. Keep transient retry only.
        if (isExitOrder(request)) {
            return io.github.resilience4j.retry.Retry.decorateSupplier(retry, call).get();
        }

        // 1) Entry orders: per-account breaker so one user's bad token can't trip
        //    placement for everyone. Retry wraps the breaker; CallNotPermitted is not
        //    in the retry-exceptions list, so an open breaker propagates immediately.
        String userKey = currentUserKey();
        io.github.resilience4j.circuitbreaker.CircuitBreaker cb = orderBreakerFor(userKey);
        Supplier<OrderResponse> decorated =
                io.github.resilience4j.retry.Retry.decorateSupplier(retry,
                        io.github.resilience4j.circuitbreaker.CircuitBreaker.decorateSupplier(cb, call));
        try {
            return decorated.get();
        } catch (CallNotPermittedException open) {
            log.error("[ResilientBroker] placeOrder CIRCUIT OPEN (user={}) — ORDER REJECTED: {}",
                    userKey, open.getMessage());
            throw new BrokerException(
                    "Broker API circuit breaker open for user " + userKey
                            + " — order rejected. Retry later.", open);
        }
    }

    @Override
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerRead", fallbackMethod = "orderStatusFallback")
    public Optional<OrderResponse> orderStatus(String brokerOrderId) {
        return delegate.orderStatus(brokerOrderId);
    }

    @Override
    public void cancelOrder(String brokerOrderId) {
        delegate.cancelOrder(brokerOrderId);
    }

    @Override
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerRead", fallbackMethod = "ordersFallback")
    public List<OrderResponse> orders() {
        return delegate.orders();
    }

    @Override
    @Retry(name = "brokerApi")
    @CircuitBreaker(name = "brokerRead", fallbackMethod = "positionsFallback")
    public List<Position> positions() {
        return delegate.positions();
    }

    @Override
    public void subscribeMarketData(Collection<String> instrumentKeys, MarketDataListener listener) {
        delegate.subscribeMarketData(instrumentKeys, listener);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Exit/close orders are tagged with an EXIT- clientOrderId prefix. Err toward "let it close". */
    private boolean isExitOrder(OrderRequest request) {
        String id = request.clientOrderId();
        String tag = request.tag();
        return startsWithIgnoreCase(id, "EXIT")
                || startsWithIgnoreCase(id, "SQUAREOFF")
                || startsWithIgnoreCase(id, "CLOSE")
                || containsIgnoreCase(tag, "EXIT")
                || containsIgnoreCase(tag, "SQUAREOFF");
    }

    private String currentUserKey() {
        Long userId = UserContext.getUserId();
        return userId != null ? String.valueOf(userId) : "primary";
    }

    /**
     * Resolve the per-user order breaker. Uses the {@code brokerOrder} named config when
     * a profile defines it (application-docker.yml); otherwise falls back to the registry
     * default so non-docker profiles don't throw ConfigurationNotFoundException.
     */
    private io.github.resilience4j.circuitbreaker.CircuitBreaker orderBreakerFor(String userKey) {
        String name = "brokerOrder-" + userKey;
        if (circuitBreakerRegistry.getConfiguration(ORDER_CB_CONFIG).isPresent()) {
            return circuitBreakerRegistry.circuitBreaker(name, ORDER_CB_CONFIG);
        }
        return circuitBreakerRegistry.circuitBreaker(name);
    }

    private static boolean startsWithIgnoreCase(String s, String prefix) {
        return s != null && s.length() >= prefix.length()
                && s.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static boolean containsIgnoreCase(String s, String needle) {
        return s != null && s.toUpperCase().contains(needle.toUpperCase());
    }

    // ── Fallback methods for read ops (invoked when brokerRead circuit is open) ──

    private List<Instrument> downloadInstrumentsFallback(Throwable t) {
        log.error("[ResilientBroker] downloadInstruments CIRCUIT OPEN: {}", t.getMessage());
        return List.of();
    }

    private Optional<Quote> quoteFallback(String key, Throwable t) {
        log.debug("[ResilientBroker] quote CIRCUIT OPEN for {}: {}", key, t.getMessage());
        return Optional.empty();
    }

    private Map<String, Quote> quotesFallback(Collection<String> keys, Throwable t) {
        log.debug("[ResilientBroker] quotes CIRCUIT OPEN: {}", t.getMessage());
        return Map.of();
    }

    private List<Candle> historicalCandlesFallback(HistoricalDataRequest req, Throwable t) {
        log.debug("[ResilientBroker] historicalCandles CIRCUIT OPEN: {}", t.getMessage());
        return List.of();
    }

    private Optional<OrderResponse> orderStatusFallback(String orderId, Throwable t) {
        log.debug("[ResilientBroker] orderStatus CIRCUIT OPEN for {}: {}", orderId, t.getMessage());
        return Optional.empty();
    }

    private List<OrderResponse> ordersFallback(Throwable t) {
        log.debug("[ResilientBroker] orders CIRCUIT OPEN: {}", t.getMessage());
        return List.of();
    }

    private List<Position> positionsFallback(Throwable t) {
        log.debug("[ResilientBroker] positions CIRCUIT OPEN: {}", t.getMessage());
        return List.of();
    }
}
