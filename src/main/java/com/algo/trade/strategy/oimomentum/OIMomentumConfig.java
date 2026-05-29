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
    /**
     * Premium SL %.
     * Backtest-optimised: 12% outperforms 15% by ~₹1k/trade on avg loss (no win-rate hit).
     * 15% was allowing too much premium decay before cutting; 12% exits cleaner on real reversals.
     */
    private double stopLossPercent = 12;                 // Option premium SL % (was 15)
    /**
     * @deprecated Not used in OIMomentumStrategy — trailing stop handles profit-taking.
     *   No fixed target is enforced. Kept for YAML backward-compat; has no runtime effect.
     */
    @Deprecated
    private double targetPercent = 25;
    /**
     * % gain from entry at which trailing stop activates.
     * Backtest-optimised: 5% outperforms 8% — locks in profit 3pp sooner, avoids the
     * "went up 7%, came all the way back" scenario. PF improves from 1.06 → 1.12.
     */
    private double trailingActivationPercent = 5;        // Trailing starts at +5% (was 8)
    /**
     * Gap between peak profit and trailing exit level.
     * Reduced from 8% → 5% to match new activation level — keeps the gap proportional.
     * Dynamic tightening still applies: gap shrinks 0.6pp per 1pp above activation.
     */
    private double trailingGapPercent = 5;               // Trail gap from peak (was 8)
    /**
     * Break-even stop: once peak profit ≥ this threshold, floor the SL to 0% (entry price).
     * Prevents profitable-then-reversed trades from becoming losses.
     * Set to 0 to disable. Default off — enable via YAML if desired.
     */
    private double breakEvenTriggerPercent = 0;          // 0 = disabled; set e.g. 5.0 to enable
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

    // ── Multi-timeframe Momentum ──────────────────────────────────────────────
    /**
     * Enable 5-min and 15-min window momentum as secondary entry triggers.
     * These fire when intraday momentum hasn't yet broken the 30-min level,
     * catching operator-driven short bursts earlier.
     */
    /**
     * Enable 5-min and 15-min window momentum as secondary entry triggers.
     * Backtest (62 trades, NIFTY): 5M adds 0 trades (redundant with 15M cascade).
     * 15M adds 9 trades but all net-negative (WR 57%, avg win ₹860 vs avg loss ₹1,598 = -₹2,710).
     * 30M alone: 53 trades, WR 68%, PF 1.74, net +₹21,660.
     * Default changed to false — only 30M breakouts qualify as entry signals.
     */
    private boolean multiTimeframeEnabled = false;
    /**
     * Threshold for 5-min window breakouts (percent above/below the 5-min high/low).
     * Slightly lower than the 30M threshold to be sensitive to short bursts.
     */
    private double shortTimeframeThresholdPct = 0.04;

    // ── Advanced Operator Signals ─────────────────────────────────────────────
    /**
     * Add OperatorFramework chain-accumulation bonus (0–20 pts) to BiasScore.
     * Bridges the 5-minute snapshot window with the 1-second tick loop.
     */
    private boolean operatorBonusEnabled = true;
    /**
     * Add bid-ask order book imbalance signal (+8 pts) to BiasScore.
     * When ATM option has bid qty >> ask qty in momentum direction, operators are accumulating.
     */
    private boolean bidAskImbalanceEnabled = true;
    /** Bid/(bid+ask) ratio threshold above which the imbalance signal fires. */
    private double bidAskImbalanceThreshold = 0.60;
    /**
     * Add IV skew signal (+8 pts) to BiasScore.
     * CE IV rising vs PE IV = unusual call demand = early bullish signal from operators.
     * Threshold: how far (CE_IV - PE_IV)/avg_IV must deviate to count as significant.
     */
    private boolean ivSkewEnabled = true;
    private double ivSkewThreshold = 0.15;
    /**
     * Add OI acceleration signal (+10 pts) to BiasScore.
     * Fires when 1-minute OI delta is ≥ 1.5× the average 3-minute per-minute rate,
     * indicating operators are ramping up NOW (not just steadily building).
     */
    private boolean oiVelocityEnabled = true;
    private double oiAccelerationMultiplier = 1.5;
    /**
     * Add max pain proximity signal (+10 pts) to BiasScore.
     * Operators are short options so they push price toward max pain before expiry.
     * Spot below max pain = they want to push UP = bullish confirmation and vice versa.
     */
    private boolean maxPainEnabled = true;
    /**
     * Min percent distance between spot and max pain to consider it a meaningful pull.
     * 0.15% = max pain is at least 0.15% away from current spot.
     */
    private double maxPainMinDistancePct = 0.15;

    // ── Re-entry Boost ────────────────────────────────────────────────────────
    /**
     * After a profitable exit, reduce confirmation ticks to 1 (from biasConfirmationTicks)
     * for the next entry in the same direction within reEntryBoostWindowSeconds.
     * Rationale: a just-profitable trade proves the direction is live; operators don't
     * reverse instantly, so re-entry confidence is higher.
     */
    private boolean reEntryBoostEnabled = true;
    private int reEntryBoostWindowSeconds = 300;

    // ── v3 Quality Improvements (audit 2026-05-28) ─────────────────────────
    // Each feature defaults OFF so existing live behaviour is unchanged after deploy.
    // Enable one at a time via YAML, monitor 1–2 sessions, then enable next.

    /**
     * Anti-pyramid: block re-entry on a strike whose previous close on this index
     * was a loss within antiPyramidCooldownMinutes. Prevents averaging into losers
     * (e.g. NIFTY 23950 CE −₹3,900 pyramid on 2026-05-27 at 11:49 + 11:51).
     * Default OFF.
     */
    private boolean antiPyramidEnabled = false;
    private int antiPyramidCooldownMinutes = 5;

    /**
     * Expiry-day OTM late cutoff: on the resolved expiry day, block all new entries
     * after expiryOtmCutoffTime. Prevents the SENSEX 75800 PE ₹38 → ₹0.95 theta-cliff
     * pattern (2026-05-27 14:55–15:08). Default OFF.
     */
    private boolean expiryOtmCutoffEnabled = false;
    private String expiryOtmCutoffTime = "14:45";

    /**
     * Daily loss circuit-breaker: halt all entries for the rest of the day when
     * cumulative dailyPnl drops below −dailyLossLimitRupees. Set 0 to disable.
     * Independent of dailyLossMultiplierOfAvgLoser — either trigger halts.
     * Default 0 (disabled).
     */
    private double dailyLossLimitRupees = 0;

    /**
     * Daily loss circuit-breaker (adaptive): halt all entries when cumulative
     * dailyPnl drops below −(multiplier × avg-loser-today). Avg-loser is the mean
     * of negative-PnL exits so far today. Recommended: 3.0. Set 0 to disable.
     * Default 0 (disabled).
     */
    private double dailyLossMultiplierOfAvgLoser = 0;

    /**
     * Consecutive-loss HARD halt: halt all entries for the rest of the day when
     * consecutiveLosses reaches this count. Distinct from consecutiveLossPause
     * (which is a soft pause). Set 0 to disable. Recommended: 5.
     * Default 0 (disabled).
     */
    private int consecutiveLossHaltCount = 0;

    /**
     * V3 OPERATOR pipeline master switch. When true, the entry path is routed through
     * V3EntryPipeline (regime → time-mode → OI signal → 4-gate → multi-strike picker →
     * conviction sizer). When false, the existing legacy pipeline is used.
     *
     * <p><b>Important (review #46):</b> the V3 pipeline (and its
     * {@link com.algo.trade.strategy.oimomentum.v3.V3DecisionRecorder}) is invoked
     * <i>only</i> when this flag is true. When v3-enabled is false, no V3 decisions
     * are evaluated and no rows are written to {@code data/v3-decisions/YYYY-MM-DD.csv}.
     * To collect V3 decision telemetry without affecting live entries, enable v3 AND
     * set {@code v3-shadow-mode: true}.
     *
     * <p>Default OFF (legacy live). Set true to route entries through V3.
     */
    private boolean v3Enabled = false;

    /**
     * Run V3 in shadow mode: pipeline evaluates and logs every decision, but the
     * actual entry still uses legacy logic. Use this to validate v3 decisions against
     * live behaviour before flipping v3Enabled=true.
     * Default false. Has no effect if v3Enabled is false.
     */
    private boolean v3ShadowMode = false;

    // ── Legacy enhancements (data-validated 29 May 2026) ──
    //
    // See OI_MOMENTUM_EMPIRICAL_REPLAY_RESULTS.md for the supporting figures.
    /**
     * P0-2: filter baseline CASE 1-5 by time-of-day mode. When true, AFTERNOON_POSITION
     * (13:30–14:45) + LAST_HOUR (14:45–15:10) entries are skipped, and MIDDAY_DISCIPLINE
     * (11:30–13:30) requires 4-of-4 alignment.
     */
    private boolean legacyTimeOfDayModeEnabled = false;

    /** P0-1: enable CASE 0 (OI-led entry that fires before any price breakout). */
    private boolean case0Enabled = false;

    /** CASE 0 shadow mode — record but do not bind. Default ON so first deploy is safe. */
    private boolean case0ShadowMode = true;

    /** CASE 0 minimum operator score. Replay calibration: 80. */
    private int case0OpScoreThreshold = 80;

    /** CASE 0 maximum 20-min spot range (% of spot). Replay calibration: 0.10. */
    private double case0CoilMaxPct = 0.10;

    /** CASE 0 minimum |PCR slope per 5 min| in the operator direction. */
    private double case0PcrSlopeMinAbs = 0.02;

    /** P1-3: enable CASE 4 watch-list bonus (+5 to next aligned signal within 20 min). */
    private boolean case4WatchlistBonusEnabled = false;

    // ── Enabled/Paper ──
    private boolean enabled = true;
    private boolean paperTrading = false;

    // ── Getters / setters for new operator + multi-timeframe fields ──
    public boolean isMultiTimeframeEnabled() { return multiTimeframeEnabled; }
    public void setMultiTimeframeEnabled(boolean v) { this.multiTimeframeEnabled = v; }
    public double getShortTimeframeThresholdPct() { return shortTimeframeThresholdPct; }
    public void setShortTimeframeThresholdPct(double v) { this.shortTimeframeThresholdPct = v; }
    public boolean isOperatorBonusEnabled() { return operatorBonusEnabled; }
    public void setOperatorBonusEnabled(boolean v) { this.operatorBonusEnabled = v; }
    public boolean isBidAskImbalanceEnabled() { return bidAskImbalanceEnabled; }
    public void setBidAskImbalanceEnabled(boolean v) { this.bidAskImbalanceEnabled = v; }
    public double getBidAskImbalanceThreshold() { return bidAskImbalanceThreshold; }
    public void setBidAskImbalanceThreshold(double v) { this.bidAskImbalanceThreshold = v; }
    public boolean isIvSkewEnabled() { return ivSkewEnabled; }
    public void setIvSkewEnabled(boolean v) { this.ivSkewEnabled = v; }
    public double getIvSkewThreshold() { return ivSkewThreshold; }
    public void setIvSkewThreshold(double v) { this.ivSkewThreshold = v; }
    public boolean isOiVelocityEnabled() { return oiVelocityEnabled; }
    public void setOiVelocityEnabled(boolean v) { this.oiVelocityEnabled = v; }
    public double getOiAccelerationMultiplier() { return oiAccelerationMultiplier; }
    public void setOiAccelerationMultiplier(double v) { this.oiAccelerationMultiplier = v; }
    public boolean isMaxPainEnabled() { return maxPainEnabled; }
    public void setMaxPainEnabled(boolean v) { this.maxPainEnabled = v; }
    public double getMaxPainMinDistancePct() { return maxPainMinDistancePct; }
    public void setMaxPainMinDistancePct(double v) { this.maxPainMinDistancePct = v; }
    public boolean isReEntryBoostEnabled() { return reEntryBoostEnabled; }
    public void setReEntryBoostEnabled(boolean v) { this.reEntryBoostEnabled = v; }
    public int getReEntryBoostWindowSeconds() { return reEntryBoostWindowSeconds; }
    public void setReEntryBoostWindowSeconds(int v) { this.reEntryBoostWindowSeconds = v; }

    // ── v3 Quality Improvements getters/setters ──
    public boolean isAntiPyramidEnabled() { return antiPyramidEnabled; }
    public void setAntiPyramidEnabled(boolean v) { this.antiPyramidEnabled = v; }
    public int getAntiPyramidCooldownMinutes() { return antiPyramidCooldownMinutes; }
    public void setAntiPyramidCooldownMinutes(int v) { this.antiPyramidCooldownMinutes = v; }
    public boolean isExpiryOtmCutoffEnabled() { return expiryOtmCutoffEnabled; }
    public void setExpiryOtmCutoffEnabled(boolean v) { this.expiryOtmCutoffEnabled = v; }
    public String getExpiryOtmCutoffTime() { return expiryOtmCutoffTime; }
    public void setExpiryOtmCutoffTime(String v) { this.expiryOtmCutoffTime = v; }
    public double getDailyLossLimitRupees() { return dailyLossLimitRupees; }
    public void setDailyLossLimitRupees(double v) { this.dailyLossLimitRupees = v; }
    public double getDailyLossMultiplierOfAvgLoser() { return dailyLossMultiplierOfAvgLoser; }
    public void setDailyLossMultiplierOfAvgLoser(double v) { this.dailyLossMultiplierOfAvgLoser = v; }
    public int getConsecutiveLossHaltCount() { return consecutiveLossHaltCount; }
    public void setConsecutiveLossHaltCount(int v) { this.consecutiveLossHaltCount = v; }
    public boolean isV3Enabled() { return v3Enabled; }
    public void setV3Enabled(boolean v) { this.v3Enabled = v; }
    public boolean isV3ShadowMode() { return v3ShadowMode; }
    public void setV3ShadowMode(boolean v) { this.v3ShadowMode = v; }

    // ── Legacy enhancements getters/setters (29 May 2026) ──
    public boolean isLegacyTimeOfDayModeEnabled() { return legacyTimeOfDayModeEnabled; }
    public void setLegacyTimeOfDayModeEnabled(boolean v) { this.legacyTimeOfDayModeEnabled = v; }
    public boolean isCase0Enabled() { return case0Enabled; }
    public void setCase0Enabled(boolean v) { this.case0Enabled = v; }
    public boolean isCase0ShadowMode() { return case0ShadowMode; }
    public void setCase0ShadowMode(boolean v) { this.case0ShadowMode = v; }
    public int getCase0OpScoreThreshold() { return case0OpScoreThreshold; }
    public void setCase0OpScoreThreshold(int v) { this.case0OpScoreThreshold = v; }
    public double getCase0CoilMaxPct() { return case0CoilMaxPct; }
    public void setCase0CoilMaxPct(double v) { this.case0CoilMaxPct = v; }
    public double getCase0PcrSlopeMinAbs() { return case0PcrSlopeMinAbs; }
    public void setCase0PcrSlopeMinAbs(double v) { this.case0PcrSlopeMinAbs = v; }
    public boolean isCase4WatchlistBonusEnabled() { return case4WatchlistBonusEnabled; }
    public void setCase4WatchlistBonusEnabled(boolean v) { this.case4WatchlistBonusEnabled = v; }

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
    public double getBreakEvenTriggerPercent() { return breakEvenTriggerPercent; }
    public void setBreakEvenTriggerPercent(double v) { this.breakEvenTriggerPercent = v; }
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
