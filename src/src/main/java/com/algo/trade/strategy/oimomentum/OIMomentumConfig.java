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
    /** OI-flip exit needs MORE patience than other exits — fade entries are counter-flow
     *  by design, so the flip condition is true almost immediately after entry
     *  (2026-06-12: two trades exited 47s/56s after entry via OI_FLIP_REVERSE). */
    private int oiFlipMinHoldSeconds = 180;              // No OI_FLIP_REVERSE before 3 min
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
     * (11:30–13:30) requires 4-of-4 alignment. LIVE-mode default ON.
     */
    private boolean legacyTimeOfDayModeEnabled = true;

    /** P0-1: enable CASE 0 (OI-led entry that fires before any price breakout). LIVE default ON. */
    private boolean case0Enabled = true;

    /** CASE 0 shadow mode — record but do not bind. LIVE default OFF (entries actually placed). */
    private boolean case0ShadowMode = false;

    /** CASE 0 minimum operator score. Replay calibration: 80. */
    private int case0OpScoreThreshold = 80;

    /** CASE 0 maximum 20-min spot range (% of spot). Replay calibration: 0.10. */
    private double case0CoilMaxPct = 0.10;

    /** CASE 0 minimum |PCR slope per 5 min| in the operator direction. */
    private double case0PcrSlopeMinAbs = 0.02;

    /** P1-3: enable CASE 4 watch-list bonus (+5 to next aligned signal within 20 min). LIVE default ON. */
    private boolean case4WatchlistBonusEnabled = true;

    // ── R3 — Adaptive CASE 0 for low-VIX (29 May 2026 — replay-validated 82% 60m win) ──
    private boolean case0LowVixEnabled = true;
    private double case0LowVixVixThreshold = 17.0;
    private int case0LowVixOpScoreThreshold = 65;
    private double case0LowVixCoilMaxPct = 0.20;

    // ── R2 — Range-edge fade (29 May 2026 — replay-validated 54% 30m win, ~14/day) ──
    private boolean rangeEdgeFadeEnabled = true;
    private double rangeEdgeFadeRangeMaxPct = 0.30;
    private double rangeEdgeFadeEdgePct = 0.20;
    private int rangeEdgeFadeOiBuildMin = 3000;

    // ── D2 — SUSTAINED_DRIFT detector (2 Jun 2026 — addresses slow-grind days
    //         where CASE 1-5 + CASE 0 + range-fade all stay silent). Replay:
    //         69.6% 60m win, ~5 fires/day/index. Live default ON. ──
    private boolean sustainedDriftEnabled = true;
    private boolean sustainedDriftShadowMode = false;
    private double sustainedDriftMinPct = 0.20;
    private int sustainedDriftOpScoreMin = 50;
    private int sustainedDriftWindowMinutes = 60;
    /**
     * D2-vs-V3 precedence (14 Jun 2026). The legacy rule silently suppressed D2
     * (and CASE0, OPERATOR_SQUEEZE) whenever V3 was live (v3Enabled &amp;&amp;
     * !v3ShadowMode) via {@code !v3BindingLive}, so D2 could never bind live while
     * V3 ran — on 2026-06-12 it fired 2,582 times but always logged
     * D2_DRIFT_SHADOW. When this is true, D2 may bind live even with V3 active; the
     * entry still flows through enterWithGates → the V3 pipeline for strike/lot/
     * conviction sizing, and V3 can still veto it. Default false preserves the
     * legacy "V3 always wins". To promote D2 to live, set BOTH
     * sustainedDriftShadowMode=false AND this=true.
     */
    private boolean sustainedDriftOverridesV3 = false;

    // ── Regime-hold exit profile (14 Jun 2026 — the trend-capture exit half) ──
    //   Applies ONLY to trades whose entryReason contains "SUSTAINED_DRIFT" (D2).
    //   On 2026-06-12 the few trend trades that ran to TRAILING_STOP made +9-10%
    //   while OI-flip/early-trail churn cut sibling trades at ~+1%. For drift-origin
    //   trades we therefore: suppress OI_FLIP_REVERSE, start trailing later, widen
    //   the trail gap and disable the aggressive gap-tightening — i.e. ride the
    //   grind instead of scalping it. Scoped + flagged so non-drift trades and the
    //   whole strategy when disabled behave exactly as before.
    private boolean regimeHoldEnabled = true;
    private boolean regimeHoldSuppressOiFlip = true;
    private double regimeHoldTrailActivationPercent = 8.0;  // was 5 — let it run before trailing arms
    private double regimeHoldTrailGapPercent = 10.0;        // was 5 — wider retrace tolerance, no tightening

    // ── OPERATOR_SQUEEZE detector (2 Jun 2026 — catches the coil → shakeout →
    //     short-squeeze pattern that hit NIFTY 12:30–13:30 IST and that every
    //     existing detector missed. Three-gate model: coil + ignition + chain
    //     confirmation. See OperatorSqueezeDetector for full spec). ──
    private boolean operatorSqueezeEnabled = true;
    private int operatorSqueezeCoilWindowMin = 20;
    private double operatorSqueezeCoilRangeMaxPct = 0.10;
    private double operatorSqueezeCoilVixDropMin = 0.05;
    private long operatorSqueezeCoilCeBuildMin = 5_000_000L;
    private int operatorSqueezeIgnitionWindowMin = 5;
    private double operatorSqueezeIgnitionReturnMinPct = 0.20;
    private long operatorSqueezeOiCollapseMinAbs = 7_000_000L;
    private double operatorSqueezeIgnitionVixMin = 0.10;
    private double operatorSqueezeIvExpansionMinPct = 5.0;
    private double operatorSqueezePcrRotationMin = 0.05;
    // A8 (2026-06-02): coil-precondition gating is OPTIONAL. With this OFF,
    // only the 4 core gates (ignition return + OI collapse + VIX expansion +
    // IV expansion) need to pass. Today's tape shows the 3 coil gates fail at
    // the 12:35 ignition bar (CE OI already started dropping, VIX already
    // expanding) — making the detector silent. Default OFF so the detector
    // can fire.
    private boolean operatorSqueezeRequireCoil = false;
    // E3 (2026-06-02): on expiry day, gamma exposure is roughly double the
    // non-expiry case for the same delta. Apply this lot-multiplier to the
    // entry to keep dollar-gamma constant. 0.5 = half-size.
    private double operatorSqueezeExpiryLotMultiplier = 0.5;

    // ── T2 — PCR slope additive bias bonus (2 Jun 2026 — for slow-PCR-roll days
    //         like 1 Jun where PCR went 0.95→1.27 in 60 min but the legacy
    //         binary level-threshold misread the rising PCR as neutral). The
    //         |slope| threshold is multiplied by direction sign; +10 to bias
    //         when the slope agrees with momentum, -5 when it actively opposes. ──
    private boolean pcrSlopeBiasBonusEnabled = true;
    private double pcrSlopeBiasMinAbs = 0.05;
    private int pcrSlopeBiasBonusPoints = 10;
    private int pcrSlopeBiasOpposePenalty = 5;

    // ── T3 — Conditional bias-floor lowering (2 Jun 2026 — when a confirmed
    //         coil break + PCR slope agree, lower the entry floor from
    //         {@code biasThresholdDefault} to {@code biasThresholdRelaxed}. This
    //         catches the 1 Jun 11:55 CASE 1 BEAR setup that landed at ~55
    //         under the standard 65 floor). Only applied when BOTH conditions
    //         hold; never lowered blindly. ──
    private boolean biasFloorRelaxEnabled = true;
    private int biasFloorDefault = 65;
    private int biasFloorRelaxed = 55;
    private double biasFloorRelaxCoilBreakRangePct = 0.15;
    private double biasFloorRelaxPcrSlopeMinAbs = 0.05;

    // ── T5 — Capture heartbeat (2 Jun 2026 — 1 Jun capture stopped at 13:51
    //         silently; alerts when no entry-path evaluation lands for N minutes
    //         during market hours OR tune CSV writes fail). ──
    private boolean captureHeartbeatEnabled = true;
    private int captureHeartbeatStaleMinutes = 10;

    // ── Theta-decay gate ──
    private boolean thetaDecayCheckEnabled = true;
    private double thetaDecayMaxCostPct = 30.0;

    // ── P4 tuning instrumentation (runtime-overridable via OiMomentumRuntimeConfig) ──
    /** When true, every reject is written to oi-momentum-rejects.csv (no 5s/30s throttle). */
    private boolean recordEveryReject = true;
    private int rejectSampleIntervalSeconds = 30;
    private int matrixRejectSampleIntervalSeconds = 5;
    /** Top-N reject reasons included in the 60-second summary log. */
    private int summaryRejectTopN = 10;
    /** Group consecutive rejects of the same (index, reason) within this window into one CSV row. */
    private int rejectEpisodeWindowSeconds = 60;

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

    public boolean isCase0LowVixEnabled() { return case0LowVixEnabled; }
    public void setCase0LowVixEnabled(boolean v) { this.case0LowVixEnabled = v; }
    public double getCase0LowVixVixThreshold() { return case0LowVixVixThreshold; }
    public void setCase0LowVixVixThreshold(double v) { this.case0LowVixVixThreshold = v; }
    public int getCase0LowVixOpScoreThreshold() { return case0LowVixOpScoreThreshold; }
    public void setCase0LowVixOpScoreThreshold(int v) { this.case0LowVixOpScoreThreshold = v; }
    public double getCase0LowVixCoilMaxPct() { return case0LowVixCoilMaxPct; }
    public void setCase0LowVixCoilMaxPct(double v) { this.case0LowVixCoilMaxPct = v; }

    // ── D2 SUSTAINED_DRIFT (T1) ──
    public boolean isSustainedDriftEnabled() { return sustainedDriftEnabled; }
    public void setSustainedDriftEnabled(boolean v) { this.sustainedDriftEnabled = v; }
    public boolean isSustainedDriftShadowMode() { return sustainedDriftShadowMode; }
    public void setSustainedDriftShadowMode(boolean v) { this.sustainedDriftShadowMode = v; }
    public double getSustainedDriftMinPct() { return sustainedDriftMinPct; }
    public void setSustainedDriftMinPct(double v) { this.sustainedDriftMinPct = v; }
    public int getSustainedDriftOpScoreMin() { return sustainedDriftOpScoreMin; }
    public void setSustainedDriftOpScoreMin(int v) { this.sustainedDriftOpScoreMin = v; }
    public int getSustainedDriftWindowMinutes() { return sustainedDriftWindowMinutes; }
    public void setSustainedDriftWindowMinutes(int v) { this.sustainedDriftWindowMinutes = v; }
    public boolean isSustainedDriftOverridesV3() { return sustainedDriftOverridesV3; }
    public void setSustainedDriftOverridesV3(boolean v) { this.sustainedDriftOverridesV3 = v; }
    public boolean isRegimeHoldEnabled() { return regimeHoldEnabled; }
    public void setRegimeHoldEnabled(boolean v) { this.regimeHoldEnabled = v; }
    public boolean isRegimeHoldSuppressOiFlip() { return regimeHoldSuppressOiFlip; }
    public void setRegimeHoldSuppressOiFlip(boolean v) { this.regimeHoldSuppressOiFlip = v; }
    public double getRegimeHoldTrailActivationPercent() { return regimeHoldTrailActivationPercent; }
    public void setRegimeHoldTrailActivationPercent(double v) { this.regimeHoldTrailActivationPercent = v; }
    public double getRegimeHoldTrailGapPercent() { return regimeHoldTrailGapPercent; }
    public void setRegimeHoldTrailGapPercent(double v) { this.regimeHoldTrailGapPercent = v; }

    // OPERATOR_SQUEEZE getters / setters
    public boolean isOperatorSqueezeEnabled() { return operatorSqueezeEnabled; }
    public void setOperatorSqueezeEnabled(boolean v) { this.operatorSqueezeEnabled = v; }
    public int getOperatorSqueezeCoilWindowMin() { return operatorSqueezeCoilWindowMin; }
    public void setOperatorSqueezeCoilWindowMin(int v) { this.operatorSqueezeCoilWindowMin = v; }
    public double getOperatorSqueezeCoilRangeMaxPct() { return operatorSqueezeCoilRangeMaxPct; }
    public void setOperatorSqueezeCoilRangeMaxPct(double v) { this.operatorSqueezeCoilRangeMaxPct = v; }
    public double getOperatorSqueezeCoilVixDropMin() { return operatorSqueezeCoilVixDropMin; }
    public void setOperatorSqueezeCoilVixDropMin(double v) { this.operatorSqueezeCoilVixDropMin = v; }
    public long getOperatorSqueezeCoilCeBuildMin() { return operatorSqueezeCoilCeBuildMin; }
    public void setOperatorSqueezeCoilCeBuildMin(long v) { this.operatorSqueezeCoilCeBuildMin = v; }
    public int getOperatorSqueezeIgnitionWindowMin() { return operatorSqueezeIgnitionWindowMin; }
    public void setOperatorSqueezeIgnitionWindowMin(int v) { this.operatorSqueezeIgnitionWindowMin = v; }
    public double getOperatorSqueezeIgnitionReturnMinPct() { return operatorSqueezeIgnitionReturnMinPct; }
    public void setOperatorSqueezeIgnitionReturnMinPct(double v) { this.operatorSqueezeIgnitionReturnMinPct = v; }
    public long getOperatorSqueezeOiCollapseMinAbs() { return operatorSqueezeOiCollapseMinAbs; }
    public void setOperatorSqueezeOiCollapseMinAbs(long v) { this.operatorSqueezeOiCollapseMinAbs = v; }
    public double getOperatorSqueezeIgnitionVixMin() { return operatorSqueezeIgnitionVixMin; }
    public void setOperatorSqueezeIgnitionVixMin(double v) { this.operatorSqueezeIgnitionVixMin = v; }
    public double getOperatorSqueezeIvExpansionMinPct() { return operatorSqueezeIvExpansionMinPct; }
    public void setOperatorSqueezeIvExpansionMinPct(double v) { this.operatorSqueezeIvExpansionMinPct = v; }
    public double getOperatorSqueezePcrRotationMin() { return operatorSqueezePcrRotationMin; }
    public void setOperatorSqueezePcrRotationMin(double v) { this.operatorSqueezePcrRotationMin = v; }
    public boolean isOperatorSqueezeRequireCoil() { return operatorSqueezeRequireCoil; }
    public void setOperatorSqueezeRequireCoil(boolean v) { this.operatorSqueezeRequireCoil = v; }
    public double getOperatorSqueezeExpiryLotMultiplier() { return operatorSqueezeExpiryLotMultiplier; }
    public void setOperatorSqueezeExpiryLotMultiplier(double v) { this.operatorSqueezeExpiryLotMultiplier = v; }

    // ── T2 PCR slope bias bonus ──
    public boolean isPcrSlopeBiasBonusEnabled() { return pcrSlopeBiasBonusEnabled; }
    public void setPcrSlopeBiasBonusEnabled(boolean v) { this.pcrSlopeBiasBonusEnabled = v; }
    public double getPcrSlopeBiasMinAbs() { return pcrSlopeBiasMinAbs; }
    public void setPcrSlopeBiasMinAbs(double v) { this.pcrSlopeBiasMinAbs = v; }
    public int getPcrSlopeBiasBonusPoints() { return pcrSlopeBiasBonusPoints; }
    public void setPcrSlopeBiasBonusPoints(int v) { this.pcrSlopeBiasBonusPoints = v; }
    public int getPcrSlopeBiasOpposePenalty() { return pcrSlopeBiasOpposePenalty; }
    public void setPcrSlopeBiasOpposePenalty(int v) { this.pcrSlopeBiasOpposePenalty = v; }

    // ── T3 Conditional bias-floor lowering ──
    public boolean isBiasFloorRelaxEnabled() { return biasFloorRelaxEnabled; }
    public void setBiasFloorRelaxEnabled(boolean v) { this.biasFloorRelaxEnabled = v; }
    public int getBiasFloorDefault() { return biasFloorDefault; }
    public void setBiasFloorDefault(int v) { this.biasFloorDefault = v; }
    public int getBiasFloorRelaxed() { return biasFloorRelaxed; }
    public void setBiasFloorRelaxed(int v) { this.biasFloorRelaxed = v; }
    public double getBiasFloorRelaxCoilBreakRangePct() { return biasFloorRelaxCoilBreakRangePct; }
    public void setBiasFloorRelaxCoilBreakRangePct(double v) { this.biasFloorRelaxCoilBreakRangePct = v; }
    public double getBiasFloorRelaxPcrSlopeMinAbs() { return biasFloorRelaxPcrSlopeMinAbs; }
    public void setBiasFloorRelaxPcrSlopeMinAbs(double v) { this.biasFloorRelaxPcrSlopeMinAbs = v; }

    // ── T5 Capture heartbeat ──
    public boolean isCaptureHeartbeatEnabled() { return captureHeartbeatEnabled; }
    public void setCaptureHeartbeatEnabled(boolean v) { this.captureHeartbeatEnabled = v; }
    public int getCaptureHeartbeatStaleMinutes() { return captureHeartbeatStaleMinutes; }
    public void setCaptureHeartbeatStaleMinutes(int v) { this.captureHeartbeatStaleMinutes = v; }

    public boolean isRangeEdgeFadeEnabled() { return rangeEdgeFadeEnabled; }
    public void setRangeEdgeFadeEnabled(boolean v) { this.rangeEdgeFadeEnabled = v; }
    public double getRangeEdgeFadeRangeMaxPct() { return rangeEdgeFadeRangeMaxPct; }
    public void setRangeEdgeFadeRangeMaxPct(double v) { this.rangeEdgeFadeRangeMaxPct = v; }
    public double getRangeEdgeFadeEdgePct() { return rangeEdgeFadeEdgePct; }
    public void setRangeEdgeFadeEdgePct(double v) { this.rangeEdgeFadeEdgePct = v; }
    public int getRangeEdgeFadeOiBuildMin() { return rangeEdgeFadeOiBuildMin; }
    public void setRangeEdgeFadeOiBuildMin(int v) { this.rangeEdgeFadeOiBuildMin = v; }

    public boolean isThetaDecayCheckEnabled() { return thetaDecayCheckEnabled; }
    public void setThetaDecayCheckEnabled(boolean v) { this.thetaDecayCheckEnabled = v; }
    public double getThetaDecayMaxCostPct() { return thetaDecayMaxCostPct; }
    public void setThetaDecayMaxCostPct(double v) { this.thetaDecayMaxCostPct = v; }

    public boolean isRecordEveryReject() { return recordEveryReject; }
    public void setRecordEveryReject(boolean v) { this.recordEveryReject = v; }
    public int getRejectSampleIntervalSeconds() { return rejectSampleIntervalSeconds; }
    public void setRejectSampleIntervalSeconds(int v) { this.rejectSampleIntervalSeconds = v; }
    public int getMatrixRejectSampleIntervalSeconds() { return matrixRejectSampleIntervalSeconds; }
    public void setMatrixRejectSampleIntervalSeconds(int v) { this.matrixRejectSampleIntervalSeconds = v; }
    public int getSummaryRejectTopN() { return summaryRejectTopN; }
    public void setSummaryRejectTopN(int v) { this.summaryRejectTopN = v; }
    public int getRejectEpisodeWindowSeconds() { return rejectEpisodeWindowSeconds; }
    public void setRejectEpisodeWindowSeconds(int v) { this.rejectEpisodeWindowSeconds = v; }

    // ── Missing getters restored 2 Jun 2026 (the backtest + strategy need these) ──
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean v) { this.enabled = v; }
    public boolean isPaperTrading() { return paperTrading; }
    public void setPaperTrading(boolean v) { this.paperTrading = v; }

    public double getMomentumThresholdPercent() { return momentumThresholdPercent; }
    public double getSpikeThresholdPercent() { return spikeThresholdPercent; }
    public double getStopLossPercent() { return stopLossPercent; }
    public double getBreakEvenTriggerPercent() { return breakEvenTriggerPercent; }
    public void setBreakEvenTriggerPercent(double v) { this.breakEvenTriggerPercent = v; }
    public double getTrailingActivationPercent() { return trailingActivationPercent; }
    public double getTrailingGapPercent() { return trailingGapPercent; }
    public int getMaxTradesPerDay() { return maxTradesPerDay; }
    public void setMaxTradesPerDay(int v) { this.maxTradesPerDay = v; }
    public int getSoftTargetTradesPerDay() { return softTargetTradesPerDay; }
    public int getMaxReversalsPerDay() { return maxReversalsPerDay; }
    public int getConsecutiveLossPause() { return consecutiveLossPause; }
    public int getCooldownAfterSlSeconds() { return cooldownAfterSlSeconds; }
    public int getMinimumHoldTimeSeconds() { return minimumHoldTimeSeconds; }
    public int getOiFlipMinHoldSeconds() { return oiFlipMinHoldSeconds; }
    public int getMiddayTradeReductionPercent() { return middayTradeReductionPercent; }

    public long getMinSqueezeOiDelta() { return minSqueezeOiDelta; }
    public double getPcrBullishThreshold() { return pcrBullishThreshold; }
    public double getPcrBearishThreshold() { return pcrBearishThreshold; }

    public String getEntryWindowStart() { return entryWindowStart; }
    public String getEntryWindowEnd() { return entryWindowEnd; }
    public String getMiddayStart() { return middayStart; }
    public String getMiddayEnd() { return middayEnd; }

    public int getSquareoffHour() { return squareoffHour; }
    public int getSquareoffMinute() { return squareoffMinute; }
    public int getBiasConfidenceThreshold() { return biasConfidenceThreshold; }
    public void setBiasConfidenceThreshold(int v) { this.biasConfidenceThreshold = v; }
    public int getBiasConfirmationTicks() { return biasConfirmationTicks; }
    public void setBiasConfirmationTicks(int v) { this.biasConfirmationTicks = v; }
    public int getBiasDecaySeconds() { return biasDecaySeconds; }
    public void setBiasDecaySeconds(int v) { this.biasDecaySeconds = v; }
    public int getBiasDecayPenalty() { return biasDecayPenalty; }
    public void setBiasDecayPenalty(int v) { this.biasDecayPenalty = v; }

    // ── 2 Jun 2026: auto-restored setters required for @ConfigurationProperties binding ──
    public void setMomentumThresholdPercent(double v) { this.momentumThresholdPercent = v; }
    public void setSpikeThresholdPercent(double v) { this.spikeThresholdPercent = v; }
    public void setRolling30MinWindowSeconds(int v) { this.rolling30MinWindowSeconds = v; }
    public void setMinOiChangePercent(double v) { this.minOiChangePercent = v; }
    public void setPcrBullishThreshold(double v) { this.pcrBullishThreshold = v; }
    public void setPcrBearishThreshold(double v) { this.pcrBearishThreshold = v; }
    public void setMinSqueezeOiDelta(long v) { this.minSqueezeOiDelta = v; }
    public void setEntryWindowStart(String v) { this.entryWindowStart = v; }
    public void setEntryWindowEnd(String v) { this.entryWindowEnd = v; }
    public void setSoftTargetTradesPerDay(int v) { this.softTargetTradesPerDay = v; }
    public void setMaxReversalsPerDay(int v) { this.maxReversalsPerDay = v; }
    public void setCooldownAfterSlSeconds(int v) { this.cooldownAfterSlSeconds = v; }
    public void setMinimumHoldTimeSeconds(int v) { this.minimumHoldTimeSeconds = v; }
    public void setOiFlipMinHoldSeconds(int v) { this.oiFlipMinHoldSeconds = v; }
    public void setConsecutiveLossPause(int v) { this.consecutiveLossPause = v; }
    public void setMiddayTradeReductionPercent(int v) { this.middayTradeReductionPercent = v; }
    public void setStopLossPercent(double v) { this.stopLossPercent = v; }
    public void setTargetPercent(double v) { this.targetPercent = v; }
    public void setTrailingActivationPercent(double v) { this.trailingActivationPercent = v; }
    public void setTrailingGapPercent(double v) { this.trailingGapPercent = v; }
    public void setSquareoffHour(int v) { this.squareoffHour = v; }
    public void setSquareoffMinute(int v) { this.squareoffMinute = v; }
    public void setOpeningSessionStart(String v) { this.openingSessionStart = v; }
    public void setOpeningSessionEnd(String v) { this.openingSessionEnd = v; }
    public void setMiddayStart(String v) { this.middayStart = v; }
    public void setMiddayEnd(String v) { this.middayEnd = v; }
    public void setClosingSessionStart(String v) { this.closingSessionStart = v; }
    // (D2 trend-capture: sustainedDriftOverridesV3 + regime-hold knobs added 14 Jun 2026)
}
