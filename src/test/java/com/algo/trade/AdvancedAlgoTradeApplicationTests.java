package com.algo.trade;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = {
        "trading.mode=PAPER",
        "trading.market-data-mode=MOCK",
        "trading.execution-mode=PAPER",
        "trading.live-trading-enabled=false",
        "trading.broker.auto-login-on-startup=false",
        "trading.broker.ngrok-enabled=false",
        "trading.algo.scheduler-enabled=false",
        "trading.algo.auto-start-scanner-after-login=false",
        "spring.datasource.url=jdbc:h2:mem:kite-api-options-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AdvancedAlgoTradeApplicationTests {

    @Test
    void contextLoads() {
    }
}
