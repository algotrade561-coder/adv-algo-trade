package com.algo.trade.strategy.oishifttrap;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for OI Shift Trap.
 * 2026-06-01: introduced single master toggle {@code enhancementsEnabled}
 * that gates ALL new features at once. Individual numeric parameters below are
 * the defaults that apply when the master toggle is ON. When OFF, strategy
 * behaves exactly as before — no behavior change.
 *
 * <p>Registered via {@code @EnableConfigurationProperties} on
 * {@code AdvancedAlgoTradeApplication}. Do <em>not</em> add {@code @Component}.</p>
 */
@ConfigurationProperties(prefix = "oi-shift-trap")
public class OiShiftTrapConfig {

    // ── Existing capture flags (kept as-is, default ON) ───────────────────────
    private boolean captureEnabled = true;
    private boolean chainSnapshotWarmupEnabled = true;
    private boolean evalEpisodeDedupEnabled = true;
    private int evalEpisodeWindowSeconds = 60;
    private boolean forwardCheckpointsEnabled = true;
    private boolean confirmationShadowEnabled = true;
    private boolean exitMaeMfeEnabled = true;

    // ── 2026-06-01 SINGLE MASTER TOGGLE — gates all new features ──────────────
    /**
     * When OFF (default), OI Shift Trap behaves exactly as it did before.
     * When ON, ALL the new features activate using the parameter values below:
     *  - Functional score gate (fixed dead gate)
     *  - Configurable trend lookback
     *  - Expiry-day adaptive thresholds (proximity + imbalance)
     *  - PCR required for entry
     *  - Distant-OI scan + convergence tracking
     *  - Fake-breakout / bull-bear trap blocker
     *  - OI absorption detector (price breaking through OI wall)
     *  - Pending signal mode (OI-first, wait for price)
     *  - Cross-index validation (2-of-3 agreement)
     *  - OI unwind exit (close on >X% drop in N min)
     *  - Liquidity hunt / OI magnet detector
     *  - Imbalance-only entry path (high-conviction shortcut)
     */
    private boolean enhancementsEnabled = false;

    // ── Tunable parameters used when enhancementsEnabled = true ───────────────
    private int trendLookbackCandles = 5;
    private int minConfidenceScore = 65;
    private int scoreBase = 30;
    private double expiryDayProximityPercent = 1.20;
    private double expiryDayImbalanceRatio = 1.30;
    private double nonExpiryProximityPercent = 0.75;
    private double nonExpiryImbalanceRatio = 1.50;
    private int distantOiMaxStrikes = 4;
    private double fakeBreakoutVolumeMultiplier = 2.0;
    private double fakeBreakoutReversalPercent = 0.30;
    private double oiAbsorptionWallOiThreshold = 200_000;
    private int pendingSignalTtlMinutes = 5;
    // Default 1 = single-index-safe (own registration counts; no peer needed).
    // Raise to 2 only when NIFTY + BANKNIFTY + SENSEX all evaluate in the same 30s window.
    private int crossIndexMinAgreement = 1;
    private double oiUnwindExitDropPercent = 10.0;
    private int oiUnwindExitWindowMinutes = 5;
    private double liquidityHuntProximityPercent = 0.30;
    private double imbalanceOnlyEntryRatio = 2.5;
    // Phase 5 — high-imbalance Telegram watch alert (no trade required).
    private double imbalanceAlertRatio = 4.0;
    private int imbalanceAlertCooldownMinutes = 5;

    // ── Existing getters/setters ─────────────────────────────────────────────
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

    // ── Master toggle ────────────────────────────────────────────────────────
    public boolean isEnhancementsEnabled() { return enhancementsEnabled; }
    public void setEnhancementsEnabled(boolean v) { this.enhancementsEnabled = v; }

    // ── Parameter getters/setters ────────────────────────────────────────────
    public int getTrendLookbackCandles() { return trendLookbackCandles; }
    public void setTrendLookbackCandles(int v) { this.trendLookbackCandles = Math.max(2, v); }
    public int getMinConfidenceScore() { return minConfidenceScore; }
    public void setMinConfidenceScore(int v) { this.minConfidenceScore = v; }
    public int getScoreBase() { return scoreBase; }
    public void setScoreBase(int v) { this.scoreBase = v; }
    public double getExpiryDayProximityPercent() { return expiryDayProximityPercent; }
    public void setExpiryDayProximityPercent(double v) { this.expiryDayProximityPercent = v; }
    public double getExpiryDayImbalanceRatio() { return expiryDayImbalanceRatio; }
    public void setExpiryDayImbalanceRatio(double v) { this.expiryDayImbalanceRatio = v; }
    public double getNonExpiryProximityPercent() { return nonExpiryProximityPercent; }
    public void setNonExpiryProximityPercent(double v) { this.nonExpiryProximityPercent = v; }
    public double getNonExpiryImbalanceRatio() { return nonExpiryImbalanceRatio; }
    public void setNonExpiryImbalanceRatio(double v) { this.nonExpiryImbalanceRatio = v; }
    public int getDistantOiMaxStrikes() { return distantOiMaxStrikes; }
    public void setDistantOiMaxStrikes(int v) { this.distantOiMaxStrikes = Math.max(1, v); }
    public double getFakeBreakoutVolumeMultiplier() { return fakeBreakoutVolumeMultiplier; }
    public void setFakeBreakoutVolumeMultiplier(double v) { this.fakeBreakoutVolumeMultiplier = v; }
    public double getFakeBreakoutReversalPercent() { return fakeBreakoutReversalPercent; }
    public void setFakeBreakoutReversalPercent(double v) { this.fakeBreakoutReversalPercent = v; }
    public double getOiAbsorptionWallOiThreshold() { return oiAbsorptionWallOiThreshold; }
    public void setOiAbsorptionWallOiThreshold(double v) { this.oiAbsorptionWallOiThreshold = v; }
    public int getPendingSignalTtlMinutes() { return pendingSignalTtlMinutes; }
    public void setPendingSignalTtlMinutes(int v) { this.pendingSignalTtlMinutes = Math.max(1, v); }
    public int getCrossIndexMinAgreement() { return crossIndexMinAgreement; }
    public void setCrossIndexMinAgreement(int v) { this.crossIndexMinAgreement = Math.max(1, Math.min(3, v)); }
    public double getOiUnwindExitDropPercent() { return oiUnwindExitDropPercent; }
    public void setOiUnwindExitDropPercent(double v) { this.oiUnwindExitDropPercent = v; }
    public int getOiUnwindExitWindowMinutes() { return oiUnwindExitWindowMinutes; }
    public void setOiUnwindExitWindowMinutes(int v) { this.oiUnwindExitWindowMinutes = Math.max(1, v); }
    public double getLiquidityHuntProximityPercent() { return liquidityHuntProximityPercent; }
    public void setLiquidityHuntProximityPercent(double v) { this.liquidityHuntProximityPercent = v; }
    public double getImbalanceOnlyEntryRatio() { return imbalanceOnlyEntryRatio; }
    public void setImbalanceOnlyEntryRatio(double v) { this.imbalanceOnlyEntryRatio = v; }
    public double getImbalanceAlertRatio() { return imbalanceAlertRatio; }
    public void setImbalanceAlertRatio(double v) { this.imbalanceAlertRatio = v; }
    public int getImbalanceAlertCooldownMinutes() { return imbalanceAlertCooldownMinutes; }
    public void setImbalanceAlertCooldownMinutes(int v) { this.imbalanceAlertCooldownMinutes = Math.max(1, v); }
}
