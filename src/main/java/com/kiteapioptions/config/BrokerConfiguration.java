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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class BrokerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(BrokerConfiguration.class);

    @Bean
    RestClient zerodhaRestClient(RestClient.Builder builder, TradingProperties properties) {
        log.info("Configuring Zerodha REST client: baseUrl={}, timeout={}",
                properties.broker().baseUrl(), properties.broker().requestTimeout());
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
        log.info("Selecting broker client: configuredMode={}, liveTradingEnabled={}, brokerName={}",
                properties.mode(), properties.liveTradingEnabled(), properties.broker().name());
        if (properties.mode() == TradingMode.LIVE) {
            log.warn("LIVE broker client selected. Order placement is still gated by liveTradingEnabled={}",
                    properties.liveTradingEnabled());
            return new ZerodhaBrokerClient(properties, zerodhaRestClient, objectMapper, instrumentCsvParser, tokenStore);
        }
        log.info("Paper broker client selected: startingCash={}, slippagePercent={}",
                properties.paper().startingCash(), properties.paper().slippagePercent());
        return new PaperBrokerClient(properties, mockMarketDataGenerator);
    }
}
