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

    /**
     * Reject on-demand / UI generate during cash session (09:15–15:30 IST, Mon–Fri).
     */
    private boolean blockDuringMarketHours = true;

    /**
     * Load forward MFE/MAE candles for BUY signals only (streams entry-candles.csv; much lighter than full load).
     */
    private boolean loadForwardCandles = true;

    /**
     * When false, {@link com.algo.trade.strategy.StrategySignalCsvRecorder} skips appending to
     * {@code entry-candles.csv}. Interim mitigation while the unified tuning-event pipeline lands;
     * see {@code important/SIGNAL_CAPTURE_TUNING_REDESIGN.md}. OI Momentum and OI Shift Trap capture
     * forward returns through dedicated services, so disabling this only affects the cross-strategy
     * 45m MFE/MAE table for the few non-OI BUYs per period.
     */
    private boolean writeCandles = false;

    public boolean isSchedulerEnabled() {
        return schedulerEnabled;
    }

    public void setSchedulerEnabled(boolean schedulerEnabled) {
        this.schedulerEnabled = schedulerEnabled;
    }

    public boolean isBlockDuringMarketHours() {
        return blockDuringMarketHours;
    }

    public void setBlockDuringMarketHours(boolean blockDuringMarketHours) {
        this.blockDuringMarketHours = blockDuringMarketHours;
    }

    public boolean isLoadForwardCandles() {
        return loadForwardCandles;
    }

    public void setLoadForwardCandles(boolean loadForwardCandles) {
        this.loadForwardCandles = loadForwardCandles;
    }

    public boolean isWriteCandles() {
        return writeCandles;
    }

    public void setWriteCandles(boolean writeCandles) {
        this.writeCandles = writeCandles;
    }
}
