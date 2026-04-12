package com.kiteapioptions.broker;

import com.kiteapioptions.domain.BrokerSession;
import com.kiteapioptions.domain.Candle;
import com.kiteapioptions.domain.HistoricalDataRequest;
import com.kiteapioptions.domain.Instrument;
import com.kiteapioptions.domain.OrderRequest;
import com.kiteapioptions.domain.OrderResponse;
import com.kiteapioptions.domain.Position;
import com.kiteapioptions.domain.Quote;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Broker-neutral boundary for market data, orders, and account state.
 */
public interface BrokerClient {

    BrokerSession session();

    List<Instrument> downloadInstruments();

    Optional<Quote> quote(String instrumentKey);

    Map<String, Quote> quotes(Collection<String> instrumentKeys);

    List<Candle> historicalCandles(HistoricalDataRequest request);

    OrderResponse placeOrder(OrderRequest request);

    Optional<OrderResponse> orderStatus(String brokerOrderId);

    List<OrderResponse> orders();

    List<Position> positions();

    void subscribeMarketData(Collection<String> instrumentKeys, MarketDataListener listener);
}
