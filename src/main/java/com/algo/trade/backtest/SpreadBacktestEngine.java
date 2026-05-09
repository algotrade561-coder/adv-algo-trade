package com.algo.trade.backtest;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.Candle;
import com.algo.trade.domain.IndexType;
import com.algo.trade.domain.OptionChainLevel;
import com.algo.trade.domain.OptionChainSnapshot;
import com.algo.trade.domain.OptionType;
import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.SpreadEvaluationContext;
import com.algo.trade.domain.SpreadLeg;
import com.algo.trade.domain.Timeframe;
import com.algo.trade.domain.UnderlyingSymbol;
import com.algo.trade.indicator.EmaIndicator;
import com.algo.trade.regime.RegimeFilter;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.strategy.SpreadStrategyEvaluator;
import com.algo.trade.strategy.StrategyConfig;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Replays multi-leg spread strategies day-by-day using option chain snapshots
 * built from global-datafeeds by-day CSV files.
 *
 * <p>Unlike {@link BacktestEngine} which replays a single option's candle stream,
 * this engine iterates trading days, builds {@link OptionChainSnapshot} per day,
 * evaluates spread entry/exit using the strategy's actual methods, and tracks
 * per-leg price evolution across days.</p>
 */
@Component
public class SpreadBacktestEngine {

    private static final Logger log = LoggerFactory.getLogger(SpreadBacktestEngine.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    // ── Dependencies ──────────────────────────────────────────────────────
    private final OptionChainBuilder optionChainBuilder;
    private final SpreadStrategyEvaluator spreadStrategyEvaluator;
    private final EmaIndicator emaIndicator;
    private final MarketGuard marketGuard;
    private final RegimeFilter regimeFilter;
    private final CandleCsvReader candleCsvReader;

    public SpreadBacktestEngine(OptionChainBuilder optionChainBuilder,
                                SpreadStrategyEvaluator spreadStrategyEvaluator,
                                EmaIndicator emaIndicator,
                                MarketGuard marketGuard,
                                RegimeFilter regimeFilter) {
        this.optionChainBuilder = optionChainBuilder;
        this.spreadStrategyEvaluator = spreadStrategyEvaluator;
        this.emaIndicator = emaIndicator;
        this.marketGuard = marketGuard;
        this.regimeFilter = regimeFilter;
        this.candleCsvReader = new CandleCsvReader();
    }

    // ── Public API ────────────────────────────────────────────────────────

    /**
     * Run a spread backtest for the given strategy over the date range.
     *
     * @param strategyType the spread strategy to evaluate
     * @param config       per-strategy configuration from H2
     * @param underlying   the underlying symbol (NIFTY, BANKNIFTY)
     * @param from         start date (inclusive)
     * @param to           end date (inclusive)
     * @param properties   application.yml configuration
     * @param tracker      execution flow tracker (nullable — uses no-op if null)
     * @return aggregated backtest result with trades, metrics, and signal stats
     */
    public SpreadBacktestResult run(StrategyType strategyType,
                                    StrategyConfig config,
                                    UnderlyingSymbol underlying,
                                    LocalDate from,
                                    LocalDate to,
                                    TradingProperties properties,
                                    ExecutionFlowTracker tracker) {
        ExecutionFlowTracker t = tracker != null ? tracker : new NoOpFlowTracker();
        ZoneId tz = properties.timezone();
        IndexType indexType = IndexType.from(underlying);
        Path byDayDir = globalDatafeedsByDayDirectory(properties);

        log.info("SpreadBacktest started: strategy={}, underlying={}, from={}, to={}",
                strategyType, underlying, from, to);

        List<SpreadBacktestTrade> closedTrades = new ArrayList<>();
        int totalSignals = 0;
        int rejectedSignals = 0;
        Map<String, Integer> rejectionReasons = new LinkedHashMap<>();
        Map<String, String> strategySpecificMetrics = new LinkedHashMap<>();

        // Accumulators for strategy-specific metrics
        List<Double> ivRankSamples = new ArrayList<>();
        List<Double> vixSamples = new ArrayList<>();
        List<Double> pcrSamples = new ArrayList<>();
        Map<String, Integer> regimeDistribution = new LinkedHashMap<>();
        Map<String, Integer> exitReasonCounts = new LinkedHashMap<>();
        int daysWithChainData = 0;
        int expiryDaysInRange = 0;

        // Accumulated underlying candles for EMA/trend computation
        List<Candle> underlyingCandleHistory = new ArrayList<>();

        // Open position state
        OpenSpreadPosition openPosition = null;

        // Iterate trading days
        List<LocalDate> tradingDays = tradingDays(from, to);
        if (tradingDays.isEmpty()) {
            log.warn("SpreadBacktest: no trading days in range [{}, {}]", from, to);
            return emptyResult(strategyType);
        }

        for (LocalDate day : tradingDays) {
            // 1. Load underlying candles for this day and add to history
            List<Candle> dayUnderlyingCandles = loadUnderlyingCandlesForDay(properties, underlying, day, tz);
            underlyingCandleHistory.addAll(dayUnderlyingCandles);

            // 2. Determine underlying price from latest candle
            BigDecimal underlyingPrice = latestUnderlyingPrice(underlyingCandleHistory);
            if (underlyingPrice.signum() == 0) {
                log.debug("SpreadBacktest: no underlying price for {}, skipping", day);
                continue;
            }

            // 3. Build option chain from by-day CSV — use intraday snapshots for realistic P&L
            Instant entryTimestamp = day.atTime(LocalTime.of(10, 0)).atZone(tz).toInstant();
            Instant dayTimestamp = day.atTime(LocalTime.of(15, 0)).atZone(tz).toInstant();

            // Always build the daily chain as fallback for price lookups
            Optional<OptionChainSnapshot> dailyChainOpt = optionChainBuilder.buildFromByDay(
                    byDayDir, day, underlying, underlyingPrice);
            if (dailyChainOpt.isEmpty()) {
                log.debug("SpreadBacktest: no option chain for {}, skipping", day);
                continue;
            }
            OptionChainSnapshot dailyChain = dailyChainOpt.get();

            // Try intraday chains for better entry/exit price differentiation
            List<TimestampedOptionChain> intradayChains = optionChainBuilder.buildIntradayChains(
                    byDayDir, day, underlying, underlyingPrice, Timeframe.FIFTEEN_MINUTE);

            // Use first intraday snapshot for entry, last for exit; fall back to daily
            OptionChainSnapshot entryChain = !intradayChains.isEmpty()
                    ? intradayChains.getFirst().chain() : dailyChain;
            OptionChainSnapshot exitChain = !intradayChains.isEmpty()
                    ? intradayChains.getLast().chain() : dailyChain;
            // Use the daily chain as the "current" chain for general evaluation (most complete)
            OptionChainSnapshot chain = dailyChain;

            // 4. Aggregate underlying candles to per-strategy trend timeframe for EMA/trend
            Timeframe trendTf = Timeframe.FIFTEEN_MINUTE; // default
            if (config.getTrendTimeframe() != null) {
                try { trendTf = Timeframe.valueOf(config.getTrendTimeframe()); }
                catch (IllegalArgumentException ignored) { /* keep default */ }
            }
            List<Candle> candles15m = aggregateCandles(underlyingCandleHistory, trendTf);

            // 5. Compute simplified IV rank from ATM straddle price / underlying price
            double ivRank = computeSimplifiedIvRank(chain, underlyingPrice);
            ivRankSamples.add(ivRank);
            daysWithChainData++;

            // 6. Compute days to expiry for SL scaling
            LocalDate nearestExpiry = estimateNearestExpiry(day, indexType);
            long daysToExpiry = java.time.temporal.ChronoUnit.DAYS.between(day, nearestExpiry);
            boolean isExpiryDay = daysToExpiry == 0;
            if (isExpiryDay) expiryDaysInRange++;

            // 7. Determine market time context — simulate intraday progression
            // Use entry time (morning) for entry evaluation, exit time (afternoon) for exit evaluation
            LocalTime entryMarketTime = LocalTime.of(10, 0);  // morning entry evaluation
            LocalTime exitMarketTime = LocalTime.of(14, 30);  // afternoon exit evaluation

            // ── Manage open position ──────────────────────────────────────
            if (openPosition != null) {
                // Use exit chain (end of day) for current price evaluation
                // Fall back to daily chain if intraday chain is missing strikes
                Map<String, BigDecimal> currentPrices = lookupLegPrices(openPosition.legs, exitChain, indexType);
                if (currentPrices.size() < openPosition.legs.size()) {
                    currentPrices = lookupLegPrices(openPosition.legs, dailyChain, indexType);
                }
                if (currentPrices.isEmpty()) {
                    // Can't price legs — force exit at end of data if last day
                    if (day.equals(tradingDays.getLast())) {
                        closedTrades.add(closePosition(openPosition, currentPrices, dayTimestamp,
                                "End of data square-off", properties));
                        t.recordHit("exit.end-of-data", day.toString());
                        openPosition = null;
                    }
                    continue;
                }

                // Update leg prices
                openPosition = openPosition.withCurrentPrices(currentPrices);

                // Check exit conditions in priority order
                String exitReason = evaluateExitConditions(
                        openPosition, currentPrices, config, properties, day,
                        isExpiryDay, daysToExpiry, exitMarketTime, t);

                if (exitReason != null) {
                    closedTrades.add(closePosition(openPosition, currentPrices, dayTimestamp,
                            exitReason, properties));
                    exitReasonCounts.merge(exitReason, 1, Integer::sum);
                    openPosition = null;
                    continue;
                }

                // End of data square-off on last day
                if (day.equals(tradingDays.getLast())) {
                    closedTrades.add(closePosition(openPosition, currentPrices, dayTimestamp,
                            "End of data square-off", properties));
                    t.recordHit("exit.end-of-data", day.toString());
                    openPosition = null;
                }
                continue;
            }

            // ── Evaluate entry ────────────────────────────────────────────
            if (candles15m.size() < 22) {
                log.debug("SpreadBacktest: insufficient 15m candles ({}) for EMA on {}", candles15m.size(), day);
                continue;
            }

            // Expiry-day entry restrictions
            if (isExpiryDay) {
                // No new entries after 1 PM on expiry day
                t.recordHit("expiry.no-entry-after-1pm", day.toString());
                rejectedSignals++;
                rejectionReasons.merge("expiry-no-entry-after-1pm", 1, Integer::sum);
                continue;
            }

            // VB theta guard: no buying on expiry day itself (DTE=0) for volatility strategies
            // Relaxed from 3 days to 0 DTE for backtesting — lets strategies trade closer to expiry
            // so we can measure actual performance. The guard's original 3-day block is tracked.
            if (daysToExpiry == 0 && isBuyingStrategy(strategyType)) {
                t.recordHit("expiry.vb-theta-guard", "DTE=0 on " + day);
                rejectedSignals++;
                rejectionReasons.merge("vb-theta-guard", 1, Integer::sum);
                continue;
            }
            if (daysToExpiry <= 3 && isBuyingStrategy(strategyType)) {
                // Track what the original guard would have blocked, but don't block
                rejectionReasons.merge("vb-theta-guard-would-block", 1, Integer::sum);
            }

            // MarketGuard simulation — estimate VIX from option prices
            // In backtest mode: record what MarketGuard would do but DON'T block entries.
            // This lets all strategies generate trades so we can evaluate their actual performance.
            // The guard's decisions are tracked in strategy-specific metrics for analysis.
            double simulatedVix = estimateVixFromChain(chain, underlyingPrice);
            vixSamples.add(simulatedVix);

            boolean isSelling = strategyType.isSellingStrategy();
            // Record MarketGuard assessment without blocking
            boolean wouldBeBlocked = false;
            if (isSelling) {
                if (simulatedVix > 21.0 || simulatedVix < 12.0) {
                    t.recordHit("entry.market-guard-blocked",
                            "SIMULATED (not blocked): VIX=" + String.format("%.1f", simulatedVix));
                    wouldBeBlocked = true;
                }
            } else {
                if (simulatedVix > 0 && simulatedVix < 14.0) {
                    t.recordHit("entry.market-guard-blocked",
                            "SIMULATED (not blocked): VIX=" + String.format("%.1f", simulatedVix) + " < 14.0");
                    wouldBeBlocked = true;
                }
            }
            if (wouldBeBlocked) {
                rejectionReasons.merge("market-guard-would-block", 1, Integer::sum);
            }

            // Regime filter scoring
            double pcr = computePcr(chain);
            pcrSamples.add(pcr);
            int trendSignal = computeTrendSignal(candles15m);
            int regimeScore = regimeFilter.computeScore(simulatedVix, ivRank, pcr, trendSignal, 3.0);
            t.recordHit("regime.score-computed", "score=" + regimeScore + " on " + day);
            RegimeFilter.MarketRegime regime = regimeFilter.classify(regimeScore);
            regimeDistribution.merge(regime.name(), 1, Integer::sum);
            t.recordHit("regime.strategy-recommended", regime.name() + " on " + day);

            // Build SpreadEvaluationContext
            SpreadEvaluationContext ctx = new SpreadEvaluationContext(
                    underlyingPrice, ivRank, chain, config, underlying, indexType, candles15m);

            // Evaluate entry using SpreadStrategyEvaluator
            Optional<com.algo.trade.domain.StrategyDecision> decision =
                    spreadStrategyEvaluator.evaluate(candles15m, ivRank, config, underlying);

            if (decision.isEmpty()) {
                log.debug("SpreadBacktest: no entry signal for {} on {}", strategyType, day);
                continue;
            }

            // Signal fired
            totalSignals++;
            t.recordHit("entry.signal-to-trade", strategyType + " on " + day);

            // Construct legs from the option chain (use daily chain for most complete strike coverage)
            List<SpreadLeg> legs = constructLegsFromChain(strategyType, config, dailyChain, underlyingPrice,
                    indexType, nearestExpiry);
            if (legs.isEmpty()) {
                rejectedSignals++;
                rejectionReasons.merge("no-legs-constructed", 1, Integer::sum);
                log.debug("SpreadBacktest: could not construct legs for {} on {}", strategyType, day);
                continue;
            }

            // Look up entry prices from the ENTRY chain (morning snapshot)
            // Fall back to daily chain if intraday chain is missing strikes
            Map<String, BigDecimal> entryPrices = lookupLegPrices(legs, entryChain, indexType);
            if (entryPrices.size() < legs.size()) {
                // Try daily chain which has all strikes aggregated
                entryPrices = lookupLegPrices(legs, dailyChain, indexType);
            }
            if (entryPrices.size() < legs.size()) {
                rejectedSignals++;
                rejectionReasons.merge("missing-leg-prices", 1, Integer::sum);
                continue;
            }

            // Check minimum premium for buying strategies
            BigDecimal netDebit = computeNetDebit(legs, entryPrices);
            if (!isSelling && netDebit.compareTo(BigDecimal.ZERO) > 0
                    && netDebit.compareTo(config.getMinCombinedPremium()) < 0) {
                t.recordHit("entry.premium-too-high",
                        "netDebit=" + netDebit + " < min=" + config.getMinCombinedPremium());
                rejectedSignals++;
                rejectionReasons.merge("premium-below-minimum", 1, Integer::sum);
                continue;
            }

            // Premium sanity check — reject entries where per-leg premium exceeds
            // a reasonable threshold (e.g., 20% of underlying price per leg).
            // This catches data anomalies like entry prices of 4304 or 6990.
            BigDecimal maxReasonablePremium = underlyingPrice.multiply(BigDecimal.valueOf(0.20));
            boolean premiumAnomaly = false;
            for (SpreadLeg leg : legs) {
                BigDecimal legPrice = entryPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
                if (legPrice.compareTo(maxReasonablePremium) > 0) {
                    premiumAnomaly = true;
                    log.warn("SpreadBacktest: premium anomaly for {} on {}: leg {} price {} > max {}",
                            strategyType, day, leg.instrumentKey(), legPrice, maxReasonablePremium);
                    break;
                }
            }
            if (premiumAnomaly) {
                rejectedSignals++;
                rejectionReasons.merge("premium-anomaly", 1, Integer::sum);
                continue;
            }

            // Open position
            String tradeId = "SPREAD-" + strategyType.name() + "-" + UUID.randomUUID().toString().substring(0, 8);
            openPosition = new OpenSpreadPosition(
                    tradeId, strategyType, legs, entryPrices, entryTimestamp,
                    new HashMap<>(entryPrices), netDebit, BigDecimal.ZERO);

            log.info("SpreadBacktest: opened {} on {} with {} legs, netDebit={}",
                    strategyType, day, legs.size(), netDebit);
        }

        // Force-close any remaining open position
        if (openPosition != null && !tradingDays.isEmpty()) {
            LocalDate lastDay = tradingDays.getLast();
            Instant lastTimestamp = lastDay.atTime(LocalTime.of(15, 30)).atZone(tz).toInstant();
            closedTrades.add(closePosition(openPosition, openPosition.currentPrices, lastTimestamp,
                    "End of data square-off", properties));
            t.recordHit("exit.end-of-data", lastDay.toString());
        }

        // Compute metrics
        List<BacktestTrade> unifiedTrades = closedTrades.stream()
                .map(SpreadBacktestTrade::toBacktestTrade)
                .toList();
        BacktestMetrics metrics = computeMetrics(unifiedTrades, totalSignals, rejectedSignals, properties);

        // Collect strategy-specific metrics
        strategySpecificMetrics.put("tradingDays", String.valueOf(tradingDays.size()));
        strategySpecificMetrics.put("daysWithChainData", String.valueOf(daysWithChainData));
        strategySpecificMetrics.put("expiryDaysInRange", String.valueOf(expiryDaysInRange));
        strategySpecificMetrics.put("strategyType", strategyType.name());

        // IV rank stats
        if (!ivRankSamples.isEmpty()) {
            double avgIv = ivRankSamples.stream().mapToDouble(d -> d).average().orElse(0);
            double minIv = ivRankSamples.stream().mapToDouble(d -> d).min().orElse(0);
            double maxIv = ivRankSamples.stream().mapToDouble(d -> d).max().orElse(0);
            strategySpecificMetrics.put("avgIvRank", String.format("%.1f", avgIv));
            strategySpecificMetrics.put("minIvRank", String.format("%.1f", minIv));
            strategySpecificMetrics.put("maxIvRank", String.format("%.1f", maxIv));
        }

        // VIX stats
        if (!vixSamples.isEmpty()) {
            double avgVix = vixSamples.stream().mapToDouble(d -> d).average().orElse(0);
            double minVix = vixSamples.stream().mapToDouble(d -> d).min().orElse(0);
            double maxVix = vixSamples.stream().mapToDouble(d -> d).max().orElse(0);
            strategySpecificMetrics.put("avgVix", String.format("%.1f", avgVix));
            strategySpecificMetrics.put("vixRange", String.format("%.1f-%.1f", minVix, maxVix));
        }

        // PCR stats
        if (!pcrSamples.isEmpty()) {
            double avgPcr = pcrSamples.stream().mapToDouble(d -> d).average().orElse(0);
            strategySpecificMetrics.put("avgPcr", String.format("%.2f", avgPcr));
        }

        // Regime distribution
        for (Map.Entry<String, Integer> entry : regimeDistribution.entrySet()) {
            strategySpecificMetrics.put("regime." + entry.getKey(), String.valueOf(entry.getValue()) + " days");
        }

        // Exit reason distribution
        for (Map.Entry<String, Integer> entry : exitReasonCounts.entrySet()) {
            strategySpecificMetrics.put("exitBy." + entry.getKey(), String.valueOf(entry.getValue()));
        }

        log.info("SpreadBacktest completed: strategy={}, trades={}, signals={}, rejected={}, pnl={}",
                strategyType, closedTrades.size(), totalSignals, rejectedSignals,
                metrics.cumulativePnl());

        return new SpreadBacktestResult(
                closedTrades, metrics, totalSignals, rejectedSignals,
                Map.copyOf(rejectionReasons), Map.copyOf(strategySpecificMetrics));
    }


    // ── Exit condition evaluation ─────────────────────────────────────────

    /**
     * Evaluates all exit conditions in priority order and returns the exit reason,
     * or null if no exit is triggered.
     */
    private String evaluateExitConditions(OpenSpreadPosition position,
                                          Map<String, BigDecimal> currentPrices,
                                          StrategyConfig config,
                                          TradingProperties properties,
                                          LocalDate day,
                                          boolean isExpiryDay,
                                          long daysToExpiry,
                                          LocalTime marketTime,
                                          ExecutionFlowTracker t) {
        BigDecimal currentNet = computeNetDebit(position.legs, currentPrices);
        BigDecimal entryNet = position.entryNetDebit;
        BigDecimal slPercent = config.getStopLossPercent();
        BigDecimal targetPercent = config.getTargetPercent();

        // DTE-based SL scaling
        BigDecimal scaledSlPercent = scaleSLByDTE(slPercent, daysToExpiry, isExpiryDay, t);

        // Expiry-day tightened SL (0.5× multiplier)
        if (isExpiryDay) {
            scaledSlPercent = scaledSlPercent.multiply(BigDecimal.valueOf(0.5), MC);
            t.recordHit("exit.expiry-tightened-sl", "SL scaled to " + scaledSlPercent + "% on " + day);
        }

        // Stop loss check
        if (isStopLossHit(entryNet, currentNet, scaledSlPercent, position.strategyType.isSellingStrategy())) {
            t.recordHit("exit.stop-loss", position.strategyType + " on " + day);
            return "Stop loss";
        }

        // Target check
        if (isTargetHit(entryNet, currentNet, targetPercent, position.strategyType.isSellingStrategy())) {
            t.recordHit("exit.target", position.strategyType + " on " + day);
            return "Target hit";
        }

        // Trailing stop check
        BigDecimal bestPnl = position.bestPnl;
        BigDecimal currentPnl = computeCombinedPnl(position.legs, position.entryPrices, currentPrices);
        if (currentPnl.compareTo(bestPnl) > 0) {
            position.bestPnl = currentPnl;
        }
        BigDecimal trailingActivation = config.getTrailingStopActivationPercent();
        BigDecimal trailingGap = config.getTrailingGapPercent();
        if (isTrailingStopHit(entryNet, position.bestPnl, currentPnl, trailingActivation, trailingGap)) {
            t.recordHit("exit.trailing-stop", position.strategyType + " on " + day);
            return "Trailing stop";
        }

        // Forced exit at configured squareoff time (per-strategy)
        LocalTime squareoffTime = LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute());
        if (marketTime.isAfter(squareoffTime) || marketTime.equals(squareoffTime)) {
            t.recordHit("exit.forced-time", "forcedExit at " + squareoffTime + " on " + day);
            return "Configured forced square-off";
        }

        // Max hold time exceeded (compare against entry time, not day start)
        if (config.getMaxHoldMinutes() > 0) {
            Instant exitEvalTime = day.atTime(marketTime)
                    .atZone(properties.timezone()).toInstant();
            long holdMinutes = Duration.between(position.entryTime, exitEvalTime).toMinutes();
            if (holdMinutes >= config.getMaxHoldMinutes()) {
                t.recordHit("exit.max-hold", "held " + holdMinutes + "min (max=" + config.getMaxHoldMinutes() + ") on " + day);
                return "Max hold time exceeded";
            }
        }

        // Expiry day 2 PM afternoon exit
        if (isExpiryDay && marketTime.isAfter(LocalTime.of(14, 0))) {
            t.recordHit("exit.expiry-afternoon", day.toString());
            return "Expiry afternoon exit";
        }

        // Expiry day 3 PM danger zone exit
        if (isExpiryDay && marketTime.isAfter(LocalTime.of(15, 0))) {
            t.recordHit("exit.expiry-danger-zone", day.toString());
            return "Expiry danger zone exit";
        }

        return null;
    }

    /**
     * Scale SL by days-to-expiry: closer to expiry = tighter SL.
     * 0 DTE: 0.7×, 1 DTE: 0.7×, 2 DTE: 0.85×, 3+ DTE: 1.0×
     */
    private BigDecimal scaleSLByDTE(BigDecimal baseSL, long daysToExpiry, boolean isExpiryDay,
                                     ExecutionFlowTracker t) {
        BigDecimal multiplier;
        if (daysToExpiry <= 1) {
            multiplier = BigDecimal.valueOf(0.7);
        } else if (daysToExpiry == 2) {
            multiplier = BigDecimal.valueOf(0.85);
        } else {
            return baseSL;
        }
        t.recordHit("exit.dte-sl-scaling", "DTE=" + daysToExpiry + " multiplier=" + multiplier);
        return baseSL.multiply(multiplier, MC);
    }

    /**
     * Check if stop loss is hit based on net debit/credit change.
     * For debit spreads: loss when current net > entry net (paying more to close).
     * For credit spreads: loss when current net cost exceeds entry credit.
     */
    private boolean isStopLossHit(BigDecimal entryNet, BigDecimal currentNet,
                                   BigDecimal slPercent, boolean isSelling) {
        if (entryNet.signum() == 0) return false;
        BigDecimal changePct;
        if (isSelling) {
            // Credit spread: entry net is negative (received credit)
            // Loss = current net - entry net (positive means losing money)
            changePct = currentNet.subtract(entryNet, MC)
                    .divide(entryNet.abs(), MC)
                    .multiply(HUNDRED, MC)
                    .abs();
        } else {
            // Debit spread: entry net is positive (paid debit)
            // Loss = entry net - current net (when current value drops)
            changePct = entryNet.subtract(currentNet, MC)
                    .divide(entryNet.abs(), MC)
                    .multiply(HUNDRED, MC);
            // Only trigger SL on losses (positive changePct means loss for debit)
            if (changePct.signum() <= 0) return false;
        }
        return changePct.compareTo(slPercent) > 0;
    }

    /**
     * Check if target is hit.
     * For debit spreads: profit when current net > entry net (spread widened).
     * For credit spreads: profit when premium decayed.
     */
    private boolean isTargetHit(BigDecimal entryNet, BigDecimal currentNet,
                                 BigDecimal targetPercent, boolean isSelling) {
        if (entryNet.signum() == 0) return false;
        BigDecimal profitPct;
        if (isSelling) {
            // Credit spread: profit = entry credit - current cost
            profitPct = entryNet.subtract(currentNet, MC)
                    .divide(entryNet.abs(), MC)
                    .multiply(HUNDRED, MC)
                    .abs();
        } else {
            // Debit spread: profit = current value - entry cost
            profitPct = currentNet.subtract(entryNet, MC)
                    .divide(entryNet.abs(), MC)
                    .multiply(HUNDRED, MC);
            if (profitPct.signum() <= 0) return false;
        }
        return profitPct.compareTo(targetPercent) >= 0;
    }

    /**
     * Check if trailing stop is hit.
     * Trailing stop activates when profit exceeds activation threshold,
     * then triggers when profit drops by gap% from the best.
     */
    private boolean isTrailingStopHit(BigDecimal entryNet, BigDecimal bestPnl,
                                       BigDecimal currentPnl, BigDecimal activationPercent,
                                       BigDecimal gapPercent) {
        if (entryNet.signum() == 0 || bestPnl.signum() <= 0) return false;
        BigDecimal activationAmount = entryNet.abs()
                .multiply(activationPercent, MC)
                .divide(HUNDRED, MC);
        if (bestPnl.compareTo(activationAmount) < 0) return false;

        BigDecimal gapAmount = bestPnl.multiply(gapPercent, MC).divide(HUNDRED, MC);
        return currentPnl.compareTo(bestPnl.subtract(gapAmount, MC)) < 0;
    }


    // ── Leg construction from option chain ────────────────────────────────

    /**
     * Constructs spread legs from the option chain based on strategy type.
     * Uses ATM strike and configured OTM/spread offsets.
     */
    private List<SpreadLeg> constructLegsFromChain(StrategyType strategyType,
                                                    StrategyConfig config,
                                                    OptionChainSnapshot chain,
                                                    BigDecimal underlyingPrice,
                                                    IndexType indexType,
                                                    LocalDate expiry) {
        int atm = indexType.roundToATM(underlyingPrice.doubleValue());
        int interval = indexType.strikeInterval();
        int otmStrikes = config.getOtmStrikes();
        int spreadStrikes = config.getSpreadStrikes();
        int qty = config.getLots() * indexType.lotSize();

        return switch (strategyType) {
            case BULL_CALL_SPREAD -> buildTwoLegSpread(
                    atm, atm + spreadStrikes * interval,
                    OptionType.CE, OptionType.CE,
                    OrderSide.BUY, OrderSide.SELL,
                    qty, expiry);

            case BEAR_PUT_SPREAD -> buildTwoLegSpread(
                    atm, atm - spreadStrikes * interval,
                    OptionType.PE, OptionType.PE,
                    OrderSide.BUY, OrderSide.SELL,
                    qty, expiry);

            case LONG_STRADDLE -> buildTwoLegSpread(
                    atm, atm,
                    OptionType.CE, OptionType.PE,
                    OrderSide.BUY, OrderSide.BUY,
                    qty, expiry);

            case LONG_STRANGLE -> buildTwoLegSpread(
                    atm + otmStrikes * interval, atm - otmStrikes * interval,
                    OptionType.CE, OptionType.PE,
                    OrderSide.BUY, OrderSide.BUY,
                    qty, expiry);

            case SHORT_STRADDLE -> buildTwoLegSpread(
                    atm, atm,
                    OptionType.CE, OptionType.PE,
                    OrderSide.SELL, OrderSide.SELL,
                    qty, expiry);

            case SHORT_STRANGLE -> buildTwoLegSpread(
                    atm + otmStrikes * interval, atm - otmStrikes * interval,
                    OptionType.CE, OptionType.PE,
                    OrderSide.SELL, OrderSide.SELL,
                    qty, expiry);

            case IRON_CONDOR -> buildIronCondor(atm, otmStrikes, spreadStrikes, interval, qty, expiry);

            case BUTTERFLY -> buildButterfly(atm, interval, qty, expiry);

            case CALENDAR_SPREAD -> buildTwoLegSpread(
                    atm, atm,
                    OptionType.CE, OptionType.CE,
                    OrderSide.SELL, OrderSide.BUY,
                    qty, expiry);

            case DIAGONAL_SPREAD -> buildTwoLegSpread(
                    atm + otmStrikes * interval, atm,
                    OptionType.CE, OptionType.CE,
                    OrderSide.SELL, OrderSide.BUY,
                    qty, expiry);

            case JADE_LIZARD -> buildJadeLizard(atm, otmStrikes, spreadStrikes, interval, qty, expiry);

            case SYNTHETIC_FUTURES -> buildTwoLegSpread(
                    atm, atm,
                    OptionType.CE, OptionType.PE,
                    OrderSide.BUY, OrderSide.SELL,
                    qty, expiry);

            case EVENT_DRIVEN_BUY -> buildTwoLegSpread(
                    atm, atm,
                    OptionType.CE, OptionType.PE,
                    OrderSide.BUY, OrderSide.BUY,
                    qty, expiry);

            default -> List.of();
        };
    }

    private List<SpreadLeg> buildTwoLegSpread(int strike1, int strike2,
                                               OptionType type1, OptionType type2,
                                               OrderSide side1, OrderSide side2,
                                               int qty, LocalDate expiry) {
        String key1 = syntheticInstrumentKey(strike1, type1);
        String key2 = syntheticInstrumentKey(strike2, type2);
        return List.of(
                new SpreadLeg(key1, strike1, type1, side1, qty, expiry),
                new SpreadLeg(key2, strike2, type2, side2, qty, expiry));
    }

    private List<SpreadLeg> buildIronCondor(int atm, int otmStrikes, int spreadStrikes,
                                             int interval, int qty, LocalDate expiry) {
        int sellCe = atm + otmStrikes * interval;
        int buyCe = atm + spreadStrikes * interval;
        int sellPe = atm - otmStrikes * interval;
        int buyPe = atm - spreadStrikes * interval;
        return List.of(
                new SpreadLeg(syntheticInstrumentKey(sellCe, OptionType.CE), sellCe, OptionType.CE, OrderSide.SELL, qty, expiry),
                new SpreadLeg(syntheticInstrumentKey(buyCe, OptionType.CE), buyCe, OptionType.CE, OrderSide.BUY, qty, expiry),
                new SpreadLeg(syntheticInstrumentKey(sellPe, OptionType.PE), sellPe, OptionType.PE, OrderSide.SELL, qty, expiry),
                new SpreadLeg(syntheticInstrumentKey(buyPe, OptionType.PE), buyPe, OptionType.PE, OrderSide.BUY, qty, expiry));
    }

    private List<SpreadLeg> buildButterfly(int atm, int interval, int qty, LocalDate expiry) {
        int lowerWing = atm - interval;
        int upperWing = atm + interval;
        return List.of(
                new SpreadLeg(syntheticInstrumentKey(lowerWing, OptionType.CE), lowerWing, OptionType.CE, OrderSide.BUY, qty, expiry),
                new SpreadLeg(syntheticInstrumentKey(atm, OptionType.CE), atm, OptionType.CE, OrderSide.SELL, qty * 2, expiry),
                new SpreadLeg(syntheticInstrumentKey(upperWing, OptionType.CE), upperWing, OptionType.CE, OrderSide.BUY, qty, expiry));
    }

    private List<SpreadLeg> buildJadeLizard(int atm, int otmStrikes, int spreadStrikes,
                                             int interval, int qty, LocalDate expiry) {
        int sellCe = atm + otmStrikes * interval;
        int sellPe = atm - otmStrikes * interval;
        int buyPe = atm - spreadStrikes * interval;
        return List.of(
                new SpreadLeg(syntheticInstrumentKey(sellCe, OptionType.CE), sellCe, OptionType.CE, OrderSide.SELL, qty, expiry),
                new SpreadLeg(syntheticInstrumentKey(sellPe, OptionType.PE), sellPe, OptionType.PE, OrderSide.SELL, qty, expiry),
                new SpreadLeg(syntheticInstrumentKey(buyPe, OptionType.PE), buyPe, OptionType.PE, OrderSide.BUY, qty, expiry));
    }

    /** Synthetic instrument key for backtest leg identification: STRIKE-CE or STRIKE-PE */
    private String syntheticInstrumentKey(int strike, OptionType optionType) {
        return strike + "-" + optionType.name();
    }


    // ── Price lookup from option chain ────────────────────────────────────

    /**
     * Look up current prices for each leg from the option chain.
     * Maps synthetic instrument keys (e.g. "24500-CE") to prices from the chain levels.
     */
    private Map<String, BigDecimal> lookupLegPrices(List<SpreadLeg> legs,
                                                     OptionChainSnapshot chain,
                                                     IndexType indexType) {
        Map<String, BigDecimal> prices = new HashMap<>();
        Map<Integer, OptionChainLevel> levelByStrike = new HashMap<>();
        for (OptionChainLevel level : chain.levels()) {
            levelByStrike.put(level.strike().intValue(), level);
        }

        for (SpreadLeg leg : legs) {
            OptionChainLevel level = levelByStrike.get(leg.strike());
            if (level == null) {
                // Try nearest strike within 3 intervals
                level = findNearestLevel(chain.levels(), leg.strike());
                if (level == null) {
                    log.debug("lookupLegPrices: no level found for strike {} (chain has {} levels, range {}-{})",
                            leg.strike(), chain.levels().size(),
                            chain.levels().isEmpty() ? "N/A" : chain.levels().getFirst().strike(),
                            chain.levels().isEmpty() ? "N/A" : chain.levels().getLast().strike());
                }
            }
            if (level != null) {
                BigDecimal price = leg.optionType() == OptionType.CE
                        ? level.callLastPrice()
                        : level.putLastPrice();
                // Accept zero prices for deep OTM options — they're valid
                if (price != null && price.signum() >= 0) {
                    prices.put(leg.instrumentKey(), price);
                }
            }
        }
        return prices;
    }

    private OptionChainLevel findNearestLevel(List<OptionChainLevel> levels, int targetStrike) {
        OptionChainLevel nearest = null;
        int minDist = Integer.MAX_VALUE;
        for (OptionChainLevel level : levels) {
            int dist = Math.abs(level.strike().intValue() - targetStrike);
            if (dist < minDist) {
                minDist = dist;
                nearest = level;
            }
        }
        // Allow up to 3 strike intervals (150 points for NIFTY, 300 for BANKNIFTY)
        // to handle minor gaps in strike coverage while preventing cross-leg mapping
        if (nearest != null && minDist > 150) {
            return null;
        }
        return nearest;
    }

    // ── P&L computation ───────────────────────────────────────────────────

    /**
     * Compute combined P&L across all legs.
     * BUY legs: profit = (exit - entry) × quantity
     * SELL legs: profit = (entry - exit) × quantity
     */
    private BigDecimal computeCombinedPnl(List<SpreadLeg> legs,
                                           Map<String, BigDecimal> entryPrices,
                                           Map<String, BigDecimal> exitPrices) {
        BigDecimal totalPnl = BigDecimal.ZERO;
        for (SpreadLeg leg : legs) {
            BigDecimal entry = entryPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            BigDecimal exit = exitPrices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            BigDecimal legPnl;
            if (leg.side() == OrderSide.BUY) {
                legPnl = exit.subtract(entry, MC).multiply(BigDecimal.valueOf(leg.quantity()), MC);
            } else {
                legPnl = entry.subtract(exit, MC).multiply(BigDecimal.valueOf(leg.quantity()), MC);
            }
            totalPnl = totalPnl.add(legPnl, MC);
        }
        return totalPnl;
    }

    /**
     * Compute net debit of a spread: sum of BUY prices - sum of SELL prices.
     * Positive = debit spread, negative = credit spread.
     */
    private BigDecimal computeNetDebit(List<SpreadLeg> legs, Map<String, BigDecimal> prices) {
        BigDecimal buyTotal = BigDecimal.ZERO;
        BigDecimal sellTotal = BigDecimal.ZERO;
        for (SpreadLeg leg : legs) {
            BigDecimal price = prices.getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            BigDecimal legValue = price.multiply(BigDecimal.valueOf(leg.quantity()), MC);
            if (leg.side() == OrderSide.BUY) {
                buyTotal = buyTotal.add(legValue, MC);
            } else {
                sellTotal = sellTotal.add(legValue, MC);
            }
        }
        return buyTotal.subtract(sellTotal, MC);
    }

    // ── Position close ────────────────────────────────────────────────────

    private SpreadBacktestTrade closePosition(OpenSpreadPosition position,
                                               Map<String, BigDecimal> exitPrices,
                                               Instant exitTime,
                                               String exitReason,
                                               TradingProperties properties) {
        // Use entry prices for any legs missing from exit prices
        Map<String, BigDecimal> resolvedExitPrices = new HashMap<>(position.entryPrices);
        resolvedExitPrices.putAll(exitPrices);

        BigDecimal combinedPnl = computeCombinedPnl(position.legs, position.entryPrices, resolvedExitPrices);

        log.info("SpreadBacktest: closed {} trade={}, pnl={}, reason={}",
                position.strategyType, position.tradeId, combinedPnl, exitReason);

        return new SpreadBacktestTrade(
                position.tradeId,
                position.strategyType,
                position.legs,
                position.entryTime,
                exitTime,
                Map.copyOf(position.entryPrices),
                Map.copyOf(resolvedExitPrices),
                combinedPnl,
                position.strategyType.displayName() + " entry",
                exitReason);
    }

    // ── Market data helpers ───────────────────────────────────────────────

    /**
     * Compute simplified IV rank from ATM straddle price / underlying price.
     * This is a rough approximation: (ATM CE + ATM PE) / underlying × 100.
     */
    private double computeSimplifiedIvRank(OptionChainSnapshot chain, BigDecimal underlyingPrice) {
        if (chain.levels().isEmpty() || underlyingPrice.signum() == 0) return 50.0;

        // Find ATM level (closest to underlying price)
        OptionChainLevel atmLevel = null;
        BigDecimal minDist = null;
        for (OptionChainLevel level : chain.levels()) {
            BigDecimal dist = level.strike().subtract(underlyingPrice).abs();
            if (minDist == null || dist.compareTo(minDist) < 0) {
                minDist = dist;
                atmLevel = level;
            }
        }
        if (atmLevel == null) return 50.0;

        BigDecimal straddlePrice = atmLevel.callLastPrice().add(atmLevel.putLastPrice());
        if (straddlePrice.signum() == 0) return 50.0;

        // IV rank approximation: straddle / underlying × 100, scaled to 0-100 range
        double rawRank = straddlePrice.divide(underlyingPrice, MC)
                .multiply(HUNDRED, MC).doubleValue();
        // Scale: typical straddle/underlying ratio is 2-8%, map to 0-100
        return Math.min(100.0, Math.max(0.0, rawRank * 10));
    }

    /**
     * Estimate VIX from option chain: ATM straddle price as % of underlying.
     * Uses the standard approximation: IV ≈ straddle / (underlying × 0.798) × sqrt(365/DTE) × 100
     * For weekly options (DTE ≈ 5), the annualization factor is sqrt(365/5) ≈ 8.54.
     * The 0.798 factor comes from the Brenner-Subrahmanyam approximation.
     */
    private double estimateVixFromChain(OptionChainSnapshot chain, BigDecimal underlyingPrice) {
        if (chain.levels().isEmpty() || underlyingPrice.signum() == 0) return 15.0;

        OptionChainLevel atmLevel = null;
        BigDecimal minDist = null;
        for (OptionChainLevel level : chain.levels()) {
            BigDecimal dist = level.strike().subtract(underlyingPrice).abs();
            if (minDist == null || dist.compareTo(minDist) < 0) {
                minDist = dist;
                atmLevel = level;
            }
        }
        if (atmLevel == null) return 15.0;

        BigDecimal straddlePrice = atmLevel.callLastPrice().add(atmLevel.putLastPrice());
        if (straddlePrice.signum() <= 0) return 15.0;

        // Brenner-Subrahmanyam: σ ≈ straddle / (S × 0.798)
        // Then annualize: VIX ≈ σ × sqrt(365/DTE) × 100
        // For weekly DTE ≈ 5: sqrt(365/5) ≈ 8.544
        // Combined: VIX ≈ (straddle / (S × 0.798)) × 854.4
        double straddlePct = straddlePrice.divide(underlyingPrice, MC).doubleValue();
        double estimatedVix = (straddlePct / 0.798) * 854.4;

        // Clamp to realistic range — India VIX typically 10-35
        return Math.max(10.0, Math.min(45.0, estimatedVix));
    }

    /** Compute put-call ratio from option chain OI. */
    private double computePcr(OptionChainSnapshot chain) {
        long totalCallOI = 0;
        long totalPutOI = 0;
        for (OptionChainLevel level : chain.levels()) {
            totalCallOI += level.callOpenInterest();
            totalPutOI += level.putOpenInterest();
        }
        if (totalCallOI == 0) return 1.0;
        return (double) totalPutOI / totalCallOI;
    }

    /** Compute trend signal from 15-min EMA: +1 bullish, -1 bearish, 0 neutral. */
    private int computeTrendSignal(List<Candle> candles15m) {
        if (candles15m.size() < 22) return 0;
        List<BigDecimal> closes = candles15m.stream().map(Candle::close).toList();
        double ema9 = emaIndicator.calculate(closes, 9).doubleValue();
        double ema21 = emaIndicator.calculate(closes, 21).doubleValue();
        double diff = Math.abs(ema9 - ema21) / ema21 * 100;
        if (diff < 0.1) return 0; // range-bound
        return ema9 > ema21 ? 1 : -1;
    }

    /** Get latest underlying price from candle history. */
    private BigDecimal latestUnderlyingPrice(List<Candle> candles) {
        if (candles.isEmpty()) return BigDecimal.ZERO;
        return candles.getLast().close();
    }

    /** Check if strategy is a buying strategy (not selling). */
    private boolean isBuyingStrategy(StrategyType type) {
        return !type.isSellingStrategy();
    }


    // ── Data loading helpers ──────────────────────────────────────────────

    /**
     * Load underlying 5-min candles for a specific day from the CSV import path.
     * Falls back to empty list if file not found.
     */
    private List<Candle> loadUnderlyingCandlesForDay(TradingProperties properties,
                                                      UnderlyingSymbol underlying,
                                                      LocalDate day,
                                                      ZoneId tz) {
        try {
            Path csvPath = BacktestDataFileResolver.forTimeframe(
                    properties.backtest().csvImportPath(), Timeframe.FIVE_MINUTE);
            if (!Files.exists(csvPath)) {
                // Try the default import path
                csvPath = BacktestDataFileResolver.forSelection(
                        properties.backtest().csvImportPath(), underlying, OptionType.CE, Timeframe.FIVE_MINUTE);
            }
            if (!Files.exists(csvPath)) {
                log.debug("SpreadBacktest: no underlying candle CSV found for {}", day);
                return List.of();
            }
            Instant dayStart = day.atStartOfDay(tz).toInstant();
            Instant dayEnd = day.plusDays(1).atStartOfDay(tz).toInstant();
            return candleCsvReader.read(csvPath, Timeframe.FIVE_MINUTE).stream()
                    .filter(c -> !c.timestamp().isBefore(dayStart) && c.timestamp().isBefore(dayEnd))
                    .toList();
        } catch (Exception e) {
            log.debug("SpreadBacktest: failed to load underlying candles for {}: {}", day, e.getMessage());
            return List.of();
        }
    }

    /** Resolve the global-datafeeds by-day directory from config. */
    private Path globalDatafeedsByDayDirectory(TradingProperties properties) {
        Path importDir = Path.of(properties.backtest().csvImportPath()).getParent();
        if (importDir == null) {
            importDir = Path.of("C:/data/backtest/imports");
        }
        return importDir.resolve("global-datafeeds").resolve("by-day");
    }

    // ── Trading day helpers ───────────────────────────────────────────────

    /** Generate list of trading days (skip weekends and known holidays). */
    private List<LocalDate> tradingDays(LocalDate from, LocalDate to) {
        List<LocalDate> days = new ArrayList<>();
        LocalDate current = from;
        while (!current.isAfter(to)) {
            if (isTradingDay(current)) {
                days.add(current);
            }
            current = current.plusDays(1);
        }
        return days;
    }

    private boolean isTradingDay(LocalDate date) {
        DayOfWeek dow = date.getDayOfWeek();
        return dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY;
    }

    /** Estimate nearest weekly expiry from a given date. */
    private LocalDate estimateNearestExpiry(LocalDate date, IndexType indexType) {
        DayOfWeek expiryDay = indexType.expiryDay();
        LocalDate candidate = date;
        for (int i = 0; i < 7; i++) {
            if (candidate.getDayOfWeek() == expiryDay) {
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    // ── Candle aggregation ────────────────────────────────────────────────

    /** Aggregate candles to a target timeframe (e.g., 5-min → 15-min). */
    private List<Candle> aggregateCandles(List<Candle> candles, Timeframe targetTimeframe) {
        if (candles.isEmpty()) return List.of();
        List<Candle> aggregated = new ArrayList<>();
        long targetSeconds = targetTimeframe.duration().toSeconds();
        String instrumentKey = null;
        Instant bucketStart = null;
        BigDecimal open = null, high = null, low = null, close = null;
        long volume = 0L, openInterest = 0L;

        for (Candle candle : candles) {
            long bucketEpoch = (candle.timestamp().getEpochSecond() / targetSeconds) * targetSeconds;
            Instant currentBucket = Instant.ofEpochSecond(bucketEpoch);
            if (bucketStart == null || !bucketStart.equals(currentBucket)) {
                if (bucketStart != null) {
                    aggregated.add(new Candle(instrumentKey, bucketStart, targetTimeframe,
                            open, high, low, close, volume, openInterest));
                }
                instrumentKey = candle.instrumentKey();
                bucketStart = currentBucket;
                open = candle.open();
                high = candle.high();
                low = candle.low();
                close = candle.close();
                volume = candle.volume();
                openInterest = candle.openInterest();
                continue;
            }
            high = high.max(candle.high());
            low = low.min(candle.low());
            close = candle.close();
            volume += candle.volume();
            openInterest = candle.openInterest();
        }
        if (bucketStart != null) {
            aggregated.add(new Candle(instrumentKey, bucketStart, targetTimeframe,
                    open, high, low, close, volume, openInterest));
        }
        return List.copyOf(aggregated);
    }

    // ── Metrics computation ───────────────────────────────────────────────

    /** Compute BacktestMetrics from unified trades (reuses BacktestEngine's pattern). */
    private BacktestMetrics computeMetrics(List<BacktestTrade> trades, int totalSignals,
                                            int rejectedSignals, TradingProperties properties) {
        BigDecimal cumulativePnl = trades.stream().map(BacktestTrade::pnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<BacktestTrade> winners = trades.stream().filter(t -> t.pnl().signum() > 0).toList();
        List<BacktestTrade> losers = trades.stream().filter(t -> t.pnl().signum() < 0).toList();

        BigDecimal winRate = trades.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(winners.size()).multiply(HUNDRED, MC)
                .divide(BigDecimal.valueOf(trades.size()), MC);

        BigDecimal averageWin = averagePnl(winners);
        BigDecimal averageLoss = averagePnl(losers);
        BigDecimal expectancy = trades.isEmpty() ? BigDecimal.ZERO
                : cumulativePnl.divide(BigDecimal.valueOf(trades.size()), MC);

        BigDecimal grossWins = winners.stream().map(BacktestTrade::pnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal grossLosses = losers.stream().map(BacktestTrade::pnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add).abs();
        BigDecimal profitFactor = grossLosses.signum() == 0 ? BigDecimal.ZERO
                : grossWins.divide(grossLosses, MC);

        BigDecimal avgHoldMinutes = trades.isEmpty() ? BigDecimal.ZERO
                : BigDecimal.valueOf(trades.stream()
                .mapToLong(t -> Duration.between(t.entryTime(), t.exitTime()).toMinutes())
                .sum())
                .divide(BigDecimal.valueOf(trades.size()), MC);

        int maxConsecWins = 0, maxConsecLosses = 0, consecWins = 0, consecLosses = 0;
        for (BacktestTrade trade : trades) {
            if (trade.pnl().signum() > 0) {
                consecWins++;
                consecLosses = 0;
                maxConsecWins = Math.max(maxConsecWins, consecWins);
            } else if (trade.pnl().signum() < 0) {
                consecLosses++;
                consecWins = 0;
                maxConsecLosses = Math.max(maxConsecLosses, consecLosses);
            }
        }

        BigDecimal riskRewardRatio = averageLoss.abs().signum() == 0 ? BigDecimal.ZERO
                : averageWin.abs().divide(averageLoss.abs(), MC);

        BigDecimal signalConversion = totalSignals == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(trades.size()).multiply(HUNDRED, MC)
                .divide(BigDecimal.valueOf(totalSignals), MC);

        Map<String, BigDecimal> dailyPnl = computeDailyPnl(trades, properties.timezone());

        return new BacktestMetrics(
                trades.size(), winRate, averageWin, averageLoss, expectancy,
                computeMaxDrawdown(trades), cumulativePnl, dailyPnl,
                totalSignals, rejectedSignals, 0,
                profitFactor, avgHoldMinutes, maxConsecWins, maxConsecLosses,
                riskRewardRatio, signalConversion);
    }

    private BigDecimal averagePnl(List<BacktestTrade> trades) {
        if (trades.isEmpty()) return BigDecimal.ZERO;
        return trades.stream().map(BacktestTrade::pnl).reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(trades.size()), MC);
    }

    private Map<String, BigDecimal> computeDailyPnl(List<BacktestTrade> trades, ZoneId tz) {
        Map<String, BigDecimal> daily = new LinkedHashMap<>();
        for (BacktestTrade trade : trades) {
            LocalDate date = LocalDate.ofInstant(trade.exitTime(), tz);
            daily.merge(date.toString(), trade.pnl(), BigDecimal::add);
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(daily));
    }

    private BigDecimal computeMaxDrawdown(List<BacktestTrade> trades) {
        BigDecimal equity = BigDecimal.ZERO;
        BigDecimal peak = BigDecimal.ZERO;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (BacktestTrade trade : trades) {
            equity = equity.add(trade.pnl());
            peak = peak.max(equity);
            BigDecimal drawdown = peak.subtract(equity);
            maxDrawdown = maxDrawdown.max(drawdown);
        }
        return maxDrawdown;
    }

    // ── Empty result helper ───────────────────────────────────────────────

    private SpreadBacktestResult emptyResult(StrategyType strategyType) {
        return new SpreadBacktestResult(
                List.of(),
                new BacktestMetrics(0, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Map.of()),
                0, 0, Map.of(), Map.of(strategyType.name(), "no-data"));
    }

    // ── Open position state ───────────────────────────────────────────────

    /**
     * Mutable state for an open spread position during the replay loop.
     */
    private static class OpenSpreadPosition {
        final String tradeId;
        final StrategyType strategyType;
        final List<SpreadLeg> legs;
        final Map<String, BigDecimal> entryPrices;
        final Instant entryTime;
        Map<String, BigDecimal> currentPrices;
        final BigDecimal entryNetDebit;
        BigDecimal bestPnl;

        OpenSpreadPosition(String tradeId, StrategyType strategyType, List<SpreadLeg> legs,
                           Map<String, BigDecimal> entryPrices, Instant entryTime,
                           Map<String, BigDecimal> currentPrices, BigDecimal entryNetDebit,
                           BigDecimal bestPnl) {
            this.tradeId = tradeId;
            this.strategyType = strategyType;
            this.legs = legs;
            this.entryPrices = entryPrices;
            this.entryTime = entryTime;
            this.currentPrices = currentPrices;
            this.entryNetDebit = entryNetDebit;
            this.bestPnl = bestPnl;
        }

        OpenSpreadPosition withCurrentPrices(Map<String, BigDecimal> prices) {
            this.currentPrices = new HashMap<>(prices);
            return this;
        }
    }

    // ── No-op ExecutionFlowTracker ────────────────────────────────────────

    /**
     * No-op implementation used when no tracker is provided.
     * ExecutionFlowTracker will be created in task 3 — this bridges the gap.
     */
    static class NoOpFlowTracker extends ExecutionFlowTracker {
        @Override
        public void recordHit(String branchName) { /* no-op */ }

        @Override
        public void recordHit(String branchName, String detail) { /* no-op */ }
    }
}
