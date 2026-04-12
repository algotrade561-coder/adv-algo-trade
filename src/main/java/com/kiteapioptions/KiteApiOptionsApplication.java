package com.kiteapioptions;

import com.kiteapioptions.config.TradingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Spring Boot entrypoint for the Kite API options application.
 */
@SpringBootApplication
@EnableConfigurationProperties(TradingProperties.class)
public class KiteApiOptionsApplication {

    public static void main(String[] args) {
        SpringApplication.run(KiteApiOptionsApplication.class, args);
    }
}
