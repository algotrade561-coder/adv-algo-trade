package com.algo.trade.tuning.capture;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * YAML-driven defaults for {@link CaptureToggleService}. Registered via
 * {@code @EnableConfigurationProperties} on the application class — do <em>not</em>
 * add {@code @Component} (would create a duplicate bean).
 *
 * <pre>
 * tuning:
 *   capture:
 *     poll-interval-sec: 30   # DB → cache refresh cadence
 * </pre>
 */
@ConfigurationProperties(prefix = "tuning.capture")
public class CaptureToggleProperties {

    /** How often {@link CaptureToggleService} re-reads the DB. Default 30s. */
    private int pollIntervalSec = 30;

    public int getPollIntervalSec() { return pollIntervalSec; }
    public void setPollIntervalSec(int v) { this.pollIntervalSec = Math.max(5, v); }
}
