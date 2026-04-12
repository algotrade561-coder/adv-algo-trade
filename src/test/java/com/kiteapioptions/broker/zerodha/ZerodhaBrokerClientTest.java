package com.kiteapioptions.broker.zerodha;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OrderRequest;
import com.kiteapioptions.domain.OrderSide;
import com.kiteapioptions.domain.OrderStatus;
import com.kiteapioptions.domain.OrderType;
import com.kiteapioptions.domain.ProductType;
import com.kiteapioptions.marketdata.KiteInstrumentCsvParser;
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
