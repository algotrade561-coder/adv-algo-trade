package com.algo.trade.strategy.oimomentum;

/**
 * Configuration for OIMomentumStrategy — all thresholds configurable.
 * Loaded from application.yml under 'oi-momentum' prefix.
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "oi-momentum")
@org.springframework.stereotype.Component
public class OIMomentumConfig {

    // ── Momentum Detection ──
    private double momentumThresholdPercent = 0.08;      // Large candle threshold
    private double spikeThresholdPercent = 0.40;         // Event spike: index move in 10 min
    private int rolling30MinWindowSeconds = 1800;        // 30-min rolling window

    // ── Entry Filters ──
    /**
     * @deprecated Not wired — config.getMinOiChangePercent() is never called in
     *   OIMomentumStrategy. oiAvailable is set by raw zero-check, not this threshold.
     *   Kept for backward-compat; see minSqueezeOiDelta for the active floor.
     */
    @Deprecated
    private double minOiChangePercent = 1.5;
    private double pcrBullishThreshold = 1.1;            // PCR > 1.1 = bullish (was 1.2 — dead zone too wide)
    private double pcrBearishThreshold = 0.9;            // PCR < 0.9 = bearish (was 0.8 — dead zone too wide)
    /** Min |ceΔ| and |peΔ| for CASE3 squeeze range bypass (both must be negative). */
    private long minSqueezeOiDelta = 100_000L;

    // ── Entry Window ──
    /**
     * When new entries are permitted each day (IST, HH:mm).
     * Was hardcoded to 09:30–14:30 in isEntryWindow(); now configurable.
     * 09:25 avoids the erratic first-candle noise while capturing most of opening.
     * 14:55 allows closing-session momentum entries (14:30–15:00 is high-volume).
     */
    private String entryWindowStart = "09:25";
    private String entryWindowEnd = "14:55";

    // ── Trade Throttling ──
    private int maxTradesPerDay = 20;                    // Hard cap per index
    private int softTargetTradesPerDay = 15;             // Soft target per index
    private int maxReversalsPerDay = 3;                  // Max direction flips per index
    private int cooldownAfterSlSeconds = 90;             // Cooldown after SL hit (was 120)
    private int minimumHoldTimeSeconds = 45;             // Don't exit before 45s
    private int consecutiveLossPause = 3;                // Pause after N consecutive losses
    /**
     * % of softTargetTradesPerDay allowed during midday window before throttling kicks in.
     * e.g. 50 → allow softTarget * 0.50 = 7 trades before midday throttle engages.
     * Previously the code hardcoded /2 (50%), ignoring this field — now wired properly.
     */
    private int middayTradeReductionPercent = 50;

    // ── Exit Parameters ──
    private double stopLossPercent = 15;                 // Option premium SL %
    /**
     * @deprecated Not used in OIMomentumStrategy — trailing stop handles profit-taking.
     *   No fixed target is enforced. Kept for YAML backward-compat; has no runtime effect.
     */
    @Deprecated
    private double targetPercent = 25;
    private double trailingActivationPercent = 8;        // Trailing starts at +8% (was 12 — left orphan zone)
    private double trailingGapPercent = 8;               // Trail gap from peak
    private int squareoffHour = 15;
    private int squareoffMinute = 10;

    // ── Session Windows ──
    private String openingSessionStart = "09:16";        // Reference only; entry start controlled by entryWindowStart
    private String openingSessionEnd = "10:00";
    private String middayStart = "12:00";
    private String middayEnd = "13:00";                  // Was "13:30" — shortened dead zone by 30 min
    /**
     * @deprecated Not read by OIMomentumStrategy — entry cutoff now controlled by entryWindowEnd.
     */
    @Deprecated
    private String closingSessionStart = "15:00";

    // ── Adaptive Bias Engine (Stage 1) ────────────────────────────────────────
    /**
     * Minimum bias confidence score (0–100) required before an entry is allowed.
     *
     * Threshold calibration:
     *   Case 1 (M+OI+PCR align): raw 75 → passes easily at 45
     *   Case 2 (M+PCR, no OI):   raw 40 → needs BAL(+15) to reach 55; passes at 45 without BAL
     *   Case 3 (M+OI, PCR 0):    raw 55 → passes at 45; survives DECAY(-10) at 45
     *
     * Lowered from 55 → 45: threshold of 55 was inadvertently blocking Case 2 (max raw 40)
     * and Case 3 with any decay (55−20=35). All genuine Case 1 setups still score 60–90.
     */
    private int biasConfidenceThreshold = 45;
    /**
     * Consecutive ticks where bias score ≥ threshold AND same direction before entry.
     * 2 ticks = 2 seconds — sufficient at 1-second tick rate to filter single-tick noise
     * while not delaying genuine momentum signals.
     * Reduced from 3: the extra second adds latency without materially improving quality.
     */
    private int biasConfirmationTicks = 2;
    /**
     * Seconds after the last OI advancement before the OI contribution starts decaying.
     * Raised from 90 → 180: NSE OI is sampled once per minute into the ring buffer.
     * At 90s, a single delayed OI sample (ring buffer up to 60s late at open) triggers decay.
     * 180s = 3 OI update cycles — genuinely stale before penalising.
     */
    private int biasDecaySeconds = 180;
    /**
     * Score points deducted when OI signal is stale (past biasDecaySeconds).
     * Reduced from 20 → 10: softer penalty preserves valid Case 1/3 setups through brief
     * OI quiet periods (midday lulls, early morning). -20 was eliminating Case 3 entirely
     * (55 − 20 = 35, always below threshold).
     */
    private int biasDecayPenalty = 10;

    // ── Enabled/Paper ──
    private boolean enabled = true;
    private boolean paperTrading = false;

    // Getters and setters
    public double getMomentumThresholdPercent() { return momentumThresholdPercent; }
    public void setMomentumThresholdPercent(double v) { this.momentumThresholdPercent = v; }
    public double getSpikeThresholdPercent() { return spikeThresholdPercent; }
    public void setSpikeThresholdPercent(double v) { this.spikeThresholdPercent = v; }
    public int getRolling30MinWindowSeconds() { return rolling30MinWindowSeconds; }
    public void setRolling30MinWindowSeconds(int v) { this.rolling30MinWindowSeconds = v; }
    public double getMinOiChangePercent() { return minOiChangePercent; }
    public void setMinOiChangePercent(double v) { this.minOiChangePercent = v; }
    public double getPcrBullishThreshold() { return pcrBullishThreshold; }
    public void setPcrBullishThreshold(double v) { this.pcrBullishThreshold = v; }
    public double getPcrBearishThreshold() { return pcrBearishThreshold; }
    public void setPcrBearishThreshold(double v) { this.pcrBearishThreshold = v; }
    public long getMinSqueezeOiDelta() { return minSqueezeOiDelta; }
    public void setMinSqueezeOiDelta(long v) { this.minSqueezeOiDelta = v; }
    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int v) { this.maxTradesPerDay = v; }
    public int getSoftTargetTradesPerDay() { return softTargetTradesPerDay; }
    public void setSoftTargetTradesPerDay(int v) { this.softTargetTradesPerDay = v; }
    public int getMaxReversalsPerDay() { return maxReversalsPerDay; }
    public void setMaxReversalsPerDay(int v) { this.maxReversalsPerDay = v; }
    public int getCooldownAfterSlSeconds() { return cooldownAfterSlSeconds; }
    public void setCooldownAfterSlSeconds(int v) { this.cooldownAfterSlSeconds = v; }
    public int getMinimumHoldTimeSeconds() { return minimumHoldTimeSeconds; }
    public void setMinimumHoldTimeSeconds(int v) { this.minimumHoldTimeSeconds = v; }
    public int getConsecutiveLossPause() { return consecutiveLossPause; }
    public void setConsecutiveLossPause(int v) { this.consecutiveLossPause = v; }
    public int getMiddayTradeReductionPercent() { return middayTradeReductionPercent; }
    public void setMiddayTradeReductionPercent(int v) { this.middayTradeReductionPercent = v; }
    public double getStopLossPercent() { return stopLossPercent; }
    public void setStopLossPercent(double v) { this.stopLossPercent = v; }
    public double getTargetPercent() { return targetPercent; }
    public void setTargetPercent(double v) { this.targetPercent = v; }
    public double getTrailingActivationPercent() { return trailingActivationPercent; }
    public void setTrailingActivationPercent(double v) { this.trailingActivationPercent = v; }
    public double getTrailingGapPercent() { return trailingGapPercent; }
    public void setTrailingGapPercent(double v) { this.trailingGapPercent = v; }
    public int getSquareoffHour() { return squareoffHour; }
    public void setSquareoffHour(int v) { this.squareoffHour = v; }
    public int getSquareoffMinute() { return squareoffMinute; }
    public void setSquareoffMinute(int v) { this.squareoffMinute = v; }
    public String getOpeningSessionStart() { return openingSessionStart; }
    public void setOpeningSessionStart(String v) { this.openingSessionStart = v; }
    public String getOpeningSessionEnd() { return openingSessionEnd; }
    public void setOpeningSessionEnd(String v) { this.openingSessionEnd = v; }
    public String getMiddayStart() { return middayStart; }
    public void setMiddayStart(String v) { this.middayStart = v; }
    public String getMiddayEnd() { return middayEnd; }
    public void setMiddayEnd(String v) { this.middayEnd = v; }
    @Deprecated public String getClosingSessionStart() { return closingSessionStart; }
    @Deprecated public void setClosingSessionStart(String v) { this.closingSessionStart = v; }
    public String getEntryWindowStart() { return entryWindowStart; }
    public void setEntryWindowStart(String v) { this.entryWindowStart = v; }
    public String getEntryWindowEnd() { return entryWindowEnd; }
    public void setEntryWindowEnd(String v) { this.entryWindowEnd = v; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isPaperTrading() { return paperTrading; }
    public void setPaperTrading(boolean v) { this.paperTrading = v; }

    // Bias engine getters/setters
    public int getBiasConfidenceThreshold() { return biasConfidenceThreshold; }
    public void setBiasConfidenceThreshold(int v) { this.biasConfidenceThreshold = v; }
    public int getBiasConfirmationTicks() { return biasConfirmationTicks; }
    public void setBiasConfirmationTicks(int v) { this.biasConfirmationTicks = v; }
    public int getBiasDecaySeconds() { return biasDecaySeconds; }
    public void setBiasDecaySeconds(int v) { this.biasDecaySeconds = v; }
    public int getBiasDecayPenalty() { return biasDecayPenalty; }
    public void setBiasDecayPenalty(int v) { this.biasDecayPenalty = v; }
}
