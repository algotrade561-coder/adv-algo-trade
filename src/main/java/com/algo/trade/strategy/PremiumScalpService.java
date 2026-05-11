package com.algo.trade.strategy;

import com.algo.trade.config.TradingProperties;
import com.algo.trade.domain.*;
import com.algo.trade.indicator.RangeBoundDetector;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;

/**
 * Premium Scalp Strategy — Two-component intraday system:
 *
 * Component 1: SELL ATM straddle at market open, hold all day (theta collection).
 * Component 2: BUY scalps (CE or PE) on 1-min candle close momentum, quick exits.
 *
 * Scalp filters:
 *   - Triggered by CandleClosedEvent (1-min candle close) — not polling
 *   - VWAP direction: only buy CE above VWAP, only buy PE below VWAP
 *   - RangeBound filter: skip scalps when market is choppy (ATR compressed + low efficiency)
 *   - 0.15% spot move threshold
 *
 * All positions squared off by 15:00. Paper trading only.
 */
@Service
public class PremiumScalpService {

    private static final Logger log = LoggerFactory.getLogger(PremiumScalpService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final long NIFTY_SPOT_TOKEN = 256265L;

    // ── Timing ──
    private static final LocalTime SELL_ENTRY_TIME = LocalTime.of(9, 20);
    private static final LocalTime BUY_SCALP_START = LocalTime.of(9, 25);
    private static final LocalTime BUY_SCALP_END = LocalTime.of(14, 45);
    private static final LocalTime SQUARE_OFF_TIME = LocalTime.of(15, 0);

    // ── Buy scalp parameters ──
    private static final double MIN_SPOT_MOVE_FOR_SCALP = 0.15; // 0.15% move = trigger (36 pts at 24000)
    private static final int SCALP_TARGET_POINTS = 25;           // ₹25 target per scalp
    private static final int SCALP_STOPLOSS_POINTS = 15;         // ₹15 SL per scalp (1.67:1 R:R)
    private static final int SCALP_MAX_HOLD_MINUTES = 15;        // Max 15 min hold
    private static final int MAX_SCALPS_PER_DAY = 50;            // Cap at 50 (paper — observe max signals)
    private static final int COOLDOWN_SECONDS = 120;             // 2 min between scalps

    // ── Sell straddle parameters ──
    private static final double SELL_STOPLOSS_PERCENT = 20;      // 20% SL on straddle
    private static final String UNDERLYING = "NIFTY";
    private static final int LOT_SIZE = 65;
    private static final String SPOT_INSTRUMENT_KEY = "NSE:256265";

    private final TradingProperties properties;
    private final TradeRepository tradeRepository;
    private final StrategyConfigService strategyConfigService;
    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final LiveCandleBuilder liveCandleBuilder;
    private final RangeBoundDetector rangeBoundDetector;
    private final VwapIndicator vwapIndicator;
    private final com.algo.trade.persistence.StrategyDecisionRepository decisionRepository;

    // ── State ──
    private volatile String sellCeTradeId = null;
    private volatile String sellPeTradeId = null;
    private volatile String activeBuyTradeId = null;
    private volatile Instant lastScalpTime = Instant.EPOCH;
    private volatile int scalpsToday = 0;
    private volatile LocalDate currentDay = null;
    private volatile BigDecimal lastSpotPrice = BigDecimal.ZERO;
    private volatile int ceScalpsOpen = 0;
    private volatile int peScalpsOpen = 0;
    private volatile boolean straddleMissedWarned = false;

    public PremiumScalpService(TradingProperties properties,
                                TradeRepository tradeRepository,
                                StrategyConfigService strategyConfigService,
                                InstrumentCache instrumentCache,
                                MarketDataService marketDataService,
                                LiveCandleBuilder liveCandleBuilder,
                                RangeBoundDetector rangeBoundDetector,
                                VwapIndicator vwapIndicator,
                                com.algo.trade.persistence.StrategyDecisionRepository decisionRepository) {
        this.properties = properties;
        this.tradeRepository = tradeRepository;
        this.strategyConfigService = strategyConfigService;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.liveCandleBuilder = liveCandleBuilder;
        this.rangeBoundDetector = rangeBoundDetector;
        this.vwapIndicator = vwapIndicator;
        this.decisionRepository = decisionRepository;
    }

    // ── Startup Recovery ────────────────────────────────────────────────────

    @PostConstruct
    void recoverState() {
        LocalDate today = LocalDate.now(IST);
        currentDay = today;

        List<TradeEntity> openTrades = tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> StrategyType.PREMIUM_SCALP.name().equals(t.getStrategyType()))
                .filter(t -> t.getEntryTime() != null
                        && t.getEntryTime().atZone(IST).toLocalDate().equals(today))
                .toList();

        for (TradeEntity t : openTrades) {
            if (t.getEntryReason() != null && t.getEntryReason().contains("_SELL:")) {
                if ("CE".equals(t.getOptionType())) sellCeTradeId = t.getTradeId();
                else if ("PE".equals(t.getOptionType())) sellPeTradeId = t.getTradeId();
            } else if (t.getEntryReason() != null && t.getEntryReason().contains("_BUY:")) {
                activeBuyTradeId = t.getTradeId();
                if ("CE".equals(t.getOptionType())) ceScalpsOpen++;
                else if ("PE".equals(t.getOptionType())) peScalpsOpen++;
            }
        }

        Instant todayStart = today.atStartOfDay(IST).toInstant();
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(IST).toInstant();
        List<TradeEntity> todayTrades = tradeRepository.findByEntryTimeBetween(todayStart, tomorrowStart).stream()
                .filter(t -> StrategyType.PREMIUM_SCALP.name().equals(t.getStrategyType()))
                .filter(t -> t.getEntryReason() != null && t.getEntryReason().contains("_BUY:"))
                .toList();
        scalpsToday = todayTrades.size();

        todayTrades.stream()
                .filter(t -> t.getStatus() == TradeStatus.CLOSED && t.getExitTime() != null)
                .max(Comparator.comparing(TradeEntity::getExitTime))
                .ifPresent(t -> lastScalpTime = t.getExitTime());

        if (sellCeTradeId != null || sellPeTradeId != null || activeBuyTradeId != null) {
            log.info("[PremiumScalp] Recovered state: sellCe={}, sellPe={}, activeBuy={}, scalpsToday={}, ceOpen={}, peOpen={}",
                    sellCeTradeId, sellPeTradeId, activeBuyTradeId, scalpsToday, ceScalpsOpen, peScalpsOpen);
        }
    }

    // ── Event-Driven Scalp Trigger (1-min candle close) ─────────────────────

    /**
     * Primary scalp trigger — fires on 5-minute candle close from WebSocket.
     * Uses the candle body (close - open) as the momentum signal.
     * A strong 5-min candle body > 0.15% of spot = confirmed directional move.
     */
    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        if (event.instrumentToken() != NIFTY_SPOT_TOKEN) return;
        if (!isEnabled()) return;

        LocalTime now = LocalTime.now(IST);
        if (now.isBefore(BUY_SCALP_START) || now.isAfter(BUY_SCALP_END)) return;

        // Use 5-min candle close for ENTRY decisions (confirmed move)
        if (event.timeframe() == Timeframe.FIVE_MINUTE) {
            Candle candle = event.candle();
            BigDecimal body = candle.close().subtract(candle.open());
            BigDecimal range = candle.high().subtract(candle.low());
            double bodyPercent = candle.open().signum() > 0
                    ? body.abs().divide(candle.open(), MC).doubleValue() * 100 : 0;

            // Strong candle: body > 0.15% AND body > 60% of range (not a doji)
            boolean strongCandle = bodyPercent >= MIN_SPOT_MOVE_FOR_SCALP
                    && range.signum() > 0
                    && body.abs().doubleValue() / range.doubleValue() > 0.6;

            if (strongCandle) {
                boolean bullish = body.signum() > 0;
                evaluateScalpEntry(candle.close(), bullish, bodyPercent);
            }
        }

        // Use 1-min candle close for EXIT checks (faster SL response)
        if (event.timeframe() == Timeframe.ONE_MINUTE && activeBuyTradeId != null) {
            checkScalpExit();
        }
    }

    // ── Scheduled Tick (straddle management + safety net) ───────────────────

    /**
     * 30-second scheduled tick handles:
     * - Straddle open/SL/square-off (not latency-sensitive)
     * - Day reset
     * - Safety net for scalp exits if WebSocket is down
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 10_000)
    public void tick() {
        if (!isEnabled()) return;

        LocalTime now = LocalTime.now(IST);
        LocalDate today = LocalDate.now(IST);

        if (!today.equals(currentDay)) {
            resetDay(today);
        }

        if (now.isBefore(SELL_ENTRY_TIME) || now.isAfter(SQUARE_OFF_TIME.plusMinutes(5))) {
            return;
        }

        // Square off
        if (now.isAfter(SQUARE_OFF_TIME)) {
            squareOffAll("End of day square-off at " + now);
            return;
        }

        // Straddle management
        if (sellCeTradeId == null && now.isAfter(SELL_ENTRY_TIME) && now.isBefore(LocalTime.of(10, 0))) {
            openSellStraddle();
        } else if (sellCeTradeId == null && now.isAfter(LocalTime.of(10, 0)) && now.isBefore(BUY_SCALP_END)) {
            if (!straddleMissedWarned) {
                log.warn("[PremiumScalp] Straddle entry window missed (after 10:00) — scalps running without straddle hedge");
                straddleMissedWarned = true;
            }
        }

        if (sellCeTradeId != null) {
            checkSellStraddleSL();
        }

        // Safety net: check scalp exit even if WebSocket events aren't flowing
        if (activeBuyTradeId != null) {
            checkScalpExit();
        }
    }

    // ── Scalp Entry Logic (with VWAP + RangeBound filters) ──────────────────

    private void evaluateScalpEntry(BigDecimal spotPrice, boolean bullish, double movePercent) {
        // Already in a scalp — wait for exit
        if (activeBuyTradeId != null) return;

        // Cooldown
        if (Instant.now().isBefore(lastScalpTime.plusSeconds(COOLDOWN_SECONDS))) {
            log.debug("[PremiumScalp] Scalp skipped: cooldown active");
            return;
        }

        // Max scalps
        if (scalpsToday >= MAX_SCALPS_PER_DAY) return;

        // ── FILTER 1: RangeBound check — skip scalps in choppy market ──
        List<Candle> candles1m = liveCandleBuilder.getHistory(NIFTY_SPOT_TOKEN, Timeframe.ONE_MINUTE);
        if (candles1m.size() >= 34 && rangeBoundDetector.isRangeBound(candles1m)) {
            log.info("[PremiumScalp] Scalp skipped: market is range-bound (choppy)");
            return;
        }

        // ── FILTER 2: VWAP direction — only buy CE above VWAP, PE below VWAP ──
        BigDecimal vwap = vwapIndicator.calculate(candles1m);
        if (vwap.signum() > 0) {
            if (bullish && spotPrice.compareTo(vwap) < 0) {
                log.info("[PremiumScalp] CE scalp skipped: spot {} below VWAP {}", spotPrice, vwap);
                return;
            }
            if (!bullish && spotPrice.compareTo(vwap) > 0) {
                log.info("[PremiumScalp] PE scalp skipped: spot {} above VWAP {}", spotPrice, vwap);
                return;
            }
        }

        // Direction and hedge cap
        OptionType optionType = bullish ? OptionType.CE : OptionType.PE;
        if (bullish && ceScalpsOpen >= 1) return;
        if (!bullish && peScalpsOpen >= 1) return;

        // Resolve instrument and get price
        int atmStrike = roundToStrike(spotPrice.doubleValue(), 50);
        Optional<Instrument> inst = resolveOption(atmStrike, optionType);
        if (inst.isEmpty()) {
            log.warn("[PremiumScalp] Cannot resolve option: strike={}, type={}", atmStrike, optionType);
            return;
        }

        BigDecimal premium = getLastPrice(inst.get().instrumentKey());
        if (premium.signum() <= 0 || premium.doubleValue() < 5) {
            log.debug("[PremiumScalp] Scalp skipped: premium too low ({})", premium);
            return;
        }

        // Open buy scalp
        activeBuyTradeId = openPaperTrade(inst.get().instrumentKey(),
                optionType.name(), premium,
                "PREMIUM_SCALP_BUY: " + optionType + " scalp, move=" +
                        String.format("%.3f%%", movePercent) + ", strike=" + atmStrike +
                        ", vwap=" + vwap.intValue());

        if (bullish) ceScalpsOpen++;
        else peScalpsOpen++;

        scalpsToday++;
        lastScalpTime = Instant.now();

        // Record entry signal for reports
        recordDecision("BUY_" + optionType.name(), optionType.name(), spotPrice, premium,
                inst.get().instrumentKey(),
                "Scalp #" + scalpsToday + ": move=" + String.format("%.3f%%", movePercent) + ", vwap=" + vwap.intValue(),
                true);

        log.info("[PremiumScalp] Buy scalp #{} opened: {} strike={}, premium=₹{}, move={}%, vwap={}",
                scalpsToday, optionType, atmStrike, premium,
                String.format("%.3f", movePercent), vwap.intValue());
    }

    // ── Component 1: Sell Straddle ──────────────────────────────────────────

    private void openSellStraddle() {
        BigDecimal spotPrice = getSpotPrice();
        if (spotPrice.signum() == 0) return;

        int atmStrike = roundToStrike(spotPrice.doubleValue(), 50);

        Optional<Instrument> ceInst = resolveOption(atmStrike, OptionType.CE);
        Optional<Instrument> peInst = resolveOption(atmStrike, OptionType.PE);

        if (ceInst.isEmpty() || peInst.isEmpty()) {
            log.warn("[PremiumScalp] Cannot resolve ATM options for strike {}", atmStrike);
            return;
        }

        BigDecimal cePremium = getLastPrice(ceInst.get().instrumentKey());
        BigDecimal pePremium = getLastPrice(peInst.get().instrumentKey());

        if (cePremium.signum() <= 0 || pePremium.signum() <= 0) {
            log.warn("[PremiumScalp] Zero premium for ATM options");
            return;
        }

        sellCeTradeId = openPaperTrade(ceInst.get().instrumentKey(), "CE", cePremium,
                "PREMIUM_SCALP_SELL: ATM CE straddle leg, strike=" + atmStrike);
        sellPeTradeId = openPaperTrade(peInst.get().instrumentKey(), "PE", pePremium,
                "PREMIUM_SCALP_SELL: ATM PE straddle leg, strike=" + atmStrike);

        BigDecimal combined = cePremium.add(pePremium);
        log.info("[PremiumScalp] Sell straddle opened: strike={}, CE=₹{}, PE=₹{}, combined=₹{}",
                atmStrike, cePremium, pePremium, combined);
    }

    private void checkSellStraddleSL() {
        TradeEntity ceTrade = sellCeTradeId != null ? tradeRepository.findById(sellCeTradeId).orElse(null) : null;
        TradeEntity peTrade = sellPeTradeId != null ? tradeRepository.findById(sellPeTradeId).orElse(null) : null;

        if (ceTrade == null || peTrade == null) return;
        if (ceTrade.getStatus() != TradeStatus.OPEN || peTrade.getStatus() != TradeStatus.OPEN) return;

        BigDecimal ceCurrentPrice = getLastPrice(ceTrade.getInstrumentKey());
        BigDecimal peCurrentPrice = getLastPrice(peTrade.getInstrumentKey());
        if (ceCurrentPrice.signum() <= 0 || peCurrentPrice.signum() <= 0) return;

        BigDecimal entryTotal = ceTrade.getEntryPrice().add(peTrade.getEntryPrice());
        BigDecimal currentTotal = ceCurrentPrice.add(peCurrentPrice);

        double lossPercent = currentTotal.subtract(entryTotal)
                .divide(entryTotal, MC).doubleValue() * 100;

        if (lossPercent > SELL_STOPLOSS_PERCENT) {
            log.warn("[PremiumScalp] Sell straddle SL hit: loss={}%, closing", String.format("%.1f", lossPercent));
            closePaperTrade(sellCeTradeId, ceCurrentPrice, "SELL_STRADDLE_SL: premium expanded " + String.format("%.1f%%", lossPercent));
            closePaperTrade(sellPeTradeId, peCurrentPrice, "SELL_STRADDLE_SL: premium expanded " + String.format("%.1f%%", lossPercent));
            sellCeTradeId = null;
            sellPeTradeId = null;
        }
    }

    // ── Scalp Exit ──────────────────────────────────────────────────────────

    private void checkScalpExit() {
        if (activeBuyTradeId == null) return;

        TradeEntity trade = tradeRepository.findById(activeBuyTradeId).orElse(null);
        if (trade == null || trade.getStatus() != TradeStatus.OPEN) {
            activeBuyTradeId = null;
            return;
        }

        BigDecimal currentPrice = getLastPrice(trade.getInstrumentKey());
        if (currentPrice.signum() <= 0) return;

        BigDecimal entryPrice = trade.getEntryPrice();
        double pnlPoints = currentPrice.subtract(entryPrice).doubleValue();
        long holdSeconds = java.time.Duration.between(trade.getEntryTime(), Instant.now()).getSeconds();

        if (pnlPoints >= SCALP_TARGET_POINTS) {
            closePaperTrade(activeBuyTradeId, currentPrice,
                    "SCALP_TARGET: +" + String.format("%.1f", pnlPoints) + " pts");
            releaseHedgeSlot(trade);
            activeBuyTradeId = null;
            return;
        }

        if (pnlPoints <= -SCALP_STOPLOSS_POINTS) {
            closePaperTrade(activeBuyTradeId, currentPrice,
                    "SCALP_SL: " + String.format("%.1f", pnlPoints) + " pts");
            releaseHedgeSlot(trade);
            activeBuyTradeId = null;
            return;
        }

        if (holdSeconds >= SCALP_MAX_HOLD_MINUTES * 60L) {
            closePaperTrade(activeBuyTradeId, currentPrice,
                    "SCALP_TIME: held " + (holdSeconds / 60) + " min, pnl=" +
                            String.format("%.1f", pnlPoints) + " pts");
            releaseHedgeSlot(trade);
            activeBuyTradeId = null;
        }
    }

    private void releaseHedgeSlot(TradeEntity trade) {
        if ("CE".equals(trade.getOptionType())) ceScalpsOpen = Math.max(0, ceScalpsOpen - 1);
        else if ("PE".equals(trade.getOptionType())) peScalpsOpen = Math.max(0, peScalpsOpen - 1);
    }

    // ── Square Off ──────────────────────────────────────────────────────────

    private void squareOffAll(String reason) {
        if (sellCeTradeId != null) {
            TradeEntity ce = tradeRepository.findById(sellCeTradeId).orElse(null);
            if (ce != null && ce.getStatus() == TradeStatus.OPEN) {
                closePaperTrade(sellCeTradeId, getLastPrice(ce.getInstrumentKey()), reason);
            }
            sellCeTradeId = null;
        }
        if (sellPeTradeId != null) {
            TradeEntity pe = tradeRepository.findById(sellPeTradeId).orElse(null);
            if (pe != null && pe.getStatus() == TradeStatus.OPEN) {
                closePaperTrade(sellPeTradeId, getLastPrice(pe.getInstrumentKey()), reason);
            }
            sellPeTradeId = null;
        }
        if (activeBuyTradeId != null) {
            TradeEntity buy = tradeRepository.findById(activeBuyTradeId).orElse(null);
            if (buy != null && buy.getStatus() == TradeStatus.OPEN) {
                closePaperTrade(activeBuyTradeId, getLastPrice(buy.getInstrumentKey()), reason);
            }
            activeBuyTradeId = null;
        }
        ceScalpsOpen = 0;
        peScalpsOpen = 0;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean isEnabled() {
        try {
            StrategyConfig config = strategyConfigService.getConfig(StrategyType.PREMIUM_SCALP, UNDERLYING);
            return config != null && config.isEnabled() && config.isPaperTrading();
        } catch (Exception e) {
            // Config may not exist yet on first run — will be auto-created by StrategyConfigService
            return false;
        }
    }

    private void resetDay(LocalDate today) {
        currentDay = today;
        scalpsToday = 0;
        sellCeTradeId = null;
        sellPeTradeId = null;
        activeBuyTradeId = null;
        lastSpotPrice = BigDecimal.ZERO;
        ceScalpsOpen = 0;
        peScalpsOpen = 0;
        straddleMissedWarned = false;
        log.info("[PremiumScalp] Day reset: {}", today);
    }

    private String openPaperTrade(String instrumentKey, String optionType, BigDecimal price, String reason) {
        String tradeId = "PAPER-PSCALP-" + UUID.randomUUID().toString().substring(0, 8);
        TradeEntity trade = new TradeEntity(tradeId, instrumentKey, UNDERLYING, optionType,
                TradeStatus.OPEN, LOT_SIZE, price, Instant.now(), reason);
        trade.setStrategyType(StrategyType.PREMIUM_SCALP.name());
        tradeRepository.save(trade);
        return tradeId;
    }

    private void closePaperTrade(String tradeId, BigDecimal exitPrice, String reason) {
        if (tradeId == null) return;
        TradeEntity trade = tradeRepository.findById(tradeId).orElse(null);
        if (trade == null || trade.getStatus() != TradeStatus.OPEN) return;

        if (exitPrice == null || exitPrice.signum() <= 0) {
            exitPrice = trade.getEntryPrice();
        }

        boolean isSell = trade.getEntryReason() != null && trade.getEntryReason().contains("_SELL:");
        BigDecimal pnl = isSell
                ? trade.getEntryPrice().subtract(exitPrice).multiply(BigDecimal.valueOf(LOT_SIZE))
                : exitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(LOT_SIZE));

        trade.close(exitPrice, Instant.now(), pnl, "PAPER_EXIT: " + reason);
        tradeRepository.save(trade);

        log.info("[PremiumScalp] Trade closed: id={}, type={}, entry=₹{}, exit=₹{}, pnl=₹{}, reason={}",
                tradeId, isSell ? "SELL" : "BUY",
                trade.getEntryPrice(), exitPrice, pnl, reason);
    }

    private BigDecimal getSpotPrice() {
        Optional<Quote> quote = marketDataService.quote(SPOT_INSTRUMENT_KEY);
        return quote.map(Quote::lastPrice).orElse(BigDecimal.ZERO);
    }

    private BigDecimal getLastPrice(String instrumentKey) {
        Optional<Quote> quote = marketDataService.quote(instrumentKey);
        return quote.map(Quote::lastPrice).orElse(BigDecimal.ZERO);
    }

    private Optional<Instrument> resolveOption(int strike, OptionType optionType) {
        LocalDate expiry = instrumentCache.nearestExpiry(UnderlyingSymbol.NIFTY, LocalDate.now(IST))
                .orElse(null);
        if (expiry == null) return Optional.empty();
        return instrumentCache.findOption(UnderlyingSymbol.NIFTY, expiry,
                BigDecimal.valueOf(strike), optionType);
    }

    private int roundToStrike(double spot, int interval) {
        return (int) (Math.round(spot / interval) * interval);
    }

    /** Record a strategy decision for the reports screen. */
    private void recordDecision(String signalType, String optionType, BigDecimal spotPrice,
                                 BigDecimal optionPrice, String instrumentKey, String reason, boolean executed) {
        com.algo.trade.persistence.StrategyDecisionEntity entity =
                com.algo.trade.persistence.StrategyDecisionEntity.forStrategy(
                        StrategyType.PREMIUM_SCALP.name(), Instant.now(), UNDERLYING,
                        signalType, optionType, spotPrice, BigDecimal.valueOf(70), reason);
        entity.setOptionPrice(optionPrice);
        entity.setSelectedInstrumentKey(instrumentKey);
        entity.setPaperTrade(true);
        entity.setExecutionStage(executed ? "PAPER_FILLED" : "REJECTED");
        entity.setExecutionReason(executed ? "Paper scalp opened" : reason);
        decisionRepository.save(entity);
    }

    // ── Public API for monitoring ───────────────────────────────────────────

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", isEnabled());
        status.put("underlying", UNDERLYING);
        status.put("paperTrading", true);
        status.put("currentDay", currentDay);
        status.put("sellCeTradeId", sellCeTradeId);
        status.put("sellPeTradeId", sellPeTradeId);
        status.put("activeBuyTradeId", activeBuyTradeId);
        status.put("scalpsToday", scalpsToday);
        status.put("maxScalpsPerDay", MAX_SCALPS_PER_DAY);
        status.put("lastSpotPrice", lastSpotPrice);
        status.put("sellStraddlePnl", getSellStraddlePnl());
        status.put("scalpTargetPoints", SCALP_TARGET_POINTS);
        status.put("scalpStoplossPoints", SCALP_STOPLOSS_POINTS);
        status.put("scalpMaxHoldMinutes", SCALP_MAX_HOLD_MINUTES);
        status.put("sellStraddleSLPercent", SELL_STOPLOSS_PERCENT);
        status.put("cooldownSeconds", COOLDOWN_SECONDS);
        status.put("minSpotMovePercent", MIN_SPOT_MOVE_FOR_SCALP);
        status.put("filters", "VWAP + RangeBound + 0.15% move");
        return status;
    }

    public BigDecimal getSellStraddlePnl() {
        BigDecimal totalPnl = BigDecimal.ZERO;
        if (sellCeTradeId != null) {
            TradeEntity ce = tradeRepository.findById(sellCeTradeId).orElse(null);
            if (ce != null && ce.getStatus() == TradeStatus.OPEN) {
                BigDecimal currentPrice = getLastPrice(ce.getInstrumentKey());
                if (currentPrice.signum() > 0) {
                    totalPnl = totalPnl.add(ce.getEntryPrice().subtract(currentPrice).multiply(BigDecimal.valueOf(LOT_SIZE)));
                }
            }
        }
        if (sellPeTradeId != null) {
            TradeEntity pe = tradeRepository.findById(sellPeTradeId).orElse(null);
            if (pe != null && pe.getStatus() == TradeStatus.OPEN) {
                BigDecimal currentPrice = getLastPrice(pe.getInstrumentKey());
                if (currentPrice.signum() > 0) {
                    totalPnl = totalPnl.add(pe.getEntryPrice().subtract(currentPrice).multiply(BigDecimal.valueOf(LOT_SIZE)));
                }
            }
        }
        return totalPnl;
    }
}
