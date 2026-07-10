package com.algo.trade.execution;

import com.algo.trade.broker.BrokerClient;
import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategyType;
import com.algo.trade.domain.OrderRequest;
import com.algo.trade.domain.OrderResponse;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.OrderStatus;
import com.algo.trade.domain.OrderType;
import com.algo.trade.domain.ProductType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.OrderEntity;
import com.algo.trade.persistence.OrderRepository;
import com.algo.trade.persistence.ErrorEventEntity;
import com.algo.trade.persistence.ErrorEventRepository;
import com.algo.trade.persistence.StrategyDecisionEntity;
import com.algo.trade.persistence.StrategyDecisionRepository;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.risk.RiskEngine;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Converts approved strategy entry decisions into broker orders and journal records.
 */
@Service
public class ExecutionEngine {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final Logger log = LoggerFactory.getLogger(ExecutionEngine.class);
    /** NSE/BSE F&O tick size — all option prices must be multiples of ₹0.05. */
    private static final BigDecimal TICK_SIZE = new BigDecimal("0.05");

    /** Prevents two monitors from placing duplicate broker SELL orders for the same trade. */
    private final Set<String> closingInProgress = ConcurrentHashMap.newKeySet();
    /**
     * Counts concurrent entry executions in flight (order placed but trade not yet created).
     * Used together with max-open-trades to prevent over-entry.
     * Value = number of pending LIMIT orders that haven't filled/cancelled yet.
     */
    /** §B6 (2026-06-29): PER-USER in-flight entry counters. Was a single global AtomicInteger, which let one
     *  user's pending entry (the primary scalps constantly) block ALL other users at the concurrent-entry gate
     *  ("open+pending=0 ... in-flight (1) >= max (1)" on u=8). Keyed by userId; null/unset → DEFAULT. */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> entriesInFlightByUser =
            new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.concurrent.atomic.AtomicInteger inFlightFor(Long userId) {
        Long key = userId != null ? userId : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        return entriesInFlightByUser.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger(0));
    }

    private java.util.concurrent.atomic.AtomicInteger inFlightCurrent() {
        return inFlightFor(com.algo.trade.multiuser.UserContext.getUserId());
    }

    /**
     * Rejection circuit breaker — auto-halts entries after consecutive broker rejections.
     * Prevents infinite order placement when broker keeps rejecting (tick size, margin, rate limit).
     * Resets on any successful order placement. Halt persists until user resumes from UI.
     */
    private static final int MAX_CONSECUTIVE_REJECTIONS = 3;
    /**
     * P1.5: per-user consecutive-rejection counters. Previously a single GLOBAL counter meant a
     * secondary (copy) user's rejects accumulated against the same counter and soft-halted the
     * WHOLE engine. Now each user has its own counter and only the primary/default user's streak
     * trips the global soft halt.
     */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger> consecutiveRejectionsByUser
            = new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.concurrent.atomic.AtomicInteger rejectionCounter(Long userId) {
        Long key = userId != null ? userId : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        return consecutiveRejectionsByUser.computeIfAbsent(key, k -> new java.util.concurrent.atomic.AtomicInteger(0));
    }

    private final TradingProperties properties;
    private final GlobalConfigService globalConfigService;
    private final BrokerClient brokerClient;
    private final RiskEngine riskEngine;
    private final TradingStateService tradingStateService;
    private final TradeRepository tradeRepository;
    private final OrderRepository orderRepository;
    private final ErrorEventRepository errorEventRepository;
    private final StrategyDecisionRepository decisionRepository;
    private final ExecutionTuningRecorder executionOutcomeCsvRecorder;
    private final TelegramAlertService telegramAlertService;
    private final com.algo.trade.config.PositionSyncProperties positionSyncProperties;
    private final StrategyConfigService strategyConfigService;

    /** Multi-user signal copy — optional, only active when multi-user mode is enabled.
     *  @Lazy breaks the cycle: ExecutionEngine → SignalCopyService → UserAwareExecutionService → ExecutionEngine. */
    @Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.multiuser.SignalCopyService signalCopyService;

    /**
     * SAFETY: should PAPER entries fan out to other users (as REAL orders)? Default NO.
     * A paper-mode strategy means "testing without money" — it must not place real
     * broker orders on secondary users' accounts. Enable only deliberately.
     */
    @org.springframework.beans.factory.annotation.Value("${trading.multiuser.signal-copy.copy-paper-signals:false}")
    private boolean copyPaperSignals;
    /** Exit marketable-LIMIT slippage cap (SELL at LTP-X% / BUY-to-cover at LTP+X%). Wider than the entry
     *  buffer so stops ALWAYS fill on fast/thin options (the 1% default left an SL stuck in a no-bid hole,
     *  filling ~2min late at -28% vs the -12% stop on 2026-06-29). 2026-06-29: 1.0 -> 8.0, config-driven. */
    @org.springframework.beans.factory.annotation.Value("${smart-order.exit-protection-percent:8.0}")
    private double exitProtectionPercent;
    /** Protective exits (stop-loss / trailing-stop / time square-off) route as TRUE MARKET orders so they
     *  ALWAYS fill — a stop resting unfilled in a no-bid hole is worse than slippage (the -28% vs -12% SL on
     *  2026-06-29). Opportunistic TARGET exits keep the marketable LIMIT (no urgency, don't gift slippage on a
     *  profit-take). Config-gated so it can be turned off without a rebuild. */
    @org.springframework.beans.factory.annotation.Value("${smart-order.protective-exit-market:true}")
    private boolean protectiveExitMarket;
    /** Min trade age before the broker-flat exit guard may reconcile-close a position (avoids closing a
     *  just-filled position that hasn't surfaced in the broker's positions() yet). §B2 fix, config-tunable. */
    @org.springframework.beans.factory.annotation.Value("${smart-order.broker-flat-guard-min-age-seconds:15}")
    private long brokerFlatGuardMinAgeSeconds;
    /** When a protective exit is blocked by a PENDING MANUAL sell, throttle the stand-down log+alert to at most
     *  once per this many seconds per instrument (2026-07-03: 06-30 spammed ~46 warns/alerts on one stuck trade). */
    @org.springframework.beans.factory.annotation.Value("${trading.manual-exit.alert-throttle-seconds:120}")
    private long manualExitAlertThrottleSeconds;
    /** If the manual sell has blocked the bot's protective exit for this long AND the position is underwater past
     *  its stop-loss, ESCALATE to a loud alert (the stuck manual order is defeating the stop). 0 disables escalation. */
    @org.springframework.beans.factory.annotation.Value("${trading.manual-exit.escalate-after-seconds:300}")
    private long manualExitEscalateAfterSeconds;
    /** Per-instrument stuck-manual-exit tracking: {@code [firstDetectedMs, lastAlertMs]}. Cleared when the block clears. */
    private final java.util.concurrent.ConcurrentHashMap<String, long[]> stuckManualExit =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Same-instrument re-entry cooldown — block re-buying a strike within N seconds of exiting it, to stop the
     *  scalp-target → reEntryBoost churn (sold @89.25, re-bought @89.55 2s later). 0 disables. §B5 fix. */
    @org.springframework.beans.factory.annotation.Value("${trading.same-instrument-reentry-cooldown-seconds:45}")
    private long sameInstrumentReentryCooldownSeconds;
    /** SOLID anti-churn (2026-06-30): per-UNDERLYING, same-direction re-entry cooldown. The per-instrument
     *  cooldown above misses STRIKE-ROTATION churn — exit 23950PE then re-buy 23900PE 4s later as the ATM
     *  moves (validated from the 06-30 broker fills). Block re-entering the SAME direction (PE-after-PE /
     *  CE-after-CE) on an underlying within N seconds of ANY exit on it; an opposite-direction reversal is
     *  still allowed. Robust code default (90s) so a YAML slip can't silently disable it. 0 disables. */
    @org.springframework.beans.factory.annotation.Value("${trading.same-underlying-reentry-cooldown-seconds:90}")
    private long sameUnderlyingReentryCooldownSeconds;
    /** PRICE-BASED re-entry rule (2026-07-01): after exiting a strike, only re-buy it if the premium has come
     *  DOWN below the previous entry price — never average UP into the same strike at a higher price. Applies
     *  within {@code reentry-price-rule-window-seconds} of the exit so a genuinely new setup much later isn't
     *  permanently blocked. buffer-pct optionally requires it to be meaningfully cheaper (0 = strictly below). */
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-require-lower-price:true}")
    private boolean reentryRequireLowerPrice;
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-lower-price-buffer-pct:0.0}")
    private double reentryLowerPriceBufferPct;
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-price-rule-window-seconds:1800}")
    private long reentryPriceRuleWindowSeconds;
    /** OI-continuation bypass (2026-07-08) of ONLY the buy-reference leg of the price rule. When the just-bought
     *  strike keeps ripping — writers still piling in on our side (OI rising) AND premium still thrusting up (NOT
     *  fading) — re-buying HIGHER is riding a confirmed winner, not averaging up into churn. Bypasses ONLY the
     *  "below last BUY" rejection; the sell-reference (round-trip) block, cooldowns/window and every risk gate are
     *  untouched. Capped per user:strike:day; every bypass logs REENTRY_OI_CONTINUATION. Flag off => exact current
     *  behaviour. Validated live: 07-06 SENSEX 78000CE +15% (OI building + premium thrusting → fires) vs 07-08
     *  fading PE re-chases (premChgPct<0 → inert, still correctly blocked). */
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-price-rule.oi-continuation-bypass.enabled:true}")
    private boolean reentryOiContinuationBypassEnabled;
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-price-rule.oi-continuation-bypass.min-oi-rise-pct:5.0}")
    private double reentryOiContinuationMinOiRisePct;
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-price-rule.oi-continuation-bypass.min-thrust-pct:0.0}")
    private double reentryOiContinuationMinThrustPct;
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-price-rule.oi-continuation-bypass.max-per-strike-day:1}")
    private int reentryOiContinuationMaxPerStrikeDay;
    /** Per user:instrument:date → count of OI-continuation buy-reference bypasses taken today (caps re-entries). */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> reentryOiContinuationCountByKey =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Conviction override (2026-07-01): the blunt same-underlying re-entry cooldown can miss a GENUINELY
     *  strong fresh signal (e.g. index ripping — book a CE at target, a fresh high-conviction CE fires 30s
     *  later). When enabled, a fresh, direction-aligned operator signal with score ≥ min bypasses ONLY the
     *  same-underlying cooldown (the price "no averaging up" rule + same-instrument cooldown still apply).
     *  DEFAULT OFF — turn on deliberately; every bypass logs REENTRY_CONVICTION_OVERRIDE so the tuning loop
     *  can A/B whether the high-conviction re-entries actually made money vs the ones still blocked. */
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-conviction-override.enabled:false}")
    private boolean reentryConvictionOverrideEnabled;
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-conviction-override.min-operator-score:95}")
    private int reentryConvictionMinScore;
    /** When true, the conviction override ALSO requires the day/week structure (MTF) to agree with the
     *  re-entry direction — so a bypass needs both the fresh operator flow AND the higher-timeframe lean
     *  confirming. Falls back to operator-only when MTF is unavailable (an MTF outage won't disable it). */
    @org.springframework.beans.factory.annotation.Value("${trading.reentry-conviction-override.require-mtf-aligned:true}")
    private boolean reentryOverrideRequireMtf;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.strategy.oimomentum.OperatorFrameworkService operatorFrameworkService;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private com.algo.trade.marketdata.MultiTimeframeContextService mtfContextService;
    /** ENTRY margin-rejection backoff (2026-07-01): after this many INSUFFICIENT-FUNDS entry rejections for a
     *  user, stop attempting entries for them for {@code margin-reject-backoff-minutes} (auto-clears on any
     *  successful fill for that user). Fixes the async u:8 copy-margin storm — 71 identical rejects in 5 min,
     *  which bypassed the synchronous circuit breaker (they arrive via OrderFillWatchdog). */
    @org.springframework.beans.factory.annotation.Value("${trading.margin-reject-threshold:2}")
    private int marginRejectThreshold;
    @org.springframework.beans.factory.annotation.Value("${trading.margin-reject-backoff-minutes:20}")
    private long marginRejectBackoffMinutes;
    /** Max open trades per UNDERLYING — diversification cap. Default 1 forces each open slot to a different
     *  index (1 NIFTY + 1 BANKNIFTY/SENSEX, never 2 NIFTY). §D2 fix, config-tunable. */
    @org.springframework.beans.factory.annotation.Value("${trading.max-open-per-underlying:1}")
    private int maxOpenPerUnderlying;
    /** Per-instrument last-exit timestamps for the re-entry cooldown (§B5). */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> lastExitByInstrument =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Per-underlying+direction last-exit timestamps (key "NIFTY:PE") for the solid anti-churn cooldown. */
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> lastExitByUnderlyingDir =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** The REAL exit reason (STOP_LOSS/TARGET/TRAILING_STOP/…) for a trade whose exit order is PENDING a fill,
     *  keyed by tradeId. The watchdog materialises the close later and would otherwise label the tuning
     *  ExitEvent generically ("WATCHDOG_FILLED_EXIT"), destroying exit-attribution — so it consumes this. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> pendingExitReasonByTradeId =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Consume (return + remove) the real exit reason stashed when a pending exit order was placed. Used by
     *  {@code OrderFillWatchdog} so the causal reason survives into the tuning ExitEvent. (2026-07-02) */
    public String consumePendingExitReason(String tradeId) {
        return tradeId == null ? null : pendingExitReasonByTradeId.remove(tradeId);
    }
    /** PER-USER last BUY price + time for a strike (key "userId:instrumentKey") — the reference for the
     *  price-based re-entry rule (only re-buy this strike below this price). Recorded at PLACEMENT so it
     *  survives ANY close type (bot exit, manual/broker close, sync) — the manual-close case is exactly
     *  what slipped through today (SENSEX 76900CE closed manually then re-bought higher). */
    private final java.util.concurrent.ConcurrentHashMap<String, BigDecimal> lastBuyPriceByUserInstrument =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> lastBuyTimeByUserInstrument =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ── Conviction-Trend Override (CTO, 2026-07-03) ──────────────────────────────────────────────────
    // The anti-churn blocks (price rule + cooldowns) are correct for a FLAT market but wrongly block
    // pyramiding into a GENUINE move (13:03 NIFTY 24350PE bought @96.4 ran +18% on +31M volume with OI
    // capitulation — the bot's adds at 101.5/106 were blocked). The discriminator is the TAPE, not the
    // score (opScore was 69–80 and the operator even pointed the WRONG way). When a same-strike VOLUME
    // SURGE + directional PRICE THRUST confirm a real move, CTO bypasses the churn/price blocks. Applies
    // to ALL OI-momentum entries, CE and PE, both users. NEVER bypasses risk gates (daily-loss / max-open /
    // kill-switch — those live upstream). Capped: pyramids only into an in-profit position, max-adds/day.
    /** OI-price/volume tape monitor (shared singleton, buffers filled by the strategy loop). Optional. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.strategy.oimomentum.OiDivergenceMonitor oiDivergenceMonitor;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.enabled:false}")
    private boolean ctoEnabled;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.shadow:true}")
    private boolean ctoShadow;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.min-vol-ratio:1.5}")
    private double ctoMinVolRatio;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.min-thrust-pct:2.0}")
    private double ctoMinThrustPct;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.min-volume:500}")
    private long ctoMinVolume;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.max-adds:1}")
    private int ctoMaxAdds;
    // ── CTO conviction-scale (2026-07-07) ────────────────────────────────────────────────────────────
    // The strict depth-only microstructure gate (depthConfirm + spoof-veto) blocked EVERY CTO add across the
    // 3-day study ("no real buying") — even on genuine trends. This adds an ALTERNATIVE confirmation: when the
    // strict gate does NOT confirm, allow the add IFF a real trend confirms it — a fresh, aligned operator
    // score >= min AND OI velocity (OI rise% over the divergence window) >= min AND (optionally) price still
    // continuing in favour (premium thrust > 0). A chop tape (neutral/opposed operator, flat OI) fails at least
    // one leg, so this can never pyramid into a fake. EVERY existing CTO guard is unchanged and still required:
    // same-strike volume surge, premium thrust, in-profit pyramidOk (never averages down), max-adds cap, and
    // the upstream risk gates (daily-loss / max-open / margin / kill-switch). Flag off => today's exact
    // (depth-only, blocked) behaviour.
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.cto-scale.enabled:true}")
    private boolean ctoScaleEnabled;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.cto-scale.require-op-score-min:65}")
    private int ctoScaleOpScoreMin;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.cto-scale.require-oivel-min:5.0}")
    private double ctoScaleOiVelMin;
    @org.springframework.beans.factory.annotation.Value("${trading.conviction-trend-override.cto-scale.require-price-continuation:true}")
    private boolean ctoScaleRequirePriceContinuation;
    /** Per user:instrument:date → count of CTO trend-adds taken (caps runaway pyramiding on a spike). */
    private final java.util.concurrent.ConcurrentHashMap<String, Integer> ctoAddsByKey =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Per user:instrument → epoch-ms of the last CTO fire — so the resulting trade's entryReason gets a
     *  [CTO] tag (makes the override queryable in the trade record for after-the-fact A/B). Short-lived. */
    private final java.util.concurrent.ConcurrentHashMap<String, Long> ctoLastFireMsByKey =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Conviction Override Engine — microstructure entry gate (live, config-gated). Optional bean. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.marketdata.ConvictionOverrideEngine convictionOverrideEngine;
    @org.springframework.beans.factory.annotation.Value("${conviction-override.entry.depth-confirm:true}")
    private boolean coeEntryDepthConfirm;
    @org.springframework.beans.factory.annotation.Value("${conviction-override.entry.spoof-veto:true}")
    private boolean coeEntrySpoofVeto;

    // ── EntryPipeline micro-veto rail (Market-Memory V5, docs/MARKET-MEMORY-V5-DESIGN.md §5) ──────
    /** Per-strike baseline/state memory. Optional bean. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.marketdata.MarketMemoryEngine marketMemoryEngine;
    @org.springframework.beans.factory.annotation.Value("${entry-pipeline.veto-rail.enabled:true}")
    private boolean vetoRailEnabled;
    @org.springframework.beans.factory.annotation.Value("${entry-pipeline.veto-rail.spike-top-pct:6.0}")
    private double vetoSpikeTopPct;
    @org.springframework.beans.factory.annotation.Value("${entry-pipeline.veto-rail.blow-off-vol-rate:6.0}")
    private double vetoBlowOffVolRate;
    @org.springframework.beans.factory.annotation.Value("${entry-pipeline.veto-rail.heavy-offer-imb:-0.40}")
    private double vetoHeavyOfferImb;
    /** Cross-pollination #1 (07-10): veto bias entries when writers are BUILDING on the target strike.
     *  ON — no counter-case on either live day; avalanche/deep exempt upstream. */
    @org.springframework.beans.factory.annotation.Value("${entry-pipeline.veto-rail.writers-against.enabled:true}")
    private boolean biasWritersAgainstEnabled;
    @org.springframework.beans.factory.annotation.Value("${entry-pipeline.veto-rail.writers-against.oi-pct:1.5}")
    private double biasWritersAgainstOiPct;
    /** Cross-pollination #2 (07-10): rule-B timing for bias entries. DEFAULT OFF — 07-09's winning
     *  dip-buys conflict with 07-10's losing ones; enable only after the two-day join validates. */
    @org.springframework.beans.factory.annotation.Value("${entry-pipeline.veto-rail.falling-premium.enabled:false}")
    private boolean biasFallingPremiumEnabled;
    @org.springframework.beans.factory.annotation.Value("${entry-pipeline.veto-rail.falling-premium.pct:1.0}")
    private double biasFallingPremiumPct;
    /** V5 avalanche stacking (review-3 issue 1): MEMORY_AVALANCHE entries may hold up to this many
     *  concurrent positions per underlying (different strikes). Other strategies keep the base cap. */
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.stack-enabled:true}")
    private boolean avalancheStackEnabled;
    @org.springframework.beans.factory.annotation.Value("${oi-momentum.avalanche.max-concurrent-per-index:3}")
    private int avalancheMaxConcurrentPerIndex;

    /**
     * V5 state-aware entry vetoes, evaluated for every option-BUY candidate with a resolved strike.
     * Blocks only the states the 3-day study proved hostile: DEAD (×0.4–0.75 continuation both ways),
     * SPIKE_TOP (premium already > +6% above its 15-min low — both 07-08 SENSEX SL losers), BLOW_OFF
     * (volume ≥6× trend = worst continuation bucket, 15.7%), HEAVY_OFFER (L5 imb ≤ −0.40). A DEEP
     * avalanche (dOI5m ≤ −8% with price turning) overrides every veto. One [EntryPipeline] decision
     * line is logged per candidate — the blocked-entry opportunity-cost dataset.
     */
    private java.util.Optional<String> microVetoRail(StrategyDecision decision, BigDecimal optionPremium) {
        if (!vetoRailEnabled || marketMemoryEngine == null || !marketMemoryEngine.isEnabled()) return java.util.Optional.empty();
        try {
            // SCOPE: OI-momentum candidates ONLY. The veto thresholds were calibrated exclusively on
            // OI-momentum's ATM±10 selection profile (3-day study) — executeEntry() is shared by every
            // strategy (straddles/strangles/expiry/directional) and those must not be policed by
            // vetoes never validated on them.
            String firstReason = decision.reasons().isEmpty() ? "" : decision.reasons().getFirst();
            if (!firstReason.startsWith("OI_MOMENTUM")) return java.util.Optional.empty();
            // MEMORY_AVALANCHE candidates (ANY tier) are exempt: the avalanche pattern IS its own
            // validated quality filter, and it structurally CONTRADICTS SPIKE_TOP — rule B requires
            // the premium to be RISING off its low, so a genuine avalanche is almost always >6% above
            // its 15-min low. The replay applied NO rail to avalanche entries (+₹105k includes them);
            // live day one showed SPIKE_TOP mass-vetoing them (351 vetoes + 65 stack rejects by 10:00,
            // e.g. a ₹12.50 premium blocked for being ₹1.90 off its low). Rail stays for chase entries.
            if (firstReason.contains("MEMORY_AVALANCHE")) {
                marketMemoryEngine.event("ENTRY_PASS", decision.underlying().name(),
                        decision.underlying() + " " + decision.selectedStrike().map(Object::toString).orElse("?")
                                + " " + decision.optionType().map(Enum::name).orElse("?"),
                        "avalanche candidate — veto rail exempt (pattern is its own filter)");
                return java.util.Optional.empty();
            }
            if (decision.selectedStrike().isEmpty() || decision.optionType().isEmpty()) return java.util.Optional.empty();
            com.algo.trade.domain.IndexType idx = com.algo.trade.domain.IndexType.from(decision.underlying());
            int strike = decision.selectedStrike().get().intValue();
            String ty = decision.optionType().get().name();
            var mem = marketMemoryEngine.get(idx, strike, ty);
            if (mem == null || mem.warming()) return java.util.Optional.empty(); // no memory yet — pass through
            // Deep override honors a <=30s-old deep classification too (flicker grace — chunky OI
            // prints must not let a veto kill the rare deep entry between detection and this gate).
            if (mem.deepAvalanche() || marketMemoryEngine.recentDeepAvalanche(idx, strike, ty, 30)) {
                log.info("[EntryPipeline] PASS deep-avalanche override — {} {} {} dOI5m={}% dP5m={}% (all vetoes bypassed)",
                        idx, strike, ty, String.format("%.1f", mem.dOi5mPct()), String.format("%.1f", mem.dP5mPct()));
                return java.util.Optional.empty();
            }
            String veto = null;
            double px = optionPremium.doubleValue();
            if (mem.state() == com.algo.trade.marketdata.MarketMemoryEngine.MarketState.DEAD) {
                veto = "DEAD_STATE";
            } else if (mem.low15m() > 0 && px > mem.low15m() * (1 + vetoSpikeTopPct / 100.0)) {
                veto = String.format("SPIKE_TOP(px=%.1f > low15m %.1f +%.0f%%)", px, mem.low15m(), vetoSpikeTopPct);
            } else if (mem.volRate30s() >= vetoBlowOffVolRate) {
                veto = String.format("BLOW_OFF(volRate=%.1fx)", mem.volRate30s());
            } else if (biasWritersAgainstEnabled && mem.dOi5mPct() >= biasWritersAgainstOiPct && mem.zOi() >= 0) {
                // Cross-pollination #1 (2026-07-10): writers BUILDING on the strike a bias entry is
                // about to buy = the entry thesis contradicted by the chain. Today's evidence: 24200PE
                // entered at dOI5m +20.1% (−244), 24150CE at +2.1% WRITER_PRESS. Yesterday's winners
                // all sat at negative/flat dOI — no known counter-case, so this one binds. Avalanche
                // and deep entries never reach here (exempt above).
                veto = String.format("WRITERS_AGAINST(dOI5m=+%.1f%% zOi=%.1f — writers building into the entry)",
                        mem.dOi5mPct(), mem.zOi());
            } else if (biasFallingPremiumEnabled && mem.dP5mPct() <= -biasFallingPremiumPct) {
                // Cross-pollination #2: rule-B timing for bias entries (don't buy a falling premium).
                // DEFAULT OFF: today's 3 losers entered at dP5m −0.7..−3.9, but 07-09's 24100PE
                // dip-buys WON (+933) — ambiguous across days; validate the join before enabling.
                veto = String.format("FALLING_PREMIUM(dP5m=%.1f%% — rule B: buy the turn, not the dip)", mem.dP5mPct());
            } else if (convictionOverrideEngine != null) {
                var os = convictionOverrideEngine.get(idx, strike, ty);
                if (os != null && os.bookImbalance() <= vetoHeavyOfferImb) {
                    veto = String.format("HEAVY_OFFER(imb=%.2f)", os.bookImbalance());
                }
            }
            // Central event sink: ring buffer (UI §16) + INFO log + daily decisions CSV — the
            // blocked-entry opportunity-cost dataset, one row per candidate.
            marketMemoryEngine.event(veto == null ? "ENTRY_PASS" : "ENTRY_VETO",
                    idx.name(), idx + " " + strike + " " + ty,
                    String.format("%sstate=%s tier=%s zOi=%.1f dOI5m=%.2f%% dP5m=%.1f%% volRate=%.1f volUnit=%.1f%% px=%.1f pain=%.1f%%",
                            veto == null ? "" : veto + " | ", mem.state(), mem.tier(), mem.zOi(), mem.dOi5mPct(),
                            mem.dP5mPct(), mem.volRate30s(), mem.volUnitPct(), px, mem.painPct()));
            return java.util.Optional.ofNullable(veto);
        } catch (Exception e) {
            log.debug("[EntryPipeline] veto rail skipped: {}", e.toString());
            return java.util.Optional.empty();
        }
    }
    /** PER-USER last SELL (exit) price + time for a strike (key "userId:instrumentKey") — blocks re-buying
     *  the same strike at or near where you just sold it, which is the definition of churn. The buy-price
     *  rule alone is insufficient: buy @119, sell @121 → lastBuy=119, so re-buy @120 passes the "below last
     *  buy" check even though you just sold at 121 and are buying back within 1% of your sell. This closes
     *  that gap: re-buy must also be below lastSell × (1 - buffer%). Recorded at CLOSE time. (2026-07-03) */
    /** Atomic price+time pair: recorded and read as ONE value so the churn guard can never observe a
     *  torn (price-without-time) state from two independent map puts. */
    private record SellRef(BigDecimal price, Instant time) {}
    private final java.util.concurrent.ConcurrentHashMap<String, SellRef> lastSellByUserInstrument =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** §B1 margin backoff: when an exit is rejected for INSUFFICIENT MARGIN (Zerodha treats the closing SELL
     *  as a naked short on expiry day), stop hammering it every tick — back off for this many seconds and
     *  alert ONCE. The position stays open (the bot can't exit it without margin) but the retry storm + the
     *  per-tick "URGENT" alerts stop. tradeId → don't-retry-until timestamp. */
    @org.springframework.beans.factory.annotation.Value("${smart-order.margin-block-cooldown-seconds:120}")
    private long marginBlockCooldownSeconds;
    private final java.util.concurrent.ConcurrentHashMap<String, Instant> exitMarginBlockedUntil =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** ENTRY margin-rejection backoff state (2026-07-01): per-user consecutive INSUFFICIENT-FUNDS entry rejects
     *  and the resulting backoff deadline. Cleared on any successful fill for that user. */
    private final java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>
            entryMarginRejectStreak = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Long, Instant> entryMarginBackoffUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** ALIGNED-FIRE (2026-06-29): true-parallel multi-user dispatch. The ORIGINATING (primary) entry computes
     *  one common fire-instant = now + this budget, fans out to the other users, and EVERY user (primary
     *  included) parks until that exact instant before calling the broker — so all users' orders dispatch
     *  together instead of the primary leading. The budget must cover each user's gate+sizing prep (a few ms);
     *  a user that preps slower than the budget just fires immediately (graceful, never blocks). 0 disables. */
    @org.springframework.beans.factory.annotation.Value("${trading.multiuser.sync-fire-budget-ms:15}")
    private long syncFireBudgetMs;
    /** Per-thread common fire-instant (nanoTime). Set on the primary's scan thread and on each secondary's pool
     *  thread (via setSyncFireAt) so every user aligns to the same broker-call moment. */
    private static final ThreadLocal<Long> SYNC_FIRE_AT_NANOS = new ThreadLocal<>();
    /** Set by SignalCopyService on each secondary's pool thread before it runs the entry. */
    public static void setSyncFireAt(long nanos) { SYNC_FIRE_AT_NANOS.set(nanos); }
    public static void clearSyncFireAt() { SYNC_FIRE_AT_NANOS.remove(); }
    /** Park the current thread until the common fire-instant (one-shot). Bounded + graceful: never waits if the
     *  instant has passed, capped at 200ms as a safety. This is a timed park, NOT a lock — it cannot deadlock. */
    private void parkUntilSyncFireTime() {
        Long fireAt = SYNC_FIRE_AT_NANOS.get();
        if (fireAt == null) return;
        SYNC_FIRE_AT_NANOS.remove(); // one-shot per order
        long wait = fireAt - System.nanoTime();
        if (wait > 0 && wait < 200_000_000L) java.util.concurrent.locks.LockSupport.parkNanos(wait);
        // Per-user skew from the common fire instant — proves the alignment. Negative/0 = on time; a large
        // POSITIVE value means this user's gate prep exceeded the budget (raise trading.multiuser.sync-fire-budget-ms).
        long skewUs = (System.nanoTime() - fireAt) / 1000;
        log.info("[AlignedFire] u:{} broker-call skew {}us from common instant (budget {}ms)",
                com.algo.trade.multiuser.UserContext.getUserId(), skewUs, syncFireBudgetMs);
    }

    private final MarketDataService marketDataService;
    private final SmartOrderRouter smartOrderRouter;
    private final Clock clock;

    @Autowired(required = false)
    private com.algo.trade.underlying.UnderlyingConfigService underlyingConfigService;

    /** ATM-anchored dynamic max-entry-premium resolver. Optional — when absent or disabled the
     *  guard falls back to the configured fixed cap (see premiumRangeGuard). */
    @Autowired(required = false)
    private com.algo.trade.strategy.filter.DynamicEntryPremiumService dynamicEntryPremiumService;

    @Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @Autowired(required = false)
    private com.algo.trade.execution.exit.EntryLiquidityRecorder entryLiquidityRecorder;

    /** P0-4 defense: normalize every broker quantity to a valid exchange lot multiple before send. */
    @Autowired(required = false)
    private LotSizeValidator lotSizeValidator;

    @Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.ShiftTrapMaeMfeTracker shiftTrapMaeMfeTracker;

    /** General MAE/MFE tracker (OI_MOMENTUM + every non-shift-trap buying strategy). Registered for
     *  watchdog-materialized LIMIT fills here, which the strategies' own immediate-fill path never reaches. */
    @Autowired(required = false)
    private com.algo.trade.tuning.infra.MaeMfeTracker maeMfeTracker;

    @Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.OiShiftTrapConfig oiShiftTrapConfig;

    @Autowired(required = false)
    private com.algo.trade.strategy.oishifttrap.ShiftTrapOiUnwindExitDetector shiftTrapOiUnwindExitDetector;

    /** P0-7: per-user halt resolution. Optional — present only in multi-user mode. When absent
     *  (single-user), the engine falls back to the global {@link TradingStateService} halt. */
    @Autowired(required = false)
    private com.algo.trade.multiuser.UserTradingStateManager userTradingStateManager;

    @Autowired
    public ExecutionEngine(TradingProperties properties, GlobalConfigService globalConfigService, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                           TradeRepository tradeRepository, OrderRepository orderRepository,
                           ErrorEventRepository errorEventRepository,
                           StrategyDecisionRepository decisionRepository,
                           ExecutionTuningRecorder executionOutcomeCsvRecorder,
                           TelegramAlertService telegramAlertService,
                           com.algo.trade.config.PositionSyncProperties positionSyncProperties,
                           StrategyConfigService strategyConfigService,
                           MarketDataService marketDataService,
                           SmartOrderRouter smartOrderRouter) {
        this(properties, globalConfigService, brokerClient, riskEngine, tradingStateService, tradeRepository, orderRepository, errorEventRepository, decisionRepository,
                executionOutcomeCsvRecorder, telegramAlertService, positionSyncProperties, strategyConfigService, marketDataService, smartOrderRouter, Clock.systemUTC());
    }

    ExecutionEngine(TradingProperties properties, GlobalConfigService globalConfigService, BrokerClient brokerClient, RiskEngine riskEngine, TradingStateService tradingStateService,
                    TradeRepository tradeRepository, OrderRepository orderRepository,
                    ErrorEventRepository errorEventRepository,
                    StrategyDecisionRepository decisionRepository, ExecutionTuningRecorder executionOutcomeCsvRecorder,
                    TelegramAlertService telegramAlertService,
                    com.algo.trade.config.PositionSyncProperties positionSyncProperties, StrategyConfigService strategyConfigService,
                    MarketDataService marketDataService, SmartOrderRouter smartOrderRouter, Clock clock) {
        this.properties = properties;
        this.globalConfigService = globalConfigService;
        this.brokerClient = brokerClient;
        this.riskEngine = riskEngine;
        this.tradingStateService = tradingStateService;
        this.tradeRepository = tradeRepository;
        this.orderRepository = orderRepository;
        this.errorEventRepository = errorEventRepository;
        this.decisionRepository = decisionRepository;
        this.executionOutcomeCsvRecorder = executionOutcomeCsvRecorder;
        this.telegramAlertService = telegramAlertService;
        this.positionSyncProperties = positionSyncProperties;
        this.strategyConfigService = strategyConfigService;
        this.marketDataService = marketDataService;
        this.smartOrderRouter = smartOrderRouter;
        this.clock = clock;
    }

    @Transactional
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize) {
        return executeEntry(decision, optionPremium, lotSize, (StrategyConfig) null);
    }

    /**
     * Execute entry with optional per-strategy config for position sizing and CSV recording.
     * If strategyConfig is null, falls back to directional buy config.
     */
    @Transactional(timeout = 30) // 30-second timeout prevents indefinite lock holding during slow broker I/O
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize, StrategyConfig strategyConfig) {
        return executeEntry(decision, optionPremium, lotSize, strategyConfig, null);
    }

    /**
     * Environment metadata captured at entry time for post-trade analysis.
     * Write-once: set on TradeEntity at creation, never modified after.
     */
    public record EnvironmentMetadata(int environmentScore, String environmentBreakdown, String sessionWindow) {}

    /**
     * Execute entry with optional per-strategy config and environment metadata.
     * Environment metadata (score, breakdown, session) is persisted on the TradeEntity for post-trade analysis.
     */
    @Transactional(timeout = 30)
    public ExecutionResult executeEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize,
                                         StrategyConfig strategyConfig, EnvironmentMetadata envMetadata) {
        StrategyConfig effectiveConfig = strategyConfig != null ? strategyConfig : strategyConfigService.getDirectionalBuyConfig(decision.underlying().name());
        BigDecimal stopLossPercent = effectiveConfig.getStopLossPercent();
        log.info("Entry execution requested: signalType={}, underlying={}, instrument={}, optionType={}, premium={}, lotSize={}, running={}, killSwitch={}",
                decision.signalType(),
                decision.underlying(),
                decision.selectedInstrumentKey().orElse(""),
                decision.optionType().map(Enum::name).orElse(""),
                optionPremium,
                lotSize,
                tradingStateService.running(),
                tradingStateService.killSwitchEnabled());
        if (optionPremium == null || optionPremium.signum() <= 0) {
            log.warn("Entry execution rejected: optionPremium is zero or null for instrument={}",
                    decision.selectedInstrumentKey().orElse(""));
            return ExecutionResult.rejected(List.of("Option premium is zero or unavailable — cannot place order"));
        }
        // ── EntryPipeline micro-veto rail (Market-Memory V5) — SIGNAL-quality veto, so it applies
        // before fan-out (a DEAD/spike-top/blow-off candidate is bad for every user equally). ─────
        java.util.Optional<String> microVeto = microVetoRail(decision, optionPremium);
        if (microVeto.isPresent()) {
            return ExecutionResult.rejected(List.of("EntryPipeline veto: " + microVeto.get()));
        }
        // Rejection circuit breaker — soft halt is activated by trackBrokerRejection()
        // and persists until user resumes from UI. No need for a separate check here —
        // tradingStateService.running() + haltMode covers it. But we add an explicit
        // early return with a clear message for diagnostics.
        // ── P0-7: PER-USER halt enforcement ──────────────────────────────────────
        // A global HARD halt (and engine-stop/kill-switch below) is a system-wide master stop
        // for every account. A SOFT halt is scoped to the user it was raised on: the
        // primary/default user uses the global TradingStateService halt; every other user uses
        // their own UserTradingState. So a primary/global SOFT halt does NOT block other active
        // users — and when the source (primary) user is soft-halted we still fan the signal out
        // to the other users so their independent accounts keep trading.
        com.algo.trade.risk.HaltMode globalHalt = tradingStateService.haltMode();
        if (globalHalt == com.algo.trade.risk.HaltMode.HARD) {
            log.warn("Entry execution rejected: GLOBAL HARD halt — system-wide stop");
            return ExecutionResult.rejected(List.of(
                    "Entry halted: HARD — resume from UI to continue trading"));
        }
        com.algo.trade.risk.HaltMode userHalt = effectiveHaltModeForCurrentUser(globalHalt);
        if (userHalt != com.algo.trade.risk.HaltMode.NONE) {
            Long haltUid = com.algo.trade.multiuser.UserContext.getUserId();
            log.warn("Entry execution rejected: {} halt for userId={} — other active users unaffected",
                    userHalt, haltUid);
            // This user is halted, but other active users must still trade: fan the signal out to
            // them (mirrors the normal fan-out below; runs each under their own per-user halt gate).
            if (signalCopyService != null && signalCopyService.isEnabled()) {
                signalCopyService.fireForAllUsersAsync(haltUid, decision, optionPremium, lotSize, strategyConfig);
            }
            return ExecutionResult.rejected(List.of("Entry halted: " + userHalt
                    + " (this user) — other active users unaffected"));
        }
        StrategyDecisionEntity savedDecision = persistDecision(decision, false, strategyConfig);
        if (!tradingStateService.running()) {
            log.warn("Entry execution rejected: trading engine is stopped");
            List<String> reasons = List.of("Trading engine is stopped");
            updateExecutionStage(savedDecision, "TRADING_STOPPED", reasons.getFirst());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "TRADING_STOPPED", false,
                    null, null, null, null, reasons, effectiveConfig);
            // In capture-when-stopped mode this branch fires on every captured signal — suppress the
            // per-signal Telegram alert so a data-collection day doesn't spam. The order is still rejected
            // (no placement); the TRADING_STOPPED outcome is still recorded for the report.
            if (!tradingStateService.captureWhenStopped()) {
                telegramAlertService.entryRejected(decision, optionPremium, "TRADING_STOPPED", reasons);
            }
            return ExecutionResult.rejected(reasons);
        }
        // MARGIN BACKOFF (2026-07-01): if this user just hit repeated INSUFFICIENT-FUNDS rejections (the async
        // copy-margin storm — u:8's 71 rejects today), stop attempting entries for them until it clears or a
        // fill frees margin. Per-user: primary/other users are unaffected (fan-out runs each under its own gate).
        Long marginUid = com.algo.trade.multiuser.UserContext.getUserId();
        if (isEntryMarginBackedOff(marginUid)) {
            List<String> reasons = List.of("Margin backoff active for userId=" + marginUid
                    + " (repeated insufficient-funds) — entries paused");
            log.warn("Entry execution rejected: {}", reasons.getFirst());
            updateExecutionStage(savedDecision, "MARGIN_BACKOFF", reasons.getFirst());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "MARGIN_BACKOFF", false,
                    null, null, null, null, reasons, effectiveConfig);
            // Margin backoff is PER-USER (this user's insufficient-funds streak) — the other users have their
            // own margin state, so fan the signal out to them (mirrors the risk/sizing/cap self-reject paths;
            // the 5s copy dedup guards against a re-fan storm). Was silently starving secondaries. (2026-07-02)
            fanOutToOtherUsersOnSelfReject(decision, optionPremium, lotSize, strategyConfig);
            return ExecutionResult.rejected(reasons);
        }

        List<String> orderGuardRejections = orderGuardRejections(decision, optionPremium);
        if (!orderGuardRejections.isEmpty()) {
            log.warn("Entry execution rejected by order guard: reasons={}", orderGuardRejections);
            updateExecutionStage(savedDecision, "ORDER_GUARD_REJECTED", String.join("; ", orderGuardRejections));
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_GUARD_REJECTED", false,
                    null, null, null, null, orderGuardRejections, effectiveConfig);
            telegramAlertService.entryRejected(decision, optionPremium, "ORDER_GUARD_REJECTED", orderGuardRejections);
            // Order-guard reasons are PER-USER (the price rule is per-user; cooldowns are now per-user too) —
            // the OTHER users may not be in cooldown / above their own last-buy, so fan out and let each
            // re-evaluate its own guards. Was a silent secondary-starvation: a primary price-rule/cooldown
            // reject dropped the secondaries' entire shot at the signal (580 price-rule rejects today). (2026-07-02)
            fanOutToOtherUsersOnSelfReject(decision, optionPremium, lotSize, strategyConfig);
            return ExecutionResult.rejected(orderGuardRejections);
        }

        int openTradeCount = openTradeCount();
        int tradesToday = tradesToday();
        BigDecimal dailyPnl = dailyPnl();
        int consecutiveLosses = consecutiveLosses();
        log.info("Entry risk context: openTradeCount={}, tradesToday={}, dailyPnl={}, consecutiveLosses={}",
                openTradeCount, tradesToday, dailyPnl, consecutiveLosses);
        String riskStrategyType = effectiveConfig.getStrategyType() != null ? effectiveConfig.getStrategyType().name() : null;
        var risk = riskEngine.evaluateEntry(decision, openTradeCount, tradesToday, dailyPnl,
                consecutiveLosses, tradingStateService.killSwitchEnabled(), riskStrategyType);
        if (!risk.allowed()) {
            log.warn("Entry execution rejected by risk engine: reasons={}", risk.reasons());
            updateExecutionStage(savedDecision, "RISK_REJECTED", String.join("; ", risk.reasons()));
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "RISK_REJECTED", false,
                    null, null, null, null, risk.reasons(), effectiveConfig);
            telegramAlertService.entryRejected(decision, optionPremium, "RISK_REJECTED", risk.reasons());
            // This is a PER-USER gate (consec losses, daily loss, max trades/day) — fan out so the OTHER
            // users still get the signal and decide on their own gate. The primary's loss streak (or any
            // per-user limit) must not block independent users.
            fanOutToOtherUsersOnSelfReject(decision, optionPremium, lotSize, strategyConfig);
            return ExecutionResult.rejected(risk.reasons());
        }

        // Use ATR-adaptive sizing when available (set by StrategyExecutionPipeline before this call).
        // ATR converts the underlying's volatility into an option SL % that scales with market conditions.
        // Falls back to fixed stopLossPercent when ATR is 0 (e.g. insufficient candle history).
        double atr = effectiveConfig.getAtrValue();
        // P0-1/P0-4 FIX: callers pass lotSize = desiredLotCount × contractLot. Recover the TRUE
        // contract lot from the underlying and the desired REAL lot count, and let RiskEngine cap
        // real lots (not folded blocks). This makes quantity always a multiple of the contract lot.
        int contractLot = com.algo.trade.domain.IndexType.from(decision.underlying()).lotSize();
        int desiredLots = Math.max(1, (int) Math.round((double) lotSize / contractLot));
        var sizing = atr > 0
                ? riskEngine.calculateQuantityWithATR(optionPremium, contractLot, desiredLots, atr)
                : riskEngine.calculateQuantity(optionPremium, contractLot, desiredLots, stopLossPercent);
        if (!sizing.allowed()) {
            log.warn("Entry execution rejected by position sizing: reason={}, riskAmount={}, estimatedCost={}",
                    sizing.reason(), sizing.riskAmount(), sizing.estimatedCost());
            List<String> reasons = List.of(sizing.reason());
            updateExecutionStage(savedDecision, "SIZING_REJECTED", sizing.reason());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "SIZING_REJECTED", false,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), null, reasons, effectiveConfig);
            telegramAlertService.entryRejected(decision, optionPremium, "SIZING_REJECTED", reasons);
            // Per-user sizing (capital-based) — fan out so other users still get an independent shot.
            fanOutToOtherUsersOnSelfReject(decision, optionPremium, lotSize, strategyConfig);
            return ExecutionResult.rejected(reasons);
        }
        log.info("Entry sizing accepted: quantity={}, riskAmount={}, estimatedCost={}, reason={}",
                sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), sizing.reason());

        if (!entryAllowed(openTradeCount)) {
            int pendingCount = countAlgoPendingOrders();
            log.warn("Entry execution rejected: open+pending algo positions ({}) + in-flight ({}) >= max open trades ({}) [algo pending={}]",
                    openTradeCount, Math.max(0, inFlightCurrent().get() - pendingCount),
                    globalConfigService.getMaxOpenTrades(), pendingCount);
            List<String> reasons = List.of("Max open trades reached (open+pending=" + openTradeCount
                    + ", in-flight=" + Math.max(0, inFlightCurrent().get() - pendingCount)
                    + ", max=" + globalConfigService.getMaxOpenTrades() + ")");
            updateExecutionStage(savedDecision, "CONCURRENT_ENTRY_BLOCKED", reasons.getFirst());
            // Per-user concurrency cap (this user's open+pending) — fan out so other users are unaffected.
            fanOutToOtherUsersOnSelfReject(decision, optionPremium, lotSize, strategyConfig);
            return ExecutionResult.rejected(reasons);
        }

        // §LOT-CAP (2026-06-30, user directive): the TOTAL open position must not cross the per-user profile lot
        // cap — even when ADDING to an existing position. Sum THIS user's open algo lots for the underlying and
        // reject if existing + this entry would exceed maxLotsPerTrade (per-user resolved; e.g. BALANCED=2). The
        // per-underlying concentration cap usually blocks an add first; this is the explicit lot-count guarantee.
        {
            int profileMaxLots = Math.max(1, globalConfigService.getMaxLotsPerTrade());
            int capLotSize = com.algo.trade.domain.IndexType.from(decision.underlying()).lotSize();
            if (capLotSize > 0) {
                final Long capUser = com.algo.trade.multiuser.UserContext.getUserId();
                final String capUnd = decision.underlying().name();
                int heldShares = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                        .filter(t -> !t.isPaperTrade())
                        .filter(t -> !t.getTradeId().startsWith("SYNC-"))
                        .filter(t -> capUnd.equals(t.getUnderlying()))
                        .filter(t -> sameUser(t.getUserId(), capUser))
                        .mapToInt(TradeEntity::getQuantity).sum();
                int heldLots = heldShares / capLotSize;
                int newLots = Math.max(1, sizing.quantity() / capLotSize);
                if (heldLots + newLots > profileMaxLots) {
                    log.warn("Entry BLOCKED by position lot-cap: holding {} lots in {} + {} new > profile cap {} (user={})",
                            heldLots, capUnd, newLots, profileMaxLots, capUser);
                    List<String> reasons = List.of("Position lot-cap: " + heldLots + " held + " + newLots
                            + " new > " + profileMaxLots + " lots in " + capUnd + " (profile maxLotsPerTrade)");
                    updateExecutionStage(savedDecision, "LOT_CAP_BLOCKED", reasons.getFirst());
                    fanOutToOtherUsersOnSelfReject(decision, optionPremium, lotSize, strategyConfig);
                    return ExecutionResult.rejected(reasons);
                }
            }
        }

        inFlightCurrent().incrementAndGet();
        boolean releaseEntryInFlight = true; // default: release in finally. Set to false for pending limit orders.
        try {
            // Multi-user ALIGNED-FIRE: the ORIGINATING (primary) call computes one common fire-instant, fans the
            // signal out to the other users, and every user parks until that instant before calling the broker —
            // so all users' orders dispatch TOGETHER (no primary lead). A coordinated secondary already has
            // SYNC_FIRE_AT set → it must NOT re-fan or reset the instant.
            if (signalCopyService != null && signalCopyService.isEnabled() && SYNC_FIRE_AT_NANOS.get() == null) {
                Long sourceUser = com.algo.trade.multiuser.UserContext.getUserId();
                // Only align (and pay the budget delay) when there are OTHER users to fire alongside.
                boolean hasPeers = signalCopyService.otherActiveUserCount(sourceUser) > 0;
                long fireAt = (hasPeers && syncFireBudgetMs > 0) ? System.nanoTime() + syncFireBudgetMs * 1_000_000L : 0L;
                if (fireAt > 0) SYNC_FIRE_AT_NANOS.set(fireAt); // the primary aligns to the same instant too
                signalCopyService.fireForAllUsersAsync(sourceUser, decision, optionPremium, lotSize, strategyConfig, fireAt);
            }

            String clientOrderId = "ENTRY-" + UUID.randomUUID();
            // SmartOrderRouter decides MARKET vs LIMIT based on liquidity
            SmartOrderRouter.RoutingDecision routing = smartOrderRouter.route(
                    decision.selectedInstrumentKey().orElseThrow(), OrderSide.BUY, optionPremium);

            // OI_MOMENTUM: use MARKET for SPIKE entries (instant fill needed),
            // but for non-spike entries use anticipatory discount (catch the dip before the move).
            OrderType entryOrderType = routing.orderType();
            Optional<BigDecimal> entryLimitPrice = routing.limitPrice().or(() -> Optional.of(optionPremium));
            String strategyTag = "strategy-entry";
            if (strategyConfig != null && strategyConfig.getStrategyType() == StrategyType.OI_MOMENTUM) {
                // Check if this is a spike/reversal entry (needs instant fill) or anticipatory (can wait)
                String signalName = decision.signalType() != null ? decision.signalType().name() : "";
                String reasonsStr = decision.reasons() != null ? String.join(" ", decision.reasons()) : "";
                boolean isSpikeEntry = signalName.contains("SPIKE")
                        || reasonsStr.contains("SPIKE") || reasonsStr.contains("REVERSE");
                if (isSpikeEntry) {
                    // Spike/reversal: need instant fill — use MARKET
                    entryOrderType = OrderType.MARKET;
                    entryLimitPrice = Optional.empty();
                    strategyTag = "oi-momentum-spike";
                } else {
                    // Non-spike (CASE 0, drift, squeeze, range-fade): operator will shake out first
                    // Use anticipatory discount to place limit below current price
                    SmartOrderRouter.RoutingDecision discountRouting = smartOrderRouter.routeWithDiscount(
                            decision.selectedInstrumentKey().orElseThrow(), OrderSide.BUY, optionPremium);
                    entryOrderType = discountRouting.orderType();
                    entryLimitPrice = discountRouting.limitPrice();
                    strategyTag = "oi-momentum-discount";
                }
            }

            // P0-4 defense-in-depth: normalize to a valid exchange lot multiple before sending to broker.
            // RiskEngine already returns a multiple of the contract lot, but this guards against any
            // instrument-master lot mismatch and is the last line before a broker InputException.
            int orderQuantity = sizing.quantity();
            if (lotSizeValidator != null) {
                String tradingSymbol = decision.selectedInstrumentKey().orElseThrow();
                LotSizeValidator.ValidationResult lotCheck = lotSizeValidator.validate(tradingSymbol, orderQuantity);
                if (!lotCheck.valid() && lotCheck.adjustedQuantity() > 0) {
                    log.warn("Entry quantity normalized to lot multiple: {} → {} (lotSize={}, reason={})",
                            orderQuantity, lotCheck.adjustedQuantity(), lotCheck.lotSize(), lotCheck.reason());
                    orderQuantity = lotCheck.adjustedQuantity();
                }
            }

            OrderRequest orderRequest = new OrderRequest(clientOrderId, decision.selectedInstrumentKey().orElseThrow(),
                    OrderSide.BUY, entryOrderType, ProductType.MIS, orderQuantity,
                    entryLimitPrice, strategyTag);
            log.info("Placing entry order: clientOrderId={}, instrument={}, side={}, orderType={}, product={}, quantity={}, routing={}",
                    orderRequest.clientOrderId(), orderRequest.instrumentKey(), orderRequest.side(),
                    orderRequest.orderType(), orderRequest.productType(), orderRequest.quantity(), routing.reason());
            // Entry tuning correlationKey — the SAME SignalDecisionKey the signal + execution events use.
            // Stamped on the order (carried to the trade on fill) and on the synchronous trade below, so the
            // exit event keys back to the entry and the eval→signal→execution→EXIT join closes.
            String entryCorrelationKey;
            try { entryCorrelationKey = com.algo.trade.reporting.SignalDecisionKey.from(decision); }
            catch (Exception ex) {
                entryCorrelationKey = null;
                log.warn("[Entry] SignalDecisionKey.from failed — exit tuning event will be orphaned (no entry linkage): {}",
                        ex.toString());
            }
            OrderResponse order;
            try {
                parkUntilSyncFireTime(); // ALIGNED-FIRE: wait for the common instant so all users' orders go out together
                order = placeOrderWithRetry(orderRequest, 2);
                persistOrderWithSignalTime(order, decision.timestamp(), optionPremium,
                        resolveStrategyType(decision, strategyConfig), entryCorrelationKey,
                        decision.reasons() != null && !decision.reasons().isEmpty() ? decision.reasons().getFirst() : null);
                // Price-based re-entry reference: remember what THIS user just paid for this strike so a later
                // re-entry is only allowed BELOW it (no averaging up). Recorded at placement → survives every
                // close type incl. manual/broker close. Per-user key.
                if (order != null && order.instrumentKey() != null && optionPremium != null && optionPremium.signum() > 0) {
                    recordEntryBuyPrice(com.algo.trade.multiuser.UserContext.getUserId(), order.instrumentKey(), optionPremium);
                }
                log.info("Entry order response: clientOrderId={}, brokerOrderId={}, status={}, requestedQuantity={}, filledQuantity={}, averageFillPrice={}, rejectionReason={}",
                        order.clientOrderId(), order.brokerOrderId().orElse(""), order.status(), order.requestedQuantity(),
                        order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(""));
            } catch (RuntimeException ex) {
                return rejectBrokerFailure(savedDecision, decision, optionPremium, lotSize, sizing.quantity(), sizing.riskAmount(),
                        sizing.estimatedCost(), orderRequest.clientOrderId(), ex, effectiveConfig);
            }

            // Limit order in book — watchdog will poll for fill and create TradeEntity
            // IMPORTANT: Do NOT release entryInFlight here — keep it held until the order
            // fills, cancels, or expires. This prevents duplicate entries from concurrent scans.
            // The OrderFillWatchdog or the auto-cancel timer will release it.
            if (order.status() == OrderStatus.OPEN || order.status() == OrderStatus.NEW) {
                rejectionCounter(com.algo.trade.multiuser.UserContext.getUserId()).set(0); // order accepted — reset this user's breaker
                log.info("Entry limit order placed — OrderFillWatchdog will track: clientOrderId={}, brokerOrderId={} (entryInFlight held)",
                        order.clientOrderId(), order.brokerOrderId().orElse(""));
                List<String> reasons = List.of("Limit order placed — awaiting fill");
                updateExecutionStage(savedDecision, "ORDER_OPEN", "brokerOrderId=" + order.brokerOrderId().orElse(""));
                executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_OPEN", false,
                        sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons, effectiveConfig);
                telegramAlertService.systemAlert("📋 Limit order placed — awaiting fill"
                        + System.lineSeparator() + "Signal: " + decision.signalType()
                        + System.lineSeparator() + "Instrument: " + order.instrumentKey()
                        + System.lineSeparator() + "Quantity: " + sizing.quantity()
                        + System.lineSeparator() + "Limit price: ₹" + optionPremium
                        + System.lineSeparator() + "Broker order: " + order.brokerOrderId().orElse(""));
                // Schedule a safety release of entriesInFlight after (cancelMinutes + 1) minutes
                int cancelMinutes = globalConfigService.getLimitOrderCancelMinutes();
                scheduleEntryInFlightRelease(cancelMinutes + 1, com.algo.trade.multiuser.UserContext.getUserId());
                releaseEntryInFlight = false; // tell finally block NOT to decrement
                return ExecutionResult.accepted(order, reasons);
            }

            if (order.status() == OrderStatus.COMPLETE) {
                rejectionCounter(com.algo.trade.multiuser.UserContext.getUserId()).set(0); // order filled — reset this user's breaker
                BigDecimal fillPrice = order.averageFillPrice().orElse(optionPremium);
                String tradeId = "TRD-" + UUID.randomUUID();
                TradeEntity tradeEntity = new TradeEntity(tradeId, order.instrumentKey(),
                        decision.underlying().name(), decision.optionType().orElseThrow().name(), TradeStatus.OPEN,
                        order.filledQuantity(), fillPrice, Instant.now(clock),
                        withCtoTag(order.instrumentKey(), String.join("; ", decision.reasons())));
                tradeEntity.setStrategyType(resolveStrategyType(decision, strategyConfig));
                tradeEntity.setEntryCorrelationKey(entryCorrelationKey); // exit-event keys back to the entry signal
                tradeEntity.setProductType("MIS"); // Intraday entry
                tagOwnership(tradeEntity); // Multi-user: user_id + broker account captured at entry
                tradeEntity.setAppliedTrailingStopActivationPercent(effectiveConfig.getTrailingStopActivationPercent());
                tradeEntity.setAppliedTrailingGapPercent(effectiveConfig.getTrailingGapPercent());
                if (envMetadata != null) {
                    tradeEntity.setEnvironmentScore(envMetadata.environmentScore());
                    tradeEntity.setEnvironmentBreakdown(envMetadata.environmentBreakdown());
                    tradeEntity.setEntrySessionWindow(envMetadata.sessionWindow());
                }
                if (entryLiquidityRecorder != null) {
                    entryLiquidityRecorder.recordTradeEntry(tradeEntity, effectiveConfig);
                }
                registerShiftTrapMaeTracking(tradeEntity);
                registerShiftTrapEntryOi(tradeEntity);
                tradeRepository.save(tradeEntity);
                linkOrderToTrade(order.clientOrderId(), tradeId);
                tradingStateService.recordTradeEntry();
        log.info("Entry trade opened: tradeId={}, instrument={}, quantity={}, entryPrice={}",
                        tradeId, order.instrumentKey(), order.filledQuantity(), fillPrice);
                logArmedExits(tradeEntity, "immediate-fill");
                List<String> reasons = List.of("Entry order filled and trade journal updated");
                updateExecutionStage(savedDecision, "ORDER_FILLED", "tradeId=" + tradeId);
                executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_FILLED", true,
                        sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons, effectiveConfig);
                telegramAlertService.entryOrderFilled(decision, optionPremium, sizing.quantity(), sizing.estimatedCost(), order);
                return ExecutionResult.accepted(order, reasons, tradeId);
            }
            log.warn("Entry order not filled: clientOrderId={}, status={}, reason={}",
                    order.clientOrderId(), order.status(), order.rejectionReason().orElse("Entry order was not filled"));
            trackBrokerRejection(order.rejectionReason().orElse("unknown"));
            List<String> reasons = List.of(order.rejectionReason().orElse("Entry order was not filled"));
            updateExecutionStage(savedDecision, "ORDER_NOT_FILLED", reasons.getFirst());
            executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "ORDER_NOT_FILLED", false,
                    sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), order, reasons, effectiveConfig);
            telegramAlertService.orderNotFilled(decision, optionPremium, sizing.quantity(), order, reasons);
            return ExecutionResult.rejected(reasons);
        } finally {
            if (releaseEntryInFlight) {
                inFlightCurrent().decrementAndGet();
            }
            // ALIGNED-FIRE safety: always clear the fire-instant on this thread so a stale value (e.g. if an
            // exception fired between setting it and the park) can never make the NEXT entry skip its fan-out.
            SYNC_FIRE_AT_NANOS.remove();
        }
    }

    /**
     * Execute a paper trade entry — creates a TradeEntity with simulated fill at current market price.
     * No broker order is placed. The trade is managed by existing exit monitors (SL/target/trailing/maxHold)
     * and closed via closeTrade which detects the "PAPER-" prefix and skips the broker exit order.
     */
    @Transactional
    public ExecutionResult executePaperEntry(StrategyDecision decision, BigDecimal optionPremium, int lotSize, StrategyConfig strategyConfig) {
        StrategyConfig effectiveConfig = strategyConfig != null ? strategyConfig : strategyConfigService.getDirectionalBuyConfig(decision.underlying().name());
        BigDecimal stopLossPercent = effectiveConfig.getStopLossPercent();
        log.info("PAPER entry requested: signalType={}, underlying={}, instrument={}, premium={}",
                decision.signalType(), decision.underlying(),
                decision.selectedInstrumentKey().orElse(""), optionPremium);

        StrategyDecisionEntity savedDecision = persistDecision(decision, true, strategyConfig);
        if (!tradingStateService.running()) {
            log.warn("PAPER entry rejected: trading engine is stopped");
            updateExecutionStage(savedDecision, "TRADING_STOPPED", "Trading engine is stopped");
            return ExecutionResult.rejected(List.of("Trading engine is stopped"));
        }

        double atrPaper = effectiveConfig.getAtrValue();
        int contractLotPaper = com.algo.trade.domain.IndexType.from(decision.underlying()).lotSize();
        int desiredLotsPaper = Math.max(1, (int) Math.round((double) lotSize / contractLotPaper));
        var sizing = atrPaper > 0
                ? riskEngine.calculateQuantityWithATR(optionPremium, contractLotPaper, desiredLotsPaper, atrPaper)
                : riskEngine.calculateQuantity(optionPremium, contractLotPaper, desiredLotsPaper, stopLossPercent);
        if (!sizing.allowed()) {
            updateExecutionStage(savedDecision, "PAPER_SIZING_REJECTED", sizing.reason());
            return ExecutionResult.rejected(List.of(sizing.reason()));
        }

        if (!entryAllowed(0)) {
            log.warn("PAPER entry rejected: max open trades reached (in-flight={})", inFlightCurrent().get());
            updateExecutionStage(savedDecision, "CONCURRENT_ENTRY_BLOCKED", "Concurrent entry blocked");
            return ExecutionResult.rejected(List.of("Concurrent entry blocked — max open trades reached"));
        }
        inFlightCurrent().incrementAndGet();
        try {
        String tradeId = "PAPER-TRD-" + UUID.randomUUID();
        String instrumentKey = decision.selectedInstrumentKey().orElse("UNKNOWN");
        TradeEntity trade = new TradeEntity(tradeId, instrumentKey,
                decision.underlying().name(), decision.optionType().map(Enum::name).orElse("CE"),
                TradeStatus.OPEN, sizing.quantity(), optionPremium, Instant.now(clock),
                "PAPER_TRADE [" + decision.signalType().name() + "]: " + String.join("; ", decision.reasons()));
        trade.setStrategyType(resolveStrategyType(decision, strategyConfig));
        trade.setProductType("MIS"); // Paper trades default to MIS
        tagOwnership(trade); // Multi-user: user_id + broker account captured at entry
        trade.setAppliedTrailingStopActivationPercent(effectiveConfig.getTrailingStopActivationPercent());
        trade.setAppliedTrailingGapPercent(effectiveConfig.getTrailingGapPercent());
        if (entryLiquidityRecorder != null) {
            entryLiquidityRecorder.recordTradeEntry(trade, effectiveConfig);
        }
        tradeRepository.save(trade);

        updateExecutionStage(savedDecision, "PAPER_FILLED", "tradeId=" + tradeId);
        log.info("PAPER trade opened: tradeId={}, instrument={}, qty={}, entryPrice={}",
                tradeId, instrumentKey, sizing.quantity(), optionPremium);
        // Paper trades don't count toward hourly cap (no broker margin consumed)

        OrderResponse syntheticOrder = new OrderResponse(
                "PAPER-" + UUID.randomUUID(), Optional.empty(), instrumentKey,
                OrderSide.BUY, OrderStatus.COMPLETE, sizing.quantity(), sizing.quantity(),
                Optional.of(optionPremium), Optional.empty(), Instant.now(clock));
        executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "PAPER_FILLED", true,
                sizing.quantity(), sizing.riskAmount(), sizing.estimatedCost(), syntheticOrder,
                List.of("Paper trade opened — exit managed by live monitors"), effectiveConfig);

        // Multi-user: PAPER entries do NOT fan out by default — the copy path places
        // REAL broker orders for other users, so a paper-mode strategy on the primary
        // would trade real money on secondary accounts. Gate behind explicit opt-in.
        if (copyPaperSignals && signalCopyService != null && signalCopyService.isEnabled()) {
            Long sourceUser = com.algo.trade.multiuser.UserContext.getUserId();
            log.warn("PAPER entry fan-out is ENABLED (copy-paper-signals=true) — firing REAL copies for other users");
            signalCopyService.fireForAllUsersAsync(sourceUser, decision, optionPremium, lotSize, strategyConfig);
        } else if (signalCopyService != null && signalCopyService.isEnabled()) {
            log.info("PAPER entry NOT copied to other users (trading.multiuser.signal-copy.copy-paper-signals=false)");
        }

        return ExecutionResult.accepted(syntheticOrder, List.of("Paper trade opened"), tradeId);
        } finally {
            inFlightCurrent().decrementAndGet();
        }
    }

    @Transactional(timeout = 30)
    public ExecutionResult closeTrade(String tradeId, BigDecimal lastPrice, String reason) {
        if (!closingInProgress.add(tradeId)) {
            log.warn("Close already in progress for tradeId={} reason={} — duplicate suppressed", tradeId, reason);
            return ExecutionResult.rejected(List.of("Close already in progress"));
        }
        try {
            ExecutionResult result = runAsTradeOwner(tradeId, () -> doCloseTrade(tradeId, lastPrice, reason));
            if (result.accepted()) {
                // Keep tradeId in closingInProgress permanently — prevents any subsequent
                // close attempts from other monitors (scheduled backup, FailSafe, etc.)
                // that may fire before they re-read the CLOSED status from DB.
                log.debug("Trade {} closed successfully — retaining close guard", tradeId);
            } else {
                closingInProgress.remove(tradeId);
            }
            return result;
        } catch (Exception e) {
            closingInProgress.remove(tradeId);
            throw e;
        }
    }

    /** Optional — present when multi-user wiring is active. Resolves the broker account for tagging/verification. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.auth.UserBrokerConfigRepository userBrokerConfigRepository;

    /** Broker config of the CURRENT UserContext user, if multi-user wiring is active. */
    private java.util.Optional<com.algo.trade.auth.UserBrokerConfig> currentBrokerConfig() {
        if (userBrokerConfigRepository == null) return java.util.Optional.empty();
        try {
            return userBrokerConfigRepository.findByUserId(com.algo.trade.multiuser.UserContext.getUserId());
        } catch (Exception e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Entry-time ownership capture: user_id + broker_name + broker_client_id
     * (e.g. ZERODHA / SX0602). The exit flow routes and verifies against these,
     * so the close always fires on the exact account that opened the position.
     */
    private void tagOwnership(TradeEntity trade) {
        trade.setUserId(com.algo.trade.multiuser.UserContext.getUserId());
        currentBrokerConfig().ifPresent(c -> {
            trade.setBrokerName(c.getBrokerName());
            trade.setBrokerClientId(c.getBrokerClientId());
        });
    }

    /**
     * Exit-time safety check: alert loudly if the owning user's CURRENT broker
     * account differs from the one the trade was opened on (e.g. API key swapped
     * mid-day). The exit still proceeds — blocking would leave the position
     * unmanaged — but operators get a critical alert to intervene.
     */
    private void verifyExitAccount(TradeEntity trade) {
        if (trade.getBrokerClientId() == null || trade.getBrokerClientId().isBlank()) return; // legacy trade
        String current = currentBrokerConfig()
                .map(com.algo.trade.auth.UserBrokerConfig::getBrokerClientId).orElse(null);
        if (current != null && !current.equalsIgnoreCase(trade.getBrokerClientId())) {
            String msg = String.format(
                    "Exit account mismatch for trade %s: opened on %s but user %s now maps to %s — verify position manually!",
                    trade.getTradeId(), trade.getBrokerClientId(), trade.getUserId(), current);
            log.error("[ExitVerify] {}", msg);
            if (errorEventService != null) errorEventService.critical("ExecutionEngine", msg);
            telegramAlertService.systemAlert("🚨 " + msg);
        }
    }

    /**
     * Multi-user exit routing: exit monitors (candle-close listener, scheduled backup,
     * failsafe squareoff, watchdog, shutdown handler) run on scheduler/event threads
     * where UserContext is NOT set. Without this, the broker token resolution falls
     * back to the primary/file token and a non-primary user's exit order would be
     * placed on the WRONG Zerodha account. Always execute the close under the trade
     * owner's context (entries tag trades with user_id).
     */
    private ExecutionResult runAsTradeOwner(String tradeId, java.util.function.Supplier<ExecutionResult> action) {
        Long ownerId = tradeRepository.findById(tradeId)
                .map(TradeEntity::getUserId)
                .orElse(null);
        if (ownerId == null) {
            return action.get(); // legacy/pre-multiuser trade — current behavior unchanged
        }
        final ExecutionResult[] result = new ExecutionResult[1];
        com.algo.trade.multiuser.UserContext.runAs(ownerId, () -> result[0] = action.get());
        return result[0];
    }

    /**
     * EXIT copy: when the PRIMARY's trade closes, mirror the close to other users'
     * open copies of the same instrument+strategy. Strategy loops (e.g. OIMomentum)
     * only manage the primary's activeTradeId — without this, copied positions stay
     * open until the 15:20 FailSafe. Only fans out for primary/default-owned closes,
     * so a copied close never cascades again.
     */
    /** Post-exit drift capture — was the exit premature? (tuning data, best-effort). */
    @Autowired(required = false)
    private com.algo.trade.monitoring.PostExitDriftRecorder postExitDriftRecorder;

    private void copyExitToOtherUsers(TradeEntity trade, BigDecimal price, String reason) {
        // Piggyback: every close that reaches here is a real exit — record drift checkpoints.
        if (postExitDriftRecorder != null) {
            postExitDriftRecorder.record(trade.getTradeId(), trade.getInstrumentKey(),
                    trade.getStrategyType(), reason, price);
        }
        if (signalCopyService == null || !signalCopyService.isEnabled()) return;
        Long owner = trade.getUserId();
        if (owner != null && !owner.equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) return;
        try {
            signalCopyService.fireExitForAllUsersAsync(trade, price, reason);
        } catch (Exception e) {
            log.warn("Exit copy fan-out failed for {}: {}", trade.getTradeId(), e.getMessage());
        }
    }

    private ExecutionResult doCloseTrade(String tradeId, BigDecimal lastPrice, String reason) {
        log.info("Close trade requested: tradeId={}, lastPrice={}, reason={}", tradeId, lastPrice, reason);
        TradeEntity trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tradeId: " + tradeId));
        if (trade.getStatus() != TradeStatus.OPEN) {
            log.warn("Close trade rejected: tradeId={}, status={}", tradeId, trade.getStatus());
            return ExecutionResult.rejected(List.of("Trade is not open"));
        }
        // Stash the REAL exit reason (STOP_LOSS/TARGET/…) so a pending-LIMIT exit that materialises later via
        // OrderFillWatchdog carries it into the tuning ExitEvent instead of "WATCHDOG_FILLED_EXIT". Consumed
        // by the watchdog on fill; removed below when this call closes the trade immediately. (2026-07-02)
        if (reason != null && !reason.isBlank()) {
            pendingExitReasonByTradeId.put(tradeId, reason);
        }
        // §B5: anchor the same-instrument re-entry cooldown the moment we begin closing this strike.
        // PER-USER key (2026-07-02): keyed by the trade OWNER so one user's exit doesn't put ANOTHER user
        // into cooldown (the churnAndPriceGuards reads mirror this with the entering user's id). Previously
        // global-keyed → a primary exit silently blocked the secondary's re-entry on the same strike.
        if (trade.getInstrumentKey() != null) {
            Long exitOwner = trade.getUserId() != null
                    ? trade.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
            lastExitByInstrument.put(exitOwner + ":" + trade.getInstrumentKey(), Instant.now(clock));
            // Record the SELL price for the price-based re-entry rule (don't re-buy near where you just sold).
            if (lastPrice != null && lastPrice.signum() > 0) {
                String sellKey = exitOwner + ":" + trade.getInstrumentKey();
                lastSellByUserInstrument.put(sellKey, new SellRef(lastPrice, Instant.now(clock)));
            }
            // (Price-based re-entry reference is recorded at PLACEMENT — see recordEntryBuyPrice — so it also
            //  covers manual/sync closes that never reach this method.)
            // SOLID anti-churn: also anchor the per-underlying SAME-DIRECTION cooldown (catches strike
            // rotation — exit 23950PE then re-buy 23900PE seconds later). Keyed by user + underlying + PE/CE.
            String exDir = trade.getInstrumentKey().endsWith("CE") ? "CE"
                    : trade.getInstrumentKey().endsWith("PE") ? "PE" : null;
            if (exDir != null && trade.getUnderlying() != null) {
                lastExitByUnderlyingDir.put(exitOwner + ":" + trade.getUnderlying() + ":" + exDir, Instant.now(clock));
            }
        }

        // §B1 margin backoff: if a prior exit was margin-rejected, don't hammer the broker every tick — back
        // off until the cooldown lapses (then re-attempt once). Stops the per-tick retry storm + alert spam.
        Instant blockedUntil = exitMarginBlockedUntil.get(tradeId);
        if (blockedUntil != null && Instant.now(clock).isBefore(blockedUntil) && !tradeId.startsWith("PAPER-")) {
            log.debug("doCloseTrade: {} margin-blocked until {} — skipping exit attempt", tradeId, blockedUntil);
            return ExecutionResult.rejected(List.of("Exit margin-blocked — backing off (add margin / square off manually)"));
        }

        // Paper trades: close without broker order — just compute P&L and update DB
        if (tradeId.startsWith("PAPER-")) {
            boolean isShort = isShortEntry(trade);
            BigDecimal realizedPnl = isShort
                    ? trade.getEntryPrice().subtract(lastPrice).multiply(BigDecimal.valueOf(trade.getQuantity()))
                    : lastPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(trade.getQuantity()));
            trade.close(lastPrice, Instant.now(clock), realizedPnl, "PAPER_EXIT: " + reason);
            tradeRepository.save(trade);
            executionOutcomeCsvRecorder.recordExit(trade, lastPrice, realizedPnl, reason, null);
            log.info("PAPER trade closed: tradeId={}, exitPrice={}, realizedPnl={}, reason={}",
                    tradeId, lastPrice, realizedPnl, reason);
            // Paper trade outcomes don't affect rolling win rate for risk gates
            telegramAlertService.systemAlert(String.format(
                    "📝 Paper Trade Closed: %s | Entry ₹%.2f → Exit ₹%.2f | P&L ₹%.2f | %s",
                    trade.getInstrumentKey(), trade.getEntryPrice().doubleValue(),
                    lastPrice.doubleValue(), realizedPnl.doubleValue(), reason));
            copyExitToOtherUsers(trade, lastPrice, reason);
            return new ExecutionResult(true, Optional.empty(), List.of("Paper trade closed: P&L=" + realizedPnl));
        }

        // BROKER-TRUTH GUARD (2026-06-29, §B2/B4-G1): never place a SELL into a position the broker no longer
        // holds. After a MANUAL/external close the bot kept firing SELLs for ~60s (until PositionSynchronizer
        // caught up) — a naked-short risk. Query the live broker position; if the account is FLAT for this
        // instrument and the trade is old enough to have settled into positions(), reconcile it closed WITHOUT
        // a sell. Age guard avoids prematurely closing a just-filled position not yet reflected in positions().
        // Runs in the trade owner's context (doCloseTrade is wrapped by runAsTradeOwner).

        // §B6 / §6 (2026-06-29): Don't place a duplicate exit. Before selling, check for an EXISTING pending
        // exit on this instrument — both (a) bot-placed orders in our DB and (b) MANUAL orders placed directly
        // on the broker (Kite app) that the bot never recorded. A second sell into the same position = naked
        // short. If either exists, stand down and let it fill; the watchdog / PositionSynchronizer reconciles
        // the close. Runs in the trade owner's context (doCloseTrade is wrapped by runAsTradeOwner).
        // Exit side: SELL closes a long, BUY closes a short.
        OrderSide exitSideGuard = isShortEntry(trade) ? OrderSide.BUY : OrderSide.SELL;
        Long tradeOwnerId = trade.getUserId() != null
                ? trade.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        // (a) bot-placed pending exit in our DB, scoped to THIS trade's owner so a peer user's pending order on
        //     the same strike never blocks this user's exit. (Prev bug: `SELL || BUY && isShort` — operator
        //     precedence made short exits match ANY pending order, including unrelated entries.)
        try {
            boolean hasLocalPendingExit = orderRepository.findByStatusIn(List.of(OrderStatus.OPEN, OrderStatus.NEW)).stream()
                    .filter(o -> trade.getInstrumentKey().equals(o.getInstrumentKey()))
                    .filter(o -> tradeOwnerId.equals(o.getUserId() != null
                            ? o.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID))
                    .anyMatch(o -> exitSideGuard.name().equalsIgnoreCase(o.getSide()));
            if (hasLocalPendingExit) {
                log.warn("doCloseTrade: SKIPPED — bot exit already pending for {} (tradeId={}, reason={}) — "
                        + "waiting for it to fill instead of placing a duplicate", trade.getInstrumentKey(), tradeId, reason);
                return new ExecutionResult(true, Optional.empty(),
                        List.of("Pending bot exit already exists — no duplicate placed"));
            }
        } catch (Exception ex) {
            log.debug("doCloseTrade: local pending-exit check failed (proceeding): {}", ex.getMessage());
        }
        // (b) MANUAL pending exit in the LIVE broker order book — a sell the user placed on the Kite app that the
        //     bot never recorded, so check (a) cannot see it. This is the §6 manual-order protection. Best-effort:
        //     a broker API hiccup must never wedge a protective exit, so any failure falls through to the normal
        //     exit path below. ~one REST call per close (closingInProgress prevents per-tick repeats).
        try {
            boolean hasManualPendingExit = brokerClient.orders().stream()
                    .filter(o -> trade.getInstrumentKey().equals(o.instrumentKey()))
                    .filter(o -> o.side() == exitSideGuard)
                    .anyMatch(o -> o.status() == OrderStatus.OPEN || o.status() == OrderStatus.NEW);
            if (hasManualPendingExit) {
                String instr = trade.getInstrumentKey();
                long nowMs = System.currentTimeMillis();
                long[] st = stuckManualExit.computeIfAbsent(instr, k -> new long[]{nowMs, 0L});
                long stuckSec = (nowMs - st[0]) / 1000L;
                // How far underwater is the position right now? (sign-aware; blocked stop can't act while manual pends)
                double profitPct = 0;
                if (lastPrice != null && lastPrice.signum() > 0 && trade.getEntryPrice() != null
                        && trade.getEntryPrice().signum() > 0) {
                    double ep = trade.getEntryPrice().doubleValue();
                    double lp = lastPrice.doubleValue();
                    profitPct = isShortEntry(trade) ? (ep - lp) / ep * 100.0 : (lp - ep) / ep * 100.0;
                }
                double slPct = trade.getAppliedStopLossPercent() != null
                        ? trade.getAppliedStopLossPercent().doubleValue() : 10.0;
                boolean pastSl = profitPct <= -Math.abs(slPct);
                boolean escalate = manualExitEscalateAfterSeconds > 0
                        && stuckSec >= manualExitEscalateAfterSeconds && pastSl;
                // Throttle the log+alert (was spamming ~every fire — 46× on one stuck trade on 2026-06-30).
                boolean speak = st[1] == 0L || (nowMs - st[1]) >= manualExitAlertThrottleSeconds * 1000L;
                if (speak) {
                    st[1] = nowMs;
                    if (escalate) {
                        log.error("doCloseTrade: STUCK MANUAL EXIT — a MANUAL {} on {} has blocked the bot's "
                                + "protective exit for {}s while the position is {}% (past SL {}%). tradeId={} reason={}. "
                                + "The stop-loss is DEFEATED until the manual order fills/cancels.",
                                exitSideGuard, instr, stuckSec, String.format("%.1f", profitPct),
                                String.format("%.1f", slPct), tradeId, reason);
                        telegramAlertService.systemAlert(String.format("⚠️ URGENT %s: your manual %s has been "
                                + "PENDING %dm while the position bled to %.0f%% (past the %.0f%% stop). The bot's stop is "
                                + "BLOCKED — cancel/adjust your order NOW or it will keep bleeding.",
                                instr, exitSideGuard, stuckSec / 60, profitPct, slPct));
                    } else {
                        log.warn("doCloseTrade: SKIPPED — a MANUAL {} order is already pending at the broker for {} "
                                + "(tradeId={}, reason={}, stuck={}s) — standing down to avoid a double-sell / naked short",
                                exitSideGuard, instr, tradeId, reason, stuckSec);
                        telegramAlertService.systemAlert("ℹ️ " + instr
                                + ": manual exit already pending at broker — bot stood down (no duplicate sell).");
                    }
                }
                return new ExecutionResult(true, Optional.empty(),
                        List.of("Manual exit already pending at broker — no duplicate placed"));
            }
            // Manual block cleared for this instrument → reset the stuck tracker so a future episode times fresh.
            stuckManualExit.remove(trade.getInstrumentKey());
        } catch (Exception ex) {
            log.debug("doCloseTrade: broker order-book check failed (proceeding with exit): {}", ex.getMessage());
        }

        long tradeAgeSec = trade.getEntryTime() != null
                ? Duration.between(trade.getEntryTime(), Instant.now(clock)).getSeconds() : Long.MAX_VALUE;
        // Broker's ACTUAL net holding for this instrument — fetched once (only after the settle-in age guard, so
        // a just-filled position not yet in positions() isn't acted on). Reused for the flat-reconcile below AND
        // the exit-qty clamp further down. null = too young / positions() unavailable → trust the recorded qty.
        Integer brokerNet = null;
        if (tradeAgeSec >= brokerFlatGuardMinAgeSeconds) {
            brokerNet = brokerNetQtyForInstrument(trade.getInstrumentKey());
            if (brokerNet != null && brokerNet == 0) {
                BigDecimal exitPx = (lastPrice != null && lastPrice.signum() > 0) ? lastPrice : trade.getEntryPrice();
                boolean shrt = isShortEntry(trade);
                BigDecimal realizedPnl = shrt
                        ? trade.getEntryPrice().subtract(exitPx).multiply(BigDecimal.valueOf(trade.getQuantity()))
                        : exitPx.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(trade.getQuantity()));
                trade.close(exitPx, Instant.now(clock), realizedPnl,
                        "RECONCILED_FLAT: broker holds 0 (external/manual close) — " + reason);
                tradeRepository.save(trade);
                executionOutcomeCsvRecorder.recordExit(trade, exitPx, realizedPnl, reason, null);
                log.warn("doCloseTrade: SKIPPED SELL — broker is FLAT for {} (tradeId={}, age={}s); reconciled "
                        + "closed @~{} pnl={} instead of placing a naked-short SELL", trade.getInstrumentKey(),
                        tradeId, tradeAgeSec, exitPx, realizedPnl);
                telegramAlertService.systemAlert("ℹ️ " + trade.getInstrumentKey()
                        + " already flat at broker — reconciled without a SELL (no naked short).");
                copyExitToOtherUsers(trade, exitPx, reason);
                return new ExecutionResult(true, Optional.empty(),
                        List.of("Reconciled flat (broker holds 0) — no sell placed: P&L=" + realizedPnl));
            }
        }

        boolean validLastPrice = lastPrice != null && lastPrice.signum() > 0;
        if (!validLastPrice) {
            log.warn("doCloseTrade: lastPrice is zero/null for tradeId={} instrument={} — using MARKET order",
                    tradeId, trade.getInstrumentKey());
        }
        // Use the same product type as entry (MIS/CNC/NRML). Default to MIS if not set.
        ProductType exitProductType = resolveProductType(trade);
        // Determine exit side: SELL for long positions, BUY for short positions
        boolean isShort = isShortEntry(trade);
        OrderSide exitSide = isShort ? OrderSide.BUY : OrderSide.SELL;
        // §3 FIX (2026-06-29): protective exits (SL / trailing / forced square-off) route as TRUE MARKET so the
        // stop always fills; only opportunistic TARGET exits keep the marketable LIMIT. A no-bid-ask hole left
        // the secondary SL stuck ~2min, filling at -28% vs the -12% stop — MARKET prevents that.
        boolean protectiveExit = protectiveExitMarket && isProtectiveExit(reason);
        boolean useMarketExit = !validLastPrice || protectiveExit;
        // Apply market protection for fast fill (only for the marketable-LIMIT path)
        BigDecimal exitLimitPrice = useMarketExit ? null : applyExitProtection(lastPrice, exitSide);
        // §B3 (2026-06-29): clamp the exit qty to the broker's ACTUAL holding. Selling MORE than held turns the
        // excess into a naked short → Zerodha margin-rejects ("Insufficient funds") → the exit FAILS and the
        // position bleeds (the −19/−28% loss tail on 06-29, including winners at +9–11% that couldn't book).
        // brokerNet is null when the trade is too young to have settled into positions() — trust recorded qty then.
        int exitQty = trade.getQuantity();
        if (brokerNet != null && brokerNet != 0) {
            int held = Math.abs(brokerNet);
            if (held < exitQty) {
                log.warn("doCloseTrade: CLAMPING exit qty {}→{} for {} (tradeId={}) — broker holds only {}; selling "
                        + "the recorded qty would naked-short the excess and margin-reject the exit",
                        exitQty, held, trade.getInstrumentKey(), tradeId, held);
                exitQty = held;
            }
        }
        OrderRequest orderRequest = useMarketExit
                ? new OrderRequest("EXIT-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        exitSide, OrderType.MARKET, exitProductType, exitQty, Optional.empty(),
                        protectiveExit ? "exit-protective-mkt" : "exit-mkt")
                : new OrderRequest("EXIT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        exitSide, OrderType.LIMIT, exitProductType, exitQty, Optional.of(exitLimitPrice),
                        "exit");
        verifyExitAccount(trade); // assert we're closing on the same broker account the entry was placed on
        log.info("Placing exit order: tradeId={}, side={}, clientOrderId={}, instrument={}, quantity={}, limitPrice={}",
                tradeId, exitSide, orderRequest.clientOrderId(), orderRequest.instrumentKey(), orderRequest.quantity(), exitLimitPrice);
        // ALIGNED-FIRE (exit): the originating (primary) close computes a common instant, fans the exit out to the
        // copies NOW (each arms + parks), then the primary parks too — so all users' exits dispatch TOGETHER. A
        // coordinated copy already has SYNC_FIRE_AT set → it must NOT re-fan. Align only when copies actually exist.
        if (signalCopyService != null && signalCopyService.isEnabled() && SYNC_FIRE_AT_NANOS.get() == null) {
            long exitFireAt = syncFireBudgetMs > 0 ? System.nanoTime() + syncFireBudgetMs * 1_000_000L : 0L;
            if (exitFireAt > 0 && signalCopyService.fireExitAlignedNow(trade, lastPrice, reason, exitFireAt) > 0) {
                SYNC_FIRE_AT_NANOS.set(exitFireAt);
            }
        }
        OrderResponse order;
        try {
            parkUntilSyncFireTime(); // ALIGNED-FIRE: wait for the common instant so all users' exits go out together
            order = brokerClient.placeOrder(orderRequest);
        } catch (RuntimeException ex) {
            // Retry: re-fetch current LTP and use marketable LIMIT with fresh price
            log.warn("Exit LIMIT order failed for tradeId={}, retrying with fresh LTP: {}", tradeId, ex.getMessage());
            if (errorEventService != null) errorEventService.high("ExecutionEngine", "Exit LIMIT failed for " + tradeId + " — retrying: " + ex.getMessage(), ex);
            telegramAlertService.systemAlert("⚠️ Exit LIMIT failed for " + trade.getInstrumentKey() + " — retrying with fresh price");
            try {
                // Re-fetch current price for the retry (original lastPrice may be stale)
                BigDecimal freshPrice = marketDataService.quote(trade.getInstrumentKey())
                        .map(q -> q.lastPrice())
                        .filter(p -> p != null && p.signum() > 0)
                        .orElse(lastPrice);
                // Protective exits stay MARKET on retry too — never re-rest a stop as a limit.
                BigDecimal retryLimitPrice = protectiveExit ? null : applyExitProtection(freshPrice, exitSide);
                OrderRequest retryRequest = retryLimitPrice != null
                        ? new OrderRequest("EXIT-RETRY-" + UUID.randomUUID(), trade.getInstrumentKey(),
                                exitSide, OrderType.LIMIT, exitProductType, trade.getQuantity(),
                                Optional.of(retryLimitPrice), "exit-retry-fresh")
                        : new OrderRequest("EXIT-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                                exitSide, OrderType.MARKET, exitProductType, trade.getQuantity(),
                                Optional.empty(), "exit-mkt-retry");
                order = brokerClient.placeOrder(retryRequest);
            } catch (RuntimeException retryEx) {
                // §B1: a MARGIN rejection is a known, recurring condition (expiry-day naked-short margin on the
                // closing SELL) — don't spam a CRITICAL error + "URGENT" alert every tick. Mark it margin-blocked
                // (alerts once, backs off). Non-margin failures keep the loud URGENT path.
                if (isMarginError(retryEx.getMessage())) {
                    markExitMarginBlocked(tradeId, trade.getInstrumentKey(), retryEx.getMessage());
                } else {
                    log.error("Exit retry also failed for tradeId={}: {}", tradeId, retryEx.getMessage());
                    if (errorEventService != null) errorEventService.critical("ExecutionEngine", "Exit retry failed for " + tradeId + " (" + trade.getInstrumentKey() + "): " + retryEx.getMessage(), retryEx);
                    telegramAlertService.systemAlert("🚨 URGENT: Exit failed for " + trade.getInstrumentKey()
                            + " — POSITION STILL OPEN! Manual intervention required.");
                }
                return ExecutionResult.rejected(List.of("Exit order failed after retry: " + retryEx.getMessage()));
            }
        }
        persistOrder(order, tradeId);
        log.info("Exit order response: clientOrderId={}, brokerOrderId={}, status={}, filledQuantity={}, averageFillPrice={}, rejectionReason={}",
                order.clientOrderId(), order.brokerOrderId().orElse(""), order.status(), order.filledQuantity(),
                order.averageFillPrice().orElse(null), order.rejectionReason().orElse(""));
        if (order.status() != OrderStatus.COMPLETE) {
            // Order is pending — watchdog will track it
            if (order.status() == OrderStatus.OPEN || order.status() == OrderStatus.NEW) {
                log.info("Exit order pending — watchdog will track: tradeId={}, clientOrderId={}", tradeId, order.clientOrderId());
                return ExecutionResult.accepted(order, List.of("Exit order pending — watchdog tracking"));
            }
            log.warn("Exit order not filled: tradeId={}, status={}, reason={}",
                    tradeId, order.status(), order.rejectionReason().orElse("Exit order was not filled"));

            // Fallback: if REJECTED due to margin, retry with MARKET order (Zerodha often accepts
            // MARKET for closing existing positions even when LIMIT fails margin check)
            String rejectReason = order.rejectionReason().orElse("");
            if (order.status() == OrderStatus.REJECTED && rejectReason.toLowerCase().contains("insufficient funds")) {
                // Safety check: verify the position still exists at broker before retrying
                // This prevents accidentally opening a naked short if the position was already closed
                boolean positionStillOpen = false;
                try {
                    positionStillOpen = brokerClient.positions().stream()
                            .anyMatch(p -> trade.getInstrumentKey().equals(p.instrumentKey()) && p.quantity() != 0);
                } catch (Exception posEx) {
                    log.warn("Cannot verify position at broker — skipping MARKET fallback: {}", posEx.getMessage());
                }

                if (positionStillOpen) {
                    log.warn("Exit LIMIT rejected for margin but position confirmed open — retrying with MARKET: tradeId={}", tradeId);
                    try {
                        OrderRequest marketFallback = new OrderRequest(
                                "EXIT-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                                exitSide, OrderType.MARKET, exitProductType, trade.getQuantity(),
                                Optional.empty(), "exit-margin-fallback");
                        OrderResponse marketOrder = brokerClient.placeOrder(marketFallback);
                        persistOrder(marketOrder, tradeId);
                        if (marketOrder.status() == OrderStatus.COMPLETE || marketOrder.status() == OrderStatus.OPEN
                                || marketOrder.status() == OrderStatus.NEW) {
                            log.info("Exit MARKET fallback accepted: tradeId={}, status={}", tradeId, marketOrder.status());
                            if (marketOrder.status() == OrderStatus.COMPLETE) {
                                BigDecimal mktExitPrice = marketOrder.averageFillPrice().orElse(lastPrice);
                                BigDecimal mktPnl = isShort
                                        ? trade.getEntryPrice().subtract(mktExitPrice).multiply(BigDecimal.valueOf(trade.getQuantity()))
                                        : mktExitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(trade.getQuantity()));
                                trade.close(mktExitPrice, Instant.now(clock), mktPnl, reason);
                                tradeRepository.save(trade);
                                copyExitToOtherUsers(trade, mktExitPrice, reason);
                                return ExecutionResult.accepted(marketOrder, List.of("Exit filled via MARKET fallback"));
                            }
                            return ExecutionResult.accepted(marketOrder, List.of("Exit MARKET pending — watchdog tracking"));
                        }
                    } catch (Exception mktEx) {
                        log.error("Exit MARKET fallback also failed: tradeId={}, error={}", tradeId, mktEx.getMessage());
                    }
                } else {
                    log.info("Exit rejected but position no longer open at broker — skipping MARKET fallback: tradeId={}", tradeId);
                }
            }

            telegramAlertService.systemAlert("⚠️ Exit order rejected for " + trade.getInstrumentKey()
                    + " — " + order.rejectionReason().orElse("unknown reason"));
            return ExecutionResult.rejected(List.of(order.rejectionReason().orElse("Exit order was not filled")));
        }

        BigDecimal exitPrice = order.averageFillPrice().orElse(lastPrice);
        BigDecimal realizedPnl = isShort
                ? trade.getEntryPrice().subtract(exitPrice).multiply(BigDecimal.valueOf(trade.getQuantity()))
                : exitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(trade.getQuantity()));
        trade.close(exitPrice, Instant.now(clock), realizedPnl, reason);
        tradeRepository.save(trade);
        pendingExitReasonByTradeId.remove(tradeId); // closed immediately here — watchdog won't materialise it
        executionOutcomeCsvRecorder.recordExit(trade, exitPrice, realizedPnl, reason, order);
        log.info("Trade closed: tradeId={}, exitPrice={}, realizedPnl={}, reason={}", tradeId, exitPrice, realizedPnl, reason);
        // Scratch-aware, per-strategy win-rate recording: a ~flat sub-minute stop-out (|P&L| within the
        // configured dead-band) is excluded from the win/loss ratio instead of counting as a full loss.
        tradingStateService.recordTradeOutcome(realizedPnl, trade.getEntryPrice(), trade.getQuantity(), trade.getStrategyType());
        telegramAlertService.tradeClosed(tradeId, trade.getInstrumentKey(), trade.getQuantity(),
                trade.getEntryPrice(), exitPrice, realizedPnl, reason, order);
        copyExitToOtherUsers(trade, exitPrice, reason);
        return ExecutionResult.accepted(order, List.of("Exit order filled and trade journal updated"));
    }

    @Transactional(timeout = 30)
    public ExecutionResult closePartialTrade(String tradeId, int partialQuantity, BigDecimal lastPrice, String layerReason) {
        return runAsTradeOwner(tradeId, () -> doClosePartialTrade(tradeId, partialQuantity, lastPrice, layerReason));
    }

    private ExecutionResult doClosePartialTrade(String tradeId, int partialQuantity, BigDecimal lastPrice, String layerReason) {
        log.info("Partial close requested: tradeId={}, partialQuantity={}, lastPrice={}, layer={}", tradeId, partialQuantity, lastPrice, layerReason);
        TradeEntity trade = tradeRepository.findById(tradeId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tradeId: " + tradeId));
        if (trade.getStatus() != TradeStatus.OPEN) {
            log.warn("Partial close rejected: tradeId={}, status={}", tradeId, trade.getStatus());
            return ExecutionResult.rejected(List.of("Trade is not open"));
        }
        if (partialQuantity <= 0 || partialQuantity >= trade.getQuantity()) {
            log.warn("Partial close quantity invalid ({}), falling back to full close: tradeId={}", partialQuantity, tradeId);
            return closeTrade(tradeId, lastPrice, layerReason);
        }

        if (trade.isPaperTrade()) {
            boolean isShort = isShortEntry(trade);
            BigDecimal partialPnl = isShort
                    ? trade.getEntryPrice().subtract(lastPrice).multiply(BigDecimal.valueOf(partialQuantity))
                    : lastPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(partialQuantity));
            trade.partialClose(partialQuantity, partialPnl, layerReason);
            tradeRepository.save(trade);
            log.info("PAPER partial close: tradeId={}, layer={}, qty={}, price={}, partialPnl={}, remainingQty={}",
                    tradeId, layerReason, partialQuantity, lastPrice, partialPnl, trade.getQuantity());
            telegramAlertService.systemAlert(String.format(
                    "📊 Partial Profit Booked (%s): %s | %d lots @ ₹%.2f | P&L ₹%.2f | Remaining: %d lots",
                    layerReason, trade.getInstrumentKey(), partialQuantity, lastPrice.doubleValue(),
                    partialPnl.doubleValue(), trade.getQuantity()));
            return new ExecutionResult(true, Optional.empty(), List.of("Paper partial close: layer=" + layerReason + " pnl=" + partialPnl));
        }

        ProductType partialProductType = resolveProductType(trade);
        BigDecimal partialExitPrice = applyExitProtection(lastPrice, OrderSide.SELL);
        OrderRequest orderRequest = new OrderRequest("PARTIAL-" + UUID.randomUUID(), trade.getInstrumentKey(),
                OrderSide.SELL, OrderType.LIMIT, partialProductType, partialQuantity, Optional.of(partialExitPrice),
                "partial-exit");
        verifyExitAccount(trade); // assert we're closing on the same broker account the entry was placed on
        OrderResponse order;
        try {
            order = brokerClient.placeOrder(orderRequest);
        } catch (RuntimeException ex) {
            log.warn("Partial exit LIMIT failed for tradeId={}, retrying MARKET: {}", tradeId, ex.getMessage());
            if (errorEventService != null) errorEventService.high("ExecutionEngine", "Partial exit LIMIT failed for " + tradeId + " — retrying MARKET: " + ex.getMessage(), ex);
            try {
                OrderRequest marketReq = new OrderRequest("PARTIAL-MKT-" + UUID.randomUUID(), trade.getInstrumentKey(),
                        OrderSide.SELL, OrderType.MARKET, partialProductType, partialQuantity, Optional.empty(),
                        "partial-exit-mkt");
                order = brokerClient.placeOrder(marketReq);
            } catch (RuntimeException retryEx) {
                log.error("Partial exit MARKET retry failed for tradeId={}: {}", tradeId, retryEx.getMessage());
                if (errorEventService != null) errorEventService.critical("ExecutionEngine", "Partial exit retry failed for " + tradeId + ": " + retryEx.getMessage(), retryEx);
                return ExecutionResult.rejected(List.of("Partial exit failed after retry: " + retryEx.getMessage()));
            }
        }
        persistOrder(order, tradeId);
        if (order.status() != OrderStatus.COMPLETE) {
            log.warn("Partial exit order not filled: tradeId={}, status={}", tradeId, order.status());
            return ExecutionResult.rejected(List.of("Partial exit order not filled: " + order.status()));
        }
        BigDecimal exitPrice = order.averageFillPrice().orElse(lastPrice);
        boolean isShort = isShortEntry(trade);
        BigDecimal partialPnl = isShort
                ? trade.getEntryPrice().subtract(exitPrice).multiply(BigDecimal.valueOf(partialQuantity))
                : exitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(partialQuantity));
        trade.partialClose(partialQuantity, partialPnl, layerReason);
        tradeRepository.save(trade);
        log.info("Partial close filled: tradeId={}, layer={}, qty={}, exitPrice={}, partialPnl={}, remainingQty={}",
                tradeId, layerReason, partialQuantity, exitPrice, partialPnl, trade.getQuantity());
        telegramAlertService.systemAlert(String.format(
                "📊 Partial Profit Booked (%s): %s | %d lots @ ₹%.2f | P&L ₹%.2f | Remaining: %d lots",
                layerReason, trade.getInstrumentKey(), partialQuantity, exitPrice.doubleValue(),
                partialPnl.doubleValue(), trade.getQuantity()));
        return ExecutionResult.accepted(order, List.of("Partial close: layer=" + layerReason + " qty=" + partialQuantity));
    }

    private StrategyDecisionEntity persistDecision(StrategyDecision decision) {
        return persistDecision(decision, false, null);
    }

    private StrategyDecisionEntity persistDecision(StrategyDecision decision, boolean paperTrade) {
        return persistDecision(decision, paperTrade, null);
    }

    private StrategyDecisionEntity persistDecision(StrategyDecision decision, boolean paperTrade,
                                                   StrategyConfig explicitConfig) {
        log.info("Persisting strategy decision: timestamp={}, underlying={}, signalType={}, instrument={}, reasons={}",
                decision.timestamp(), decision.underlying(), decision.signalType(),
                decision.selectedInstrumentKey().orElse(""), decision.reasons());
        StrategyDecisionEntity entity = new StrategyDecisionEntity(decision.timestamp(), decision.underlying().name(),
                decision.signalType().name(), decision.underlyingPrice(), decision.optionPrice().orElse(null),
                decision.optionOpenInterest().orElse(null), decision.lotSize().orElse(null),
                decision.lotPrice().orElse(null),
                decision.selectedInstrumentKey().orElse(null), decision.selectedStrike().orElse(null),
                decision.optionType().map(Enum::name).orElse(null), decision.vwapConditionPassed(),
                decision.imbalance().orElse(null), decision.volumeSpike(), decision.confidenceScore(),
                String.join("; ", decision.reasons()));
        entity.setStrategyType(resolveStrategyType(decision, explicitConfig));
        entity.setPaperTrade(paperTrade);
        // Multi-user: tag the signal with the current user's ID for per-user filtering
        if (com.algo.trade.multiuser.UserContext.isSet()) {
            entity.setUserId(com.algo.trade.multiuser.UserContext.getUserId());
        }
        return decisionRepository.save(entity);
    }

    private void updateExecutionStage(StrategyDecisionEntity entity, String stage, String reason) {
        if (entity != null) {
            entity.setExecutionStage(stage);
            entity.setExecutionReason(reason);
            decisionRepository.save(entity);
        }
    }

    private void persistOrder(OrderResponse order) {
        persistOrder(order, null);
    }

    /** Back-links the persisted entry order to the trade it materialized into. */
    private void linkOrderToTrade(String clientOrderId, String tradeId) {
        try {
            orderRepository.findById(clientOrderId).ifPresent(o -> {
                o.setTradeId(tradeId);
                orderRepository.save(o);
            });
        } catch (Exception e) {
            log.debug("Order→trade back-link failed for {}: {}", clientOrderId, e.getMessage());
        }
    }

    private void persistOrder(OrderResponse order, String tradeId) {
        OrderEntity entity = new OrderEntity(order.clientOrderId(), order.brokerOrderId().orElse(null),
                order.instrumentKey(), order.side().name(), order.status(), order.requestedQuantity(),
                order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(null),
                order.updatedAt());
        entity.setOrderPlacedAt(Instant.now(clock));
        entity.setUserId(com.algo.trade.multiuser.UserContext.getUserId());
        entity.setTradeId(tradeId);
        currentBrokerConfig().ifPresent(c -> {
            entity.setBrokerName(c.getBrokerName());
            entity.setBrokerClientId(c.getBrokerClientId());
        });
        orderRepository.save(entity);
    }

    private void persistOrderWithSignalTime(OrderResponse order, Instant signalTimestamp, BigDecimal limitPrice,
                                            String strategyType, String entryCorrelationKey, String signalReason) {
        OrderEntity entity = new OrderEntity(order.clientOrderId(), order.brokerOrderId().orElse(null),
                order.instrumentKey(), order.side().name(), order.status(), order.requestedQuantity(),
                order.filledQuantity(), order.averageFillPrice().orElse(null), order.rejectionReason().orElse(null),
                order.updatedAt());
        entity.setSignalTimestamp(signalTimestamp);
        entity.setOrderPlacedAt(Instant.now(clock));
        entity.setStrategyType(strategyType);
        entity.setEntryCorrelationKey(entryCorrelationKey); // carry the entry key to the trade materialized on fill
        entity.setSignalReason(signalReason);               // C3: carry the SIGNAL identity to the materialized trade
        entity.setUserId(com.algo.trade.multiuser.UserContext.getUserId());
        currentBrokerConfig().ifPresent(c -> {
            entity.setBrokerName(c.getBrokerName());
            entity.setBrokerClientId(c.getBrokerClientId());
        });
        if (order.averageFillPrice().isPresent() && limitPrice != null) {
            entity.setSlippage(order.averageFillPrice().get().subtract(limitPrice).abs());
        }
        orderRepository.save(entity);
    }

    /**
     * Called by OrderFillWatchdog when a pending limit order fills.
     * Creates the TradeEntity so exit monitors (max hold, trailing stop, forced exit) can manage it.
     */
    @Transactional
    public void openTradeFromFilledOrder(OrderEntity orderEntity) {
        if (orderEntity.isTradeMaterialized()) {
            log.info("openTradeFromFilledOrder skipped: order already materialized, clientOrderId={}",
                    orderEntity.getClientOrderId());
            return;
        }
        String tradeId = "TRD-" + UUID.randomUUID();
        String instrumentKey = orderEntity.getInstrumentKey();
        if (orderEntity.getAverageFillPrice() == null || orderEntity.getAverageFillPrice().signum() <= 0) {
            log.error("openTradeFromFilledOrder skipped: fill price is null/zero for clientOrderId={} instrument={}",
                    orderEntity.getClientOrderId(), instrumentKey);
            if (errorEventService != null) errorEventService.critical("ExecutionEngine", "Fill price missing for " + instrumentKey + " order=" + orderEntity.getClientOrderId() + " — trade NOT opened");
            telegramAlertService.systemAlert("🚨 Watchdog: fill price missing for " + instrumentKey
                    + " order=" + orderEntity.getClientOrderId() + " — trade NOT opened, manual review needed");
            return;
        }
        BigDecimal fillPrice = orderEntity.getAverageFillPrice();
        int filledQty = orderEntity.getFilledQuantity();

        // A fill for this user means margin was available → clear any entry margin backoff for them.
        resetEntryMarginBackoff(orderEntity.getUserId());

        // Derive underlying and option type from instrument key using UnderlyingSymbol enum
        String underlying = extractUnderlyingFromKey(instrumentKey);
        String optionType = instrumentKey.toUpperCase().contains("PE") ? "PE" : "CE";

        // 4 Jun 2026 PM: defensive default. If strategyType is missing on the
        // order entity (e.g. an old order placed before persistOrderWithSignalTime
        // was the default path, or DB races losing the field), fall back to
        // OI_MOMENTUM's conservative SL/Target (15%/25%) rather than letting
        // the trade run with broker-default 30%/60% from DirectionalBuy. Today's
        // SENSEX 73900 PE loss was amplified by exactly this mis-default: it
        // hit -36% before SL fired because the wrong (wider) SL was applied.
        // OIM's tighter SL bounds the loss to ~-15% in similar future cases.
        if (orderEntity.getStrategyType() == null || orderEntity.getStrategyType().isBlank()) {
            log.error("openTradeFromFilledOrder: orphan order MISSING strategyType — "
                    + "defaulting to OI_MOMENTUM conservative SL/Target. clientOrderId={} instrument={}",
                    orderEntity.getClientOrderId(), instrumentKey);
            orderEntity.setStrategyType("OI_MOMENTUM");
            telegramAlertService.systemAlert("ORPHAN RECOVERY: clientOrderId="
                    + orderEntity.getClientOrderId() + " instrument=" + instrumentKey
                    + " was missing strategyType. Applied OIM conservative defaults. Manual review.");
        }
        String entryReason = "Limit order filled (watchdog) [" + orderEntity.getStrategyType() + "]: " + orderEntity.getClientOrderId();
        // C3 (2026-07-10): keep the SIGNAL identity on the trade. Order text alone broke every
        // contains("MEMORY_AVALANCHE") check (ladder/cooldown/episode dead on watchdog fills; a
        // restart then orphaned ladder custody — the 78200CE −4.8% bleed). Signal reason FIRST.
        if (orderEntity.getSignalReason() != null && !orderEntity.getSignalReason().isBlank()) {
            entryReason = orderEntity.getSignalReason() + " | " + entryReason;
        }
        // Use current time as entry time for orphaned orders discovered after restart.
        // The original fill time (orderEntity.getUpdatedAt()) may be minutes/hours old,
        // which would immediately trigger maxHoldTime exit. Using Instant.now() gives
        // the trade a fresh hold timer from the moment the system becomes aware of it.
        Instant entryTime = Instant.now(clock);
        TradeEntity trade = new TradeEntity(tradeId, instrumentKey, underlying, optionType,
                TradeStatus.OPEN, filledQty, fillPrice, entryTime, withCtoTag(instrumentKey, entryReason));
        // Set product type — default to MIS for watchdog-recovered orders (they were placed by our system as MIS)
        trade.setProductType("MIS");
        // Inherit the entry signal's tuning key from the order so the exit event (emitted by the watchdog,
        // which has no strategy state) keys back to the entry — closing the eval→signal→execution→EXIT join.
        trade.setEntryCorrelationKey(orderEntity.getEntryCorrelationKey());
        tagOwnership(trade); // Multi-user: user_id + broker account captured at entry
        // Use strategy type stored on the order entity at placement time
        if (orderEntity.getStrategyType() != null && !orderEntity.getStrategyType().isBlank()) {
            trade.setStrategyType(orderEntity.getStrategyType());
            // Set trailing stop params from the strategy config so exit monitors use consistent values
            try {
                StrategyConfig entryConfig = strategyConfigService.getConfig(
                        StrategyType.valueOf(orderEntity.getStrategyType()), underlying);
                if (entryConfig != null) {
                    trade.setAppliedTrailingStopActivationPercent(entryConfig.getTrailingStopActivationPercent());
                    trade.setAppliedTrailingGapPercent(entryConfig.getTrailingGapPercent());
                    // Fix #4 (2026-06-02): Freeze SL/target at entry-time config so
                    // later UI/config changes don't retroactively alter audit trail.
                    // Also captures bid/ask/volume/OI baseline for liquidity exits.
                    if (entryLiquidityRecorder != null) {
                        entryLiquidityRecorder.recordTradeEntry(trade, entryConfig);
                    }
                } else {
                    log.warn("No strategy config for {}/{} — trailing params not set on watchdog trade {}",
                            orderEntity.getStrategyType(), underlying, tradeId);
                }
            } catch (IllegalArgumentException ignored) {
                log.debug("Unknown strategy type on order {}: {} — trailing params not set",
                        orderEntity.getClientOrderId(), orderEntity.getStrategyType());
            }
        }
        registerShiftTrapMaeTracking(trade);
        registerShiftTrapEntryOi(trade);
        // General MAE/MFE: pending LIMIT fills materialize HERE, not in the strategy's immediate-fill path, so
        // without this the tracker never sees them and every exit reports 0.00 MAE/MFE (the give-back blind spot).
        registerMaeMfeTracking(trade, fillPrice);
        tradeRepository.save(trade);

        // Mark order as materialized to prevent duplicate trade creation
        orderEntity.setTradeMaterialized(true);

        // Update order entity with final status
        orderEntity.setStatus(OrderStatus.COMPLETE);
        orderEntity.setFilledQuantity(filledQty);
        orderEntity.setAverageFillPrice(fillPrice);
        orderRepository.save(orderEntity);

        // Tuning: emit the entry-FILL execution event (the sync immediate-fill path already writes
        // ORDER_FILLED; the watchdog path did not, so watchdog-filled trades had only the filledQty=0
        // ORDER_OPEN placeholder — breaking fill_ratio and the decision-record's "executed" count).
        // Keyed by the entry correlationKey stamped on the order so the fill joins signal↔exit.
        executionOutcomeCsvRecorder.recordFill(orderEntity.getStrategyType(), underlying,
                orderEntity.getEntryCorrelationKey(), orderEntity.getClientOrderId(),
                orderEntity.getRequestedQuantity(), filledQty, fillPrice);

        logArmedExits(trade, "watchdog-fill");
        log.info("Watchdog opened trade from filled order: tradeId={}, clientOrderId={}, instrument={}, qty={}, price={}",
                tradeId, orderEntity.getClientOrderId(), instrumentKey, filledQty, fillPrice);
        tradingStateService.recordTradeEntry();
        // Release the entry gate — the limit order has been converted to a trade
        releaseEntryInFlightGate();
        telegramAlertService.systemAlert("Limit order filled (watchdog)"
                + System.lineSeparator() + "Trade: " + tradeId
                + System.lineSeparator() + "Instrument: " + instrumentKey
                + System.lineSeparator() + "Qty: " + filledQty
                + System.lineSeparator() + "Price: " + fillPrice);
    }

    /** Matches {@link LivePositionExitMonitor} MAX_EXIT_SL_PCT — log the thresholds ExitMonitor will enforce. */
    private static final double EXIT_MONITOR_MAX_SL_PCT = 10.0;

    /**
     * One-line audit log after entry: stamped trade fields + effective ExitMonitor thresholds (global profile,
     * SL capped). Makes post-trade verification from logs possible without a DB query.
     */
    private void logArmedExits(TradeEntity trade, String source) {
        if (trade == null) {
            return;
        }
        double monitorSl = Math.min(globalConfigService.getStopLossPercent().doubleValue(), EXIT_MONITOR_MAX_SL_PCT);
        double monitorTarget = globalConfigService.getTargetPercent().doubleValue();
        double monitorTrailAct = globalConfigService.getTrailingStopActivationPercent().doubleValue();
        double monitorTrailGap = globalConfigService.getTrailingGapPercent().doubleValue();
        log.info("Armed exits [{}]: tradeId={} userId={} strategy={} {} | "
                        + "stamped SL={}% target={}% trail={}%/{}% | "
                        + "monitor SL={}% target={}% trail={}%/{}%",
                source, trade.getTradeId(), trade.getUserId(), trade.getStrategyType(), trade.getInstrumentKey(),
                fmtPct(trade.getAppliedStopLossPercent()), fmtPct(trade.getAppliedTargetPercent()),
                fmtPct(trade.getAppliedTrailingStopActivationPercent()), fmtPct(trade.getAppliedTrailingGapPercent()),
                monitorSl, monitorTarget, monitorTrailAct, monitorTrailGap);
    }

    private static String fmtPct(BigDecimal value) {
        return value != null ? value.stripTrailingZeros().toPlainString() : "?";
    }

    /**
     * Register a watchdog-materialized trade with the general {@link com.algo.trade.tuning.infra.MaeMfeTracker}
     * so its self-scheduled tick accumulates MAE/MFE. The strategies only register on an IMMEDIATE fill; pending
     * LIMIT orders (the common OI_MOMENTUM path) materialize via the watchdog, which previously skipped this —
     * so exits reported 0.00 MAE/MFE and the give-back / exit-quality tables were blind. OI_SHIFT_TRAP uses its
     * own tracker ({@link #registerShiftTrapMaeTracking}) so it's excluded here to avoid double work.
     * Best-effort: any failure is swallowed (tracking must never block trade creation).
     */
    private void registerMaeMfeTracking(TradeEntity trade, BigDecimal entryPremium) {
        if (maeMfeTracker == null || trade == null) return;
        String st = trade.getStrategyType();
        if (st == null || st.isBlank() || "OI_SHIFT_TRAP".equals(st)) return;
        if (entryPremium == null || entryPremium.signum() <= 0) return;
        try {
            StrategyType strategyType = StrategyType.valueOf(st);
            com.algo.trade.domain.IndexType index = com.algo.trade.domain.IndexType.fromName(trade.getUnderlying());
            if (index == null) return;
            com.algo.trade.domain.OptionType optType = "PE".equalsIgnoreCase(trade.getOptionType())
                    ? com.algo.trade.domain.OptionType.PE : com.algo.trade.domain.OptionType.CE;
            com.algo.trade.tuning.infra.MaeMfeTracker.Direction direction = strategyType.isSellingStrategy()
                    ? com.algo.trade.tuning.infra.MaeMfeTracker.Direction.SHORT
                    : com.algo.trade.tuning.infra.MaeMfeTracker.Direction.LONG;
            maeMfeTracker.onEntry(new com.algo.trade.tuning.infra.MaeMfeTracker.EntryContext(
                    trade.getTradeId(), strategyType, index, trade.getEntryCorrelationKey(), direction,
                    trade.getInstrumentKey(), parseStrike(trade.getInstrumentKey()), optType,
                    entryPremium, 0.0,
                    trade.getEntryTime() != null ? trade.getEntryTime() : Instant.now(clock)));
        } catch (Exception ex) {
            log.warn("registerMaeMfeTracking failed (non-fatal) for {}: {}", trade.getTradeId(), ex.getMessage());
        }
    }

    /** Best-effort parse of the strike from an instrument key (the trailing digit run before the CE/PE suffix).
     *  e.g. "NFO:NIFTY26JUN24050PE" → 24050. Returns 0 on any parse issue (strike is metadata, never critical). */
    private static int parseStrike(String instrumentKey) {
        try {
            if (instrumentKey == null) return 0;
            String s = instrumentKey;
            int colon = s.indexOf(':');
            if (colon >= 0) s = s.substring(colon + 1);
            if (s.length() >= 2) s = s.substring(0, s.length() - 2); // drop CE/PE suffix
            int i = s.length();
            while (i > 0 && Character.isDigit(s.charAt(i - 1))) i--;
            return i < s.length() ? Integer.parseInt(s.substring(i)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * Place order with retry on transient failures.
     * Before retrying, checks if the order already exists in the broker (idempotency).
     * Max retries with exponential backoff (500ms, 1000ms).
     */
    private OrderResponse placeOrderWithRetry(OrderRequest request, int maxRetries) {
        RuntimeException lastException = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return brokerClient.placeOrder(request);
            } catch (RuntimeException ex) {
                lastException = ex;
                String msg = ex.getMessage() != null ? ex.getMessage().toLowerCase() : "";
                // Only retry on transient failures (timeout, connection reset)
                boolean transient_ = msg.contains("timeout") || msg.contains("timed out")
                        || msg.contains("connection reset") || msg.contains("connection refused")
                        || msg.contains("socket") || msg.contains("eof");
                if (!transient_ || attempt >= maxRetries) {
                    throw ex; // non-transient or exhausted retries — propagate
                }
                // Idempotency check: verify the order wasn't actually placed despite the error
                try {
                    List<com.algo.trade.domain.Position> positions = brokerClient.positions();
                    boolean alreadyHasPosition = positions.stream()
                            .anyMatch(p -> p.instrumentKey().equals(request.instrumentKey()) && p.quantity() > 0);
                    if (alreadyHasPosition) {
                        log.warn("Broker retry aborted: position already exists for {} — order likely went through despite error",
                                request.instrumentKey());
                        throw ex; // don't retry — the order was placed
                    }
                } catch (Exception posEx) {
                    log.debug("Idempotency check failed: {}", posEx.getMessage());
                    // Can't verify — don't retry to be safe
                    throw ex;
                }
                long backoffMs = 500L * (attempt + 1);
                log.warn("Broker order failed (attempt {}/{}), retrying in {}ms: {}",
                        attempt + 1, maxRetries + 1, backoffMs, ex.getMessage());
                try { Thread.sleep(backoffMs); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); throw ex; }
            }
        }
        throw lastException;
    }

    /**
     * Release one entry-in-flight slot. Called by OrderFillWatchdog after a pending limit order
     * fills, cancels, or expires. Also called by the safety timer.
     */
    /**
     * P0-5b: release the close de-dupe guard for a trade. {@code closeTrade} retains a trade in
     * {@code closingInProgress} permanently once a close is accepted (to suppress duplicate exits).
     * If that exit order is later CANCELLED/REJECTED unfilled, the position is still OPEN and must be
     * exitable again — otherwise the trade is wedged until FailSafe. The watchdog calls this when an
     * EXIT order terminates unfilled.
     */
    public void releaseCloseGuard(String tradeId) {
        if (tradeId != null && closingInProgress.remove(tradeId)) {
            log.info("Close guard released for tradeId={} (exit order terminated unfilled) — exit can be retried", tradeId);
        }
    }

    public void releaseEntryInFlightGate() {
        // Runs in the order owner's context (OrderFillWatchdog wraps checkOrder/openTradeFromFilledOrder in
        // UserContext.runAs(ownerId)), so this decrements the CORRECT user's in-flight counter (§B6).
        int prev = inFlightCurrent().getAndUpdate(v -> Math.max(0, v - 1));
        if (prev > 0) {
            log.info("entriesInFlight[u:{}] decremented: {} → {}",
                    com.algo.trade.multiuser.UserContext.getUserId(), prev, prev - 1);
        }
    }

    /**
     * Schedule a safety release of one entriesInFlight slot after the given minutes.
     * Prevents permanent counter leak if OrderFillWatchdog fails to process the order.
     */
    private void scheduleEntryInFlightRelease(int minutes, Long userId) {
        // §B6: the safety-release runs on a context-less timer thread, so the userId is captured at schedule
        // time and the decrement targets THAT user's per-user counter (not the DEFAULT user).
        Thread.ofVirtual().name("entry-gate-safety-release").start(() -> {
            try {
                Thread.sleep(Duration.ofMinutes(minutes));
                int prev = inFlightFor(userId).getAndUpdate(v -> Math.max(0, v - 1));
                if (prev > 0) {
                    log.warn("entriesInFlight[u:{}] safety release after {}min: {} → {} — watchdog may have missed the order",
                            userId, minutes, prev, prev - 1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * P0-7 — resolve the SOFT halt that applies to the CURRENT user.
     *
     * <p>The primary/default user (and single-user mode, i.e. no {@link com.algo.trade.multiuser.UserTradingStateManager})
     * uses the global {@link TradingStateService} halt passed in as {@code globalHalt}. Every other
     * user uses their own {@link com.algo.trade.multiuser.UserTradingState}, so a global/primary
     * SOFT halt — or another user's soft halt — never blocks them. A global HARD halt is handled by
     * the caller as a system-wide master stop and never reaches here.</p>
     *
     * <p>Any resolution error falls back to the conservative global halt.</p>
     */
    private com.algo.trade.risk.HaltMode effectiveHaltModeForCurrentUser(com.algo.trade.risk.HaltMode globalHalt) {
        try {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            if (userTradingStateManager != null && uid != null
                    && !uid.equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) {
                com.algo.trade.risk.HaltMode userHalt = userTradingStateManager.getState(uid).getHaltMode();
                return userHalt != null ? userHalt : com.algo.trade.risk.HaltMode.NONE;
            }
        } catch (Exception ex) {
            log.debug("[P0-7] per-user halt resolution failed, using global halt: {}", ex.getMessage());
        }
        return globalHalt; // primary/default user or single-user mode
    }

    /**
     * Check if a new entry is allowed given current open trades and in-flight entries.
     * openTradeCount already includes pending orders from DB, so we only add
     * entriesInFlight for orders placed in THIS JVM session that haven't been
     * persisted to the order table yet (the brief window between broker response
     * and DB write).
     */
    private boolean entryAllowed(int openTradeCount) {
        int maxOpen = globalConfigService.getMaxOpenTrades();
        int maxPending = globalConfigService.getMaxPendingOrders();
        // Count open trades + ALL pending orders (OPEN/NEW in DB) + in-flight orders not yet in DB.
        // This prevents multiple entries within a single scan cycle from exceeding maxOpenTrades.
        int pendingFromDb = countAlgoPendingOrders();
        int effectiveInFlight = Math.max(0, inFlightCurrent().get() - pendingFromDb);

        // P0-3 FIX: openTradeCount ALREADY includes pending algo orders (via countAlgoPendingOrders),
        // so adding pendingFromDb again here double-counted each pending order (~2 slots each).
        // Total = open trades + pending algo orders (already in openTradeCount) + in-flight not yet in DB.
        int totalPositions = openTradeCount + effectiveInFlight;
        if (totalPositions >= maxOpen) return false;
        if (pendingFromDb >= maxPending) return false;
        return true;
    }

    private ExecutionResult rejectBrokerFailure(
            StrategyDecisionEntity savedDecision,
            StrategyDecision decision,
            BigDecimal optionPremium,
            int lotSize,
            Integer quantity,
            BigDecimal riskAmount,
            BigDecimal estimatedCost,
            String clientOrderId,
            RuntimeException ex,
            StrategyConfig strategyConfig
    ) {
        String message = exceptionMessage(ex);
        log.warn("Entry order placement failed: clientOrderId={}, instrument={}, message={}",
                clientOrderId, decision.selectedInstrumentKey().orElse(""), message, ex);
        trackBrokerRejection(message);
        errorEventRepository.save(new ErrorEventEntity(Instant.now(clock), "ExecutionEngine",
                message != null && message.length() > 4000 ? message.substring(0, 4000) : message));
        List<String> reasons = List.of(message);
        updateExecutionStage(savedDecision, "BROKER_ERROR", message);
        executionOutcomeCsvRecorder.recordEntry(decision, optionPremium, lotSize, "BROKER_ERROR", false,
                quantity, riskAmount, estimatedCost,
                new OrderResponse(clientOrderId, Optional.empty(), decision.selectedInstrumentKey().orElse(""),
                        OrderSide.BUY, OrderStatus.REJECTED, quantity == null ? 0 : quantity, 0,
                        Optional.empty(), Optional.of(message), Instant.now(clock)),
                reasons, strategyConfig);
        telegramAlertService.entryRejected(decision, optionPremium, "BROKER_ERROR", reasons);
        return ExecutionResult.rejected(reasons);
    }

    private String exceptionMessage(RuntimeException ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            message = ex.getMessage();
        }
        if (message == null || message.isBlank()) {
            message = ex.getClass().getSimpleName();
        }
        return message;
    }

    /**
     * Fan a per-user-REJECTED entry out to the OTHER active users. A per-user gate failing for THIS user
     * (consecutive losses, daily loss, capital-based sizing, max open trades) must NOT block the others —
     * each target re-runs its OWN gate in its own context and decides independently. Mirrors the per-user
     * HALT fan-out above. Recursion-safe: SignalCopyService's 5s signal dedupe suppresses the targets'
     * own re-fan-out, and once a target holds the position its gate self-rejects further attempts.
     * The primary remains the market-data source; its trading state no longer vetoes the other users.
     */
    /**
     * Net quantity the BROKER actually reports for an instrument (positive long / negative short / 0 flat or
     * absent). Returns null if the broker query fails — callers fall back to the recorded qty rather than
     * blocking on a transient error. Used by the broker-flat exit guard (§B2/B4-G1).
     */
    private Integer brokerNetQtyForInstrument(String instrumentKey) {
        if (instrumentKey == null) return null;
        try {
            return brokerClient.positions().stream()
                    .filter(p -> instrumentKey.equals(p.instrumentKey()))
                    .mapToInt(com.algo.trade.domain.Position::quantity)
                    .sum();
        } catch (Exception e) {
            log.warn("brokerNetQtyForInstrument: positions() failed for {} — cannot verify, proceeding with recorded qty: {}",
                    instrumentKey, e.getMessage());
            return null;
        }
    }

    /** True if a broker error message is an insufficient-margin rejection. */
    static boolean isMarginError(String msg) {
        if (msg == null) return false;
        String m = msg.toLowerCase(java.util.Locale.ROOT);
        return m.contains("insufficient funds") || m.contains("margin required") || m.contains("marginexception");
    }

    /**
     * §B1: record that an exit for this trade was rejected for MARGIN and pause exit retries for the cooldown.
     * Stops the per-tick retry storm + the per-tick "URGENT" alert; alerts ONCE per cooldown window. Callable
     * from the OrderFillWatchdog when it detects an async margin REJECTED exit, and from the sync retry path.
     */
    public void markExitMarginBlocked(String tradeId, String instrumentKey, String msg) {
        if (tradeId == null) return;
        Instant now = Instant.now(clock);
        Instant prev = exitMarginBlockedUntil.put(tradeId, now.plusSeconds(Math.max(30, marginBlockCooldownSeconds)));
        if (prev == null || prev.isBefore(now)) { // first block, or a previous window already lapsed → alert once
            log.error("doCloseTrade: exit MARGIN-BLOCKED for {} ({}) — pausing exit retries {}s to stop the per-tick "
                    + "storm. Add margin or square off MANUALLY. {}", tradeId, instrumentKey, marginBlockCooldownSeconds, msg);
            telegramAlertService.systemAlert("⚠️ EXIT blocked by MARGIN: " + instrumentKey + " — add margin or square "
                    + "off MANUALLY. Auto-exit paused " + marginBlockCooldownSeconds + "s (position still OPEN).");
        }
    }

    /**
     * ENTRY margin backoff (2026-07-01): record an INSUFFICIENT-FUNDS entry rejection for a user. After
     * {@code margin-reject-threshold} consecutive such rejects, back that user's entries off for
     * {@code margin-reject-backoff-minutes}. Callable from OrderFillWatchdog for the ASYNC reject path (where
     * Zerodha rejects a copied entry post-acceptance) — this is what the synchronous circuit breaker misses.
     * Stops the u:8 copy-margin storm (71 identical rejects in 5 min). Auto-clears on a successful fill.
     */
    public void recordEntryMarginRejection(Long userId, String instrument, String msg) {
        Long uid = userId != null ? userId : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        int streak = entryMarginRejectStreak.computeIfAbsent(uid, k -> new java.util.concurrent.atomic.AtomicInteger(0))
                .incrementAndGet();
        if (streak >= Math.max(1, marginRejectThreshold)) {
            Instant now = Instant.now(clock);
            Instant prev = entryMarginBackoffUntil.put(uid, now.plusSeconds(Math.max(60, marginRejectBackoffMinutes * 60)));
            if (prev == null || prev.isBefore(now)) { // first trip or prior window lapsed → alert once
                log.warn("Entry MARGIN backoff ENGAGED for userId={} after {} insufficient-funds rejects — pausing "
                        + "this user's entries {}min (auto-clears on a fill). instrument={} {}",
                        uid, streak, marginRejectBackoffMinutes, instrument, msg);
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert("⚠️ Entries paused for user " + uid + " — repeated INSUFFICIENT "
                            + "MARGIN (" + streak + " rejects). Add funds; auto-resumes in " + marginRejectBackoffMinutes
                            + "min or on a fill.");
                }
            }
        }
    }

    /** True if this user's entries are paused by the margin backoff. */
    public boolean isEntryMarginBackedOff(Long userId) {
        Long uid = userId != null ? userId : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        Instant until = entryMarginBackoffUntil.get(uid);
        return until != null && Instant.now(clock).isBefore(until);
    }

    /** Clear a user's margin backoff + streak — called on a successful fill (margin freed up). */
    private void resetEntryMarginBackoff(Long userId) {
        if (userId == null) userId = com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        var s = entryMarginRejectStreak.get(userId);
        if (s != null && s.get() > 0) s.set(0);
        entryMarginBackoffUntil.remove(userId);
    }

    /** Record the price this user just paid for a strike (per-user key), for the price-based re-entry rule. */
    private void recordEntryBuyPrice(Long userId, String instrumentKey, BigDecimal price) {
        if (instrumentKey == null || price == null || price.signum() <= 0) return;
        Long uid = userId != null ? userId : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        String key = uid + ":" + instrumentKey;
        lastBuyPriceByUserInstrument.put(key, price);
        lastBuyTimeByUserInstrument.put(key, Instant.now(clock));
    }

    /**
     * Record a SELL (exit) reference from an EXTERNAL close — a manual sell on the Kite app detected by
     * position-sync. 2026-07-03 (review fix): the sell-price re-entry rule only recorded bot-side closes,
     * so "user manually sells, bot re-buys near the sell price seconds later" — the most common manual
     * churn pattern — was unprotected. Called by PositionSynchronizer when it reconciles a manual close.
     */
    public void recordExternalSellReference(Long userId, String instrumentKey, BigDecimal price) {
        if (instrumentKey == null || price == null || price.signum() <= 0) return;
        Long uid = userId != null ? userId : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        String key = uid + ":" + instrumentKey;
        lastSellByUserInstrument.put(key, new SellRef(price, Instant.now(clock)));
    }

    /**
     * Adaptive re-entry buffer: higher-premium options need a smaller % drop because each % point
     * represents more absolute rupees. Additionally, the buffer is INDEX-AWARE: BANKNIFTY and SENSEX
     * have wider intraday ranges than NIFTY, so a 3% dip happens much more frequently on those indices.
     * Without index-awareness, the buffer would be too lenient for volatile indices (allowing churn)
     * while being too strict for NIFTY (blocking legitimate re-entries).
     *
     * <p>Tiers (reference price = last buy/sell):
     * <pre>
     * NIFTY (narrow range):    ≤₹150 → 10% | ₹150-300 → 5% | >₹300 → 3%
     * BANKNIFTY (wide range):  ≤₹200 → 10% | ₹200-400 → 7% | >₹400 → 5%
     * SENSEX (wide range):     ≤₹200 → 10% | ₹200-400 → 7% | >₹400 → 5%
     * </pre>
     * The rationale: BANKNIFTY/SENSEX premiums swing 5-7% intraday routinely, so a 3% buffer would
     * let through near-sell-price re-buys that are just normal oscillation, not genuine dips.
     * NIFTY is tighter — a 3% dip in a ₹400+ option is more significant.
     */
    private double adaptiveReentryBuffer(double referencePrice, String underlying) {
        boolean wideRange = underlying != null
                && (underlying.contains("BANK") || underlying.contains("SENSEX"));
        double buffer;
        if (wideRange) {
            // BANKNIFTY / SENSEX: wider intraday range, need a larger buffer to prevent churn
            if (referencePrice > 400) {
                buffer = 5.0;
            } else if (referencePrice > 200) {
                buffer = 7.0;
            } else {
                buffer = reentryLowerPriceBufferPct; // default (10%)
            }
        } else {
            // NIFTY: tighter range, can use smaller buffer for high-premium
            if (referencePrice > 300) {
                buffer = 3.0;
            } else if (referencePrice > 150) {
                buffer = 5.0;
            } else {
                buffer = reentryLowerPriceBufferPct; // default (10%)
            }
        }
        // Never go below a sensible floor (1%) regardless of config
        return Math.max(buffer, Math.min(reentryLowerPriceBufferPct, 1.0));
    }

    /**
     * Rehydrate the price-based re-entry references (last BUY and last SELL per user+instrument) from the
     * DB at startup. 2026-07-03: these maps are in-memory only — every mid-session deploy WIPED them, so
     * for the next 30 min the "only re-buy cheaper" rule was blind (proven live: the 12:13 restart erased
     * u:1's 12:03 reference and a 12:21 re-buy at 105.10 filled that the 10%-buffer rule should have
     * blocked). Seeds from today's trades still inside the rule window: BUY ref = latest entry price/time,
     * SELL ref = latest CLOSED exit price/time. Best-effort — a failure must never block startup.
     */
    @jakarta.annotation.PostConstruct
    void rehydrateReentryPriceReferences() {
        if (!reentryRequireLowerPrice) return;
        try {
            Instant windowStart = Instant.now(clock).minusSeconds(reentryPriceRuleWindowSeconds);
            int buys = 0;
            int sells = 0;
            for (TradeEntity t : tradeRepository.findByEntryTimeBetween(todayStart(), tomorrowStart())) {
                if (t.getInstrumentKey() == null) continue;
                Long uid = t.getUserId() != null ? t.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
                String key = uid + ":" + t.getInstrumentKey();
                if (t.getEntryTime() != null && t.getEntryTime().isAfter(windowStart)
                        && t.getEntryPrice() != null && t.getEntryPrice().signum() > 0) {
                    Instant prev = lastBuyTimeByUserInstrument.get(key);
                    if (prev == null || t.getEntryTime().isAfter(prev)) {
                        lastBuyPriceByUserInstrument.put(key, t.getEntryPrice());
                        lastBuyTimeByUserInstrument.put(key, t.getEntryTime());
                        buys++;
                    }
                }
                if (t.getStatus() == TradeStatus.CLOSED && t.getExitTime() != null
                        && t.getExitTime().isAfter(windowStart)
                        && t.getExitPrice() != null && t.getExitPrice().signum() > 0) {
                    SellRef prevSell = lastSellByUserInstrument.get(key);
                    if (prevSell == null || prevSell.time() == null || t.getExitTime().isAfter(prevSell.time())) {
                        lastSellByUserInstrument.put(key, new SellRef(t.getExitPrice(), t.getExitTime()));
                        sells++;
                    }
                }
            }
            if (buys > 0 || sells > 0) {
                log.info("Re-entry price references rehydrated after restart: {} buy ref(s), {} sell ref(s) "
                        + "within the {}s window — the only-re-buy-cheaper rule survives the deploy",
                        buys, sells, reentryPriceRuleWindowSeconds);
            }
        } catch (Exception e) {
            log.warn("Re-entry reference rehydration failed (non-fatal, references start empty): {}", e.getMessage());
        }
    }

    private void fanOutToOtherUsersOnSelfReject(StrategyDecision decision, BigDecimal optionPremium,
                                                int lotSize, StrategyConfig strategyConfig) {
        if (signalCopyService != null && signalCopyService.isEnabled()) {
            Long sourceUser = com.algo.trade.multiuser.UserContext.getUserId();
            signalCopyService.fireForAllUsersAsync(sourceUser, decision, optionPremium, lotSize, strategyConfig);
        }
    }

    private int openTradeCount() {
        // P0-3b: scope OPEN trades to the current user (was global). A cross-user / phantom SYNC open
        // trade must not consume the current user's algo cap. sameUser treats null as DEFAULT, so
        // single-user mode (and legacy null-owner trades) still count correctly.
        Long currentUser = com.algo.trade.multiuser.UserContext.getUserId();
        int openTrades = (int) tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> globalConfigService.isManageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .filter(t -> sameUser(t.getUserId(), currentUser))
                .count();
        // Pending algo limit orders count as "open" — they'll become trades when filled.
        // P0-3: exclude manual / cross-user pending orders so a batch of them can't silently
        // halt ALL algo entries for the day.
        int pendingOrders = countAlgoPendingOrders();
        return openTrades + pendingOrders;
    }

    /**
     * Count pending (OPEN/NEW) orders that are algo-originated AND owned by the current user.
     * P0-3: manual orders (no strategy type / non-ENTRY client id) and other users' orders must
     * NOT consume the current user's algo entry cap.
     */
    private int countAlgoPendingOrders() {
        Long currentUser = com.algo.trade.multiuser.UserContext.getUserId();
        return (int) orderRepository.findByStatusIn(List.of(OrderStatus.OPEN, OrderStatus.NEW)).stream()
                .filter(o -> o.getStrategyType() != null
                        || (o.getClientOrderId() != null && o.getClientOrderId().startsWith("ENTRY-")))
                .filter(o -> sameUser(o.getUserId(), currentUser))
                .count();
    }

    private boolean sameUser(Long orderUser, Long currentUser) {
        Long ou = orderUser != null ? orderUser : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        Long cu = currentUser != null ? currentUser : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
        return ou.equals(cu);
    }

    /** Effective open position count including pending orders — for external callers. */
    public int effectiveOpenTradeCount() {
        return openTradeCount();
    }

    /** Count open (non-paper) trades for a given strategy type — used by per-strategy position limit gate. */
    public long countOpenTradesForStrategy(String strategyType) {
        return tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> strategyType.equals(t.getStrategyType()))
                .count();
    }

    /** Find open trades by instrument key — all users (legacy; prefer {@link #findOpenTradesByInstrumentForUser}). */
    public List<TradeEntity> findOpenTradesByInstrument(String instrumentKey) {
        return tradeRepository.findByInstrumentKeyAndStatus(instrumentKey, TradeStatus.OPEN);
    }

    /**
     * Open trades for one instrument owned by one user. Multi-user signal-copy often places the same
     * strike for primary + secondary — duplicate guards must be per (instrument, user), not per instrument.
     */
    public List<TradeEntity> findOpenTradesByInstrumentForUser(String instrumentKey, Long userId) {
        return tradeRepository.findByInstrumentKeyAndStatus(instrumentKey, TradeStatus.OPEN).stream()
                .filter(t -> sameUser(t.getUserId(), userId))
                .toList();
    }

    /**
     * Mark a filled entry order as materialized when an open trade for the same user+instrument already
     * exists (prevents orphan-reconcile loops without creating a duplicate trade).
     */
    @Transactional
    public void markFilledOrderMaterialized(OrderEntity orderEntity, String existingTradeId) {
        if (orderEntity == null || orderEntity.isTradeMaterialized()) {
            return;
        }
        orderEntity.setTradeMaterialized(true);
        orderRepository.save(orderEntity);
        log.info("Filled order marked materialized (existing trade): clientOrderId={}, tradeId={}, userId={}",
                orderEntity.getClientOrderId(), existingTradeId, orderEntity.getUserId());
        if (orderEntity.getClientOrderId() == null || !orderEntity.getClientOrderId().startsWith("EXIT-")) {
            releaseEntryInFlightGate();
        }
    }

    /** Save a trade entity — used by OrderFillWatchdog when closing trades from exit fills. */
    public void saveTradeEntity(TradeEntity trade) {
        tradeRepository.save(trade);
    }

    /** Populate entry Greeks on a trade from live option data. Called by scheduler after trade creation. */
    public void populateEntryGreeks(String tradeId, Double delta, Double gamma, Double theta, Double iv) {
        tradeRepository.findById(tradeId).ifPresent(trade -> {
            trade.setEntryDelta(delta);
            trade.setEntryGamma(gamma);
            trade.setEntryTheta(theta);
            trade.setEntryIV(iv);
            tradeRepository.save(trade);
            log.debug("Entry Greeks populated: tradeId={} delta={} gamma={} theta={} iv={}",
                    tradeId, delta, gamma, theta, iv);
        });
    }

    /**
     * Determine if a trade is a short entry (SELL side) based on strategy type.
     * Uses strategyType field first, falls back to entryReason parsing.
     */
    private boolean isShortEntry(TradeEntity trade) {
        // Check strategy type — selling strategies have short entries
        if (trade.getStrategyType() != null && !trade.getStrategyType().isBlank()) {
            // SHORT_POSITION is set by PositionSynchronizer for broker short positions
            if ("SHORT_POSITION".equals(trade.getStrategyType())) return true;
            try {
                return StrategyType.valueOf(trade.getStrategyType()).isSellingStrategy();
            } catch (IllegalArgumentException ignored) {}
        }
        // Fallback: check entry reason for SELL/SHORT markers
        String reason = trade.getEntryReason();
        return reason != null && (reason.contains("[SELL_CE]") || reason.contains("[SELL_PE]")
                || reason.contains("SELL_CE") || reason.contains("SELL_PE")
                || reason.contains("SHORT position"));
    }

    /**
     * Prefer an explicitly passed strategy config (e.g. OI Momentum); otherwise parse decision reasons.
     * Uses {@code explicitConfig} only when non-null — not the directional-buy fallback config.
     */
    private String resolveStrategyType(StrategyDecision decision, StrategyConfig explicitConfig) {
        if (explicitConfig != null && explicitConfig.getStrategyType() != null) {
            return explicitConfig.getStrategyType().name();
        }
        return extractStrategyType(decision);
    }

    /** Extract strategy type name from a StrategyDecision's reasons list. */
    private String extractStrategyType(StrategyDecision decision) {
        StrategyType[] typesByLength =
                StrategyType.values();
        typesByLength = Arrays.copyOf(typesByLength, typesByLength.length);
        Arrays.sort(typesByLength, Comparator.comparingInt((StrategyType t) -> t.name().length())
                .reversed());

        for (String reason : decision.reasons()) {
            // Skip common phrases that contain strategy names as substrings
            // "RSI momentum gate" contains "MOMENTUM" but isn't a MOMENTUM strategy signal
            if (reason.toLowerCase().contains("rsi momentum")) continue;
            if (reason.toLowerCase().contains("breakout condition")) continue;
            if (reason.toLowerCase().contains("breakout confirmation")) continue;

            String upper = reason.toUpperCase().replace(" ", "_").replace("-", "_").replace("&", "AND");
            for (StrategyType type : typesByLength) {
                if (upper.contains(type.name())) return type.name();
            }
            if (upper.contains("ITM") && upper.contains("CONVICTION")) return "ITM_CONVICTION";
            if (upper.contains("GAP") && upper.contains("GO")) return "GAP_AND_GO";
        }
        // Fallback: derive from signal type
        return decision.signalType().name().startsWith("BUY_") ? "DIRECTIONAL_BUY" : "UNKNOWN";
    }

    /** Extract underlying symbol from instrument key using enum matching. */
    private static String extractUnderlyingFromKey(String instrumentKey) {
        if (instrumentKey == null) return "NIFTY";
        String upper = instrumentKey.toUpperCase();
        // Check longer names first to avoid BANKNIFTY matching NIFTY
        if (upper.contains("MIDCPNIFTY")) return "MIDCPNIFTY";
        if (upper.contains("FINNIFTY")) return "FINNIFTY";
        if (upper.contains("BANKNIFTY")) return "BANKNIFTY";
        if (upper.contains("SENSEX")) return "SENSEX";
        if (upper.contains("NIFTY")) return "NIFTY";
        return "NIFTY";
    }

    private int tradesToday() {
        // P0-3c: scope per current user (was global). The risk LIMIT (getMaxTradesPerDay)
        // already resolves per-user via the effective config; the COUNT must match it, else
        // one user's trades consume another user's daily cap.
        Long currentUser = com.algo.trade.multiuser.UserContext.getUserId();
        return (int) tradeRepository.findByEntryTimeBetween(todayStart(), tomorrowStart()).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> globalConfigService.isManageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .filter(t -> sameUser(t.getUserId(), currentUser))
                .count();
    }

    /**
     * Bot-only daily P&L for the current user, as used by the daily-loss entry gate. Excludes paper trades
     * and MANUAL/broker-synced (SYNC-) trades unless {@code manageSyncedTrades} is on. Public so the dashboard
     * block panel can display the SAME figure the gate enforces against (manual Kite orders must not count
     * toward the algo daily-loss limit).
     */
    public BigDecimal dailyPnl() {
        // P0-3c: scope daily P&L per current user (was global). The daily-loss limit is per-user;
        // summing every user's P&L would halt all users when the COMBINED loss breaches one user's cap.
        Long currentUser = com.algo.trade.multiuser.UserContext.getUserId();
        return tradeRepository.findByEntryTimeBetween(todayStart(), tomorrowStart()).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> globalConfigService.isManageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .filter(t -> sameUser(t.getUserId(), currentUser))
                .map(t -> {
                    BigDecimal booked = t.getRealizedPnl() != null ? t.getRealizedPnl() : BigDecimal.ZERO;
                    if (t.getStatus() == TradeStatus.OPEN) {
                        BigDecimal currentPrice = marketDataService.quote(t.getInstrumentKey())
                                .map(com.algo.trade.domain.Quote::lastPrice)
                                .filter(p -> p != null && p.signum() > 0)
                                .orElse(null);
                        if (currentPrice != null) {
                            BigDecimal unrealized = currentPrice.subtract(t.getEntryPrice())
                                    .multiply(BigDecimal.valueOf(t.getQuantity()));
                            return booked.add(unrealized);
                        }
                    }
                    return booked;
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int consecutiveLosses() {
        // P0-3c: scope the loss streak per current user (was global). The max-consecutive-losses
        // limit is per-user; counting every user's losses together blocks a user with 0 losses
        // once the COMBINED streak hits one user's cap (this was the "Max consecutive losses (2)"
        // halt on the secondary user despite it having taken no losing trade of its own).
        Long currentUser = com.algo.trade.multiuser.UserContext.getUserId();
        // DAILY RESET (user directive 2026-06-29): count only THIS session's closed bot trades, not a
        // 30-day window. A losing day from several sessions ago (e.g. 4 losses on Jun 25) must not keep
        // halting entries days later — the streak resets at market open, consistent with the daily-loss
        // limit and the dashboard's own today-only consecutive-loss display. Bounded to today's trades.
        List<TradeEntity> recentClosed = tradeRepository.findByEntryTimeBetween(
                        todayStart(), tomorrowStart()).stream()
                .filter(trade -> trade.getStatus() == TradeStatus.CLOSED)
                .filter(trade -> !trade.isPaperTrade())
                .filter(trade -> globalConfigService.isManageSyncedTrades() || !trade.getTradeId().startsWith("SYNC-"))
                .filter(trade -> sameUser(trade.getUserId(), currentUser))
                .sorted((a, b) -> b.getEntryTime().compareTo(a.getEntryTime()))
                .limit(20) // only need to check recent trades
                .toList();
        int losses = 0;
        int consecutiveWins = 0;
        for (TradeEntity trade : recentClosed) {
            if (trade.getRealizedPnl().signum() < 0) {
                if (consecutiveWins < 2) {
                    // Need 2 consecutive winners to truly clear the loss streak
                    losses++;
                    consecutiveWins = 0;
                } else {
                    break; // 2+ consecutive winners — streak is genuinely broken
                }
            } else {
                if (losses == 0) break; // no losses to count
                consecutiveWins++;
            }
        }
        return losses;
    }

    /** OI Momentum tags reasons as {@code OI_MOMENTUM[NIFTY]: ...} — not {@code OI_MOMENTUM:}. */
    static boolean isOiMomentumDecision(StrategyDecision decision) {
        if (decision == null || decision.reasons() == null) {
            return false;
        }
        return decision.reasons().stream().anyMatch(ExecutionEngine::reasonIndicatesOiMomentum);
    }

    private static boolean reasonIndicatesOiMomentum(String reason) {
        if (reason == null || reason.isBlank()) {
            return false;
        }
        return reason.startsWith("OI_MOMENTUM:")
                || reason.startsWith("OI_MOMENTUM[");
    }

    /**
     * Anti-churn + price-based re-entry guards that MUST apply to every strategy INCLUDING OI_MOMENTUM
     * (unlike the rest of orderGuardRejections, which OI_MOMENTUM skips). Multi-user-safe: the price rule is
     * keyed per-user; the cooldowns are exit-anchored time gates (they never fire on a simultaneous aligned-
     * fire entry, only on a re-entry after a close).
     */
    /**
     * Conviction-Trend Override — returns true when the TAPE confirms a genuine move on this exact strike, so
     * the anti-churn/price blocks should be bypassed (pyramid into a winner). TAPE-DRIVEN, not score-driven:
     * a same-strike volume surge (≥ minVolRatio, on non-thin volume) AND a directional premium thrust
     * (≥ minThrustPct over the monitor window) — the two things that separate a real move from flat-market
     * churn (the score/operator can be moderate or even wrong-way, as on the 24350PE). Safety: pyramids only
     * when any existing position on this instrument is IN PROFIT (never average up into a loser), and caps
     * adds/instrument/day. Shadow mode records the would-override and returns false (no behaviour change).
     * NEVER bypasses risk gates — those are evaluated separately, upstream of the churn guards.
     */
    private boolean convictionTrendOverride(StrategyDecision decision, BigDecimal optionPremium, String instrumentKey) {
        if (!ctoEnabled || oiDivergenceMonitor == null || decision == null || instrumentKey == null
                || optionPremium == null || optionPremium.signum() <= 0) return false;
        if (decision.selectedStrike().isEmpty() || decision.optionType().isEmpty()) return false;
        try {
            com.algo.trade.domain.IndexType index;
            try { index = com.algo.trade.domain.IndexType.valueOf(decision.underlying().name()); }
            catch (IllegalArgumentException notAnIndex) { return false; }
            int strike = decision.selectedStrike().get().intValue();
            String type = decision.optionType().get().name(); // CE / PE
            double premNow = optionPremium.doubleValue();

            // Tape confirmation from the shared monitor buffers (filled by the strategy's per-tick sampleBand).
            var vol = oiDivergenceMonitor.evaluateVolumeSurge(index, strike, type);
            boolean volSurge = vol.valid() && vol.priorVol() >= ctoMinVolume && vol.ratio() >= ctoMinVolRatio;
            var div = oiDivergenceMonitor.evaluate(index, strike, type, premNow);
            double thrustPct = div.valid() ? div.premChgPct() : 0.0; // premium change over window; rising = in favour of a long
            boolean thrust = div.valid() && thrustPct >= ctoMinThrustPct;

            // Both tape legs must confirm. Short-circuit BEFORE the open-trades DB scan (review: Tamil #3) so
            // the pyramid check runs only when the tape already confirms — never on the flat-market common
            // case. Also logs a near-miss (exactly one leg passed) at debug for threshold tuning (review #5).
            if (!volSurge || !thrust) {
                if (volSurge != thrust) {
                    log.debug("[CTO] near-miss {} strike={}{} volRatio={} (need {}) thrust={}% (need {}) volSurge={} thrust={} — no override",
                            instrumentKey, strike, type, fmtN(vol.ratio()), ctoMinVolRatio, fmtN(thrustPct), ctoMinThrustPct, volSurge, thrust);
                }
                return false;
            }

            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            if (uid == null) uid = com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;

            // Pyramid safety: any OPEN position on this instrument (this user) must be in profit — never avg up into a loser.
            boolean pyramidOk = true;
            for (TradeEntity t : tradeRepository.findByStatus(TradeStatus.OPEN)) {
                Long owner = t.getUserId() != null ? t.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
                if (!instrumentKey.equals(t.getInstrumentKey()) || !uid.equals(owner)) continue;
                if (t.getEntryPrice() != null && premNow <= t.getEntryPrice().doubleValue()) { pyramidOk = false; break; }
            }

            String addKey = uid + ":" + instrumentKey + ":" + java.time.LocalDate.now(clock);
            int adds = ctoAddsByKey.getOrDefault(addKey, 0);
            boolean underCap = adds < ctoMaxAdds;

            // Conviction Override Engine microstructure gate (live, config-tunable): the surge/thrust must
            // be backed by real one-directional buying on the option's own book (depthConfirm) AND must not
            // be spoofed / absorbed (fakeMove veto). This is what stops CTO overriding on a FAKE move.
            boolean depthOk = !coeEntryDepthConfirm || convictionOverrideEngine == null
                    || convictionOverrideEngine.confirmsLong(index, strike, type);
            boolean notFake = !coeEntrySpoofVeto || convictionOverrideEngine == null
                    || !convictionOverrideEngine.isFakeMove(index, strike, type);
            boolean microOk = depthOk && notFake;
            // FIX 2 (2026-07-07) conviction-scale: when the strict depth-only gate does NOT confirm, allow the
            // add IFF a genuine trend confirms it (fresh aligned operator score + strong OI velocity + price
            // continuation). Only evaluated when microOk is false (short-circuit) so the confirming path is
            // byte-identical to today. Every other guard below (volSurge, thrust, pyramidOk, underCap) is
            // still required — this only substitutes for the microstructure leg, never for the risk/scale caps.
            boolean convictionOk = false;
            if (ctoScaleEnabled && !microOk) {
                convictionOk = ctoScaleConvictionConfirms(index, strike, type, thrustPct,
                        div.valid() ? div.oiRisePct() : 0.0);
            }
            boolean gateOk = microOk || convictionOk;
            boolean fire = volSurge && thrust && pyramidOk && underCap && gateOk;
            if (!fire) {
                if (volSurge && thrust && pyramidOk && underCap && !gateOk) {
                    log.info("[CTO] microstructure gate BLOCKED override {} strike={}{} depthConfirm={} fakeMove={} ctoScaleConviction=false "
                            + "— tape confirmed but neither flow/book nor trend-conviction did (spoofed/absorbed/chop or no real buying)",
                            instrumentKey, strike, type, depthOk, !notFake);
                }
                return false;
            }
            if (ctoShadow) {
                log.info("[CTO] SHADOW would-override {} strike={}{} volRatio={} thrust={}% inProfit={} adds={}/{} — churn blocks would be bypassed",
                        instrumentKey, strike, type, fmtN(vol.ratio()), fmtN(thrustPct), pyramidOk, adds, ctoMaxAdds);
                return false;
            }
            ctoAddsByKey.merge(addKey, 1, Integer::sum);
            ctoLastFireMsByKey.put(uid + ":" + instrumentKey, System.currentTimeMillis()); // so the fill's trade record gets a [CTO] tag
            log.warn("[CTO] CONVICTION_TREND_OVERRIDE — churn/price blocks BYPASSED for {} strike={}{} "
                    + "(volRatio={}, thrust={}%, add #{}/{}, gate={}, userId={})",
                    instrumentKey, strike, type, fmtN(vol.ratio()), fmtN(thrustPct), adds + 1, ctoMaxAdds,
                    microOk ? "microstructure" : "conviction-scale", uid);
            return true;
        } catch (Exception e) {
            log.debug("[CTO] convictionTrendOverride skipped (non-fatal): {}", e.toString());
            return false;
        }
    }

    /**
     * FIX 2 (2026-07-07): conviction-based confirmation that a CTO add is riding a GENUINE trend, used ONLY
     * when the strict depth/spoof microstructure gate did NOT confirm. Requires ALL of:
     * <ul>
     *   <li>a fresh operator signal ALIGNED with the add's side ({@code CE→+1, PE→−1}) at/above the score bar;</li>
     *   <li>OI velocity (OI rise% over the divergence window) at/above the bar;</li>
     *   <li>(optionally) price still continuing in favour — premium thrust > 0.</li>
     * </ul>
     * A chop tape (neutral/opposed operator, flat OI) fails at least one leg, so this can never authorise an
     * add into a fake. It substitutes ONLY for the microstructure leg — the caller still enforces the volume
     * surge, premium thrust, in-profit pyramidOk (never averages down) and max-adds cap. Non-fatal on any
     * error (returns false ⇒ do NOT bypass the gate ⇒ today's blocked behaviour).
     */
    private boolean ctoScaleConvictionConfirms(com.algo.trade.domain.IndexType index, int strike, String type,
                                               double thrustPct, double oiRisePct) {
        try {
            if (operatorFrameworkService == null) return false;
            int dir = "CE".equals(type) ? 1 : -1;
            var op = operatorFrameworkService.getOperatorSignal(index);
            boolean opStrong = op != null && op.isFresh() && op.alignsWith(dir)
                    && op.getScore() >= ctoScaleOpScoreMin;
            boolean oiStrong = oiRisePct >= ctoScaleOiVelMin;
            boolean priceCont = !ctoScaleRequirePriceContinuation || thrustPct > 0;
            boolean ok = opStrong && oiStrong && priceCont;
            if (ok) {
                log.warn("[CTO] conviction-scale CONFIRMS add {} strike={}{} opScore={} (min={}) oiVel={}% (min={}) "
                        + "thrust={}% priceCont={} — genuine trend, bypassing depth-only gate", index, strike, type,
                        op.getScore(), ctoScaleOpScoreMin, fmtN(oiRisePct), fmtN(ctoScaleOiVelMin), fmtN(thrustPct), priceCont);
            } else {
                log.debug("[CTO] conviction-scale NOT confirmed {} strike={}{} opStrong={} oiStrong={} (oiVel={}% min={}) priceCont={}",
                        index, strike, type, opStrong, oiStrong, fmtN(oiRisePct), fmtN(ctoScaleOiVelMin), priceCont);
            }
            return ok;
        } catch (Exception e) {
            log.debug("[CTO] conviction-scale check skipped (non-fatal): {}", e.toString());
            return false;
        }
    }

    private static String fmtN(double v) { return String.format(java.util.Locale.US, "%.2f", v); }

    /** Append a {@code CONVICTION_TREND_OVERRIDE} marker to a filled trade's entryReason when this fill
     *  was authorised by CTO within the last ~20s — so the override is queryable on the trade record for
     *  after-the-fact A/B (which CTO adds happened, and their eventual P&L via the exit). Best-effort. */
    private String withCtoTag(String instrumentKey, String baseReason) {
        try {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            if (uid == null) uid = com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
            Long fireMs = ctoLastFireMsByKey.remove(uid + ":" + instrumentKey);
            if (fireMs != null && System.currentTimeMillis() - fireMs < 20_000L) {
                return baseReason + " | CONVICTION_TREND_OVERRIDE";
            }
        } catch (Exception ignore) { /* tag is best-effort, never fail a fill over it */ }
        return baseReason;
    }

    private void churnAndPriceGuards(StrategyDecision decision, BigDecimal optionPremium,
                                     String instrumentKey, List<String> rejections) {
        if (instrumentKey == null || instrumentKey.isBlank()) return;

        // Conviction-Trend Override: when the tape confirms a genuine move on this strike, bypass ALL the
        // churn/price blocks below (price rule + cooldowns). Risk gates are evaluated separately upstream and
        // still apply. Enforces only when shadow=false; otherwise records the would-override and falls through.
        if (convictionTrendOverride(decision, optionPremium, instrumentKey)) {
            return;
        }

        // V5 (docs/MARKET-MEMORY-V5-DESIGN.md §3): a MEMORY_AVALANCHE entry whose strike is STILL in the
        // AVALANCHE state bypasses the churn/price blocks — panic-covering re-entries legitimately re-buy
        // ABOVE the last exit (07-07 24500 PE: exit @44 → re-enter @71.60 → ride to 93; the churn rules would
        // have blocked exactly that). Same rationale as CTO (churn blocks exist for FLAT markets, not confirmed
        // moves), but keyed on the live memory state, not the reason string alone. Risk gates upstream apply.
        if (marketMemoryEngine != null && marketMemoryEngine.isEnabled()
                && decision.reasons() != null && !decision.reasons().isEmpty()
                && decision.reasons().get(0).contains("MEMORY_AVALANCHE")
                && decision.selectedStrike().isPresent() && decision.optionType().isPresent()) {
            try {
                com.algo.trade.domain.IndexType avIdx = com.algo.trade.domain.IndexType.from(decision.underlying());
                int avStrike = decision.selectedStrike().get().intValue();
                String avTy = decision.optionType().get().name();
                var mem = marketMemoryEngine.get(avIdx, avStrike, avTy);
                // Flicker grace: OI prints are chunky — the state can drop for one classification tick
                // between signal detection and this gate. A strike classified AVALANCHE within the last
                // 30s is still the same event (the replay entered at the signal tick, zero-latency).
                boolean liveNow = mem != null && mem.state() == com.algo.trade.marketdata.MarketMemoryEngine.MarketState.AVALANCHE;
                if (liveNow || marketMemoryEngine.recentAvalanche(avIdx, avStrike, avTy, 30)) {
                    log.warn("[EntryPipeline] AVALANCHE churn-bypass — {} state {} (dOI5m={}%, tier={}) — price/cooldown blocks waived",
                            instrumentKey, liveNow ? "live" : "recent(<=30s)",
                            mem != null ? String.format("%.1f", mem.dOi5mPct()) : "?",
                            mem != null ? mem.tier() : "?");
                    return;
                }
            } catch (Exception ignore) { /* memory hiccup → normal churn guards apply */ }
        }

        // Cooldowns are keyed per ENTERING user so a secondary isn't blocked by the primary's exit (and vice
        // versa) — mirrors the exit-anchor key in doCloseTrade and the price-rule key below. (2026-07-02)
        Long cuid = com.algo.trade.multiuser.UserContext.getUserId();
        if (cuid == null) cuid = com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;

        // §B5: same-instrument re-entry cooldown — don't re-buy a strike right after exiting it. 0 disables.
        if (sameInstrumentReentryCooldownSeconds > 0) {
            Instant lastExit = lastExitByInstrument.get(cuid + ":" + instrumentKey);
            if (lastExit != null) {
                long sinceSec = Duration.between(lastExit, Instant.now(clock)).getSeconds();
                if (sinceSec < sameInstrumentReentryCooldownSeconds) {
                    rejections.add("Same-instrument re-entry cooldown: " + instrumentKey + " exited " + sinceSec
                            + "s ago (cooldown " + sameInstrumentReentryCooldownSeconds + "s)");
                }
            }
        }

        // PRICE-BASED re-entry rule (2026-07-01): after exiting a strike, never re-buy it AT/ABOVE the price we
        // bought it at — only if the premium has come back DOWN. Stops the averaging-up churn (SENSEX 76900CE
        // 287→318→359; NIFTY 24100CE sold 128.00 → re-bought 128.05). Per-user key; window from last buy.
        // 2026-07-03: buffer is now ADAPTIVE based on premium level — higher-₹ options need a smaller %
        // drop because each % point is more absolute rupees (₹500 × 3% = ₹15 dip, same as ₹150 × 10%).
        if (reentryRequireLowerPrice && optionPremium != null && optionPremium.signum() > 0) {
            Long ruid = com.algo.trade.multiuser.UserContext.getUserId();
            if (ruid == null) ruid = com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
            String rkey = ruid + ":" + instrumentKey;
            BigDecimal lastBuy = lastBuyPriceByUserInstrument.get(rkey);
            Instant lastBuyTime = lastBuyTimeByUserInstrument.get(rkey);
            if (lastBuy != null && lastBuy.signum() > 0 && lastBuyTime != null
                    && Duration.between(lastBuyTime, Instant.now(clock)).getSeconds() < reentryPriceRuleWindowSeconds) {
                double effectiveBuffer = adaptiveReentryBuffer(lastBuy.doubleValue(), decision.underlying().name());
                BigDecimal threshold = lastBuy.multiply(
                        BigDecimal.valueOf(1.0 - effectiveBuffer / 100.0));
                // OI-continuation bypass: if the strike WOULD be blocked here but the tape shows OI still
                // building in-favour AND premium still thrusting up (not fading), ride the continuation and
                // skip ONLY this buy-reference rejection. Short-circuit && => when the premium is already below
                // threshold (rule passes) or the flag is off, the bypass check is inert. Never touches the
                // sell-reference block below, the cooldowns/window, or any risk gate.
                if (optionPremium.compareTo(threshold) >= 0
                        && !oiContinuationBypassesReentry(decision, optionPremium.doubleValue(), instrumentKey, ruid)) {
                    rejections.add("Re-entry above last buy price blocked: " + instrumentKey + " premium="
                            + optionPremium + " not below threshold " + threshold.setScale(2, java.math.RoundingMode.HALF_UP)
                            + " (prior buy " + lastBuy + " − " + String.format("%.1f", effectiveBuffer) + "% adaptive buffer; userId=" + ruid
                            + "; window " + reentryPriceRuleWindowSeconds + "s)");
                }
            }
            // SELL-PRICE re-entry guard (2026-07-03): don't re-buy at or near where you JUST SOLD — that's
            // churn. The buy-price rule alone misses this: buy @119, sell @121 → lastBuy=119, so re-buy @120
            // passes "below last buy" even though you sold 1% higher seconds ago. This closes the gap.
            // Uses the SAME adaptive buffer: re-buy must be meaningfully cheaper than what you sold for.
            SellRef sellRef = lastSellByUserInstrument.get(rkey);
            BigDecimal lastSell = sellRef != null ? sellRef.price() : null;
            Instant lastSellTime = sellRef != null ? sellRef.time() : null;
            if (lastSell != null && lastSell.signum() > 0 && lastSellTime != null
                    && Duration.between(lastSellTime, Instant.now(clock)).getSeconds() < reentryPriceRuleWindowSeconds) {
                double effectiveSellBuffer = adaptiveReentryBuffer(lastSell.doubleValue(), decision.underlying().name());
                BigDecimal sellThreshold = lastSell.multiply(
                        BigDecimal.valueOf(1.0 - effectiveSellBuffer / 100.0));
                if (optionPremium.compareTo(sellThreshold) >= 0) {
                    rejections.add("Re-entry above last sell price blocked: " + instrumentKey + " premium="
                            + optionPremium + " not below threshold " + sellThreshold.setScale(2, java.math.RoundingMode.HALF_UP)
                            + " (prior sell " + lastSell + " − " + String.format("%.1f", effectiveSellBuffer) + "% adaptive buffer; userId=" + ruid
                            + "; window " + reentryPriceRuleWindowSeconds + "s)");
                }
            }
        }

        // SOLID anti-churn: per-UNDERLYING, same-direction re-entry cooldown (catches strike rotation —
        // exit 23950PE → buy 23900PE seconds later). Opposite-side reversal is still allowed.
        if (sameUnderlyingReentryCooldownSeconds > 0) {
            String reDir = instrumentKey.endsWith("CE") ? "CE" : instrumentKey.endsWith("PE") ? "PE" : null;
            if (reDir != null) {
                Instant lastUndExit = lastExitByUnderlyingDir.get(cuid + ":" + decision.underlying().name() + ":" + reDir);
                if (lastUndExit != null) {
                    long sinceSec = Duration.between(lastUndExit, Instant.now(clock)).getSeconds();
                    if (sinceSec < sameUnderlyingReentryCooldownSeconds
                            && !convictionOverridesReentry(decision, reDir, sinceSec)) {
                        rejections.add("Same-underlying re-entry cooldown: " + decision.underlying().name() + ":"
                                + reDir + " exited " + sinceSec + "s ago (cooldown "
                                + sameUnderlyingReentryCooldownSeconds + "s)");
                    }
                }
            }
        }
    }

    /**
     * OI-continuation bypass (2026-07-08): returns true when the buy-reference re-entry price rejection should
     * be SKIPPED because the tape confirms the strike is STILL a live continuation — writers still building on
     * our side (OI rise% over the divergence window ≥ min) AND premium still thrusting in-favour (premChg% >
     * min, i.e. rising, NOT fading). This is what separates riding a confirmed winner (07-06 SENSEX 78000CE
     * +15% as OI kept building) from re-chasing a fade (07-08 PE re-chases whose premium was FALLING →
     * premChgPct<0 → this stays inert → still correctly blocked). Reuses the shared {@code oiDivergenceMonitor}
     * (same instance CTO-scale reads) so no new tape plumbing. Bypasses ONLY the buy-reference leg — the caller
     * still enforces the sell-reference (round-trip) block, the cooldowns/window and every upstream risk gate.
     * Capped at {@code max-per-strike-day} per user:strike:day so a single spike can't re-open churn; each fire
     * logs a REENTRY_OI_CONTINUATION marker for the tuning A/B. Flag off (or monitor null / not-an-index /
     * missing strike-type / cap hit / any error) ⇒ returns false ⇒ today's exact blocked behaviour.
     */
    private boolean oiContinuationBypassesReentry(StrategyDecision decision, double premNow,
                                                  String instrumentKey, Long ruid) {
        if (!reentryOiContinuationBypassEnabled || oiDivergenceMonitor == null || decision == null) return false;
        if (decision.selectedStrike().isEmpty() || decision.optionType().isEmpty()) return false;
        try {
            com.algo.trade.domain.IndexType index;
            try { index = com.algo.trade.domain.IndexType.valueOf(decision.underlying().name()); }
            catch (IllegalArgumentException notAnIndex) { return false; }
            int strike = decision.selectedStrike().get().intValue();
            String type = decision.optionType().get().name(); // CE / PE

            // Per user:strike:day cap — checked BEFORE the tape read so a hit is cheap and can't fire again.
            String capKey = ruid + ":" + instrumentKey + ":" + java.time.LocalDate.now(clock);
            int used = reentryOiContinuationCountByKey.getOrDefault(capKey, 0);
            if (used >= reentryOiContinuationMaxPerStrikeDay) {
                log.debug("[REENTRY] OI-continuation cap reached {} ({}/{}) — buy-reference rule enforced",
                        instrumentKey, used, reentryOiContinuationMaxPerStrikeDay);
                return false;
            }

            var div = oiDivergenceMonitor.evaluate(index, strike, type, premNow);
            if (!div.valid()) return false;
            double oiRise = div.oiRisePct();
            double thrust = div.premChgPct(); // 100*(now-past)/past; >0 = premium rising = in-favour of the long
            boolean oiBuilding = oiRise >= reentryOiContinuationMinOiRisePct;
            boolean thrusting = thrust > reentryOiContinuationMinThrustPct; // must be moving the right way, not fading
            if (!(oiBuilding && thrusting)) {
                log.debug("[REENTRY] OI-continuation NOT confirmed {} strike={}{} oiRise={}% (min={}) thrust={}% (min>{}) "
                                + "— buy-reference rule enforced", instrumentKey, strike, type, fmtN(oiRise),
                        fmtN(reentryOiContinuationMinOiRisePct), fmtN(thrust), fmtN(reentryOiContinuationMinThrustPct));
                return false;
            }
            reentryOiContinuationCountByKey.merge(capKey, 1, Integer::sum);
            log.warn("[REENTRY] REENTRY_OI_CONTINUATION — buy-reference price rule BYPASSED for {} strike={}{} "
                            + "(oiRise={}%, thrust={}%, use #{}/{}, userId={}) — OI still building + premium thrusting "
                            + "in-favour; sell-reference / cooldowns / risk gates UNCHANGED", instrumentKey, strike, type,
                    fmtN(oiRise), fmtN(thrust), used + 1, reentryOiContinuationMaxPerStrikeDay, ruid);
            return true;
        } catch (Exception e) {
            log.debug("[REENTRY] OI-continuation bypass skipped (non-fatal): {}", e.toString());
            return false;
        }
    }

    /**
     * True when a GENUINELY exceptional, fresh, direction-aligned operator signal justifies bypassing the
     * same-underlying re-entry cooldown. High bar + default-off so it can't re-open the churn we just closed;
     * every bypass is logged with a REENTRY_CONVICTION_OVERRIDE marker for the tuning A/B.
     */
    private boolean convictionOverridesReentry(StrategyDecision decision, String reDir, long sinceSec) {
        if (!reentryConvictionOverrideEnabled || operatorFrameworkService == null || reDir == null) return false;
        try {
            com.algo.trade.domain.IndexType index = com.algo.trade.domain.IndexType.from(decision.underlying());
            int dir = "CE".equals(reDir) ? 1 : -1;
            var op = operatorFrameworkService.getOperatorSignal(index);
            boolean opStrong = op != null && op.isFresh() && op.alignsWith(dir)
                    && op.getScore() >= reentryConvictionMinScore;
            if (!opStrong) return false;
            // MTF structure gate: require the day/week lean to agree too (when available). MTF unavailable →
            // operator-only fallback so an outage can't silently disable the feature.
            String mtfState = "off";
            boolean mtfOk = true;
            if (reentryOverrideRequireMtf && mtfContextService != null) {
                var mtf = mtfContextService.getContext(index);
                if (mtf != null && mtf.available()) {
                    mtfOk = mtf.alignsWith(dir);
                    mtfState = mtfOk ? "aligned" : "opposed";
                } else {
                    mtfState = "unavailable";   // fall back to operator-only
                }
            }
            if (!mtfOk) return false;
            log.info("[ReentryOverride] REENTRY_CONVICTION_OVERRIDE — same-underlying cooldown BYPASSED for {}:{} "
                    + "(exited {}s ago) on fresh operator score={} aligned dir={} (min={}, mtf={})",
                    decision.underlying().name(), reDir, sinceSec, op.getScore(), dir, reentryConvictionMinScore, mtfState);
            return true;
        } catch (Exception e) {
            log.debug("[ReentryOverride] check skipped: {}", e.toString());
            return false;
        }
    }

    private List<String> orderGuardRejections(StrategyDecision decision, BigDecimal optionPremium) {
        List<String> rejections = new ArrayList<>();
        String instrumentKey = decision.selectedInstrumentKey().orElse("");
        if (instrumentKey.isBlank()) {
            return rejections;
        }

        // ANTI-CHURN + PRICE guards — MUST run for ALL strategies INCLUDING OI_MOMENTUM. (2026-07-01 fix:
        // these were previously BELOW the OI_MOMENTUM early-return → dead code for it, so the re-buy-at-a-
        // higher-price + rapid churn slipped through. They are multi-user-safe: the price rule is keyed
        // per-user, and the cooldowns are exit-anchored time gates so they never break simultaneous aligned-fire.)
        churnAndPriceGuards(decision, optionPremium, instrumentKey, rejections);

        // PREMIUM RANGE — must run for ALL strategies INCLUDING OI_MOMENTUM (2026-07-09 fix: this sat
        // below the early-return while the strategy's own 15-700 check was removed in the config-reuse
        // refactor, each side assuming the other enforced it → NO premium band applied at all; a ₹1044
        // SENSEX entry sailed past the ₹400 Index-Config cap, and ₹13.50 lottery tickets got in with
        // no floor. Stateless check — no conflict with the OI-momentum 1-sec loop.)
        premiumRangeGuard(decision, optionPremium, rejections);

        // OI_MOMENTUM has its own duplicate-order/concentration logic and a 1-sec loop — skip the
        // REMAINING global guards below (duplicate open order/trade, per-underlying concentration)
        // that conflict with it. The anti-churn/price + premium guards above already ran.
        if (isOiMomentumDecision(decision)) {
            return rejections;
        }

        // 1. Duplicate open order check — prevent placing another order for the same instrument. PER-USER: a
        // peer user's open order on this instrument (normal in copy mode) must NOT block this user's entry.
        boolean existingOpenBuyOrder = orderRepository.findByInstrumentKeyAndSideAndStatusIn(
                instrumentKey,
                OrderSide.BUY.name(),
                List.of(OrderStatus.NEW, OrderStatus.OPEN)
        ).stream().anyMatch(o -> sameUser(o.getUserId(), com.algo.trade.multiuser.UserContext.getUserId()));
        if (existingOpenBuyOrder) {
            rejections.add("Open buy order already exists for instrument: " + instrumentKey);
        }

        // 3. Existing open trade check — prevent doubling up on the same instrument. PER-USER: in copy mode a
        // peer legitimately holds the SAME strike; that must not reject THIS user's own entry.
        boolean existingOpenTrade = tradeRepository.findByInstrumentKeyAndStatus(instrumentKey, TradeStatus.OPEN).stream()
                .filter(t -> !t.isPaperTrade())
                // Exclude MANUAL/broker-synced (SYNC-) positions — a manual trade must not block algo entries
                // (unless the operator opted to manage synced trades). Matches openTradeCount()'s filter.
                .filter(t -> globalConfigService.isManageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .anyMatch(t -> sameUser(t.getUserId(), com.algo.trade.multiuser.UserContext.getUserId()));
        if (existingOpenTrade) {
            rejections.add("Open trade already exists for instrument: " + instrumentKey);
        }

        // (anti-churn + price guards moved to churnAndPriceGuards(), called above the OI_MOMENTUM early-return.)

        // 4. Per-underlying open trade limit — prevent concentration on a single underlying.
        //    §D2 (2026-06-29): default 1 → each open slot must go to a DIFFERENT underlying (1 NIFTY +
        //    1 BANKNIFTY/SENSEX, never 2 NIFTY). Config trading.max-open-per-underlying; capped at maxOpenTrades.
        String underlying = decision.underlying().name();
        // PER-USER: this concentration cap must count only THIS user's positions — one user's NIFTY
        // position must not block another user from NIFTY (the gate was previously account-wide).
        final Long concUser = com.algo.trade.multiuser.UserContext.getUserId();
        long openTradesForUnderlying = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> !t.isPaperTrade())
                .filter(t -> globalConfigService.isManageSyncedTrades() || !t.getTradeId().startsWith("SYNC-"))
                .filter(t -> underlying.equals(t.getUnderlying()))
                .filter(t -> sameUser(t.getUserId(), concUser))
                .count();
        // Also count pending BUY orders for the same underlying (this user only)
        long pendingOrdersForUnderlying = orderRepository.findByStatusIn(
                List.of(OrderStatus.OPEN, OrderStatus.NEW)).stream()
                .filter(o -> OrderSide.BUY.name().equals(o.getSide()))
                .filter(o -> o.getInstrumentKey() != null && o.getInstrumentKey().toUpperCase().contains(underlying))
                .filter(o -> sameUser(o.getUserId(), concUser))
                .count();
        long totalOpenForUnderlying = openTradesForUnderlying + pendingOrdersForUnderlying;
        int maxPerUnderlying = Math.min(Math.max(1, maxOpenPerUnderlying), globalConfigService.getMaxOpenTrades());
        // V5 avalanche STACKING (2026-07-09, review-3 issue 1): simultaneous avalanches on DIFFERENT
        // strikes are the replay's main P&L source (peak 8-14 concurrent/index). MEMORY_AVALANCHE
        // candidates may stack up to avalanche.max-concurrent-per-index positions on one underlying;
        // the global maxOpenTrades and the §LOT-CAP profile guard still bound the total absolutely.
        if (avalancheStackEnabled && !decision.reasons().isEmpty()
                && decision.reasons().getFirst().contains("MEMORY_AVALANCHE")) {
            maxPerUnderlying = Math.min(Math.max(maxPerUnderlying, avalancheMaxConcurrentPerIndex),
                    globalConfigService.getMaxOpenTrades());
        }
        if (totalOpenForUnderlying >= maxPerUnderlying) {
            rejections.add("Max open trades per underlying reached for " + underlying
                    + " (" + totalOpenForUnderlying + "/" + maxPerUnderlying
                    + ", trades=" + openTradesForUnderlying + " pending=" + pendingOrdersForUnderlying + ")");
        }

        // 5. Premium range gate — MOVED to premiumRangeGuard(), called ABOVE the OI_MOMENTUM
        //    early-return so it applies to every strategy (2026-07-09).

        // 5b. Direction flip cooldown — block CE↔PE flip on same underlying within cooldown window
        return orderGuardTail(decision, instrumentKey, rejections);
    }

    /**
     * Per-underlying premium range gate — dynamic scaling based on conviction.
     * Baseline: premium within [minEntryPremium, maxEntryPremium] (Index-Config, UI-edited).
     * High-conviction override: confidence >= 70 → +30% above cap; >= 85 → +40%. The MIN floor is
     * never overridden (far-OTM/charge-uneconomical = structural, not conviction).
     * Runs for ALL strategies including OI_MOMENTUM (see 2026-07-09 fix note at the call site).
     */
    private void premiumRangeGuard(StrategyDecision decision, BigDecimal optionPremium, List<String> rejections) {
        if (underlyingConfigService != null && optionPremium != null && optionPremium.signum() > 0) {
            // Dynamic (ATM-anchored) cap when the resolver is present & enabled; otherwise the
            // configured fixed cap — the resolver returns the configured value verbatim when disabled.
            BigDecimal maxPremium = dynamicEntryPremiumService != null
                    ? dynamicEntryPremiumService.effectiveMaxEntryPremium(decision.underlying())
                    : underlyingConfigService.getMaxEntryPremium(decision.underlying());
            if (maxPremium.signum() > 0 && optionPremium.compareTo(maxPremium) > 0) {
                // Check conviction-based override
                double confidence = decision.confidenceScore() != null
                        ? decision.confidenceScore().doubleValue() : 0;
                double overrideMultiplier = 1.0;
                if (confidence >= 85) overrideMultiplier = 1.40;      // super-confirmed: +40%
                else if (confidence >= 70) overrideMultiplier = 1.30;  // confirmed: +30%

                BigDecimal adjustedMax = maxPremium.multiply(BigDecimal.valueOf(overrideMultiplier));
                if (optionPremium.compareTo(adjustedMax) > 0) {
                    // F10-4: strategies whose decisions carry confidence 0 (OI-momentum by design)
                    // never get the conviction stretch — say "hard cap" instead of implying a
                    // high-conviction path existed and failed.
                    rejections.add("Premium ₹" + optionPremium.setScale(0, java.math.RoundingMode.HALF_UP)
                            + " exceeds adjusted max ₹" + adjustedMax.setScale(0, java.math.RoundingMode.HALF_UP)
                            + " for " + decision.underlying()
                            + " (base=" + maxPremium + ", conviction=" + String.format("%.0f", confidence)
                            + ", multiplier=" + overrideMultiplier + ")"
                            + (confidence <= 0
                                ? " — hard cap (this strategy carries no conviction stretch)"
                                : " — option too expensive even for high-conviction entry"));
                } else if (overrideMultiplier > 1.0) {
                    log.info("[OrderGuard] Premium ₹{} above base cap ₹{} but within conviction-adjusted cap ₹{}"
                                    + " (confidence={}, multiplier={}) — ALLOWING with lot scaling flag",
                            optionPremium.setScale(0, java.math.RoundingMode.HALF_UP),
                            maxPremium.setScale(0, java.math.RoundingMode.HALF_UP),
                            adjustedMax.setScale(0, java.math.RoundingMode.HALF_UP),
                            String.format("%.0f", confidence), overrideMultiplier);
                    // Flag for downstream lot scaling (PositionSizing will halve lots)
                    // Note: the decision.reasons() list is immutable; communicate via log context
                }
            }
            // 5a. Min premium floor — avoids illiquid/low-delta far-OTM options (never overridden)
            BigDecimal minPremium = underlyingConfigService.getMinEntryPremium(decision.underlying());
            if (minPremium.signum() > 0 && optionPremium.compareTo(minPremium) < 0) {
                rejections.add("Premium ₹" + optionPremium.setScale(0, java.math.RoundingMode.HALF_UP)
                        + " below min ₹" + minPremium.setScale(0, java.math.RoundingMode.HALF_UP)
                        + " for " + decision.underlying()
                        + " — option too cheap, likely far-OTM with low delta/liquidity");
            }
        }
    }

    /** Tail of the order guard (runs only for NON-OI-momentum strategies, below the early-return). */
    private List<String> orderGuardTail(StrategyDecision decision, String instrumentKey, List<String> rejections) {
        // 5b. Direction flip cooldown — block CE↔PE flip on same underlying within cooldown window
        int directionFlipCooldown = globalConfigService.getDirectionFlipCooldownMinutes();
        if (directionFlipCooldown > 0 && decision.optionType().isPresent()) {
            String oppositeType = decision.optionType().get() == com.algo.trade.domain.OptionType.CE ? "PE" : "CE";
            Instant flipWindow = clock.instant().minus(Duration.ofMinutes(directionFlipCooldown));
            // PER-USER: only THIS user's own recent opposite-side trades trigger the flip cooldown, not a peer's.
            boolean recentOppositeEntry = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> !t.isPaperTrade())
                    .filter(t -> sameUser(t.getUserId(), com.algo.trade.multiuser.UserContext.getUserId()))
                    .filter(t -> decision.underlying().name().equals(t.getUnderlying()))
                    .filter(t -> oppositeType.equals(t.getOptionType()))
                    .filter(t -> t.getEntryTime() != null && t.getEntryTime().isAfter(flipWindow))
                    .findAny().isPresent();
            // Also check recently closed trades (within the flip window)
            if (!recentOppositeEntry) {
                recentOppositeEntry = tradeRepository.findByEntryTimeBetween(flipWindow, clock.instant()).stream()
                        .filter(t -> !t.isPaperTrade())
                        .filter(t -> sameUser(t.getUserId(), com.algo.trade.multiuser.UserContext.getUserId()))
                        .filter(t -> decision.underlying().name().equals(t.getUnderlying()))
                        .filter(t -> oppositeType.equals(t.getOptionType()))
                        .findAny().isPresent();
            }
            if (recentOppositeEntry) {
                rejections.add("Direction flip cooldown: " + decision.optionType().get()
                        + " blocked — opposite " + oppositeType + " trade within last " + directionFlipCooldown + " min for " + decision.underlying());
            }
        }

        // 6. Cooldown check — include both trades AND orders placed within cooldown window
        if (globalConfigService.getCooldownMinutes() > 0) {
            Instant cooldownStart = clock.instant().minus(Duration.ofMinutes(globalConfigService.getCooldownMinutes()));
            // Check trades — PER-USER: a peer's recent trade on this instrument must not cool down THIS user.
            boolean tradeCooldown = tradeRepository.findByInstrumentKeyAndEntryTimeBetween(
                    instrumentKey, cooldownStart, clock.instant()).stream()
                    .anyMatch(t -> sameUser(t.getUserId(), com.algo.trade.multiuser.UserContext.getUserId()));
            // Check orders (any of THIS user's BUY orders placed within cooldown, regardless of status)
            boolean orderCooldown = orderRepository.findByInstrumentKeyAndSideAndStatusIn(
                    instrumentKey, OrderSide.BUY.name(),
                    List.of(OrderStatus.COMPLETE, OrderStatus.OPEN, OrderStatus.NEW, OrderStatus.CANCELLED)).stream()
                    .anyMatch(o -> sameUser(o.getUserId(), com.algo.trade.multiuser.UserContext.getUserId())
                            && o.getOrderPlacedAt() != null && o.getOrderPlacedAt().isAfter(cooldownStart));
            if (tradeCooldown || orderCooldown) {
                rejections.add("Cooldown period not elapsed for instrument: " + instrumentKey
                        + " (cooldown=" + globalConfigService.getCooldownMinutes() + "min)");
            }
        }

        return rejections;
    }



    /**
     * Round price to the nearest valid tick size (₹0.05 for NSE/BSE F&O).
     * BUY: round UP to nearest tick (willing to pay more for fill)
     * SELL: round DOWN to nearest tick (willing to accept less for fill)
     */
    static BigDecimal roundToTick(BigDecimal price, OrderSide side) {
        if (price == null || price.signum() <= 0) return price;
        java.math.RoundingMode mode = side == OrderSide.BUY
                ? java.math.RoundingMode.UP
                : java.math.RoundingMode.DOWN;
        return price.divide(TICK_SIZE, 0, mode).multiply(TICK_SIZE).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    /**
     * Resolve the product type for exit orders from the trade entity.
     * Uses the stored productType if available, defaults to MIS (intraday) for backward compatibility.
     */
    private ProductType resolveProductType(TradeEntity trade) {
        String pt = trade.getProductType();
        if (pt == null || pt.isBlank()) return ProductType.MIS;
        try {
            return ProductType.valueOf(pt);
        } catch (IllegalArgumentException e) {
            log.warn("Unknown productType '{}' on trade {} — defaulting to MIS", pt, trade.getTradeId());
            return ProductType.MIS;
        }
    }

    /**
     * Apply market protection for exit orders — side-aware.
     * SELL exit: lastPrice - protection% (willing to accept less for fast fill)
     * BUY exit (short cover): lastPrice + protection% (willing to pay more for fast fill)
     */
    private BigDecimal applyExitProtection(BigDecimal lastPrice, OrderSide side) {
        if (lastPrice == null || lastPrice.signum() <= 0) return lastPrice;
        double protectionPct = exitProtectionPercent;
        BigDecimal protection = lastPrice.multiply(BigDecimal.valueOf(protectionPct / 100), MathContext.DECIMAL64);
        BigDecimal raw = side == OrderSide.BUY
                ? lastPrice.add(protection)
                : lastPrice.subtract(protection).max(BigDecimal.ONE);
        return roundToTick(raw, side);
    }

    /**
     * A "protective" exit is one taken for RISK reasons (stop-loss, trailing stop, max-hold / forced
     * square-off) where filling reliably matters more than price. These route as TRUE MARKET orders so they
     * never rest unfilled in a no-bid window. Opportunistic exits (TARGET profit-take) are NOT protective —
     * they keep the marketable LIMIT so we don't gift slippage on a winner.
     */
    private static boolean isProtectiveExit(String reason) {
        if (reason == null) return false;
        String r = reason.toUpperCase(java.util.Locale.ROOT);
        return r.contains("STOP_LOSS") || r.contains("STOPLOSS") || r.contains("STOP-LOSS")
                || r.contains("TRAILING") || r.contains("MAX_HOLD") || r.contains("SQUAREOFF")
                || r.contains("SQUARE_OFF") || r.contains("FORCED");
    }

    /**
     * Track broker rejection and trigger soft halt if too many consecutive rejections.
     * After MAX_CONSECUTIVE_REJECTIONS (3), activates soft halt via TradingStateService.
     * Halt persists until user resumes from UI. Sends Telegram alert when halt activates.
     */
    private void trackBrokerRejection(String reason) {
        // P1.5: terminal validation rejects (bad params: lot-multiple, tick size, invalid order)
        // are deterministic config errors, NOT systemic broker failures — they must not trip the
        // circuit breaker (which would mask the real bug and halt trading). Only retryable/systemic
        // failures (margin, rate-limit, connectivity) count toward the breaker.
        if (isTerminalValidationReject(reason)) {
            log.warn("Broker validation reject (not counted toward circuit breaker): {}", reason);
            return;
        }
        Long currentUser = com.algo.trade.multiuser.UserContext.getUserId();
        boolean isPrimary = com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID.equals(currentUser);
        int count = rejectionCounter(currentUser).incrementAndGet();
        log.warn("Broker rejection #{} (userId={}): {}", count, currentUser, reason);
        if (count < MAX_CONSECUTIVE_REJECTIONS) {
            return;
        }
        // P1.5 / P0-7: the breaker soft-halts ONLY the offending user — never the whole engine.
        //  • Secondary (copy) user → soft-halt their own UserTradingState; the per-user gate in
        //    executeEntry / UserAwareExecutionService then blocks just them.
        //  • Primary/default user (or single-user mode with no UserTradingStateManager) → soft-halt
        //    the global TradingStateService, which IS the primary's own state and what the
        //    executeEntry gate reads for the default user.
        String haltReason = count + " consecutive broker rejections. Last: " + reason;
        boolean halted;
        if (!isPrimary && userTradingStateManager != null) {
            com.algo.trade.multiuser.UserTradingState userState = userTradingStateManager.getState(currentUser);
            halted = userState.getHaltMode() == com.algo.trade.risk.HaltMode.NONE;
            if (halted) userState.softHalt(haltReason);
        } else {
            halted = tradingStateService.haltMode() == com.algo.trade.risk.HaltMode.NONE;
            if (halted) tradingStateService.softHalt(haltReason);
        }
        rejectionCounter(currentUser).set(0); // reset this user's streak after halting (avoid re-trip churn)
        if (halted) {
            log.error("REJECTION CIRCUIT BREAKER (userId={}): {} — per-user soft halt activated. Resume from UI to continue.",
                    currentUser, haltReason);
            telegramAlertService.systemAlert(String.format(
                    "🛑 Entry HALTED for userId=%d: %d consecutive broker rejections — resume from UI to continue\nLast rejection: %s",
                    currentUser, count, reason));
            if (errorEventService != null) {
                errorEventService.critical("ExecutionEngine",
                        "Rejection circuit breaker (userId=" + currentUser + "): " + haltReason + " — per-user soft halt");
            }
        }
    }

    /**
     * P1.5: is this a terminal (non-retryable, deterministic) exchange validation rejection?
     * These are caused by malformed order params, not systemic broker failure, so they must not
     * trip the systemic circuit breaker. Examples: "quantity should be multiple of 65" (lot
     * multiple), tick-size violations, generic invalid-parameter errors.
     */
    private boolean isTerminalValidationReject(String reason) {
        if (reason == null) return false;
        String r = reason.toLowerCase();
        return r.contains("multiple of")
                || r.contains("tick size")
                || r.contains("quantity should be")
                || r.contains("price should be")
                || r.contains("inputexception")
                || r.contains("invalid order")
                || r.contains("invalid parameter")
                || r.contains("invalid quantity");
    }

    /** Get consecutive rejection count for the primary/default user. For diagnostics. */
    public int getConsecutiveRejections() {
        return rejectionCounter(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID).get();
    }

    /** Reset rejection counters — called when user resumes from halt via UI. */
    public void resetRejectionCounter() {
        int prev = consecutiveRejectionsByUser.values().stream()
                .mapToInt(java.util.concurrent.atomic.AtomicInteger::get).sum();
        consecutiveRejectionsByUser.clear();
        if (prev > 0) {
            log.info("Rejection counters reset: total {} → 0 (user resumed from halt)", prev);
        }
    }

    private Instant todayStart() {
        ZoneId zoneId = properties.timezone();
        return LocalDate.ofInstant(clock.instant(), zoneId).atStartOfDay(zoneId).toInstant();
    }

    private Instant tomorrowStart() {
        ZoneId zoneId = properties.timezone();
        return LocalDate.ofInstant(clock.instant(), zoneId).plusDays(1).atStartOfDay(zoneId).toInstant();
    }

    /**
     * Place a broker order for a spread leg. Used by AlgoTradeExecution for live spread execution.
     *
     * @return OrderResponse from broker, or empty if order failed
     */
    public Optional<OrderResponse> placeSpreadLegOrder(OrderRequest request) {
        try {
            OrderResponse response = brokerClient.placeOrder(request);
            log.info("Spread leg order placed: clientOrderId={}, instrument={}, side={}, status={}",
                    request.clientOrderId(), request.instrumentKey(), request.side(), response.status());
            return Optional.of(response);
        } catch (Exception ex) {
            log.error("Spread leg order failed: instrument={}, side={}, error={}",
                    request.instrumentKey(), request.side(), ex.getMessage());
            return Optional.empty();
        }
    }

    private void registerShiftTrapMaeTracking(TradeEntity trade) {
        if (shiftTrapMaeMfeTracker == null || oiShiftTrapConfig == null || !oiShiftTrapConfig.isExitMaeMfeEnabled()) {
            return;
        }
        if (!"OI_SHIFT_TRAP".equals(trade.getStrategyType())) {
            return;
        }
        var ctx = shiftTrapMaeMfeTracker.resolveContext(
                trade.getUnderlying(), trade.getOptionType(), trade.getEntryTime());
        shiftTrapMaeMfeTracker.startTracking(
                trade.getTradeId(), ctx, trade.getEntryPrice(), trade.getEntryTime());
    }

    /**
     * Phase 3 feature 11 — record entry OI for the unwind exit detector. Called at trade
     * entry alongside {@link #registerShiftTrapMaeTracking}. No-op when the detector bean
     * is missing, the strategy isn't OI_SHIFT_TRAP, or enhancements are disabled.
     */
    private void registerShiftTrapEntryOi(TradeEntity trade) {
        if (shiftTrapOiUnwindExitDetector == null) {
            return;
        }
        if (oiShiftTrapConfig == null || !oiShiftTrapConfig.isEnhancementsEnabled()) {
            return;
        }
        if (!"OI_SHIFT_TRAP".equals(trade.getStrategyType())) {
            return;
        }
        try {
            String trapSide = trade.getOptionType() != null ? trade.getOptionType() : "?";
            long entryOi = trade.getEntryOpenInterest() != null ? trade.getEntryOpenInterest() : 0L;
            long entryCallOi = "CE".equals(trapSide) ? entryOi : 0L;
            long entryPutOi = "PE".equals(trapSide) ? entryOi : 0L;
            shiftTrapOiUnwindExitDetector.recordEntryOi(
                    trade.getTradeId(), trapSide, null,
                    entryCallOi, entryPutOi, trade.getEntryTime());
        } catch (Exception ex) {
            log.debug("registerShiftTrapEntryOi failed for {}: {}", trade.getTradeId(), ex.getMessage());
        }
    }
}
