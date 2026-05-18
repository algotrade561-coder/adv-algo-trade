package com.algo.trade.strategy.oimomentum;

import com.algo.trade.domain.*;
import com.algo.trade.execution.ExecutionEngine;
import com.algo.trade.execution.TradingStateService;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.LiveInstrumentCache;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import com.algo.trade.risk.MarketGuard;
import com.algo.trade.strategy.SignalRecordContext;
import com.algo.trade.strategy.StrategySignalCsvRecorder;
import com.algo.trade.strategy.StrategyType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.*;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * OI Momentum Strategy — 1-second live tracking with OI + PCR + Momentum confluence.
 *
 * Targets 15-20 trades/day with high signal quality.
 * Runs on its own ScheduledExecutorService, independent of candle-close cycle.
 *
 * Entry Cases:
 *   Case 1: Momentum + OI + PCR align → ENTER (highest conviction)
 *   Case 2: Momentum + PCR align, no OI data → ENTER
 *   Case 3: Momentum + OI align, PCR neutral → ENTER
 *   Case 4: Conflict → SKIP (no hedge mode)
 *   Case 5: PCR conflicts, no OI → SKIP
 *
 * Position Management:
 *   - Max 1 position at a time (sequential)
 *   - Reverse on OI flip (max 3 reversals/day)
 *   - Dynamic trailing stop
 *   - Squareoff at 15:10
 */
@Service
public class OIMomentumStrategy {

    private static final Logger log = LoggerFactory.getLogger(OIMomentumStrategy.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final OIMomentumConfig config;
    private final TickMomentumDetector momentumDetector;
    private final LiveInstrumentCache liveInstrumentCache;
    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final ExecutionEngine executionEngine;
    private final TradingStateService tradingStateService;
    private final TradeRepository tradeRepository;
    private final MarketGuard marketGuard;
    private final ExpiryCalendar expiryCalendar;
    private final StrategySignalCsvRecorder signalCsvRecorder;

    // ── State ──
    private volatile String activeTradeId = null;
    private volatile int activeDirection = 0; // 1=bullish(CE), -1=bearish(PE), 0=flat
    private volatile Instant lastEntryTime = null;
    private volatile Instant lastSlTime = null;
    private volatile Instant lastReversalTime = null; // P3 #25: reversal cooldown
    private volatile double peakPrice = 0;

    /** P0 #1: Track last OI sample to avoid re-triggering on stale data. */
    private volatile long lastOiCeChange = Long.MIN_VALUE;
    private volatile long lastOiPeChange = Long.MIN_VALUE;
    private volatile boolean oiAdvanced = false;

    /** P1 #8: Cached config to avoid DB hit every tick. */
    private volatile com.algo.trade.strategy.StrategyConfig cachedConfig = null;
    private volatile Instant cachedConfigTime = null;
    private static final Duration CONFIG_CACHE_TTL = Duration.ofSeconds(30);

    /** P1 #10: Consecutive error tracking. */
    private final AtomicInteger consecutiveErrors = new AtomicInteger(0);
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    /** Real pause: strategy stops ticking until this time passes. */
    private volatile Instant pausedUntil = null;
    private static final Duration ERROR_PAUSE_DURATION = Duration.ofMinutes(5);

    /** P2 #24: Daily P&L tracking. */
    private volatile double dailyPnl = 0;

    /** Parsed midday times (P2 #20: avoid parsing every tick). */
    private volatile LocalTime middayStart = null;
    private volatile LocalTime middayEnd = null;

    /** Instrument key of a pending entry (order accepted but not yet filled). */
    private volatile String pendingEntryInstrumentKey = null;

    // ── Daily counters (reset on startup / new day) ──
    private final AtomicInteger tradesToday = new AtomicInteger(0);
    private final AtomicInteger reversalsToday = new AtomicInteger(0);
    private final AtomicInteger consecutiveLosses = new AtomicInteger(0);
    private volatile LocalDate currentDay = null;

    // ── Executor ──
    private ScheduledExecutorService executor;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.monitoring.SchedulerRegistry schedulerRegistry;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.algo.trade.notification.TelegramAlertService telegramAlertService;

    @org.springframework.beans.factory.annotation.Autowired
    private com.algo.trade.strategy.StrategyConfigService strategyConfigService;

    public OIMomentumStrategy(OIMomentumConfig config,
                               TickMomentumDetector momentumDetector,
                               LiveInstrumentCache liveInstrumentCache,
                               InstrumentCache instrumentCache,
                               MarketDataService marketDataService,
                               ExecutionEngine executionEngine,
                               TradingStateService tradingStateService,
                               TradeRepository tradeRepository,
                               MarketGuard marketGuard,
                               ExpiryCalendar expiryCalendar,
                               StrategySignalCsvRecorder signalCsvRecorder) {
        this.config = config;
        this.momentumDetector = momentumDetector;
        this.liveInstrumentCache = liveInstrumentCache;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.executionEngine = executionEngine;
        this.tradingStateService = tradingStateService;
        this.tradeRepository = tradeRepository;
        this.marketGuard = marketGuard;
        this.expiryCalendar = expiryCalendar;
        this.signalCsvRecorder = signalCsvRecorder;
    }

    @jakarta.annotation.PostConstruct
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "oi-momentum-loop");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::tick, 5, 1, TimeUnit.SECONDS);
        if (schedulerRegistry != null) {
            schedulerRegistry.register("oiMomentum", "OI Momentum 1-sec strategy loop", 1000, () -> {});
        }
        // Reconcile state from DB on startup
        reconcileFromDb();
        log.info("[OIMomentum] Started 1-second execution loop (enabled/paper controlled from UI)");
    }

    /**
     * Restore in-memory state from DB after restart:
     * - Find any open OI_MOMENTUM trade → set activeTradeId
     * - Count today's closed OI_MOMENTUM trades → set tradesToday
     * - Count today's consecutive losses → set consecutiveLosses
     */
    private void reconcileFromDb() {
        try {
            LocalDate today = LocalDate.now(IST);
            currentDay = today;

            // Find open OI_MOMENTUM trade
            var openTrades = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                    .toList();
            if (!openTrades.isEmpty()) {
                TradeEntity openTrade = openTrades.getFirst();
                activeTradeId = openTrade.getTradeId();
                activeDirection = "CE".equals(openTrade.getOptionType()) ? 1 : -1;
                peakPrice = openTrade.getPeakPrice() != null ? openTrade.getPeakPrice().doubleValue() : openTrade.getEntryPrice().doubleValue();
                lastEntryTime = openTrade.getEntryTime();
                log.info("[OIMomentum] Reconciled open trade: {} direction={}", activeTradeId, activeDirection);
            }

            // Count today's OI_MOMENTUM trades
            Instant dayStart = today.atStartOfDay(IST).toInstant();
            Instant dayEnd = today.plusDays(1).atStartOfDay(IST).toInstant();
            var todayTrades = tradeRepository.findByEntryTimeBetween(dayStart, dayEnd).stream()
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                    .toList();
            tradesToday.set(todayTrades.size());

            // Count consecutive losses (from most recent trades backwards)
            int losses = 0;
            var closedToday = todayTrades.stream()
                    .filter(t -> t.getStatus() == TradeStatus.CLOSED)
                    .sorted((a, b) -> b.getExitTime().compareTo(a.getExitTime()))
                    .toList();
            for (var t : closedToday) {
                if (t.getRealizedPnl() != null && t.getRealizedPnl().signum() < 0) losses++;
                else break;
            }
            consecutiveLosses.set(losses);

            if (tradesToday.get() > 0 || activeTradeId != null) {
                log.info("[OIMomentum] Reconciled: tradesToday={}, consecutiveLosses={}, activeTradeId={}",
                        tradesToday.get(), losses, activeTradeId);
            }
        } catch (Exception e) {
            log.warn("[OIMomentum] Reconcile failed: {}", e.getMessage());
        }
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        shuttingDown = true;
        if (executor != null) {
            executor.shutdown(); // Let in-flight tick finish
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.info("[OIMomentum] Stopped");
        }
    }

    private volatile boolean shuttingDown = false;

    /**
     * Main tick — runs every 1 second. Non-blocking, exception-safe.
     */
    private void tick() {
        try {
            if (shuttingDown) return;
            if (!isMarketHours()) return;
            if (tradingStateService.killSwitchEnabled()) return;
            if (!tradingStateService.running()) return;

            // P1 #10: Real pause — strategy stops until pausedUntil passes
            if (pausedUntil != null) {
                if (Instant.now().isBefore(pausedUntil)) {
                    return; // Still paused
                }
                pausedUntil = null; // Pause expired, resume
                log.info("[OIMomentum] Error pause expired — resuming");
            }

            // P1 #8: Cache config to avoid DB hit every tick
            var dbConfig = getCachedConfig();
            if (dbConfig == null || !dbConfig.isEnabled()) return;

            // P2 #24: Daily P&L cap check
            if (dailyPnl < -config.getStopLossPercent() * 5) {
                log.debug("[OIMomentum] Daily P&L cap breached (₹{}), pausing", dailyPnl);
                return;
            }

            // Reset daily counters on new day
            LocalDate today = LocalDate.now(IST);
            if (!today.equals(currentDay)) {
                currentDay = today;
                tradesToday.set(0);
                reversalsToday.set(0);
                consecutiveLosses.set(0);
                dailyPnl = 0;
                // Don't clear activeTradeId — let reconcileFromDb handle it (P3 #27)
                if (activeTradeId == null) {
                    activeDirection = 0;
                    peakPrice = 0;
                }
                // Parse midday times once per day (P2 #20)
                middayStart = LocalTime.parse(config.getMiddayStart());
                middayEnd = LocalTime.parse(config.getMiddayEnd());
            }

            // Feed momentum detector
            momentumDetector.tick(IndexType.NIFTY);

            // P0 #1: Check if OI has advanced since last evaluation
            double spot = liveInstrumentCache.getFuturesPrice(IndexType.NIFTY);
            if (spot > 0) {
                int atm = IndexType.NIFTY.roundToATM(spot);
                long[] oiChange = liveInstrumentCache.getAtmOiChange(IndexType.NIFTY, atm, 3, 3);
                oiAdvanced = (oiChange[0] != lastOiCeChange || oiChange[1] != lastOiPeChange);
                if (oiAdvanced) {
                    lastOiCeChange = oiChange[0];
                    lastOiPeChange = oiChange[1];
                }
            }

            // Position management runs every tick (price-based exits are real-time)
            // First: resolve pending entry if order was accepted but tradeId not yet available
            if (activeTradeId == null && pendingEntryInstrumentKey != null) {
                var found = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                        .filter(t -> pendingEntryInstrumentKey.equals(t.getInstrumentKey()))
                        .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType()))
                        .findFirst();
                if (found.isPresent()) {
                    activeTradeId = found.get().getTradeId();
                    peakPrice = found.get().getEntryPrice().doubleValue();
                    pendingEntryInstrumentKey = null;
                    log.info("[OIMomentum] Pending entry resolved: tradeId={}", activeTradeId);
                }
                // If still not found after 30s, give up
                if (pendingEntryInstrumentKey != null && lastEntryTime != null
                        && Duration.between(lastEntryTime, Instant.now()).getSeconds() > 30) {
                    log.warn("[OIMomentum] Pending entry timed out for {} — giving up", pendingEntryInstrumentKey);
                    pendingEntryInstrumentKey = null;
                    activeDirection = 0;
                }
            }

            if (activeTradeId != null) {
                managePosition();
            } else {
                // P0 #1: Only evaluate OI-based entry when OI actually changed
                // Price momentum detection still runs every tick (inside detectEntry)
                detectEntry();
            }

            if (schedulerRegistry != null) schedulerRegistry.recordRun("oiMomentum");
            consecutiveErrors.set(0); // Reset on success
        } catch (Exception e) {
            int errors = consecutiveErrors.incrementAndGet();
            log.warn("[OIMomentum] Tick error (consecutive={}): {}", errors, e.getMessage(), e);
            if (errors >= MAX_CONSECUTIVE_ERRORS) {
                pausedUntil = Instant.now().plus(ERROR_PAUSE_DURATION);
                consecutiveErrors.set(0);
                log.error("[OIMomentum] {} consecutive errors — PAUSING for {} minutes",
                        MAX_CONSECUTIVE_ERRORS, ERROR_PAUSE_DURATION.toMinutes());
                if (telegramAlertService != null) {
                    telegramAlertService.systemAlert(String.format(
                            "🚨 OIMomentum: %d consecutive errors — PAUSED for %d min\nLast: %s",
                            MAX_CONSECUTIVE_ERRORS, ERROR_PAUSE_DURATION.toMinutes(), e.getMessage()));
                }
            }
        }
    }

    /** P1 #8: Cache strategy config for 30 seconds to avoid DB hit every tick. */
    private com.algo.trade.strategy.StrategyConfig getCachedConfig() {
        Instant now = Instant.now();
        if (cachedConfig != null && cachedConfigTime != null
                && Duration.between(cachedConfigTime, now).compareTo(CONFIG_CACHE_TTL) < 0) {
            return cachedConfig;
        }
        cachedConfig = strategyConfigService.getConfig(StrategyType.OI_MOMENTUM, "NIFTY");
        cachedConfigTime = now;
        return cachedConfig;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ENTRY DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void detectEntry() {
        // ── Throttle checks ──
        if (tradesToday.get() >= config.getMaxTradesPerDay()) return;
        if (consecutiveLosses.get() >= config.getConsecutiveLossPause()) {
            log.debug("[OIMomentum] Paused: {} consecutive losses", consecutiveLosses.get());
            return;
        }
        if (lastSlTime != null && Duration.between(lastSlTime, Instant.now()).getSeconds() < config.getCooldownAfterSlSeconds()) {
            return; // Cooldown after SL
        }

        // ── Midday reduction ──
        LocalTime now = LocalTime.now(IST);
        if (isMidday(now) && tradesToday.get() >= config.getSoftTargetTradesPerDay() / 2) {
            return; // Reduce entries during midday
        }

        // ── Squareoff window — no new entries ──
        if (now.isAfter(LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute()).minusMinutes(10))) {
            return;
        }

        // ── MarketGuard safety: VIX, circuit breaker, event day ──
        if (!marketGuard.isSafeForLongPremium()) {
            return; // VIX too high, circuit breaker, or event day
        }

        // ── Event Spike Detection (highest priority) ──
        TickMomentumDetector.MomentumSignal spike = momentumDetector.detectSpike(
                IndexType.NIFTY, config.getSpikeThresholdPercent());
        if (spike.isPresent()) {
            log.info("[OIMomentum] EVENT SPIKE detected: direction={}, magnitude={}%, spot={}",
                    spike.direction(), spike.magnitude(), spike.spotPrice());
            enter(spike.direction(), "SPIKE:" + spike.type(), spike.spotPrice());
            return;
        }

        // ── Momentum Detection ──
        TickMomentumDetector.MomentumSignal momentum = momentumDetector.detect(
                IndexType.NIFTY, config.getMomentumThresholdPercent());
        if (!momentum.isPresent()) return; // No momentum — wait

        // ── OI Analysis (only when OI has actually advanced — P0 #1) ──
        double spot = momentum.spotPrice();
        int atm = IndexType.NIFTY.roundToATM(spot);
        long ceOiChange = 0;
        long peOiChange = 0;
        boolean oiAvailable = false;
        int oiDirection = 0;

        if (oiAdvanced) {
            long[] oiChange = liveInstrumentCache.getAtmOiChange(IndexType.NIFTY, atm, 3, 3);
            ceOiChange = oiChange[0];
            peOiChange = oiChange[1];
            oiAvailable = (ceOiChange != 0 || peOiChange != 0);
            if (oiAvailable) {
                if (peOiChange > ceOiChange && peOiChange > 0) oiDirection = 1;
                else if (ceOiChange > peOiChange && ceOiChange > 0) oiDirection = -1;
            }
        }
        // When OI hasn't advanced, oiAvailable=false → entry matrix uses Case 2 (momentum+PCR only)

        // ── PCR Analysis ──
        double pcr = liveInstrumentCache.getRealtimePcr(IndexType.NIFTY);
        int pcrDirection = 0;
        if (pcr >= config.getPcrBullishThreshold()) pcrDirection = 1;      // Bullish
        else if (pcr <= config.getPcrBearishThreshold()) pcrDirection = -1; // Bearish
        // else neutral (0)

        // ── Entry Decision Matrix ──
        int entryDirection = evaluateEntryCase(momentum.direction(), oiDirection, pcrDirection, oiAvailable);
        if (entryDirection != 0) {
            String reason = String.format("M:%s OI:%d PCR:%.2f(%d) case=%s",
                    momentum.type(), oiDirection, pcr, pcrDirection,
                    describeCase(momentum.direction(), oiDirection, pcrDirection, oiAvailable));
            enter(entryDirection, reason, spot);
        }
    }

    private int evaluateEntryCase(int momentumDir, int oiDir, int pcrDir, boolean oiAvailable) {
        // Case 1: All three align
        if (oiAvailable && oiDir == momentumDir && pcrDir == momentumDir) {
            return momentumDir; // Highest conviction
        }
        // Case 2: Momentum + PCR align, no OI
        if (!oiAvailable && pcrDir == momentumDir && pcrDir != 0) {
            return momentumDir;
        }
        // Case 3: Momentum + OI align, PCR neutral
        if (oiAvailable && oiDir == momentumDir && pcrDir == 0) {
            return momentumDir;
        }
        // Case 4: Conflict (OI vs momentum) → SKIP
        if (oiAvailable && oiDir != 0 && oiDir != momentumDir) {
            return 0;
        }
        // Case 5: PCR conflicts, no OI → SKIP
        if (!oiAvailable && pcrDir != 0 && pcrDir != momentumDir) {
            return 0;
        }
        return 0; // Default: no entry
    }

    private String describeCase(int mDir, int oiDir, int pcrDir, boolean oiAvail) {
        if (oiAvail && oiDir == mDir && pcrDir == mDir) return "CASE1_ALL_ALIGN";
        if (!oiAvail && pcrDir == mDir) return "CASE2_M+PCR";
        if (oiAvail && oiDir == mDir && pcrDir == 0) return "CASE3_M+OI";
        if (oiAvail && oiDir != 0 && oiDir != mDir) return "CASE4_CONFLICT";
        return "CASE5_SKIP";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // POSITION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private void managePosition() {
        TradeEntity trade = tradeRepository.findById(activeTradeId).orElse(null);
        if (trade == null || trade.getStatus() != TradeStatus.OPEN) {
            activeTradeId = null;
            activeDirection = 0;
            peakPrice = 0;
            return;
        }

        // Get current price
        Optional<Quote> quoteOpt = marketDataService.quote(trade.getInstrumentKey());
        if (quoteOpt.isEmpty()) return;
        Quote quote = quoteOpt.get();
        double currentPrice = quote.lastPrice().doubleValue();
        if (currentPrice <= 0) return;

        // P0 #5: Quote staleness check — force exit if quote is older than 60s
        if (quote.timestamp() != null
                && Duration.between(quote.timestamp(), Instant.now()).getSeconds() > 60) {
            log.warn("[OIMomentum] Stale quote ({}s old) for {} — force closing",
                    Duration.between(quote.timestamp(), Instant.now()).getSeconds(), trade.getInstrumentKey());
            closePosition(trade, currentPrice, "STALE_QUOTE_FORCE_EXIT");
            return;
        }

        double entryPrice = trade.getEntryPrice().doubleValue();

        // P0 #3: Track and persist peak price
        if (currentPrice > peakPrice) {
            peakPrice = currentPrice;
            // Persist peak to DB so it survives JVM restart
            if (trade.getPeakPrice() == null || BigDecimal.valueOf(peakPrice).compareTo(trade.getPeakPrice()) > 0) {
                trade.setPeakPrice(BigDecimal.valueOf(peakPrice));
                tradeRepository.save(trade);
            }
        }

        double profitPct = (currentPrice - entryPrice) / entryPrice * 100;
        double peakPct = (peakPrice - entryPrice) / entryPrice * 100;

        // P2 #18: Squareoff time — use !isBefore for inclusive check
        LocalTime now = LocalTime.now(IST);
        if (!now.isBefore(LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute()))) {
            closePosition(trade, currentPrice, "SQUAREOFF_TIME");
            updateDailyPnl(profitPct, trade);
            return;
        }

        // P2 #17: Stop Loss fires REGARDLESS of minimum hold time (protects against real moves)
        if (profitPct <= -config.getStopLossPercent()) {
            closePosition(trade, currentPrice, "STOP_LOSS");
            lastSlTime = Instant.now();
            consecutiveLosses.incrementAndGet();
            updateDailyPnl(profitPct, trade);
            return;
        }

        // ── Minimum hold time (gates target/trailing/reversal, NOT SL) ──
        if (lastEntryTime != null && Duration.between(lastEntryTime, Instant.now()).getSeconds() < config.getMinimumHoldTimeSeconds()) {
            return;
        }

        // ── Target ──
        if (profitPct >= config.getTargetPercent()) {
            closePosition(trade, currentPrice, "TARGET");
            consecutiveLosses.set(0);
            updateDailyPnl(profitPct, trade);
            return;
        }

        // ── Trailing Stop ──
        if (peakPct >= config.getTrailingActivationPercent()) {
            double trailLevel = peakPct - config.getTrailingGapPercent();
            if (profitPct < trailLevel) {
                closePosition(trade, currentPrice, "TRAILING_STOP");
                if (profitPct > 0) consecutiveLosses.set(0);
                else consecutiveLosses.incrementAndGet();
                updateDailyPnl(profitPct, trade);
                return;
            }
        }

        // ── Reverse on OI flip (only when OI actually advanced) ──
        if (oiAdvanced && reversalsToday.get() < config.getMaxReversalsPerDay()) {
            // P3 #25: Reversal cooldown — min 60s between reversals
            if (lastReversalTime != null && Duration.between(lastReversalTime, Instant.now()).getSeconds() < 60) {
                return;
            }

            double spot = momentumDetector.getSpot(IndexType.NIFTY);
            int atm = IndexType.NIFTY.roundToATM(spot);
            long[] oiChange = liveInstrumentCache.getAtmOiChange(IndexType.NIFTY, atm, 3, 3);
            long ceOiChange = oiChange[0];
            long peOiChange = oiChange[1];

            boolean oiFlipped = false;
            if (activeDirection == 1 && ceOiChange > peOiChange && ceOiChange > 5000) {
                oiFlipped = true;
            } else if (activeDirection == -1 && peOiChange > ceOiChange && peOiChange > 5000) {
                oiFlipped = true;
            }

            if (oiFlipped && profitPct < 5) {
                closePosition(trade, currentPrice, "OI_FLIP_REVERSE");
                updateDailyPnl(profitPct, trade);
                reversalsToday.incrementAndGet();
                lastReversalTime = Instant.now();
                // P0 #2: Route reversal through full entry gates (not direct enter())
                int newDirection = activeDirection * -1;
                // Re-fetch spot for fresh price
                double freshSpot = liveInstrumentCache.getFuturesPrice(IndexType.NIFTY);
                if (freshSpot > 0) {
                    activeDirection = 0; // Reset so detectEntry can fire
                    // Simulate entry via detectEntry path with forced direction
                    enterWithGates(newDirection, "REVERSE:OI_FLIP", freshSpot);
                }
            }
        }
    }

    /** P2 #24: Track daily P&L for drawdown cap. */
    private void updateDailyPnl(double profitPct, TradeEntity trade) {
        double pnl = (profitPct / 100.0) * trade.getEntryPrice().doubleValue() * trade.getQuantity();
        dailyPnl += pnl;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void enter(int direction, String reason, double spot) {
        if (activeTradeId != null) return; // Already in a position
        enterWithGates(direction, reason, spot);
    }

    /**
     * P0 #2: All entry paths (including reversals) go through this method
     * which enforces all throttle/safety gates.
     */
    private void enterWithGates(int direction, String reason, double spot) {
        if (activeTradeId != null) return;

        // Re-check all entry gates (P0 #2: reversals must pass these too)
        if (tradesToday.get() >= config.getMaxTradesPerDay()) {
            log.debug("[OIMomentum] Reversal blocked: max trades/day reached");
            return;
        }
        if (consecutiveLosses.get() >= config.getConsecutiveLossPause()) {
            log.debug("[OIMomentum] Reversal blocked: consecutive losses pause");
            return;
        }
        if (lastSlTime != null && Duration.between(lastSlTime, Instant.now()).getSeconds() < config.getCooldownAfterSlSeconds()) {
            log.debug("[OIMomentum] Reversal blocked: SL cooldown active");
            return;
        }
        // P2 #19: Allow event spikes to bypass MarketGuard, but block normal entries
        if (!reason.startsWith("SPIKE:") && !marketGuard.isSafeForLongPremium()) {
            log.debug("[OIMomentum] Entry blocked by MarketGuard");
            return;
        }
        LocalTime now = LocalTime.now(IST);
        if (!now.isBefore(LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute()).minusMinutes(10))) {
            log.debug("[OIMomentum] Entry blocked: too close to squareoff");
            return;
        }

        // Check DB config for paper/live mode
        var dbConfig = getCachedConfig();
        boolean paperMode = (dbConfig != null) ? dbConfig.isPaperTrading() : config.isPaperTrading();

        IndexType indexType = IndexType.NIFTY;
        int atm = indexType.roundToATM(spot);
        OptionType optType = direction > 0 ? OptionType.CE : OptionType.PE;
        LocalDate expiry = expiryCalendar.getCurrentWeeklyExpiry(indexType);

        Optional<Instrument> instOpt = instrumentCache.findOption(
                UnderlyingSymbol.NIFTY, expiry, BigDecimal.valueOf(atm), optType);
        if (instOpt.isEmpty()) {
            log.debug("[OIMomentum] Cannot resolve option: atm={}, type={}", atm, optType);
            return;
        }

        String instrumentKey = instOpt.get().instrumentKey();
        Optional<Quote> quoteOpt = marketDataService.quote(instrumentKey);
        if (quoteOpt.isEmpty() || quoteOpt.get().lastPrice().signum() <= 0) {
            log.debug("[OIMomentum] No quote for {}", instrumentKey);
            return;
        }

        // P1 #7: Bid-ask spread / liquidity check
        Quote entryQuote = quoteOpt.get();
        if (entryQuote.bid().isPresent() && entryQuote.ask().isPresent()
                && entryQuote.ask().get().signum() > 0 && entryQuote.bid().get().signum() > 0) {
            double mid = entryQuote.bid().get().add(entryQuote.ask().get())
                    .divide(BigDecimal.valueOf(2), java.math.MathContext.DECIMAL64).doubleValue();
            double spreadPct = (entryQuote.ask().get().doubleValue() - entryQuote.bid().get().doubleValue()) / mid * 100;
            if (spreadPct > 5.0) {
                log.debug("[OIMomentum] Entry rejected: bid-ask spread {}% > 5% for {}", spreadPct, instrumentKey);
                return;
            }
        }

        BigDecimal premium = quoteOpt.get().lastPrice();
        int lotSize = indexType.lotSize();

        // Build decision for execution
        StrategyDecision decision = new StrategyDecision(
                Instant.now(), UnderlyingSymbol.NIFTY,
                direction > 0 ? SignalType.BUY_CE : SignalType.BUY_PE,
                BigDecimal.valueOf(spot),
                Optional.of(premium), Optional.empty(),
                Optional.of(lotSize), Optional.of(premium.multiply(BigDecimal.valueOf(lotSize))),
                Optional.of(instrumentKey), Optional.of(BigDecimal.valueOf(atm)),
                Optional.of(optType), false, Optional.empty(), false,
                BigDecimal.ZERO,
                java.util.List.of("OI_MOMENTUM: " + reason)
        );

        recordOiBuySignal(decision, entryQuote, instrumentKey, atm, spreadPct(entryQuote), reason);

        if (paperMode) {
            var oiConfig = getCachedConfig();
            var result = executionEngine.executePaperEntry(decision, premium, lotSize, oiConfig);
            activeTradeId = result.tradeId().orElse(null);
        } else {
            var oiConfig = getCachedConfig();
            var result = executionEngine.executeEntry(decision, premium, lotSize, oiConfig);
            activeTradeId = result.tradeId().orElse(null);
            // If order accepted but tradeId not yet available (limit order pending fill),
            // poll for the trade on subsequent ticks via reconciliation in managePosition()
            if (activeTradeId == null && result.accepted()) {
                // Store instrument key so tick() can find the trade once watchdog creates it
                pendingEntryInstrumentKey = instrumentKey;
                activeDirection = direction;
                lastEntryTime = Instant.now();
                peakPrice = premium.doubleValue();
                tradesToday.incrementAndGet();
                log.info("[OIMomentum] ENTRY PENDING: order accepted, waiting for fill — instrument={}, reason={}",
                        instrumentKey, reason);
                return;
            }
        }

        if (activeTradeId != null) {
            activeDirection = direction;
            lastEntryTime = Instant.now();
            peakPrice = premium.doubleValue();
            tradesToday.incrementAndGet();
            log.info("[OIMomentum] ENTRY: direction={}, instrument={}, premium=₹{}, reason={}, trades={}",
                    direction > 0 ? "BULLISH" : "BEARISH", instrumentKey, premium, reason, tradesToday.get());
            if (telegramAlertService != null) {
                telegramAlertService.systemAlert(String.format(
                        "🎯 OIMomentum Entry: %s %s | ₹%.2f | %s | Trade #%d",
                        direction > 0 ? "BUY CE" : "BUY PE", instrumentKey,
                        premium.doubleValue(), reason, tradesToday.get()));
            }
        }
    }

    private void recordOiBuySignal(StrategyDecision decision,
                                   Quote entryQuote,
                                   String instrumentKey,
                                   int atm,
                                   Double bidAskSpread,
                                   String reason) {
        try {
            signalCsvRecorder.recordUnified(SignalRecordContext.builder()
                    .strategyType(StrategyType.OI_MOMENTUM.name())
                    .underlying(UnderlyingSymbol.NIFTY)
                    .decision(decision)
                    .selectedOptionQuote(entryQuote)
                    .selectedInstrumentKey(instrumentKey)
                    .selectedStrike(BigDecimal.valueOf(atm))
                    .breakoutPassed(true)
                    .oiPassed(oiAdvanced || reason.toUpperCase().contains("OI"))
                    .ivPassed(true)
                    .liquidityPassed(true)
                    .timePassed(true)
                    .bidAskSpread(bidAskSpread)
                    .executed(true)
                    .executionStage("SIGNAL_EMITTED")
                    .build());
        } catch (Exception ex) {
            log.warn("[OIMomentum] Failed to record BUY signal for tuning: {}", ex.getMessage());
        }
    }

    private Double spreadPct(Quote quote) {
        try {
            if (quote.bid().isEmpty() || quote.ask().isEmpty()
                    || quote.bid().get().signum() <= 0 || quote.ask().get().signum() <= 0) {
                return null;
            }
            double mid = quote.bid().get().add(quote.ask().get())
                    .divide(BigDecimal.valueOf(2), java.math.MathContext.DECIMAL64).doubleValue();
            if (mid <= 0) {
                return null;
            }
            return (quote.ask().get().doubleValue() - quote.bid().get().doubleValue()) / mid * 100;
        } catch (Exception ex) {
            return null;
        }
    }

    private void closePosition(TradeEntity trade, double currentPrice, String reason) {
        try {
            executionEngine.closeTrade(trade.getTradeId(), BigDecimal.valueOf(currentPrice), reason);
            double pnl = (currentPrice - trade.getEntryPrice().doubleValue()) * trade.getQuantity();
            log.info("[OIMomentum] EXIT: tradeId={}, reason={}, pnl=₹{}", trade.getTradeId(), reason, pnl);
            if (telegramAlertService != null) {
                telegramAlertService.systemAlert(String.format(
                        "📤 OIMomentum Exit: %s | ₹%.2f → ₹%.2f | P&L ₹%.0f | %s",
                        trade.getInstrumentKey(), trade.getEntryPrice().doubleValue(),
                        currentPrice, pnl, reason));
            }
        } catch (Exception e) {
            log.warn("[OIMomentum] Close failed: {}", e.getMessage());
        }
        activeTradeId = null;
        activeDirection = 0;
        peakPrice = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(IST);
        return now.isAfter(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(16, 30));
    }

    private boolean isMidday(LocalTime now) {
        // Use cached parsed times (parsed once per day in tick())
        if (middayStart == null || middayEnd == null) {
            middayStart = LocalTime.parse(config.getMiddayStart());
            middayEnd = LocalTime.parse(config.getMiddayEnd());
        }
        return now.isAfter(middayStart) && now.isBefore(middayEnd);
    }

    // ── Status API ──

    public java.util.Map<String, Object> getStatus() {
        return java.util.Map.of(
                "enabled", config.isEnabled(),
                "paperTrading", config.isPaperTrading(),
                "tradesToday", tradesToday.get(),
                "reversalsToday", reversalsToday.get(),
                "consecutiveLosses", consecutiveLosses.get(),
                "activeTradeId", activeTradeId != null ? activeTradeId : "",
                "activeDirection", activeDirection == 1 ? "BULLISH" : activeDirection == -1 ? "BEARISH" : "FLAT",
                "maxTradesPerDay", config.getMaxTradesPerDay()
        );
    }
}
