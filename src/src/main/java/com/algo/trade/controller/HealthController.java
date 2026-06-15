package com.algo.trade.controller;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.UnderlyingSymbol;
import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    private static final Logger log = LoggerFactory.getLogger(HealthController.class);

    private final BrokerClient brokerClient;
    private final DataSource dataSource;
    private final InstrumentCache instrumentCache;
    private final LiveInstrumentCache liveInstrumentCache;
    private final MarketDataService marketDataService;
    private final TradingStateService tradingStateService;
    private final TradingProperties properties;

    public HealthController(BrokerClient brokerClient, DataSource dataSource,
                             InstrumentCache instrumentCache,
                             LiveInstrumentCache liveInstrumentCache,
                             MarketDataService marketDataService,
                             TradingStateService tradingStateService,
                             TradingProperties properties) {
        this.brokerClient = brokerClient;
        this.dataSource = dataSource;
        this.instrumentCache = instrumentCache;
        this.liveInstrumentCache = liveInstrumentCache;
        this.marketDataService = marketDataService;
        this.tradingStateService = tradingStateService;
        this.properties = properties;
    }

    @GetMapping("/health/jvm")
    public Map<String, Object> jvmHealth() {
        Runtime rt = Runtime.getRuntime();
        long used = rt.totalMemory() - rt.freeMemory();
        long total = rt.totalMemory();
        long max = rt.maxMemory();
        long gcCount = 0, gcMs = 0;
        for (var gc : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (gc.getCollectionCount() > 0) gcCount += gc.getCollectionCount();
            if (gc.getCollectionTime() > 0) gcMs += gc.getCollectionTime();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("heapUsedMb",     used  / (1024 * 1024));
        m.put("heapTotalMb",    total / (1024 * 1024));
        m.put("heapMaxMb",      max   / (1024 * 1024));
        m.put("heapUsedPercent", (int) (used * 100 / max));
        m.put("threadCount",    java.lang.management.ManagementFactory.getThreadMXBean().getThreadCount());
        m.put("gcCollections",  gcCount);
        m.put("gcPauseMs",      gcMs);
        m.put("uptimeMs",       java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime());
        return m;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Instant timestamp = Instant.now();
        log.debug("Health endpoint called: timestamp={}", timestamp);
        return Map.of("status", "UP", "timestamp", timestamp);
    }

    @GetMapping("/health/broker")
    public Map<String, Object> brokerHealth() {
        try {
            var session = brokerClient.session();
            return Map.of(
                    "status", session.authenticated() ? "UP" : "DOWN",
                    "broker", session.brokerName(),
                    "userId", session.userId() == null ? "" : session.userId(),
                    "authenticatedAt", session.authenticatedAt() == null ? "" : session.authenticatedAt()
            );
        } catch (Exception ex) {
            log.warn("Broker health check failed: {}", ex.getMessage());
            return Map.of("status", "DOWN", "error", ex.getMessage());
        }
    }

    @GetMapping("/health/database")
    public Map<String, Object> databaseHealth() {
        try (Connection connection = dataSource.getConnection()) {
            return Map.of(
                    "status", connection.isValid(2) ? "UP" : "DOWN",
                    "databaseProduct", connection.getMetaData().getDatabaseProductName()
            );
        } catch (Exception ex) {
            log.warn("Database health check failed: {}", ex.getMessage());
            return Map.of("status", "DOWN", "error", ex.getMessage());
        }
    }

    /**
     * Diagnostic endpoint — shows exactly what the scanner sees at each step.
     * Call this to find out why no signals are being generated.
     * GET /health/scan
     */
    @GetMapping("/health/scan")
    public Map<String, Object> scanDiagnostic() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", Instant.now());
        result.put("scannerRunning", tradingStateService.running());
        result.put("killSwitch", tradingStateService.killSwitchEnabled());
        result.put("haltMode", tradingStateService.haltMode());
        result.put("dailyApproved", tradingStateService.isDailyApproved());
        result.put("marketDataMode", tradingStateService.marketDataMode());

        // Instrument cache
        int instrumentCount = instrumentCache.all().size();
        result.put("instrumentCacheSize", instrumentCount);
        result.put("liveInstrumentCacheReady", liveInstrumentCache.isReady());

        // For each underlying
        for (UnderlyingSymbol underlying : tradingStateService.enabledUnderlyings()) {
            Map<String, Object> u = new LinkedHashMap<>();

            // Spot quote
            String spotKey = properties.symbols().spotQuoteKeys().get(underlying);
            u.put("spotQuoteKey", spotKey);
            try {
                var spotQuote = marketDataService.quote(spotKey);
                u.put("spotQuotePresent", spotQuote.isPresent());
                spotQuote.ifPresent(q -> {
                    u.put("spotPrice", q.lastPrice());
                    u.put("spotQuoteAge", java.time.Duration.between(q.timestamp(), Instant.now()).toSeconds() + "s");
                });
            } catch (Exception e) {
                u.put("spotQuoteError", e.getMessage());
            }

            // Expiry
            try {
                var expiry = instrumentCache.nearestExpiry(underlying,
                        LocalDate.now(properties.timezone()), properties.symbols().defaultExpiry());
                u.put("nearestExpiry", expiry.map(Object::toString).orElse("NONE"));

                // Option count for that expiry
                if (expiry.isPresent()) {
                    long optionCount = instrumentCache.all().stream()
                            .filter(i -> i.tradable())
                            .filter(i -> i.underlying().filter(underlying::equals).isPresent())
                            .filter(i -> i.expiry().filter(expiry.get()::equals).isPresent())
                            .filter(i -> i.optionType().isPresent())
                            .count();
                    u.put("optionCountForExpiry", optionCount);

                    // ATM option quotes
                    var spotQuote = marketDataService.quote(spotKey);
                    if (spotQuote.isPresent()) {
                        java.math.BigDecimal spot = spotQuote.get().lastPrice();
                        var atmCe = instrumentCache.all().stream()
                                .filter(i -> i.tradable())
                                .filter(i -> i.underlying().filter(underlying::equals).isPresent())
                                .filter(i -> i.expiry().filter(expiry.get()::equals).isPresent())
                                .filter(i -> i.optionType().map(t -> t.name().equals("CE")).orElse(false))
                                .filter(i -> i.strike().isPresent())
                                .min(java.util.Comparator.comparing(i -> i.strike().get().subtract(spot).abs()))
                                .orElse(null);
                        if (atmCe != null) {
                            u.put("atmStrike", atmCe.strike().orElse(null));
                            u.put("atmCeKey", atmCe.instrumentKey());
                            try {
                                var ceQuote = marketDataService.quote(atmCe.instrumentKey());
                                u.put("atmCeQuotePresent", ceQuote.isPresent());
                                ceQuote.ifPresent(q -> u.put("atmCePrice", q.lastPrice()));
                            } catch (Exception e) {
                                u.put("atmCeQuoteError", e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                u.put("expiryError", e.getMessage());
            }

            result.put(underlying.name(), u);
        }
        return result;
    }
}
