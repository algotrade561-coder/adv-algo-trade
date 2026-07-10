package com.algo.trade.multiuser.ip;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * No-op OS configurer used when {@code ip-automation.enabled} is false (default) or absent.
 * Never touches the OS network stack.
 */
@Component
@ConditionalOnProperty(name = "ip-automation.enabled", havingValue = "false", matchIfMissing = true)
public class MockOsSecondaryIpConfigurer implements OsSecondaryIpConfigurer {

    private static final Logger log = LoggerFactory.getLogger(MockOsSecondaryIpConfigurer.class);

    @Override public void add(String privateIp, int prefixLength) {
        log.info("[MockOsSecondaryIp] (no-op) add {}/{}", privateIp, prefixLength);
    }

    @Override public void remove(String privateIp, int prefixLength) {
        log.info("[MockOsSecondaryIp] (no-op) remove {}/{}", privateIp, prefixLength);
    }
}
