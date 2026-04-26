package com.algo.trade.strategy.spread;

import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.Quote;
import com.algo.trade.domain.SignalType;
import com.algo.trade.domain.SpreadEvaluationContext;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.StrategyDecision;
import com.algo.trade.execution.ExecutionEngine;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
            log.info("{} restored open position group {} from DB (legs={})",
                    strategyType().displayName(), group.groupId(), group.legs().size());
        }
        if (!open.isEmpty()) {
            log.info("{} recovered {} open group(s) from DB on startup",
                    strategyType().displayName(), open.size());
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

        // 2. Subclass entry condition
        if (!shouldEnter(ctx)) {
            log.debug("{} shouldEnter=false — skipping", strategyType().displayName());
            return Optional.empty();
        }

        // 3. Construct legs
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

        // 6. Minimum premium check
        BigDecimal netDebit = netDebit(legs, entryPrices);
        if (netDebit.compareTo(BigDecimal.ZERO) > 0
                && netDebit.compareTo(config.getMinCombinedPremium()) < 0) {
            log.debug("{} net debit {} below minimum premium {} — skipping",
                    strategyType().displayName(), netDebit, config.getMinCombinedPremium());
            return Optional.empty();
        }

        // 7. Build PositionGroup and register in memory + DB
        String groupId = strategyType().name() + "-" + UUID.randomUUID().toString().substring(0, 8);
        PositionGroup group = new PositionGroup(
                groupId, strategyType(), ctx.underlying(), legs, Map.copyOf(entryPrices),
                Instant.now(), true);
        activePositions.put(groupId, group);
        positionGroupRepository.save(PositionGroupEntity.from(group, entryPrices));

        // 8. Build StrategyDecision
        BigDecimal netPremium = netDebit.abs();
        boolean isCreditStrategy = netDebit.signum() < 0;
        List<String> reasons = new ArrayList<>();
        reasons.add(strategyType().displayName() + " entry signal");
        reasons.add("Legs: " + legs.size());
        reasons.add((isCreditStrategy ? "Net credit: " : "Net debit: ") + netPremium);

        StrategyDecision decision = new StrategyDecision(
                Instant.now(),
                ctx.underlying(),
                SignalType.BUY_CE,
                ctx.underlyingPrice(),
                Optional.of(netPremium),
                Optional.empty(),
                Optional.of(config.getLots() * ctx.indexType().lotSize()),
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
                strategyType().displayName(), groupId, legs.size(), netPremium,
                isCreditStrategy ? "credit received" : "debit paid");
        return Optional.of(decision);
    }

    // ── Exit API ──────────────────────────────────────────────────────────

    /**
     * Called by {@code SpreadPositionExitMonitor} on each candle close.
     * Checks the subclass exit condition and exits all legs if triggered.
     *
     * @return true if the position was exited
     */
    public final boolean checkAndExit(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        StrategyConfig config = strategyConfigService.getConfig(strategyType());
        if (shouldExit(group, currentPrices, config)) {
            exitAllLegs(group, currentPrices);
            return true;
        }
        return false;
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

                StrategyConfig config = strategyConfigService.getConfig(strategyType());
                if (shouldExit(group, currentPrices, config)) {
                    exitAllLegs(group, currentPrices);
                }
            } catch (Exception ex) {
                log.error("Error managing position group {}: {}", group.groupId(), ex.getMessage(), ex);
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
     * Exits all legs: logs per-leg prices, computes paper P&L, closes the DB record,
     * and removes the group from the in-memory cache.
     */
    protected final void exitAllLegs(PositionGroup group, Map<String, BigDecimal> currentPrices) {
        log.info("Exiting all legs for group {}: strategy={}, legs={}",
                group.groupId(), group.strategyType().displayName(), group.legs().size());

        BigDecimal entryNetDebit = netDebit(group.legs(), group.entryPrices());
        BigDecimal exitNetDebit  = netDebit(group.legs(), currentPrices);

        for (SpreadLeg leg : group.legs()) {
            BigDecimal entryPrice = group.entryPrices().getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            BigDecimal exitPrice  = currentPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            log.info("  Exit leg: instrument={}, side={}, strike={}, entryPrice={}, exitPrice={}",
                    leg.instrumentKey(), leg.side(), leg.strike(), entryPrice, exitPrice);
        }

        // P&L: entryNetDebit - exitNetDebit works for both debit and credit strategies
        BigDecimal pnl = entryNetDebit.subtract(exitNetDebit, MC);
        String pnlLabel = pnl.signum() >= 0 ? "PROFIT" : "LOSS";
        log.info("PAPER P&L [{}] group={} strategy={} underlying={} pnl={} (entryDebit={} exitDebit={})",
                pnlLabel, group.groupId(), group.strategyType().displayName(),
                group.underlying(), pnl.setScale(2, java.math.RoundingMode.HALF_UP),
                entryNetDebit.setScale(2, java.math.RoundingMode.HALF_UP),
                exitNetDebit.setScale(2, java.math.RoundingMode.HALF_UP));

        // Persist exit to DB
        positionGroupRepository.findByGroupId(group.groupId()).ifPresent(entity -> {
            entity.close(pnl);
            positionGroupRepository.save(entity);
        });

        activePositions.remove(group.groupId());
    }

    // ── Accessors ─────────────────────────────────────────────────────────

    /** Returns an unmodifiable view of in-memory active position groups. */
    protected Map<String, PositionGroup> getActivePositions() {
        return Map.copyOf(activePositions);
    }

    /** Returns the number of currently active positions (in-memory cache). */
    protected int activePositionCount() {
        return activePositions.size();
    }
}
