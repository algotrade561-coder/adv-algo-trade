package com.algo.trade;

import com.algo.trade.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

/**
 * Spring Boot entrypoint for the Kite API options application.
 */
@SpringBootApplication
@EnableConfigurationProperties(TradingProperties.class)
@EnableScheduling
public class AdvancedAlgoTradeApplication {

    private static final Logger log = LoggerFactory.getLogger(AdvancedAlgoTradeApplication.class);

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        System.setProperty("user.timezone", "Asia/Kolkata");
        SpringApplication.run(AdvancedAlgoTradeApplication.class, args);
    }

    @Bean
    ApplicationRunner logStartupConfiguration(TradingProperties properties) {
        return args -> log.info("Application started: mode={}, liveTradingEnabled={}, timezone={}, broker={}, entryWindow={} to {}, forcedExitTime={}, maxTradesPerDay={}, maxDailyLossPercent={}, algoSchedulerEnabled={}, algoScanIntervalMs={}",
                properties.mode(),
                properties.liveTradingEnabled(),
                properties.timezone(),
                properties.broker().name(),
                properties.entry().entryStartTime(),
                properties.entry().entryCutoffTime(),
                properties.exit().forcedExitTime(),
                properties.risk().maxTradesPerDay(),
                properties.risk().maxDailyLossPercent(),
                properties.algo().schedulerEnabled(),
                properties.algo().scanIntervalMs());
    }
}
