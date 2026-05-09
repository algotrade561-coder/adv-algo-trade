package com.algo.trade.broker;

import com.algo.trade.domain.BrokerSession;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.HistoricalDataRequest;
import com.algo.trade.domain.Instrument;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.Position;
import com.algo.trade.domain.Quote;
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

    /** Cancel a pending order by broker order ID. */
    default void cancelOrder(String brokerOrderId) {
        // Default no-op — implementations should override
    }

    List<OrderResponse> orders();

    List<Position> positions();

    void subscribeMarketData(Collection<String> instrumentKeys, MarketDataListener listener);
}
