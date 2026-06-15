package com.algo.trade.config;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.broker.RoutingBrokerClient;
import com.algo.trade.broker.paper.PaperBrokerClient;
import com.algo.trade.broker.zerodha.KiteAccessTokenStore;
import com.algo.trade.broker.zerodha.ZerodhaBrokerClient;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.KiteInstrumentCsvParser;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.marketdata.MockMarketDataGenerator;
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
    RestClient zerodhaRestClient(RestClient.Builder builder, TradingProperties properties,
                                 com.algo.trade.multiuser.SourceIpRoutingRequestFactory sourceIpFactory) {
        log.info("Configuring Zerodha REST client: baseUrl={}, timeout={} (per-user source-IP routing active)",
                properties.broker().baseUrl(), properties.broker().requestTimeout());
        // SEBI static-IP rule (Apr 2026): each user's API calls must originate from THEIR
        // whitelisted IP. The factory binds each request to the current user's sourceIp.
        return builder.baseUrl(properties.broker().baseUrl())
                .requestFactory(sourceIpFactory)
                .build();
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
    MarketDataService marketDataService(BrokerClient brokerClient,
                                        com.algo.trade.marketdata.LiveInstrumentCache liveInstrumentCache) {
        return new MarketDataService(brokerClient, liveInstrumentCache);
    }

    @Bean
    BrokerClient routingBrokerClient(
            TradingProperties properties,
            RestClient zerodhaRestClient,
            ObjectMapper objectMapper,
            KiteInstrumentCsvParser instrumentCsvParser,
            MockMarketDataGenerator mockMarketDataGenerator,
            KiteAccessTokenStore tokenStore,
            TradingStateService tradingStateService,
            // Must be constructor-passed: ZerodhaBrokerClient is built with `new`, so
            // @Autowired field injection never runs on it. Without this, per-user order
            // routing is dead and secondary users get TokenException on every order.
            com.algo.trade.multiuser.UserBrokerSessionManager userSessionManager
    ) {
        log.info("Configuring routing broker client: configuredMode={}, marketDataMode={}, executionMode={}, liveTradingEnabled={}, brokerName={}",
                properties.mode(), properties.marketDataMode(), properties.executionMode(),
                properties.liveTradingEnabled(), properties.broker().name());
        BrokerClient zerodhaClient = new ZerodhaBrokerClient(properties, zerodhaRestClient, objectMapper,
                instrumentCsvParser, tokenStore, userSessionManager);
        BrokerClient paperClient = new PaperBrokerClient(properties, mockMarketDataGenerator);
        return new RoutingBrokerClient(properties, zerodhaClient, paperClient, tradingStateService);
    }
}
