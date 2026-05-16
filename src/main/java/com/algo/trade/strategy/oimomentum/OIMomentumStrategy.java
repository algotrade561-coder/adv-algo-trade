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

    // ── State ──
    private volatile String activeTradeId = null;
    private volatile int activeDirection = 0; // 1=bullish(CE), -1=bearish(PE), 0=flat
    private volatile Instant lastEntryTime = null;
    private volatile Instant lastSlTime = null;
    private volatile double peakPrice = 0;

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
                               ExpiryCalendar expiryCalendar) {
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
        log.info("[OIMomentum] Started 1-second execution loop (enabled/paper controlled from UI)");
    }

    @jakarta.annotation.PreDestroy
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
            log.info("[OIMomentum] Stopped");
        }
    }

    /**
     * Main tick — runs every 1 second. Non-blocking, exception-safe.
     */
    private void tick() {
        try {
            if (!isMarketHours()) return;
            if (tradingStateService.killSwitchEnabled()) return;
            if (!tradingStateService.running()) return;

            // Check DB config for enabled/paper state (UI-toggleable)
            var dbConfig = strategyConfigService.getConfig(StrategyType.OI_MOMENTUM, "NIFTY");
            if (dbConfig == null || !dbConfig.isEnabled()) return;
            boolean paperMode = dbConfig.isPaperTrading();

            // Reset daily counters on new day
            LocalDate today = LocalDate.now(IST);
            if (!today.equals(currentDay)) {
                currentDay = today;
                tradesToday.set(0);
                reversalsToday.set(0);
                consecutiveLosses.set(0);
                activeTradeId = null;
                activeDirection = 0;
                peakPrice = 0;
            }

            // Feed momentum detector
            momentumDetector.tick(IndexType.NIFTY);

            // Position management (if we have an open position)
            if (activeTradeId != null) {
                managePosition();
            } else {
                detectEntry();
            }

            if (schedulerRegistry != null) schedulerRegistry.recordRun("oiMomentum");
        } catch (Exception e) {
            log.debug("[OIMomentum] Tick error: {}", e.getMessage());
        }
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

        // ── Event Spike Detection (highest priority) ──
        TickMomentumDetector.MomentumSignal spike = momentumDetector.detectSpike(
                IndexType.NIFTY, config.getSpikeThresholdPercent());
        if (spike.isPresent()) {
            log.info("[OIMomentum] EVENT SPIKE detected: direction={}, magnitude={:.3f}%, spot={}",
                    spike.direction(), spike.magnitude(), spike.spotPrice());
            enter(spike.direction(), "SPIKE:" + spike.type(), spike.spotPrice());
            return;
        }

        // ── Momentum Detection ──
        TickMomentumDetector.MomentumSignal momentum = momentumDetector.detect(
                IndexType.NIFTY, config.getMomentumThresholdPercent());
        if (!momentum.isPresent()) return; // No momentum — wait

        // ── OI Analysis ──
        double spot = momentum.spotPrice();
        int atm = IndexType.NIFTY.roundToATM(spot);
        long[] oiChange = liveInstrumentCache.getAtmOiChange(IndexType.NIFTY, atm, 3, 3);
        long ceOiChange = oiChange[0];
        long peOiChange = oiChange[1];
        boolean oiAvailable = (ceOiChange != 0 || peOiChange != 0);
        // OI bullish: PE OI building (writers selling puts = bullish) OR CE OI unwinding
        // OI bearish: CE OI building (writers selling calls = bearish) OR PE OI unwinding
        int oiDirection = 0;
        if (oiAvailable) {
            if (peOiChange > ceOiChange && peOiChange > 0) oiDirection = 1;  // Bullish OI
            else if (ceOiChange > peOiChange && ceOiChange > 0) oiDirection = -1; // Bearish OI
        }

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
            // Trade was closed externally (by LivePositionExitMonitor or manually)
            activeTradeId = null;
            activeDirection = 0;
            peakPrice = 0;
            return;
        }

        // Get current price
        Optional<Quote> quoteOpt = marketDataService.quote(trade.getInstrumentKey());
        if (quoteOpt.isEmpty()) return;
        double currentPrice = quoteOpt.get().lastPrice().doubleValue();
        if (currentPrice <= 0) return;
        double entryPrice = trade.getEntryPrice().doubleValue();

        // Track peak
        if (currentPrice > peakPrice) peakPrice = currentPrice;

        double profitPct = (currentPrice - entryPrice) / entryPrice * 100;
        double peakPct = (peakPrice - entryPrice) / entryPrice * 100;

        // ── Minimum hold time ──
        if (lastEntryTime != null && Duration.between(lastEntryTime, Instant.now()).getSeconds() < config.getMinimumHoldTimeSeconds()) {
            return; // Don't exit before minimum hold
        }

        // ── Squareoff time ──
        LocalTime now = LocalTime.now(IST);
        if (now.isAfter(LocalTime.of(config.getSquareoffHour(), config.getSquareoffMinute()))) {
            closePosition(trade, currentPrice, "SQUAREOFF_TIME");
            return;
        }

        // ── Stop Loss ──
        if (profitPct <= -config.getStopLossPercent()) {
            closePosition(trade, currentPrice, "STOP_LOSS");
            lastSlTime = Instant.now();
            consecutiveLosses.incrementAndGet();
            return;
        }

        // ── Target ──
        if (profitPct >= config.getTargetPercent()) {
            closePosition(trade, currentPrice, "TARGET");
            consecutiveLosses.set(0);
            return;
        }

        // ── Trailing Stop ──
        if (peakPct >= config.getTrailingActivationPercent()) {
            double trailLevel = peakPct - config.getTrailingGapPercent();
            if (profitPct < trailLevel) {
                closePosition(trade, currentPrice, "TRAILING_STOP");
                if (profitPct > 0) consecutiveLosses.set(0);
                return;
            }
        }

        // ── Reverse on OI flip ──
        if (reversalsToday.get() < config.getMaxReversalsPerDay()) {
            double spot = momentumDetector.getSpot(IndexType.NIFTY);
            int atm = IndexType.NIFTY.roundToATM(spot);
            long[] oiChange = liveInstrumentCache.getAtmOiChange(IndexType.NIFTY, atm, 3, 3);
            long ceOiChange = oiChange[0];
            long peOiChange = oiChange[1];

            // Detect OI flip against current direction
            boolean oiFlipped = false;
            if (activeDirection == 1 && ceOiChange > peOiChange && ceOiChange > 5000) {
                oiFlipped = true; // Was bullish, now CE OI building (bearish signal)
            } else if (activeDirection == -1 && peOiChange > ceOiChange && peOiChange > 5000) {
                oiFlipped = true; // Was bearish, now PE OI building (bullish signal)
            }

            if (oiFlipped && profitPct < 5) { // Only reverse if not significantly profitable
                closePosition(trade, currentPrice, "OI_FLIP_REVERSE");
                reversalsToday.incrementAndGet();
                // Enter opposite direction
                int newDirection = activeDirection * -1;
                enter(newDirection, "REVERSE:OI_FLIP", spot);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    private void enter(int direction, String reason, double spot) {
        if (activeTradeId != null) return; // Already in a position

        // Check DB config for paper/live mode
        var dbConfig = strategyConfigService.getConfig(StrategyType.OI_MOMENTUM, "NIFTY");
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
                java.util.List.of("OIMomentum: " + reason)
        );

        if (paperMode) {
            executionEngine.executePaperEntry(decision, premium, lotSize, null);
            // Find the paper trade just created
            activeTradeId = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> t.getInstrumentKey().equals(instrumentKey))
                    .filter(t -> StrategyType.OI_MOMENTUM.name().equals(t.getStrategyType())
                            || t.getTradeId().startsWith("PAPER-"))
                    .map(TradeEntity::getTradeId)
                    .findFirst().orElse(null);
        } else {
            executionEngine.executeEntry(decision, premium, lotSize, null);
            activeTradeId = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                    .filter(t -> t.getInstrumentKey().equals(instrumentKey))
                    .map(TradeEntity::getTradeId)
                    .findFirst().orElse(null);
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

    private void closePosition(TradeEntity trade, double currentPrice, String reason) {
        try {
            executionEngine.closeTrade(trade.getTradeId(), BigDecimal.valueOf(currentPrice), reason);
            double pnl = (currentPrice - trade.getEntryPrice().doubleValue()) * trade.getQuantity();
            log.info("[OIMomentum] EXIT: tradeId={}, reason={}, pnl=₹{:.0f}", trade.getTradeId(), reason, pnl);
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
        return now.isAfter(LocalTime.of(9, 15)) && now.isBefore(LocalTime.of(15, 30));
    }

    private boolean isMidday(LocalTime now) {
        return now.isAfter(LocalTime.parse(config.getMiddayStart()))
                && now.isBefore(LocalTime.parse(config.getMiddayEnd()));
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
