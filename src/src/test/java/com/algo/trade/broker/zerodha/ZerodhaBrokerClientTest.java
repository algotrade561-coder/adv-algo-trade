package com.algo.trade.broker.zerodha;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.domain.OrderType;
import com.algo.trade.domain.ProductType;
import com.algo.trade.marketdata.KiteInstrumentCsvParser;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ZerodhaBrokerClientTest {

    @Test
    void rejectsLiveOrderWhenLiveTradingFlagIsDisabled() {
        ZerodhaBrokerClient client = new ZerodhaBrokerClient(defaultProperties(),
                RestClient.create("https://api.kite.trade"), new ObjectMapper(), new KiteInstrumentCsvParser(),
                new KiteAccessTokenStore(defaultProperties()));
        OrderRequest request = new OrderRequest("live-1", "NFO:NIFTY24APR24000CE", OrderSide.BUY,
                OrderType.MARKET, ProductType.MIS, 75, Optional.empty(), "safety-test");

        var response = client.placeOrder(request);

        assertThat(response.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(response.rejectionReason()).contains("Live trading is disabled by configuration");
    }

    private TradingProperties defaultProperties() {
        return new TradingProperties(null, false, null, null, null, null, null, null, null, null, null, null);
    }
}
