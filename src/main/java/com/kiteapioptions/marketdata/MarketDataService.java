package com.kiteapioptions.marketdata;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.HistoricalDataRequest;
import com.kiteapioptions.domain.Quote;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Broker-backed market data facade used by later strategy and backtest modules.
 */
public class MarketDataService {

    private static final Logger log = LoggerFactory.getLogger(MarketDataService.class);

    private final BrokerClient brokerClient;

    public MarketDataService(BrokerClient brokerClient) {
        this.brokerClient = brokerClient;
    }

    public Optional<Quote> quote(String instrumentKey) {
        log.debug("Quote requested: instrumentKey={}", instrumentKey);
        Optional<Quote> quote = brokerClient.quote(instrumentKey);
        log.debug("Quote response: instrumentKey={}, present={}", instrumentKey, quote.isPresent());
        return quote;
    }

    public Map<String, Quote> quotes(Collection<String> instrumentKeys) {
        log.debug("Quotes requested: count={}", instrumentKeys == null ? 0 : instrumentKeys.size());
        Map<String, Quote> quotes = brokerClient.quotes(instrumentKeys);
        log.debug("Quotes response: requestedCount={}, returnedCount={}",
                instrumentKeys == null ? 0 : instrumentKeys.size(), quotes.size());
        return quotes;
    }

    public List<Candle> historicalCandles(HistoricalDataRequest request) {
        log.info("Historical candles requested: instrumentKey={}, timeframe={}, from={}, to={}, includeOpenInterest={}",
                request.instrumentKey(), request.timeframe(), request.from(), request.to(), request.includeOpenInterest());
        List<Candle> candles = brokerClient.historicalCandles(request);
        log.info("Historical candles response: instrumentKey={}, count={}", request.instrumentKey(), candles.size());
        return candles;
    }
}
