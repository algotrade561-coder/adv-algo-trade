package com.algo.trade;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.PositionSyncProperties;
import com.algo.trade.config.LiquidityExitProperties;
import com.algo.trade.config.SpreadTradingProperties;
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
@EnableConfigurationProperties({TradingProperties.class, PositionSyncProperties.class,
        SpreadTradingProperties.class, LiquidityExitProperties.class})
@EnableScheduling
public class AdvancedAlgoTradeApplication {

    private static final Logger log = LoggerFactory.getLogger(AdvancedAlgoTradeApplication.class);

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
        System.setProperty("user.timezone", "Asia/Kolkata");
        log.info("=== ADV-ALGO-TRADE STARTING === build={}, java={}, os={}",
                AdvancedAlgoTradeApplication.class.getPackage().getImplementationVersion(),
                System.getProperty("java.version"),
                System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        // H2 stale lock cleanup — prevents startup crash after unclean shutdown
        cleanStaleLockFiles();
        SpringApplication.run(AdvancedAlgoTradeApplication.class, args);
    }

    private static void cleanStaleLockFiles() {
        java.nio.file.Path dataDir = java.nio.file.Path.of("data");
        if (!java.nio.file.Files.isDirectory(dataDir)) return;
        try (var files = java.nio.file.Files.list(dataDir)) {
            files.filter(p -> p.getFileName().toString().endsWith(".lock.db"))
                    .forEach(lockFile -> {
                        try {
                            java.nio.file.Files.deleteIfExists(lockFile);
                            log.info("Deleted stale H2 lock file: {}", lockFile);
                        } catch (Exception e) {
                            log.warn("Could not delete lock file {}: {}", lockFile, e.getMessage());
                        }
                    });
        } catch (Exception e) {
            log.debug("Lock file cleanup skipped: {}", e.getMessage());
        }
    }

    @Bean
    ApplicationRunner logStartupConfiguration(TradingProperties properties, GlobalConfigService globalConfigService) {
        return args -> log.info("Application started: mode={}, liveTradingEnabled={}, timezone={}, broker={}, entryWindow={} to {}, forcedExitTime={}, maxTradesPerDay={}, maxDailyLossPercent={}, algoSchedulerEnabled={}, algoScanIntervalMs={}",
                properties.mode(),
                properties.liveTradingEnabled(),
                properties.timezone(),
                properties.broker().name(),
                globalConfigService.getEntryStartTime(),
                globalConfigService.getEntryCutoffTime(),
                globalConfigService.getForcedExitTime(),
                globalConfigService.getMaxTradesPerDay(),
                globalConfigService.getMaxDailyLossPercent(),
                properties.algo().schedulerEnabled(),
                properties.algo().scanIntervalMs());
    }
}
