package com.algo.trade.marketdata;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.Quote;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Market data facade.
 *
 * Quote resolution order:
 *   1. LiveInstrumentCache — updated on every WebSocket tick (zero latency, no REST call)
 *   2. BrokerClient REST   — fallback when WebSocket cache has no data for the key
 *
 * Historical candles always go to REST (WebSocket only provides live ticks).
 */
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final BrokerClient brokerClient;
    private final LiveInstrumentCache liveInstrumentCache;

    public MarketDataService(BrokerClient brokerClient, LiveInstrumentCache liveInstrumentCache) {
        this.brokerClient = brokerClient;
        this.liveInstrumentCache = liveInstrumentCache;
    }

    public Optional<Quote> quote(String instrumentKey) {
        // LiveInstrumentCache only stores option instruments (CE/PE).
        // Index spot keys like "NSE:NIFTY 50" are not in the cache — go straight to REST.
        // Only try cache for option instrument keys (trading symbols don't contain spaces).
        if (instrumentKey != null && instrumentKey.contains(":")) {
            String symbol = instrumentKey.split(":", 2)[1];
            boolean isOptionSymbol = !symbol.contains(" ") && (symbol.endsWith("CE") || symbol.endsWith("PE"));
            if (isOptionSymbol) {
                Optional<Quote> live = liveInstrumentCache.getBySymbol(symbol)
                        .filter(o -> o.getLastPrice() > 0)
                        .map(o -> new Quote(
                                instrumentKey, Instant.now(),
                                java.math.BigDecimal.valueOf(o.getLastPrice()),
                                o.getVolume(), o.getOpenInterest(),
                                o.getImpliedVolatility() > 0 ? Optional.of(java.math.BigDecimal.valueOf(o.getImpliedVolatility())) : Optional.empty(),
                                o.getBestBid() > 0 ? Optional.of(java.math.BigDecimal.valueOf(o.getBestBid())) : Optional.empty(),
                                o.getBestAsk() > 0 ? Optional.of(java.math.BigDecimal.valueOf(o.getBestAsk())) : Optional.empty()));
                if (live.isPresent()) {
                    log.debug("Quote from WebSocket cache: instrumentKey={} price={}", instrumentKey, live.get().lastPrice());
                    return live;
                }
            }
        }
        // Fall back to REST for index spot quotes and any cache misses
        log.debug("Quote from REST: instrumentKey={}", instrumentKey);
        return brokerClient.quote(instrumentKey);
    }

    public Map<String, Quote> quotes(Collection<String> instrumentKeys) {
        if (instrumentKeys == null || instrumentKeys.isEmpty()) return Map.of();
        Map<String, Quote> result = new LinkedHashMap<>();
        for (String key : instrumentKeys) {
            quote(key).ifPresent(q -> result.put(key, q));
        }
        // For any keys not resolved from cache, batch fetch from REST
        List<String> missing = instrumentKeys.stream()
                .filter(k -> !result.containsKey(k))
                .toList();
        if (!missing.isEmpty()) {
            log.debug("Quotes batch REST fallback: missing={}", missing.size());
            result.putAll(brokerClient.quotes(missing));
        }
        return Map.copyOf(result);
    }

    public List<Candle> historicalCandles(HistoricalDataRequest request) {
        log.info("Historical candles requested: instrumentKey={}, timeframe={}, from={}, to={}",
                request.instrumentKey(), request.timeframe(), request.from(), request.to());
        List<Candle> candles = brokerClient.historicalCandles(request);
        log.info("Historical candles response: instrumentKey={}, count={}", request.instrumentKey(), candles.size());
        return candles;
    }

    /** Convenience overload — fetches today's candles from market open to now. Used by candle seeding on restart. */
    public List<Candle> historicalCandles(String instrumentKey, com.algo.trade.domain.Timeframe timeframe) {
        var ist = java.time.ZoneId.of("Asia/Kolkata");
        var todayOpen = java.time.LocalDate.now(ist).atTime(9, 15).atZone(ist).toInstant();
        var now = Instant.now();
        if (!todayOpen.isBefore(now)) return List.of();
        return historicalCandles(new HistoricalDataRequest(instrumentKey, todayOpen, now, timeframe, true));
    }
}
