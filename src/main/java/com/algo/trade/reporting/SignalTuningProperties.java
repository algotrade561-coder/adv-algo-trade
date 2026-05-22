package com.algo.trade.reporting;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Signal tuning report settings. The EOD scheduler is off by default on small instances;
 * use {@code POST /reports/signal-tuning/generate} when you want an on-demand run.
 */
@Component
@ConfigurationProperties(prefix = "signal-tuning")
public class SignalTuningProperties {

    /**
     * When false, the 15:30 IST cron does not load CSVs or build HTML (avoids heap spike on t3.small).
     * Manual generate via REST API is unaffected.
     */
    private boolean schedulerEnabled = false;

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }
}
