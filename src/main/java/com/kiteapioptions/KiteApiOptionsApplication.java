package com.kiteapioptions;

import com.kiteapioptions.config.TradingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Spring Boot entrypoint for the Kite API options application.
 */
@SpringBootApplication
@EnableConfigurationProperties(TradingProperties.class)
@EnableScheduling
public class KiteApiOptionsApplication {

    private static final Logger log = LoggerFactory.getLogger(KiteApiOptionsApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(KiteApiOptionsApplication.class, args);
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
