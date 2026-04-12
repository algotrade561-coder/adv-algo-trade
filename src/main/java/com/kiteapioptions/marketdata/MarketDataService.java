package com.kiteapioptions.marketdata;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.HistoricalDataRequest;
import com.kiteapioptions.domain.Quote;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Broker-backed market data facade used by later strategy and backtest modules.
 */
public class MarketDataService {

    private final BrokerClient brokerClient;

    public MarketDataService(BrokerClient brokerClient) {
        this.brokerClient = brokerClient;
    }

    public Optional<Quote> quote(String instrumentKey) {
        return brokerClient.quote(instrumentKey);
    }

    public Map<String, Quote> quotes(Collection<String> instrumentKeys) {
        return brokerClient.quotes(instrumentKeys);
    }

    public List<Candle> historicalCandles(HistoricalDataRequest request) {
        return brokerClient.historicalCandles(request);
    }
}
