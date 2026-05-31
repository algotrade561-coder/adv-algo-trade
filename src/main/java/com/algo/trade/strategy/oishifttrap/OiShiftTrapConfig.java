package com.algo.trade.strategy.oishifttrap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Capture instrumentation defaults for OI Shift Trap data-week tuning.
 * All flags default ON — deploy jar and restart, no UI toggle required.
 */
@Component
@ConfigurationProperties(prefix = "oi-shift-trap")
public class OiShiftTrapConfig {

    private boolean captureEnabled = true;
    private boolean chainSnapshotWarmupEnabled = true;
    private boolean evalEpisodeDedupEnabled = true;
    private int evalEpisodeWindowSeconds = 60;
    private boolean forwardCheckpointsEnabled = true;
    private boolean confirmationShadowEnabled = true;
    private boolean exitMaeMfeEnabled = true;

    public boolean isCaptureEnabled() { return captureEnabled; }
    public void setCaptureEnabled(boolean v) { this.captureEnabled = v; }

    public boolean isChainSnapshotWarmupEnabled() { return chainSnapshotWarmupEnabled; }
    public void setChainSnapshotWarmupEnabled(boolean v) { this.chainSnapshotWarmupEnabled = v; }

    public boolean isEvalEpisodeDedupEnabled() { return evalEpisodeDedupEnabled; }
    public void setEvalEpisodeDedupEnabled(boolean v) { this.evalEpisodeDedupEnabled = v; }

    public int getEvalEpisodeWindowSeconds() { return evalEpisodeWindowSeconds; }
    public void setEvalEpisodeWindowSeconds(int v) { this.evalEpisodeWindowSeconds = Math.max(1, v); }

    public boolean isForwardCheckpointsEnabled() { return forwardCheckpointsEnabled; }
    public void setForwardCheckpointsEnabled(boolean v) { this.forwardCheckpointsEnabled = v; }

    public boolean isConfirmationShadowEnabled() { return confirmationShadowEnabled; }
    public void setConfirmationShadowEnabled(boolean v) { this.confirmationShadowEnabled = v; }

    public boolean isExitMaeMfeEnabled() { return exitMaeMfeEnabled; }
    public void setExitMaeMfeEnabled(boolean v) { this.exitMaeMfeEnabled = v; }
}
