package com.kiteapioptions.config;

import com.kiteapioptions.broker.BrokerClient;
import com.kiteapioptions.broker.paper.PaperBrokerClient;
import com.kiteapioptions.broker.zerodha.KiteAccessTokenStore;
import com.kiteapioptions.broker.zerodha.ZerodhaBrokerClient;
import com.kiteapioptions.domain.TradingMode;
import com.kiteapioptions.marketdata.InstrumentCache;
import com.kiteapioptions.marketdata.KiteInstrumentCsvParser;
import com.kiteapioptions.marketdata.MarketDataService;
import com.kiteapioptions.marketdata.MockMarketDataGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class BrokerConfiguration {

    @Bean
    RestClient zerodhaRestClient(RestClient.Builder builder, TradingProperties properties) {
        return builder.baseUrl(properties.broker().baseUrl()).build();
    }

    @Bean
    KiteInstrumentCsvParser kiteInstrumentCsvParser() {
        return new KiteInstrumentCsvParser();
    }

    @Bean
    MockMarketDataGenerator mockMarketDataGenerator() {
        return new MockMarketDataGenerator();
    }

    @Bean
    InstrumentCache instrumentCache(BrokerClient brokerClient) {
        return new InstrumentCache(brokerClient);
    }

    @Bean
    MarketDataService marketDataService(BrokerClient brokerClient) {
        return new MarketDataService(brokerClient);
    }

    @Bean
    BrokerClient brokerClient(
            TradingProperties properties,
            RestClient zerodhaRestClient,
            ObjectMapper objectMapper,
            KiteInstrumentCsvParser instrumentCsvParser,
            MockMarketDataGenerator mockMarketDataGenerator,
            KiteAccessTokenStore tokenStore
    ) {
        if (properties.mode() == TradingMode.LIVE) {
            return new ZerodhaBrokerClient(properties, zerodhaRestClient, objectMapper, instrumentCsvParser, tokenStore);
        }
        return new PaperBrokerClient(properties, mockMarketDataGenerator);
    }
}
