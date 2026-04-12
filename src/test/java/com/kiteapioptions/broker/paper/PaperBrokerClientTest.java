package com.kiteapioptions.broker.paper;

import static org.assertj.core.api.Assertions.assertThat;

import com.kiteapioptions.config.TradingProperties;
import com.kiteapioptions.domain.OrderRequest;
import com.kiteapioptions.domain.OrderSide;
import com.kiteapioptions.domain.OrderStatus;
import com.kiteapioptions.domain.OrderType;
import com.kiteapioptions.domain.ProductType;
import com.kiteapioptions.marketdata.MockMarketDataGenerator;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PaperBrokerClientTest {

    @Test
    void fillsPaperBuyOrderAndCreatesPosition() {
        PaperBrokerClient brokerClient = new PaperBrokerClient(defaultProperties(), new MockMarketDataGenerator());
        OrderRequest request = new OrderRequest("paper-1", "NFO:NIFTY24APR24000CE", OrderSide.BUY,
                OrderType.MARKET, ProductType.MIS, 75, Optional.empty(), "test");

        var response = brokerClient.placeOrder(request);

        assertThat(response.status()).isEqualTo(OrderStatus.COMPLETE);
        assertThat(response.brokerOrderId()).contains("PAPER-paper-1");
        assertThat(brokerClient.positions()).hasSize(1);
        assertThat(brokerClient.positions().getFirst().quantity()).isEqualTo(75);
    }

    @Test
    void rejectsDuplicatePaperClientOrderId() {
        PaperBrokerClient brokerClient = new PaperBrokerClient(defaultProperties(), new MockMarketDataGenerator());
        OrderRequest request = new OrderRequest("paper-1", "NFO:NIFTY24APR24000CE", OrderSide.BUY,
                OrderType.MARKET, ProductType.MIS, 75, Optional.empty(), "test");

        brokerClient.placeOrder(request);
        var duplicate = brokerClient.placeOrder(request);

        assertThat(duplicate.status()).isEqualTo(OrderStatus.REJECTED);
        assertThat(duplicate.rejectionReason()).contains("Duplicate paper order clientOrderId");
    }

    private TradingProperties defaultProperties() {
        return new TradingProperties(null, false, null, null, null, null, null, null, null, null, null, null);
    }
}
