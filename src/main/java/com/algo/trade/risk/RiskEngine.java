package com.algo.trade.risk;

import com.algo.trade.config.GlobalConfigService;
import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.risk.HaltMode;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * Central risk gate for entries and position sizing.
 */
@Service
public class RiskEngine {

    private static final MathContext MATH_CONTEXT = MathContext.DECIMAL64;
    private static final Logger log = LoggerFactory.getLogger(RiskEngine.class);

    private final GlobalConfigService globalConfigService;
    private final TradingProperties properties;
    private final StrategyConfigService strategyConfigService;
    private final TradingStateService tradingStateService;
    private final SafeWeekPredictor safeWeekPredictor;

    /** Live broker margin — caps sizing to ACTUAL available funds, not just settings. */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.broker.BrokerMarginClient brokerMarginClient;

    /** P0-7: per-user trading state. A SOFT halt (e.g. a daily-loss breach) is scoped to the offending
     *  user, NEVER the whole engine — otherwise one user's loss halts everyone. Null in single-user
     *  mode → falls back to the global TradingStateService (which IS the primary's own state). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.multiuser.UserTradingStateManager userTradingStateManager;

    /** For the "never exceed the cap with open positions" rule: sums THIS user's already-deployed capital so
     *  a new trade is sized on REMAINING capital, not the full account (stops open-position over-leverage). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.persistence.TradeRepository tradeRepository;

    /** Cache broker margin for 30s (avoid hammering the API on every entry) — PER USER, so one user's available
     *  margin never sizes another user's trade (the cache is populated from whichever user's Kite token sized
     *  first; a single shared value oversized/undersized everyone else). Keyed by userId. */
    private record MarginCacheEntry(BigDecimal margin, long time) {}
    private final java.util.concurrent.ConcurrentHashMap<Long, MarginCacheEntry> marginCacheByUser = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MARGIN_CACHE_MS = 30_000;

    /**
     * Always-on hard ceiling on a single trade's notional, as a percent of total capital.
     * Defense-in-depth against the P0-1 oversize bug — even if upstream sizing is wrong,
     * no single entry can exceed this fraction of capital. 0 disables. Default 50%.
     */
    @org.springframework.beans.factory.annotation.Value("${trading.safety.max-single-trade-notional-percent:50}")
    private double maxSingleTradeNotionalPercent = 50;

    public RiskEngine(GlobalConfigService globalConfigService, TradingProperties properties, StrategyConfigService strategyConfigService,
                      @Lazy TradingStateService tradingStateService, SafeWeekPredictor safeWeekPredictor) {
        this.globalConfigService = globalConfigService;
        this.properties = properties;
        this.strategyConfigService = strategyConfigService;
        this.tradingStateService = tradingStateService;
        this.safeWeekPredictor = safeWeekPredictor;
    }

    /** P0-7: resolve the halt that applies to the CURRENT user. Secondary users use their own
     *  UserTradingState; the primary/default user (or single-user mode) uses the global state. */
    private HaltMode effectiveHaltForCurrentUser() {
        try {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            if (userTradingStateManager != null && uid != null
                    && !uid.equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) {
                HaltMode h = userTradingStateManager.getState(uid).getHaltMode();
                return h != null ? h : HaltMode.NONE;
            }
        } catch (Exception ex) {
            log.debug("[P0-7] per-user halt resolution failed, using global: {}", ex.getMessage());
        }
        return tradingStateService.haltMode();
    }

    /** P0-7: activate a SOFT halt scoped to the CURRENT user (per-user for secondaries; global for the
     *  primary/default user, whose own state IS the global TradingStateService). */
    private void softHaltCurrentUser(String reason) {
        try {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            if (userTradingStateManager != null && uid != null
                    && !uid.equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) {
                userTradingStateManager.getState(uid).softHalt(reason);
                return;
            }
        } catch (Exception ex) {
            log.debug("[P0-7] per-user soft-halt failed, using global: {}", ex.getMessage());
        }
        tradingStateService.softHalt(reason);
    }

    /** Per-user daily-trading approval (mirrors {@link #effectiveHaltForCurrentUser}). For a secondary user the
     *  primary's global approval must NOT gate them — one admin action on the primary would otherwise reject
     *  every secondary's entry. Their own {@code UserTradingState.dailyApproved} is authoritative. */
    private boolean effectiveDailyApprovedForCurrentUser() {
        try {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            if (userTradingStateManager != null && uid != null
                    && !uid.equals(com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID)) {
                return userTradingStateManager.getState(uid).isDailyApproved();
            }
        } catch (Exception ex) {
            log.debug("per-user daily-approval resolution failed, using global: {}", ex.getMessage());
        }
        return tradingStateService.isDailyApproved();
    }

    public RiskCheckResult evaluateEntry(
            StrategyDecision decision,
            int openTradeCount,
            int tradesToday,
            BigDecimal dailyPnl,
            int consecutiveLosses,
            boolean killSwitchEnabled
    ) {
        return evaluateEntry(decision, openTradeCount, tradesToday, dailyPnl, consecutiveLosses, killSwitchEnabled, null);
    }

    /**
     * @param strategyType strategy identifier of the incoming decision, used ONLY by the optional per-strategy
     *        win-rate auto-pause mode ({@code trading.winrate-pause.per-strategy=true}). Null → blended per-user.
     */
    public RiskCheckResult evaluateEntry(
            StrategyDecision decision,
            int openTradeCount,
            int tradesToday,
            BigDecimal dailyPnl,
            int consecutiveLosses,
            boolean killSwitchEnabled,
            String strategyType
    ) {
        log.info("Risk check started: signalType={}, openTradeCount={}, tradesToday={}, dailyPnl={}, consecutiveLosses={}, runtimeKillSwitch={}, configuredKillSwitch={}",
                decision.signalType(), openTradeCount, tradesToday, dailyPnl, consecutiveLosses, killSwitchEnabled,
                properties.safety().killSwitchEnabled());
        List<String> rejections = new ArrayList<>();
        if (killSwitchEnabled || properties.safety().killSwitchEnabled()) {
            rejections.add("Kill switch is enabled");
        }
        // HARD halt is a system-wide master stop (global). SOFT halt is PER-USER (P0-7): a secondary
        // user's daily-loss breach must NOT halt the primary or other users. Mirrors the per-user gate
        // in ExecutionEngine.executeEntry and the broker-rejection circuit breaker.
        HaltMode effectiveHalt = effectiveHaltForCurrentUser();
        if (tradingStateService.haltMode() == HaltMode.HARD || effectiveHalt == HaltMode.HARD) {
            rejections.add("Hard halt is active");
        } else if (effectiveHalt == HaltMode.SOFT) {
            rejections.add("Soft halt is active — no new entries allowed");
        }
        if (!effectiveDailyApprovedForCurrentUser()) {
            rejections.add("Daily trading not approved yet");
        }
        if (decision.signalType() != SignalType.BUY_CE && decision.signalType() != SignalType.BUY_PE) {
            rejections.add("Decision is not an entry signal");
        }
        if (openTradeCount >= globalConfigService.getMaxOpenTrades()) {
            rejections.add("Max open trades limit reached (" + openTradeCount + "/" + globalConfigService.getMaxOpenTrades() + ")");
        }
        if (tradesToday >= globalConfigService.getMaxTradesPerDay()) {
            rejections.add("Max trades per day reached");
        }
        if (dailyPnl.compareTo(effectiveDailyLossLimit().negate()) <= 0) {
            rejections.add(String.format("Max daily loss reached (limit: \u20b9%.0f)",
                    effectiveDailyLossLimit().doubleValue()));
            // Auto-halt on daily loss breach — prevents repeated evaluation + log spam. PER-USER (P0-7):
            // halt only THIS user (the breach is from this user's own dailyPnl), never the whole engine.
            if (effectiveHalt == HaltMode.NONE) {
                softHaltCurrentUser("Daily loss limit breached: P&L ₹" + dailyPnl.setScale(0, java.math.RoundingMode.HALF_UP));
                log.warn("[RiskEngine] SOFT HALT (per-user) triggered: daily loss limit breached (P&L=₹{})", dailyPnl.setScale(0, java.math.RoundingMode.HALF_UP));
            }
        }
        if (consecutiveLosses >= globalConfigService.getMaxConsecutiveLosses()) {
            rejections.add("Max consecutive losses reached");
        }

        // Rolling win-rate auto-pause — pause if recent win rate drops too low. PER-USER: uses THIS user's own
        // win-rate so one user's losing streak cannot auto-pause a profitable independent user. Fully
        // configurable via trading.winrate-pause.* (enable flag, min-trades window, threshold, scratch
        // dead-band, optional per-strategy) — the DEFAULTS preserve the historical 25%/5-trades blended
        // behaviour, so nothing changes out of the box unless tuned.
        if (tradingStateService.isWinratePauseEnabled()) {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            boolean perStrategy = tradingStateService.isWinratePausePerStrategy();
            double rollingWinRate = tradingStateService.rollingWinRateForGate(uid, strategyType);
            // In blended mode the window is today's trade count (unchanged behaviour); in per-strategy mode it
            // is the count of recorded outcomes for THIS (user, strategy) so the window matches the win rate.
            int window = perStrategy ? tradingStateService.rollingTradeCount(uid, strategyType) : tradesToday;
            int minTrades = tradingStateService.getWinratePauseMinTrades();
            double threshold = tradingStateService.getWinratePauseThresholdPercent();
            if (window >= minTrades && rollingWinRate < threshold) {
                rejections.add(String.format("Rolling win rate too low (%.0f%% on %d trades%s) — auto-paused",
                        rollingWinRate, window,
                        (perStrategy && strategyType != null) ? ", " + strategyType : ""));
            }
        }

        // Correlation note: NIFTY/BANKNIFTY/FINNIFTY/MIDCPNIFTY are highly correlated.
        // The maxOpenTrades config above already limits concurrent positions.

        if (!decision.selectedInstrumentKey().isPresent() || !decision.optionType().isPresent()) {
            rejections.add("Decision does not contain selected option instrument details");
        }
        if (rejections.isEmpty()) {
            log.info("Risk check accepted");
            return RiskCheckResult.allowed("Entry risk checks passed");
        }
        log.warn("Risk check rejected: reasons={}", rejections);
        return RiskCheckResult.rejected(rejections);
    }

    public PositionSizingResult calculateQuantity(BigDecimal optionPremium, int contractLot) {
        StrategyConfig dirConfig = strategyConfigService.getDirectionalBuyConfig();
        return calculateQuantity(optionPremium, contractLot, 0, dirConfig.getStopLossPercent());
    }

    /**
     * ATR-based position sizing: uses ATR to determine stop distance, then sizes position
     * so that max loss per trade stays within risk budget.
     *
     * @param optionPremium entry price per unit
     * @param contractLot   true exchange contract-lot size (units per ONE lot, e.g. NIFTY=65)
     * @param desiredLots   strategy's desired REAL lot count (conviction). 0 = unconstrained (risk-budget only).
     * @param atr           current ATR in points (from 15-min candles)
     * @return sizing result with quantity capped by risk, desiredLots and maxLotsPerTrade
     */
    public PositionSizingResult calculateQuantityWithATR(BigDecimal optionPremium, int contractLot, int desiredLots, double atr) {
        if (atr <= 0 || optionPremium == null || optionPremium.signum() <= 0) {
            StrategyConfig dirConfig = strategyConfigService.getDirectionalBuyConfig();
            return calculateQuantity(optionPremium, contractLot, desiredLots, dirConfig.getStopLossPercent());
        }
        // ATR-based SL: 2× ATR as stop distance (same formula as DynamicExitManager)
        double atrSlPercent = (2 * atr / optionPremium.doubleValue()) * 100;
        atrSlPercent = Math.max(15, Math.min(60, atrSlPercent)); // Clamp same as DynamicExitManager
        log.info("ATR-based position sizing: atr={}, entry={}, atrSL={}%",
                String.format("%.1f", atr), optionPremium, String.format("%.1f", atrSlPercent));
        return calculateQuantity(optionPremium, contractLot, desiredLots, BigDecimal.valueOf(atrSlPercent));
    }

    /**
     * Position sizing with explicit stop-loss percent (for per-strategy sizing).
     *
     * <p>P0-1 FIX: the unit fed in here is the TRUE exchange contract lot (e.g. NIFTY=65), and
     * {@code maxLotsPerTrade} caps the number of REAL lots. Previously callers folded
     * {@code lotCount × contractLot} into a single "lotSize" and this method re-multiplied by
     * {@code maxLotsPerTrade}, yielding {@code maxLotsPerTrade × lotCount} real lots (a ~5× oversize).
     * Now {@code contractLot} and {@code desiredLots} are kept separate and the cap bounds REAL lots.</p>
     *
     * @param optionPremium entry price per unit
     * @param contractLot   true exchange contract-lot size (units per ONE lot, e.g. NIFTY=65)
     * @param desiredLots   strategy's desired REAL lot count (conviction). 0 = unconstrained (risk-budget only).
     * @param stopLossPercent stop-loss percent used to derive risk-per-unit
     */
    public PositionSizingResult calculateQuantity(BigDecimal optionPremium, int contractLot, int desiredLots, BigDecimal stopLossPercent) {
        BigDecimal settingsCapital = globalConfigService.getTotalCapital();
        // Size on REMAINING capital — subtract what THIS user already has deployed in open positions so the
        // total open exposure never crosses the per-trade risk% cap (the "never inflate with open" rule).
        // (When broker margin is live, effectiveCapitalWithBrokerMargin further caps by actual funds.)
        BigDecimal deployedCapital = deployedCapitalForCurrentUser();
        BigDecimal effectiveCapital = effectiveCapitalWithBrokerMargin(
                settingsCapital.subtract(deployedCapital).max(BigDecimal.ZERO));

        log.info("Position sizing started: optionPremium={}, contractLot={}, desiredLots={}, settingsCapital={}, deployedCapital={}, effectiveCapital={}, maxRiskPerTradePercent={}, stopLossPercent={}",
                optionPremium, contractLot, desiredLots, settingsCapital, deployedCapital, effectiveCapital,
                globalConfigService.getMaxRiskPerTradePercent(), stopLossPercent);
        if (optionPremium == null || optionPremium.signum() <= 0) {
            log.warn("Position sizing rejected: option premium must be positive");
            return new PositionSizingResult(false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Option premium must be positive");
        }
        if (contractLot <= 0) {
            log.warn("Position sizing rejected: contract lot must be positive");
            return new PositionSizingResult(false, 0, BigDecimal.ZERO, BigDecimal.ZERO,
                    "Contract lot must be positive");
        }

        BigDecimal riskAmount = effectiveCapital
                .multiply(globalConfigService.getMaxRiskPerTradePercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        BigDecimal lossPerUnit = optionPremium
                .multiply(stopLossPercent, MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
        if (lossPerUnit.signum() <= 0) {
            log.warn("Position sizing rejected: configured stop loss produces zero risk per unit");
            return new PositionSizingResult(false, 0, riskAmount, BigDecimal.ZERO,
                    "Configured stop loss produces zero risk per unit");
        }

        int rawQuantity = riskAmount.divide(lossPerUnit, MATH_CONTEXT).intValue();
        // REAL lots affordable by the risk budget.
        int lots = rawQuantity / contractLot;
        // Never size UP beyond the strategy's desired conviction.
        if (desiredLots > 0 && lots > desiredLots) {
            log.info("Position sizing capped by desiredLots: lots={} → {}", lots, desiredLots);
            lots = desiredLots;
        }
        // Absolute ceiling on REAL lots (P0-1: this now bounds real lots, not folded blocks).
        int maxLots = globalConfigService.getMaxLotsPerTrade();
        if (maxLots > 0 && lots > maxLots) {
            log.info("Position sizing capped by maxLotsPerTrade: lots={} → {}", lots, maxLots);
            lots = maxLots;
        }
        // Apply safe week multiplier: SAFE=1.0x, MODERATE=0.5x, RISKY=0.25x
        double weekMultiplier = safeWeekPredictor != null ? safeWeekPredictor.getSizeMultiplier() : 1.0;
        if (weekMultiplier < 1.0 && lots > 1) {
            int adjustedLots = Math.max(1, (int) (lots * weekMultiplier));
            log.info("Position sizing adjusted by SafeWeek: lots={} → {} (multiplier={}, risk={})",
                    lots, adjustedLots, weekMultiplier, safeWeekPredictor.getRisk());
            lots = adjustedLots;
        }
        int quantity = lots * contractLot;
        BigDecimal estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);

        if (quantity <= 0) {
            log.warn("Position sizing rejected: premium too high for risk budget, riskAmount={}, estimatedCost={}",
                    riskAmount, estimatedCost);
            return new PositionSizingResult(false, 0, riskAmount, estimatedCost,
                    "Premium is too high for the risk budget");
        }
        if (estimatedCost.compareTo(effectiveCapital) > 0) {
            log.info("Position sizing capped by effective capital: originalQuantity={}, originalEstimatedCost={}, effectiveCapital={}",
                    quantity, estimatedCost, effectiveCapital);
            int affordableLots = effectiveCapital
                    .divide(optionPremium.multiply(BigDecimal.valueOf(contractLot), MATH_CONTEXT), MATH_CONTEXT)
                    .intValue();
            lots = Math.min(lots, affordableLots);
            quantity = lots * contractLot;
            estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);
        }
        // ALWAYS-ON hard notional sanity cap (defense-in-depth vs the P0-1 oversize).
        // Reject/clamp any single trade whose notional exceeds maxSingleTradeNotionalPercent of capital.
        if (maxSingleTradeNotionalPercent > 0) {
            BigDecimal notionalCeiling = effectiveCapital
                    .multiply(BigDecimal.valueOf(maxSingleTradeNotionalPercent), MATH_CONTEXT)
                    .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
            if (estimatedCost.compareTo(notionalCeiling) > 0 && lots > 1) {
                int cappedLots = notionalCeiling
                        .divide(optionPremium.multiply(BigDecimal.valueOf(contractLot), MATH_CONTEXT), MATH_CONTEXT)
                        .intValue();
                int newLots = Math.max(1, Math.min(lots, cappedLots));
                log.warn("Position sizing clamped by notional ceiling ({}% of capital): lots={} → {} (notional ₹{} > ₹{})",
                        maxSingleTradeNotionalPercent, lots, newLots, estimatedCost.setScale(0, java.math.RoundingMode.HALF_UP),
                        notionalCeiling.setScale(0, java.math.RoundingMode.HALF_UP));
                lots = newLots;
                quantity = lots * contractLot;
                estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);
            }
        }
        if (quantity <= 0) {
            log.warn("Position sizing rejected: estimated cost exceeds available capital, estimatedCost={}", estimatedCost);
            return new PositionSizingResult(false, 0, riskAmount, estimatedCost,
                    "Estimated cost exceeds available capital");
        }
        // Final invariant — quantity must be a multiple of the contract lot and never exceed the lot ceiling.
        if (maxLots > 0 && quantity > maxLots * contractLot) {
            log.error("[RiskEngine] SANITY CAP TRIPPED: quantity {} > maxLots×contractLot {} — clamping",
                    quantity, maxLots * contractLot);
            quantity = maxLots * contractLot;
            estimatedCost = optionPremium.multiply(BigDecimal.valueOf(quantity), MATH_CONTEXT);
        }
        log.info("Position sizing accepted: quantity={} ({} lots × {}), riskAmount={}, estimatedCost={}",
                quantity, quantity / contractLot, contractLot, riskAmount, estimatedCost);
        return new PositionSizingResult(true, quantity, riskAmount, estimatedCost,
                "Quantity sized within risk and capital limits");
    }

    /** Base daily loss limit = maxDailyLossPercent % of totalCapital (e.g. 3% of ₹60,000 = ₹1,800). */
    public BigDecimal baseDailyLossLimit() {
        return globalConfigService.getTotalCapital()
                .multiply(globalConfigService.getMaxDailyLossPercent(), MATH_CONTEXT)
                .divide(BigDecimal.valueOf(100), MATH_CONTEXT);
    }

    /** Effective limit = base + any extensions approved via UI. */
    public BigDecimal effectiveDailyLossLimit() {
        return baseDailyLossLimit().add(BigDecimal.valueOf(tradingStateService.dailyLossExtension()));
    }

    /**
     * Returns min(settingsCapital, brokerAvailableMargin) so the bot never sizes beyond
     * what the account can actually afford — regardless of what the settings say.
     * Falls back to settingsCapital if broker margin is unavailable (API down, not authenticated).
     * Cached for 30s to avoid hammering the broker API on every entry.
     */
    /** Capital THIS user already has deployed in open (non-paper, non-SYNC) positions = Σ entryPrice×qty.
     *  Subtracted from the sizing capital so total open exposure never crosses the per-trade risk% cap. */
    private BigDecimal deployedCapitalForCurrentUser() {
        if (tradeRepository == null) return BigDecimal.ZERO;
        try {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            Long me = uid != null ? uid : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
            return tradeRepository.findByStatus(com.algo.trade.domain.TradeStatus.OPEN).stream()
                    .filter(t -> !t.isPaperTrade())
                    .filter(t -> t.getTradeId() == null || !t.getTradeId().startsWith("SYNC-"))
                    .filter(t -> {
                        Long owner = t.getUserId() != null ? t.getUserId() : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
                        return owner.equals(me);
                    })
                    .map(t -> (t.getEntryPrice() != null ? t.getEntryPrice() : BigDecimal.ZERO)
                            .multiply(BigDecimal.valueOf(t.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        } catch (Exception e) {
            log.debug("deployedCapitalForCurrentUser failed (using 0): {}", e.getMessage());
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal effectiveCapitalWithBrokerMargin(BigDecimal settingsCapital) {
        if (brokerMarginClient == null) return settingsCapital;
        try {
            Long uid = com.algo.trade.multiuser.UserContext.getUserId();
            Long me = uid != null ? uid : com.algo.trade.multiuser.UserContext.DEFAULT_USER_ID;
            long now = System.currentTimeMillis();
            MarginCacheEntry cached = marginCacheByUser.get(me);
            if (cached != null && (now - cached.time()) < MARGIN_CACHE_MS) {
                return settingsCapital.min(cached.margin());
            }
            var marginsOpt = brokerMarginClient.equityMargins();  // resolves THIS user's Kite token
            if (marginsOpt.isPresent()) {
                BigDecimal available = marginsOpt.get().netAvailable();
                if (available != null && available.signum() > 0) {
                    marginCacheByUser.put(me, new MarginCacheEntry(available, now));
                    if (available.compareTo(settingsCapital) < 0) {
                        log.info("Position sizing: broker available ₹{} < settings ₹{} — using broker funds",
                                available.setScale(0, java.math.RoundingMode.HALF_UP),
                                settingsCapital.setScale(0, java.math.RoundingMode.HALF_UP));
                    }
                    return settingsCapital.min(available);
                }
            }
        } catch (Exception e) {
            log.debug("Broker margin fetch failed (using settings capital): {}", e.getMessage());
        }
        return settingsCapital;
    }
}
