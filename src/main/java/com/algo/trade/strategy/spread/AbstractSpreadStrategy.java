package com.algo.trade.strategy.spread;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.PositionGroupStatus;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.SpreadEvaluationContext;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.execution.SpreadEntryGate;
import com.algo.trade.execution.SpreadFillPrices;
import com.algo.trade.execution.SpreadLegs;
import com.algo.trade.execution.SpreadOrderExecutor;
import com.algo.trade.execution.exit.ExitEvaluationRegistry;
import com.algo.trade.execution.exit.SpreadExitPolicy;
import com.algo.trade.execution.exit.SpreadExitShadowRecorder;
import com.algo.trade.execution.exit.SpreadPremiumExitHelper;
import com.algo.trade.strategy.DynamicExitManager;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.SpreadLegEntity;
import com.algo.trade.risk.AdaptivePositionSizer;
import com.algo.trade.indicator.AtrIndicator;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.PositionGroupEntity;
import com.algo.trade.persistence.PositionGroupRepository;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyConfigService;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyType;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Template Method base class for all multi-leg spread strategies.
 *
 * <p>Subclasses implement the abstract hooks ({@link #shouldEnter}, {@link #constructLegs},
 * {@link #shouldExit}, {@link #strategyType}) while this class provides the shared lifecycle
 * (evaluate → construct → enter → persist → manage → exit) and common utilities.</p>
 *
 * <p>Position state is persisted to the {@code position_groups} / {@code spread_legs} tables
 * so that open positions survive JVM restarts. On startup {@link #restoreFromDb()} reloads
 * this strategy's open groups from DB into the in-memory cache.</p>
 *
 * <p>Exits are driven by {@code SpreadPositionExitMonitor} (event-driven on candle close),
 * which calls {@link #checkAndExit}. The legacy {@link #manageOpenPositions()} method is
 * retained for backward compatibility but is no longer called by the scheduler.</p>
 *
 * <p>This class is NOT a Spring {@code @Component} — concrete subclasses are annotated
 * as {@code @Component} and injected into the scheduler.</p>
 */
public abstract class AbstractSpreadStrategy {

    private static final MathContext MC = MathContext.DECIMAL64;
    protected final Logger log = LoggerFactory.getLogger(getClass());

    // ── Dependencies ──────────────────────────────────────────────────────
    protected final ExpiryCalendar expiryCalendar;
    protected final InstrumentCache instrumentCache;
    protected final MarketDataService marketDataService;
    protected final ExecutionEngine executionEngine;
    protected final StrategySignalCsvRecorder signalRecorder;
    protected final EmaIndicator emaIndicator;
    protected final AtrIndicator atrIndicator;
    protected final PositionGroupRepository positionGroupRepository;

    // ── In-memory cache (session + recovered from DB) ─────────────────────
    private final ConcurrentHashMap<String, PositionGroup> activePositions = new ConcurrentHashMap<>();

    @Autowired
    private StrategyConfigService strategyConfigService;

    @Autowired(required = false)
    private com.algo.trade.monitoring.ErrorEventService errorEventService;

    @Autowired
    protected com.algo.trade.risk.MarketGuard marketGuard;

    @Autowired
    private AdaptivePositionSizer adaptivePositionSizer;

    @Autowired
    private SpreadOrderExecutor spreadOrderExecutor;

    @Autowired(required = false)
    private com.algo.trade.monitoring.HedgeCostTracker hedgeCostTracker;

    @Autowired(required = false)
    private SpreadExitPolicy spreadExitPolicy;

    @Autowired(required = false)
    private ExitEvaluationRegistry exitEvaluationRegistry;

    @Autowired(required = false)
    private SpreadExitShadowRecorder spreadExitShadowRecorder;

    @Autowired(required = false)
    private com.algo.trade.config.SpreadTradingProperties spreadTradingProperties;

    @Autowired(required = false)
    private com.algo.trade.execution.exit.EntryLiquidityRecorder entryLiquidityRecorder;

    @Autowired(required = false)
    private SpreadEntryGate spreadEntryGate;

    @Autowired(required = false)
    private com.algo.trade.risk.WeeklyExposureTracker weeklyExposureTracker;

    @Autowired(required = false)
    private com.algo.trade.risk.CorrelationGate correlationGate;

    @Autowired(required = false)
    private com.algo.trade.risk.PortfolioGreeksService portfolioGreeksService;

    @Autowired(required = false)
    private com.algo.trade.indicator.IVRankTracker ivRankTracker;

    protected AbstractSpreadStrategy(ExpiryCalendar expiryCalendar,
                                     InstrumentCache instrumentCache,
                                     MarketDataService marketDataService,
                                     ExecutionEngine executionEngine,
                                     StrategySignalCsvRecorder signalRecorder,
                                     EmaIndicator emaIndicator,
                                     AtrIndicator atrIndicator,
                                     PositionGroupRepository positionGroupRepository) {
        this.expiryCalendar = expiryCalendar;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.executionEngine = executionEngine;
        this.signalRecorder = signalRecorder;
        this.emaIndicator = emaIndicator;
        this.atrIndicator = atrIndicator;
        this.positionGroupRepository = positionGroupRepository;
    }

    // ── Abstract hooks ────────────────────────────────────────────────────

    /** Return true if market conditions warrant an entry for this strategy. */
    protected abstract boolean shouldEnter(SpreadEvaluationContext ctx);

    /** Build the list of option legs for this strategy. */
    protected abstract List<SpreadLeg> constructLegs(SpreadEvaluationContext ctx);

    /** Return true if the open position should be exited now. */
    protected abstract boolean shouldExit(PositionGroup group, Map<String, BigDecimal> currentPrices, StrategyConfig config);

    /** The strategy type enum value for this implementation. */
    public abstract StrategyType strategyType();

    // ── Startup recovery ──────────────────────────────────────────────────

    /**
     * Reloads any open positions persisted to DB from a previous JVM session.
     * Called automatically by Spring after dependency injection.
     */
    @PostConstruct
    public void restoreFromDb() {
        List<PositionGroupEntity> open =
                positionGroupRepository.findByStrategyTypeAndOpenTrue(strategyType());
        for (PositionGroupEntity entity : open) {
            PositionGroup group = entity.toDomain();
            activePositions.put(group.groupId(), group);
            // Hydrate partial exit layers to prevent double-firing after restart
            if (spreadExitPolicy != null && entity.getPartialExitLayers() != null) {
                spreadExitPolicy.hydratePartialLayers(group.groupId(), entity.getPartialExitLayers());
            }
            log.info("{} restored open position group {} from DB (legs={})",
                    strategyType().displayName(), group.groupId(), group.legs().size());
        }
        if (!open.isEmpty()) {
            log.info("{} recovered {} open group(s) from DB on startup",
                    strategyType().displayName(), open.size());
        }

        // Zombie cleanup: if more than 1 open position per underlying was restored,
        // keep only the most recent and mark the rest closed/abandoned to fix DB accumulation.
        Map<com.algo.trade.domain.UnderlyingSymbol, List<PositionGroup>> byUnderlying = new HashMap<>();
        for (PositionGroup g : new ArrayList<>(activePositions.values())) {
            byUnderlying.computeIfAbsent(g.underlying(), k -> new ArrayList<>()).add(g);
        }
        for (Map.Entry<com.algo.trade.domain.UnderlyingSymbol, List<PositionGroup>> e : byUnderlying.entrySet()) {
            List<PositionGroup> groups = e.getValue();
            if (groups.size() <= 1) continue;
            groups.sort(Comparator.comparing(PositionGroup::entryTime).reversed());
            log.warn("{} abandoning {} zombie position(s) for {} on startup (keeping newest: {})",
                    strategyType().displayName(), groups.size() - 1, e.getKey(), groups.get(0).groupId());
            for (int i = 1; i < groups.size(); i++) {
                String zombieId = groups.get(i).groupId();
                positionGroupRepository.findByGroupId(zombieId).ifPresent(entity -> {
                    entity.close(BigDecimal.ZERO);
                    positionGroupRepository.save(entity);
                });
                activePositions.remove(zombieId);
            }
        }
    }

    // ── Template method ───────────────────────────────────────────────────

    /**
     * Full entry lifecycle: guard → evaluate → construct legs → fetch quotes → check premium → build decision → persist.
     *
     * @return a {@link StrategyDecision} wrapped in Optional, or empty if entry is skipped
     */
    public final Optional<StrategyDecision> evaluateAndEnter(SpreadEvaluationContext ctx) {
        StrategyConfig config = ctx.config();

        // 1. Disabled guard
        if (isDisabled(config)) {
            log.debug("{} is disabled — skipping evaluation", strategyType().displayName());
            return Optional.empty();
        }

        // 1b. One active entry per underlying (OPEN or in-flight PENDING)
        boolean hasOpenForUnderlying = new ArrayList<>(activePositions.values()).stream()
                .anyMatch(g -> g.underlying() == ctx.underlying()
                        && (g.status() == PositionGroupStatus.OPEN || g.status() == PositionGroupStatus.PENDING));
        if (hasOpenForUnderlying) {
            log.debug("{} already has an open position for {} — skipping entry",
                    strategyType().displayName(), ctx.underlying());
            return Optional.empty();
        }

        // 1c. Cross-strategy entry gate — prevents concurrent entries on same underlying
        if (spreadEntryGate != null && !spreadEntryGate.tryAcquire(ctx.underlying(), strategyType())) {
            log.debug("{} entry blocked by cross-strategy gate for {}",
                    strategyType().displayName(), ctx.underlying());
            return Optional.empty();
        }

        // 1d. Correlation gate — prevents excessive same-direction exposure across correlated indices
        if (correlationGate != null) {
            var correlationBlock = correlationGate.check(ctx.underlying(), strategyType());
            if (correlationBlock.isPresent()) {
                log.info("{} entry blocked: {}", strategyType().displayName(), correlationBlock.get());
                if (spreadEntryGate != null) spreadEntryGate.release(ctx.underlying());
                return Optional.empty();
            }
        }

        // 1e. MarketGuard — block entries during high-impact events or extreme VIX
        if (marketGuard != null) {
            // Long-vol strategies (LONG_STRADDLE, LONG_STRANGLE) bypass event-day block
            // because event days are their highest-conviction entry signal
            boolean allowEventDay = (strategyType() == StrategyType.LONG_STRADDLE
                    || strategyType() == StrategyType.LONG_STRANGLE);
            String guardBlock = strategyType().isSellingStrategy()
                    ? marketGuard.shortPremiumBlockReason()
                    : marketGuard.longPremiumBlockReason(allowEventDay);
            if (guardBlock != null) {
                log.info("{} entry blocked by MarketGuard: {}", strategyType().displayName(), guardBlock);
                if (spreadEntryGate != null) spreadEntryGate.release(ctx.underlying());
                return Optional.empty();
            }
        }

        // 1f. Portfolio Greeks cap — block if aggregate gamma/vega too high
        if (portfolioGreeksService != null && strategyType().isSellingStrategy()) {
            var greeks = portfolioGreeksService.compute();
            // Cap: absolute net gamma > 500 or absolute net vega > 5000 blocks new short entries
            if (Math.abs(greeks.netGamma()) > 500 || Math.abs(greeks.netVega()) > 5000) {
                log.info("{} entry blocked by portfolio Greeks cap: gamma={} vega={}",
                        strategyType().displayName(), greeks.netGamma(), greeks.netVega());
                if (spreadEntryGate != null) spreadEntryGate.release(ctx.underlying());
                return Optional.empty();
            }
        }

        // 2. Subclass entry condition
        if (!shouldEnter(ctx)) {
            log.debug("{} shouldEnter=false — skipping", strategyType().displayName());
            return Optional.empty();
        }

        // 3. Construct legs (adaptive lot cap applied before margin preflight in executor)
        List<SpreadLeg> legs = constructLegs(ctx);
        if (legs == null || legs.isEmpty()) {
            log.debug("{} constructLegs returned empty — skipping", strategyType().displayName());
            return Optional.empty();
        }

        // 4. Fetch live quotes for each leg
        List<String> instrumentKeys = legs.stream().map(SpreadLeg::instrumentKey).toList();
        Map<String, Quote> quotes = marketDataService.quotes(instrumentKeys);
        if (quotes.size() < legs.size()) {
            log.warn("{} could not fetch quotes for all legs ({}/{})", strategyType().displayName(),
                    quotes.size(), legs.size());
            return Optional.empty();
        }

        // 5. Build entry price map
        Map<String, BigDecimal> entryPrices = new HashMap<>();
        for (SpreadLeg leg : legs) {
            Quote q = quotes.get(leg.instrumentKey());
            if (q == null) {
                log.warn("{} missing quote for leg {}", strategyType().displayName(), leg.instrumentKey());
                return Optional.empty();
            }
            entryPrices.put(leg.instrumentKey(), q.lastPrice());
        }

        int baseLots = config.getLots();
        int adaptiveLots = adaptivePositionSizer.calculateLots(baseLots);
        List<SpreadLeg> sizedLegs = SpreadLegs.withLotQuantity(legs, adaptiveLots * ctx.indexType().lotSize());

        // 5b. Capital-aware sizing — shrink lots if total premium would exceed maxCapitalPerTrade
        if (config.getMaxCapitalPerTrade() != null && config.getMaxCapitalPerTrade().signum() > 0) {
            BigDecimal cap = config.getMaxCapitalPerTrade();
            BigDecimal grossDebit = netDebit(sizedLegs, entryPrices).abs();
            if (grossDebit.signum() > 0 && grossDebit.compareTo(cap) > 0) {
                BigDecimal premiumPerLot = grossDebit.divide(
                        BigDecimal.valueOf(adaptiveLots), 4, java.math.RoundingMode.HALF_UP);
                if (premiumPerLot.signum() <= 0) {
                    log.info("{} entry skipped: cannot compute premium per lot (lots={})",
                            strategyType().displayName(), adaptiveLots);
                    if (spreadEntryGate != null) spreadEntryGate.release(ctx.underlying());
                    return Optional.empty();
                }
                int affordableLots = cap.divide(premiumPerLot, 0, java.math.RoundingMode.DOWN).intValue();
                if (affordableLots < 1) {
                    log.info("{} entry skipped: cannot afford 1 lot at ₹{}/lot (cap ₹{})",
                            strategyType().displayName(), premiumPerLot, cap);
                    if (spreadEntryGate != null) spreadEntryGate.release(ctx.underlying());
                    return Optional.empty();
                }
                if (affordableLots < adaptiveLots) {
                    log.info("{} capital cap shrinking {} → {} lots (premium ₹{} > cap ₹{})",
                            strategyType().displayName(), adaptiveLots, affordableLots, grossDebit, cap);
                    adaptiveLots = affordableLots;
                    sizedLegs = SpreadLegs.withLotQuantity(legs, adaptiveLots * ctx.indexType().lotSize());
                }
            }
        }

        // 6. Minimum premium check (on sized legs)
        BigDecimal netDebit = netDebit(sizedLegs, entryPrices);
        if (netDebit.compareTo(BigDecimal.ZERO) > 0
                && netDebit.compareTo(config.getMinCombinedPremium()) < 0) {
            log.debug("{} net debit {} below minimum premium {} — skipping",
                    strategyType().displayName(), netDebit, config.getMinCombinedPremium());
            return Optional.empty();
        }

        // 7. Build PENDING group — broker execution confirms OPEN
        String groupId = strategyType().name() + "-" + UUID.randomUUID().toString().substring(0, 8);
        PositionGroup group = new PositionGroup(
                groupId, strategyType(), ctx.underlying(), sizedLegs, Map.copyOf(entryPrices),
                Instant.now(), PositionGroupStatus.PENDING);
        activePositions.put(groupId, group);
        positionGroupRepository.save(PositionGroupEntity.pending(group, entryPrices));

        // 8. Build StrategyDecision
        BigDecimal netPremium = netDebit.abs();
        boolean isCreditStrategy = netDebit.signum() < 0;
        List<String> reasons = new ArrayList<>();
        reasons.add(strategyType().displayName() + " entry signal");
        reasons.add("Legs: " + sizedLegs.size());
        reasons.add("Lots: " + adaptiveLots);
        reasons.add((isCreditStrategy ? "Net credit: " : "Net debit: ") + netPremium);

        StrategyDecision decision = new StrategyDecision(
                Instant.now(),
                ctx.underlying(),
                SignalType.BUY_CE,
                ctx.underlyingPrice(),
                Optional.of(netPremium),
                Optional.empty(),
                Optional.of(adaptiveLots * ctx.indexType().lotSize()),
                Optional.empty(),
                Optional.of(groupId),
                Optional.empty(),
                Optional.empty(),
                false,
                Optional.empty(),
                false,
                BigDecimal.ZERO,
                reasons
        );

        log.info("{} entry decision created: groupId={}, legs={}, netPremium={} ({})",
                strategyType().displayName(), groupId, sizedLegs.size(), netPremium,
                isCreditStrategy ? "credit received" : "debit paid");
        return Optional.of(decision);
    }

    // ── Exit API ──────────────────────────────────────────────────────────

    /**
     * Called by {@code SpreadPositionExitMonitor} on each candle close.
     */
    /** Force exit when liquidity tier fires without live quotes (uses entry marks). */
    public final boolean forceLiquidityExit(PositionGroupEntity entity,
                                            Map<String, BigDecimal> markPrices,
                                            String reason) {
        if (entity.getStatus() != PositionGroupStatus.OPEN) {
            return false;
        }
        PositionGroup group = resolveActiveGroup(entity);
        return exitAllLegs(group, markPrices, reason);
    }

    public final boolean checkAndExit(PositionGroupEntity entity,
                                      Map<String, BigDecimal> currentPrices,
                                      Map<String, com.algo.trade.domain.Quote> quotes) {
        if (entity.getStatus() != PositionGroupStatus.OPEN) {
            return false;
        }
        PositionGroup group = resolveActiveGroup(entity);
        StrategyConfig config = strategyConfigService.getConfig(strategyType(), group.underlying().name());

        if (spreadExitPolicy != null) {
            SpreadExitPolicy.Result policy = spreadExitPolicy.evaluate(entity, group, currentPrices, quotes, config);
            recordSpreadEvaluation(policy.snapshot());
            positionGroupRepository.save(entity);

            if (policy.action() == SpreadExitPolicy.Action.SKIP) {
                return false;
            }
            if (policy.action() == SpreadExitPolicy.Action.EXIT) {
                String reason = policy.reason() != null ? policy.reason() : "POLICY_EXIT";
                if (reason.startsWith("PROGRESSIVE_")) {
                    return exitPartialProgressive(group, entity, currentPrices, reason);
                }
                return exitAllLegs(group, currentPrices, reason);
            }
        }

        if (shouldExit(group, currentPrices, config)) {
            return exitAllLegs(group, currentPrices, "STRATEGY_EXIT");
        }
        return false;
    }

    /**
     * Legacy entry — resolves entity from DB.
     */
    public final boolean checkAndExit(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        return positionGroupRepository.findByGroupId(group.groupId())
                .map(entity -> {
                    Map<String, com.algo.trade.domain.Quote> quotes = new HashMap<>();
                    for (SpreadLeg leg : group.legs()) {
                        marketDataService.quote(leg.instrumentKey()).ifPresent(q ->
                                quotes.put(leg.instrumentKey(), q));
                    }
                    return checkAndExit(entity, currentPrices, quotes);
                })
                .orElse(false);
    }

    private PositionGroup resolveActiveGroup(PositionGroupEntity entity) {
        PositionGroup cached = activePositions.get(entity.getGroupId());
        return cached != null ? cached : entity.toDomain();
    }

    private void recordSpreadEvaluation(com.algo.trade.execution.exit.ExitEvaluationSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        if (exitEvaluationRegistry != null) {
            exitEvaluationRegistry.put(snapshot);
        }
        if (spreadExitShadowRecorder != null) {
            spreadExitShadowRecorder.record(snapshot);
        }
    }

    /**
     * Force exit without {@link #shouldExit()} — used by EOD square-off and shutdown handlers.
     *
     * @return true if the group was fully closed at broker and in DB
     */
    public boolean forceExit(PositionGroup group, Map<String, BigDecimal> currentPrices, String reason) {
        if (group.status() != PositionGroupStatus.OPEN) {
            return false;
        }
        return exitAllLegs(group, currentPrices, reason);
    }

    /**
     * Closes the group in DB when broker has no open legs (reconciliation).
     */
    public void closeFlatAtBroker(PositionGroup group, Map<String, BigDecimal> markPrices) {
        if (group.status() != PositionGroupStatus.OPEN) {
            return;
        }
        finalizeClosedGroup(group, markPrices, "RECONCILE-FLAT", "RECONCILE_FLAT", "RECONCILED");
    }

    // ── Legacy position management (kept for backward compat) ─────────────

    /**
     * Iterates the in-memory active positions and exits any that meet the exit criteria.
     * No longer called by the scheduler — exits are now event-driven via
     * {@code SpreadPositionExitMonitor}. Retained for testing and monitoring use.
     */
    public void manageOpenPositions() {
        for (Map.Entry<String, PositionGroup> entry : activePositions.entrySet()) {
            PositionGroup group = entry.getValue();
            if (!group.open()) continue;

            try {
                List<String> instrumentKeys = group.legs().stream()
                        .map(SpreadLeg::instrumentKey).toList();
                Map<String, Quote> quotes = marketDataService.quotes(instrumentKeys);
                Map<String, BigDecimal> currentPrices = new HashMap<>();
                for (SpreadLeg leg : group.legs()) {
                    Quote q = quotes.get(leg.instrumentKey());
                    if (q != null) currentPrices.put(leg.instrumentKey(), q.lastPrice());
                }

                if (currentPrices.size() < group.legs().size()) {
                    log.warn("Could not fetch all leg prices for group {} — skipping exit check", group.groupId());
                    continue;
                }

                StrategyConfig config = strategyConfigService.getConfig(strategyType(), group.underlying().name());
                if (group.status() == PositionGroupStatus.OPEN && shouldExit(group, currentPrices, config)) {
                    exitAllLegs(group, currentPrices, "legacy manageOpenPositions");
                }
            } catch (Exception ex) {
                log.error("Error managing position group {}: {}", group.groupId(), ex.getMessage(), ex);
                if (errorEventService != null) errorEventService.high("SpreadStrategy", "Error managing group " + group.groupId() + ": " + ex.getMessage(), ex);
            }
        }
    }

    // ── Shared utilities ──────────────────────────────────────────────────

    /** Returns true if the strategy is disabled in config. */
    protected final boolean isDisabled(StrategyConfig config) {
        return !config.isEnabled();
    }

    /** Rounds spot price to the nearest ATM strike using the index's strike interval. */
    protected final int computeATMStrike(BigDecimal spotPrice, IndexType indexType) {
        return indexType.roundToATM(spotPrice.doubleValue());
    }

    /** Current weekly expiry date for the given index. */
    protected final LocalDate currentWeeklyExpiry(IndexType indexType) {
        return expiryCalendar.getCurrentWeeklyExpiry(indexType);
    }

    /** Next weekly expiry date for the given index. */
    protected final LocalDate nextWeeklyExpiry(IndexType indexType) {
        return expiryCalendar.getNextWeeklyExpiry(indexType);
    }

    /**
     * Net debit = sum of BUY leg prices minus sum of SELL leg prices.
     * A positive value means the position costs money to enter (debit spread).
     */
    protected final BigDecimal netDebit(List<SpreadLeg> legs, Map<String, BigDecimal> prices) {
        BigDecimal buyTotal = BigDecimal.ZERO;
        BigDecimal sellTotal = BigDecimal.ZERO;
        for (SpreadLeg leg : legs) {
            BigDecimal price = prices.get(leg.instrumentKey());
            if (price == null) {
                throw new IllegalStateException("Missing price for leg: " + leg.instrumentKey());
            }
            BigDecimal legValue = price.multiply(BigDecimal.valueOf(leg.quantity()), MC);
            if (leg.side() == OrderSide.BUY) {
                buyTotal = buyTotal.add(legValue, MC);
            } else {
                sellTotal = sellTotal.add(legValue, MC);
            }
        }
        return buyTotal.subtract(sellTotal, MC);
    }

    /**
     * Net credit = sum of SELL leg prices minus sum of BUY leg prices.
     * A positive value means the position receives money on entry (credit spread).
     */
    protected final BigDecimal netCredit(List<SpreadLeg> legs, Map<String, BigDecimal> prices) {
        return netDebit(legs, prices).negate();
    }

    /**
     * Returns true if the debit position has lost more than slPercent% of its entry value.
     * Loss = (entryNet - currentNet) / entryNet × 100.
     */
    protected final boolean slHit(BigDecimal entryNet, BigDecimal currentNet, BigDecimal slPercent) {
        if (entryNet.signum() == 0) return false;
        BigDecimal lossPct = entryNet.subtract(currentNet, MC)
                .divide(entryNet.abs(), MC)
                .multiply(BigDecimal.valueOf(100), MC);
        return lossPct.compareTo(slPercent) >= 0;
    }

    /**
     * Returns true if the debit position has gained at least targetPercent% over its entry value.
     * Profit = (currentNet - entryNet) / entryNet × 100.
     */
    protected final boolean targetHit(BigDecimal entryNet, BigDecimal currentNet, BigDecimal targetPercent) {
        if (entryNet.signum() == 0) return false;
        BigDecimal profitPct = currentNet.subtract(entryNet, MC)
                .divide(entryNet.abs(), MC)
                .multiply(BigDecimal.valueOf(100), MC);
        return profitPct.compareTo(targetPercent) >= 0;
    }

    /**
     * Exits all legs: places broker exit orders (if live), computes P&L, closes the DB record,
     * and removes the group from the in-memory cache.
     */
    /** Called after live broker entry legs are confirmed. */
    public void activatePositionGroup(String groupId, List<SpreadLeg> confirmedLegs,
                                      Map<String, BigDecimal> fillPrices) {
        PositionGroup pending = activePositions.get(groupId);
        if (pending == null) {
            positionGroupRepository.findByGroupId(groupId).ifPresent(entity -> {
                if (entity.getStatus() == PositionGroupStatus.PENDING) {
                    activateFromRepository(entity, confirmedLegs, fillPrices);
                }
            });
            return;
        }
        Map<String, BigDecimal> prices = SpreadFillPrices.merge(pending.entryPrices(), fillPrices);
        List<SpreadLeg> legs = confirmedLegs != null && !confirmedLegs.isEmpty() ? confirmedLegs : pending.legs();
        PositionGroup open = new PositionGroup(
                groupId, pending.strategyType(), pending.underlying(), legs,
                Map.copyOf(prices), pending.entryTime(), PositionGroupStatus.OPEN);
        activePositions.put(groupId, open);
        persistOpenGroup(groupId, legs, prices);
        if (hedgeCostTracker != null) {
            hedgeCostTracker.recordEntry(open);
        }
        // Release cross-strategy entry gate now that position is OPEN
        if (spreadEntryGate != null) {
            spreadEntryGate.release(pending.underlying());
        }
        log.info("{} group {} activated (OPEN) with fill-aware entry prices", strategyType().displayName(), groupId);
    }

    private void activateFromRepository(PositionGroupEntity entity, List<SpreadLeg> confirmedLegs,
                                        Map<String, BigDecimal> fillPrices) {
        PositionGroup pending = entity.toDomain();
        Map<String, BigDecimal> prices = SpreadFillPrices.merge(pending.entryPrices(), fillPrices);
        List<SpreadLeg> legs = confirmedLegs != null && !confirmedLegs.isEmpty() ? confirmedLegs : pending.legs();
        PositionGroup open = new PositionGroup(
                entity.getGroupId(), pending.strategyType(), pending.underlying(), legs,
                Map.copyOf(prices), pending.entryTime(), PositionGroupStatus.OPEN);
        activePositions.put(entity.getGroupId(), open);
        persistOpenGroup(entity.getGroupId(), legs, prices);
        if (hedgeCostTracker != null) {
            hedgeCostTracker.recordEntry(open);
        }
    }

    private void persistOpenGroup(String groupId, List<SpreadLeg> legs, Map<String, BigDecimal> entryPrices) {
        positionGroupRepository.findByGroupId(groupId).ifPresent(entity -> {
            entity.markOpen();
            if (entryLiquidityRecorder != null) {
                List<String> keys = legs.stream().map(SpreadLeg::instrumentKey).toList();
                entryLiquidityRecorder.recordSpreadLegs(entity.getLegs(), marketDataService.quotes(keys));
            }
            for (SpreadLeg leg : legs) {
                entity.getLegs().stream()
                        .filter(l -> l.getInstrumentKey().equals(leg.instrumentKey()))
                        .findFirst()
                        .ifPresent(l -> {
                            BigDecimal fill = entryPrices.get(leg.instrumentKey());
                            if (fill != null && fill.signum() > 0) {
                                l.setEntryPrice(fill);
                            }
                        });
            }
            // Long-vol structures: capture entry IV rank + event-window end time for exit tiers.
            if (isLongVolStructure(strategyType())) {
                if (ivRankTracker != null) {
                    try {
                        com.algo.trade.domain.IndexType idx =
                                com.algo.trade.domain.IndexType.from(entity.getUnderlying());
                        double rank = ivRankTracker.getIVRank(idx);
                        if (rank > 0) {
                            entity.setEntryIvRank(BigDecimal.valueOf(rank));
                        }
                    } catch (Exception e) {
                        log.debug("{} could not capture entry IV rank: {}",
                                strategyType().displayName(), e.getMessage());
                    }
                }
                if (marketGuard != null) {
                    marketGuard.nextEventEndTimeToday()
                            .ifPresent(end -> {
                                entity.setExpectedEventEndTime(end);
                                log.info("{} group {} tagged with event-end {}",
                                        strategyType().displayName(), groupId, end);
                            });
                }
            }
            positionGroupRepository.save(entity);
        });
    }

    private static boolean isLongVolStructure(StrategyType type) {
        return type == StrategyType.LONG_STRADDLE || type == StrategyType.LONG_STRANGLE;
    }

    /**
     * Updates in-memory and DB legs after a straddle adjustment (replace one leg or add hedge).
     */
    public void syncGroupLegs(String groupId, List<SpreadLeg> updatedLegs, Map<String, BigDecimal> priceUpdates) {
        positionGroupRepository.findByGroupId(groupId).ifPresent(entity -> {
            if (entity.getStatus() != PositionGroupStatus.OPEN) {
                return;
            }
            // Soft-delete: mark existing legs as inactive instead of wiping
            Set<String> updatedKeys = updatedLegs.stream()
                    .map(SpreadLeg::instrumentKey)
                    .collect(java.util.stream.Collectors.toSet());
            for (SpreadLegEntity existingLeg : entity.getLegs()) {
                if (!updatedKeys.contains(existingLeg.getInstrumentKey())) {
                    existingLeg.setActive(false);
                }
            }
            // Add or update legs
            for (SpreadLeg leg : updatedLegs) {
                BigDecimal px = priceUpdates.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
                Optional<SpreadLegEntity> existing = entity.getLegs().stream()
                        .filter(l -> l.getInstrumentKey().equals(leg.instrumentKey()) && l.isActive())
                        .findFirst();
                if (existing.isPresent()) {
                    // Update quantity if changed
                    SpreadLegEntity e = existing.get();
                    if (e.getQuantity() != leg.quantity()) {
                        e.setActive(false); // deactivate old
                        entity.getLegs().add(new SpreadLegEntity(entity, leg.instrumentKey(), leg.strike(),
                                leg.optionType(), leg.side(), leg.quantity(), leg.expiry(), px));
                    }
                } else {
                    entity.getLegs().add(new SpreadLegEntity(entity, leg.instrumentKey(), leg.strike(),
                            leg.optionType(), leg.side(), leg.quantity(), leg.expiry(), px));
                }
            }
            positionGroupRepository.save(entity);
            PositionGroup open = entity.toDomain();
            activePositions.put(groupId, open);
        });
    }

    /** Called when live entry fails after PENDING was saved. */
    public void failPositionGroup(String groupId, String reason) {
        positionGroupRepository.findByGroupId(groupId).ifPresent(entity -> {
            entity.markFailed();
            positionGroupRepository.save(entity);
        });
        PositionGroup removed = activePositions.remove(groupId);
        // Release cross-strategy entry gate
        if (removed != null && spreadEntryGate != null) {
            spreadEntryGate.release(removed.underlying());
        }
        log.warn("{} group {} failed: {}", strategyType().displayName(), groupId, reason);
    }

    private boolean exitPartialProgressive(PositionGroup group, PositionGroupEntity entity,
                                           Map<String, BigDecimal> currentPrices, String reason) {
        String layerName = reason.replace("PROGRESSIVE_", "");
        double fraction = DynamicExitManager.PROGRESSIVE_LAYERS.stream()
                .filter(l -> l.name().equals(layerName))
                .mapToDouble(DynamicExitManager.ExitLayer::exitFraction)
                .findFirst()
                .orElse(0.25);

        IndexType indexType = IndexType.from(group.underlying());
        int lotSize = indexType.lotSize();
        StrategyConfig config = strategyConfigService.getConfig(strategyType(), group.underlying().name());
        boolean isLive = config != null && !config.isPaperTrading();

        List<SpreadLeg> updatedLegs = new ArrayList<>();
        Map<String, BigDecimal> priceUpdates = new HashMap<>(group.entryPrices());
        for (SpreadLeg leg : group.legs()) {
            if (leg.side() != OrderSide.SELL) {
                updatedLegs.add(leg);
                continue;
            }
            int closeQty = (int) Math.floor(leg.quantity() * fraction);
            closeQty = (closeQty / lotSize) * lotSize;
            if (closeQty <= 0) {
                updatedLegs.add(leg);
                continue;
            }
            int newQty = leg.quantity() - closeQty;
            if (newQty < lotSize) {
                return exitAllLegs(group, currentPrices, reason + "_FULL");
            }
            updatedLegs.add(new SpreadLeg(leg.instrumentKey(), leg.strike(), leg.optionType(),
                    leg.side(), newQty, leg.expiry()));
        }

        if (isLive && spreadOrderExecutor != null) {
            var result = spreadOrderExecutor.executePartialCloseShorts(
                    group.legs(), fraction, group.groupId(), strategyType().name(), false, lotSize);
            if (!result.allFilled()) {
                log.warn("{} partial exit failed for {} — {}", strategyType().displayName(), group.groupId(),
                        result.summary());
                return false;
            }
        }

        syncGroupLegs(group.groupId(), updatedLegs, priceUpdates);
        log.info("{} partial profit {} for group {}", strategyType().displayName(), reason, group.groupId());

        // P2 #30: Check if all shorts are now closed — if so, exit remaining BUY hedges
        boolean anyShortsRemaining = updatedLegs.stream()
                .anyMatch(l -> l.side() == OrderSide.SELL);
        if (!anyShortsRemaining) {
            log.info("{} all shorts closed progressively for group {} — exiting orphan hedges",
                    strategyType().displayName(), group.groupId());
            return exitAllLegs(group, currentPrices, reason + "_HEDGE_CLEANUP");
        }

        return false;
    }

    /**
     * @return true if group is fully closed in DB (and live broker when applicable)
     */
    private boolean exitAllLegs(PositionGroup group, Map<String, BigDecimal> currentPrices, String reason) {
        if (group.status() != PositionGroupStatus.OPEN) {
            return false;
        }
        log.info("Exiting all legs for group {}: strategy={}, legs={}, reason={}",
                group.groupId(), group.strategyType().displayName(), group.legs().size(), reason);

        StrategyConfig config = strategyConfigService.getConfig(strategyType(), group.underlying().name());
        boolean isLive = config != null && !config.isPaperTrading();
        boolean isPaper = config != null && config.isPaperTrading();

        Map<String, BigDecimal> exitPrices = new HashMap<>(currentPrices);

        if (isLive) {
            SpreadOrderExecutor.SpreadExecutionResult exitResult = spreadOrderExecutor.executeExit(
                    group.legs(), group.groupId(), strategyType().name(), false);
            if (!exitResult.allFilled()) {
                log.error("{} exit incomplete for group {} — keeping OPEN for retry: {}",
                        strategyType().displayName(), group.groupId(), exitResult.summary());
                if (errorEventService != null) {
                    errorEventService.high("SpreadExit",
                            "Partial exit " + group.groupId() + ": " + exitResult.summary());
                }
                return false;
            }
            if (!exitResult.fillPricesByInstrument().isEmpty()) {
                exitPrices = exitResult.fillPricesByInstrument();
            }
        } else if (isPaper) {
            for (SpreadLeg leg : group.legs()) {
                log.info("  Paper exit leg: instrument={}, side={}", leg.instrumentKey(), leg.side());
            }
        }

        finalizeClosedGroup(group, exitPrices, isLive ? "LIVE" : "PAPER", reason, isLive ? "FILLED" : "PAPER");
        return true;
    }

    private void finalizeClosedGroup(PositionGroup group, Map<String, BigDecimal> exitPrices,
                                     String modeLabel, String exitReason, String pnlSource) {
        BigDecimal entryNetDebit = netDebit(group.legs(), group.entryPrices());
        BigDecimal exitNetDebit = netDebit(group.legs(), exitPrices);

        BigDecimal pnl = entryNetDebit.subtract(exitNetDebit, MC);
        String pnlLabel = pnl.signum() >= 0 ? "PROFIT" : "LOSS";
        log.info("{} P&L [{}] group={} strategy={} underlying={} pnl={} (entryDebit={} exitDebit={})",
                modeLabel, pnlLabel, group.groupId(), group.strategyType().displayName(),
                group.underlying(), pnl.setScale(2, java.math.RoundingMode.HALF_UP),
                entryNetDebit.setScale(2, java.math.RoundingMode.HALF_UP),
                exitNetDebit.setScale(2, java.math.RoundingMode.HALF_UP));

        positionGroupRepository.findByGroupId(group.groupId()).ifPresent(entity -> {
            entity.close(pnl, exitReason, pnlSource);
            positionGroupRepository.save(entity);
        });

        if (hedgeCostTracker != null) {
            hedgeCostTracker.recordExit(group, pnl);
        }
        if (spreadExitPolicy != null) {
            spreadExitPolicy.clearPartialLayers(group.groupId());
        }
        if (exitEvaluationRegistry != null) {
            exitEvaluationRegistry.remove(group.groupId());
        }
        activePositions.remove(group.groupId());
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    /** Returns an unmodifiable view of in-memory active position groups. */
    public Map<String, PositionGroup> getActivePositions() {
        return Map.copyOf(activePositions);
    }

    /** Returns the number of currently active positions (in-memory cache). */
    protected int activePositionCount() {
        return activePositions.size();
    }
}
