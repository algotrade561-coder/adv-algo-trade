package com.algo.trade.strategy;

import com.algo.trade.domain.*;
import com.algo.trade.indicator.VwapIndicator;
import com.algo.trade.marketdata.InstrumentCache;
import com.algo.trade.marketdata.LiveCandleBuilder;
import com.algo.trade.marketdata.MarketDataService;
import com.algo.trade.marketdata.TickVolumeProfileService;
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
 * Breakout Re-Entry Strategy — Bollinger Band squeeze breakout with band-walk re-entries.
 *
 * Entry 1 (Fresh Breakout):
 *   - Bollinger squeeze detected (band width contracting)
 *   - Price breaks above upper band (bullish) or below lower band (bearish)
 *   - Above VWAP (for CE) or below VWAP (for PE)
 *   - Strong 5-min candle close in direction
 *   - Option premium ROC > 3%
 *
 * Re-entry (Band Walk):
 *   - Price pulls back to middle band (20 SMA)
 *   - Bounces — next candle closes above upper band again
 *   - 15-min trend still intact (price above 15-min EMA)
 *   - Max 3 re-entries per direction per day
 *
 * Avoid:
 *   - Low ATR days (no momentum)
 *   - Overextended VWAP distance (> 0.5% from VWAP)
 *   - After 2:30 PM (theta decay)
 *
 * Exit:
 *   - Target: 12% option premium gain
 *   - Stop: Close below middle band AND swing low break
 *   - Time: 15 min max hold
 *
 * Risk:
 *   - Max 3 losses/day → stop strategy
 *   - 2-candle cooldown after SL
 *   - Paper trading only, NIFTY only
 */
@Service
public class BreakoutReentryService {

    private static final Logger log = LoggerFactory.getLogger(BreakoutReentryService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final long NIFTY_SPOT_TOKEN = 256265L;
    private static final double MIN_POC_DISTANCE_PCT = 0.3; // skip breakout if price within 0.3% of POC

    // ── Bollinger Band parameters ──
    private static final int BB_PERIOD = 20;
    private static final double BB_STD_DEV = 2.0;
    private static final double SQUEEZE_THRESHOLD = 0.6; // Band width < 60% of avg = squeeze

    // ── Entry parameters ──
    private static final double MIN_OPTION_ROC_PERCENT = 3.0; // Option premium must move 3%+
    private static final double MAX_VWAP_DISTANCE_PERCENT = 0.5; // Don't enter if too far from VWAP
    private static final double MIN_ATR_THRESHOLD = 15.0; // Minimum ATR in points (skip low-vol days)
    private static final LocalTime ENTRY_START = LocalTime.of(9, 25);
    private static final LocalTime ENTRY_END = LocalTime.of(14, 30); // No entries after 2:30 PM

    // ── Exit parameters ──
    private static final double TARGET_PERCENT = 12.0;
    private static final double STOPLOSS_PERCENT = 7.0;
    private static final int MAX_HOLD_MINUTES = 15;

    // ── Risk parameters ──
    private static final int MAX_LOSSES_PER_DAY = 50;
    private static final int MAX_ENTRIES_PER_DIRECTION = 50;
    private static final int COOLDOWN_CANDLES_AFTER_SL = 2; // Skip 2 candles after SL

    private static final String UNDERLYING = "NIFTY";
    private static final int LOT_SIZE = 65;
    private static final String SPOT_KEY = "NSE:256265";

    private final TradeRepository tradeRepository;
    private final StrategyConfigService strategyConfigService;
    private final InstrumentCache instrumentCache;
    private final MarketDataService marketDataService;
    private final LiveCandleBuilder liveCandleBuilder;
    private final VwapIndicator vwapIndicator;
    private final com.algo.trade.persistence.StrategyDecisionRepository decisionRepository;
    private final TickVolumeProfileService tickVolumeProfileService;

    // ── State ──
    private volatile String activeTradeId = null;
    private volatile LocalDate currentDay = null;
    private volatile int lossesToday = 0;
    private volatile int ceEntriesToday = 0;
    private volatile int peEntriesToday = 0;
    private volatile int cooldownCandlesRemaining = 0;
    private volatile boolean squeezeDetected = false;
    private volatile OptionType lastBreakoutDirection = null;
    private volatile BigDecimal lastOptionPrice = BigDecimal.ZERO; // For ROC calculation
    private volatile boolean stoppedForDay = false;

    public BreakoutReentryService(TradeRepository tradeRepository,
                                   StrategyConfigService strategyConfigService,
                                   InstrumentCache instrumentCache,
                                   MarketDataService marketDataService,
                                   LiveCandleBuilder liveCandleBuilder,
                                   VwapIndicator vwapIndicator,
                                   TickVolumeProfileService tickVolumeProfileService,
                                   com.algo.trade.persistence.StrategyDecisionRepository decisionRepository) {
        this.tradeRepository = tradeRepository;
        this.strategyConfigService = strategyConfigService;
        this.instrumentCache = instrumentCache;
        this.marketDataService = marketDataService;
        this.liveCandleBuilder = liveCandleBuilder;
        this.vwapIndicator = vwapIndicator;
        this.tickVolumeProfileService = tickVolumeProfileService;
        this.decisionRepository = decisionRepository;
    }

    @PostConstruct
    void recoverState() {
        LocalDate today = LocalDate.now(IST);
        currentDay = today;

        // Recover active trade
        tradeRepository.findByStatus(TradeStatus.OPEN).stream()
                .filter(t -> StrategyType.BREAKOUT_REENTRY.name().equals(t.getStrategyType()))
                .filter(t -> t.getEntryTime() != null && t.getEntryTime().atZone(IST).toLocalDate().equals(today))
                .findFirst()
                .ifPresent(t -> activeTradeId = t.getTradeId());

        // Count today's entries and losses
        Instant todayStart = today.atStartOfDay(IST).toInstant();
        Instant tomorrowStart = today.plusDays(1).atStartOfDay(IST).toInstant();
        List<TradeEntity> todayTrades = tradeRepository.findByEntryTimeBetween(todayStart, tomorrowStart).stream()
                .filter(t -> StrategyType.BREAKOUT_REENTRY.name().equals(t.getStrategyType()))
                .toList();

        ceEntriesToday = (int) todayTrades.stream().filter(t -> "CE".equals(t.getOptionType())).count();
        peEntriesToday = (int) todayTrades.stream().filter(t -> "PE".equals(t.getOptionType())).count();
        lossesToday = (int) todayTrades.stream()
                .filter(t -> t.getStatus() == TradeStatus.CLOSED)
                .filter(t -> t.getRealizedPnl().signum() < 0)
                .count();

        if (lossesToday >= MAX_LOSSES_PER_DAY) stoppedForDay = true;

        if (activeTradeId != null || lossesToday > 0) {
            log.info("[BreakoutReentry] Recovered: active={}, ceEntries={}, peEntries={}, losses={}, stopped={}",
                    activeTradeId, ceEntriesToday, peEntriesToday, lossesToday, stoppedForDay);
        }
    }

    // ── Primary Trigger: 5-min candle close ─────────────────────────────────

    @EventListener
    public void onCandleClose(CandleClosedEvent event) {
        if (event.timeframe() != Timeframe.FIVE_MINUTE) return;
        if (event.instrumentToken() != NIFTY_SPOT_TOKEN) return;
        if (!isEnabled()) return;

        LocalTime now = LocalTime.now(IST);
        LocalDate today = LocalDate.now(IST);

        if (!today.equals(currentDay)) resetDay(today);
        if (stoppedForDay) return;
        if (now.isBefore(ENTRY_START) || now.isAfter(ENTRY_END)) return;

        // Cooldown after SL
        if (cooldownCandlesRemaining > 0) {
            cooldownCandlesRemaining--;
            log.debug("[BreakoutReentry] Cooldown: {} candles remaining", cooldownCandlesRemaining);
            return;
        }

        // Check exit for active trade
        if (activeTradeId != null) {
            checkExit(event.candle());
            return;
        }

        // Evaluate entry
        evaluateEntry(event.candle());
    }

    // ── Also check exits on 1-min candles (faster SL response) ──────────────

    @EventListener
    public void onOneMinCandleClose(CandleClosedEvent event) {
        if (event.timeframe() != Timeframe.ONE_MINUTE) return;
        if (event.instrumentToken() != NIFTY_SPOT_TOKEN) return;
        if (!isEnabled() || activeTradeId == null) return;

        // Quick SL check on 1-min candles for faster exit
        checkExit(event.candle());
    }

    // ── Scheduled safety net + square off ───────────────────────────────────

    @Scheduled(fixedDelay = 60_000, initialDelay = 15_000)
    public void tick() {
        if (!isEnabled()) return;
        LocalTime now = LocalTime.now(IST);
        LocalDate today = LocalDate.now(IST);

        if (!today.equals(currentDay)) resetDay(today);

        // Square off at 2:30 PM
        if (now.isAfter(ENTRY_END) && activeTradeId != null) {
            TradeEntity trade = tradeRepository.findById(activeTradeId).orElse(null);
            if (trade != null && trade.getStatus() == TradeStatus.OPEN) {
                BigDecimal price = getLastPrice(trade.getInstrumentKey());
                closeTrade(price, "TIME_EXIT: after 2:30 PM cutoff");
            }
        }
    }

    // ── Entry Logic ─────────────────────────────────────────────────────────

    private void evaluateEntry(Candle candle) {
        List<Candle> candles5m = liveCandleBuilder.getHistory(NIFTY_SPOT_TOKEN, Timeframe.FIVE_MINUTE);
        if (candles5m.size() < BB_PERIOD + 5) {
            log.info("[BreakoutReentry] Skipped: insufficient 5m candles ({}/{})", candles5m.size(), BB_PERIOD + 5);
            return;
        }

        BigDecimal spotPrice = candle.close();

        // Calculate Bollinger Bands
        double[] bb = calculateBollingerBands(candles5m);
        double upperBand = bb[0];
        double middleBand = bb[1];
        double lowerBand = bb[2];
        double bandWidth = bb[3];
        double avgBandWidth = bb[4];

        // ATR filter — skip low volatility days
        double atr = calculateATR(candles5m, 14);
        if (atr < MIN_ATR_THRESHOLD) {
            log.debug("[BreakoutReentry] Skipped: low ATR={}", String.format("%.1f", atr));
            return;
        }

        // VWAP
        List<Candle> candles1m = liveCandleBuilder.getHistory(NIFTY_SPOT_TOKEN, Timeframe.ONE_MINUTE);
        BigDecimal vwap = vwapIndicator.calculate(candles1m);
        if (vwap.signum() == 0) return;

        double spot = spotPrice.doubleValue();
        double vwapDistance = Math.abs(spot - vwap.doubleValue()) / vwap.doubleValue() * 100;

        // Overextended VWAP filter
        if (vwapDistance > MAX_VWAP_DISTANCE_PERCENT) {
            log.debug("[BreakoutReentry] Skipped: overextended from VWAP ({}%)", String.format("%.2f", vwapDistance));
            return;
        }

        // VPVR filter: skip if price is near POC (high-volume node — breakout will stall)
        TickVolumeProfileService.VolumeProfile vp = tickVolumeProfileService.getProfile(NIFTY_SPOT_TOKEN);
        if (vp != null) {
            double distPct = vp.distFromPocPct(spot);
            if (distPct < MIN_POC_DISTANCE_PCT) {
                log.debug("[BreakoutReentry] Skipped: price near POC={} dist={}%",
                        String.format("%.0f", vp.poc()), String.format("%.2f", distPct));
                return;
            }
            log.debug("[BreakoutReentry] VPVR: poc={} vah={} val={} dist={}% inLVN={}",
                    String.format("%.0f", vp.poc()), String.format("%.0f", vp.vah()),
                    String.format("%.0f", vp.val()), String.format("%.2f", distPct), !vp.inValueArea(spot));
        }

        // Detect squeeze
        boolean isSqueeze = bandWidth < avgBandWidth * SQUEEZE_THRESHOLD;

        // Determine breakout direction
        boolean bullishBreakout = spot > upperBand && spot > vwap.doubleValue();
        boolean bearishBreakout = spot < lowerBand && spot < vwap.doubleValue();

        // Check for re-entry (pullback to middle band then bounce)
        boolean isReentry = false;
        if (!bullishBreakout && !bearishBreakout && lastBreakoutDirection != null) {
            isReentry = checkReentryCondition(candles5m, spot, middleBand, upperBand, lowerBand);
        }

        if (!bullishBreakout && !bearishBreakout && !isReentry) return;

        // Determine direction
        OptionType optionType;
        if (isReentry) {
            optionType = lastBreakoutDirection;
        } else {
            optionType = bullishBreakout ? OptionType.CE : OptionType.PE;
            lastBreakoutDirection = optionType;
            squeezeDetected = isSqueeze;
        }

        // Max entries per direction
        if (optionType == OptionType.CE && ceEntriesToday >= MAX_ENTRIES_PER_DIRECTION) return;
        if (optionType == OptionType.PE && peEntriesToday >= MAX_ENTRIES_PER_DIRECTION) return;

        // Strong candle check — candle body > 50% of range
        double body = Math.abs(candle.close().subtract(candle.open()).doubleValue());
        double range = candle.high().subtract(candle.low()).doubleValue();
        if (range > 0 && body / range < 0.5) {
            log.debug("[BreakoutReentry] Skipped: weak candle (body/range={})", String.format("%.2f", body / range));
            return;
        }

        // Option premium ROC check
        int atmStrike = roundToStrike(spot, 50);
        Optional<Instrument> inst = resolveOption(atmStrike, optionType);
        if (inst.isEmpty()) return;

        BigDecimal premium = getLastPrice(inst.get().instrumentKey());
        if (premium.signum() <= 0 || premium.doubleValue() < 10) return;

        double optionRoc = lastOptionPrice.signum() > 0
                ? premium.subtract(lastOptionPrice).divide(lastOptionPrice, MC).doubleValue() * 100
                : 0;
        lastOptionPrice = premium;

        // First entry needs ROC > 3%, re-entries are more lenient (> 1%)
        double requiredRoc = isReentry ? 1.0 : MIN_OPTION_ROC_PERCENT;
        if (optionRoc < requiredRoc) {
            log.debug("[BreakoutReentry] Skipped: option ROC={}% < required {}%",
                    String.format("%.1f", optionRoc), requiredRoc);
            return;
        }

        // 15-min trend check for re-entries
        if (isReentry) {
            List<Candle> candles15m = liveCandleBuilder.getHistory(NIFTY_SPOT_TOKEN, Timeframe.FIFTEEN_MINUTE);
            if (!isTrendIntact(candles15m, optionType)) {
                log.debug("[BreakoutReentry] Re-entry skipped: 15-min trend not intact");
                return;
            }
        }

        // Open trade
        String tradeId = "PAPER-BRENTRY-" + UUID.randomUUID().toString().substring(0, 8);
        String reason = String.format("BREAKOUT_REENTRY_%s: %s %s, spot=%.0f, BB=[%.0f/%.0f/%.0f], VWAP=%.0f, ROC=%.1f%%, ATR=%.1f",
                isReentry ? "REENTRY" : "FRESH", optionType,
                isReentry ? "#" + (optionType == OptionType.CE ? ceEntriesToday + 1 : peEntriesToday + 1) : "#1",
                spot, lowerBand, middleBand, upperBand, vwap.doubleValue(), optionRoc, atr);

        TradeEntity trade = new TradeEntity(tradeId, inst.get().instrumentKey(), UNDERLYING,
                optionType.name(), TradeStatus.OPEN, LOT_SIZE, premium, Instant.now(), reason);
        trade.setStrategyType(StrategyType.BREAKOUT_REENTRY.name());
        tradeRepository.save(trade);

        activeTradeId = tradeId;
        if (optionType == OptionType.CE) ceEntriesToday++;
        else peEntriesToday++;

        // Record entry signal for reports
        com.algo.trade.persistence.StrategyDecisionEntity decisionEntity =
                com.algo.trade.persistence.StrategyDecisionEntity.forStrategy(
                        StrategyType.BREAKOUT_REENTRY.name(), Instant.now(), UNDERLYING,
                        "BUY_" + optionType.name(), optionType.name(),
                        BigDecimal.valueOf(spot), BigDecimal.valueOf(70), reason);
        decisionEntity.setOptionPrice(premium);
        decisionEntity.setSelectedInstrumentKey(inst.get().instrumentKey());
        decisionEntity.setPaperTrade(true);
        decisionEntity.setExecutionStage("PAPER_FILLED");
        decisionEntity.setExecutionReason("Breakout re-entry paper trade opened");
        decisionRepository.save(decisionEntity);

        log.info("[BreakoutReentry] {} opened: {} strike={}, premium=₹{}, ROC={}%, squeeze={}",
                isReentry ? "Re-entry" : "Fresh entry", optionType, atmStrike, premium,
                String.format("%.1f", optionRoc), squeezeDetected);
    }

    // ── Re-entry Condition ──────────────────────────────────────────────────

    private boolean checkReentryCondition(List<Candle> candles, double spot,
                                           double middleBand, double upperBand, double lowerBand) {
        if (candles.size() < 3) return false;

        // Previous candle touched or crossed middle band (pullback)
        double prevClose = candles.get(candles.size() - 2).close().doubleValue();
        boolean pulledBack;
        if (lastBreakoutDirection == OptionType.CE) {
            pulledBack = prevClose <= middleBand * 1.002; // Within 0.2% of middle band
            // Current candle bounced back above upper band
            return pulledBack && spot > upperBand;
        } else {
            pulledBack = prevClose >= middleBand * 0.998;
            return pulledBack && spot < lowerBand;
        }
    }

    // ── Exit Logic ──────────────────────────────────────────────────────────

    private void checkExit(Candle candle) {
        if (activeTradeId == null) return;

        TradeEntity trade = tradeRepository.findById(activeTradeId).orElse(null);
        if (trade == null || trade.getStatus() != TradeStatus.OPEN) {
            activeTradeId = null;
            return;
        }

        BigDecimal currentPrice = getLastPrice(trade.getInstrumentKey());
        if (currentPrice.signum() <= 0) return;

        double pnlPercent = currentPrice.subtract(trade.getEntryPrice())
                .divide(trade.getEntryPrice(), MC).doubleValue() * 100;
        long holdMinutes = java.time.Duration.between(trade.getEntryTime(), Instant.now()).toMinutes();

        // Target hit
        if (pnlPercent >= TARGET_PERCENT) {
            closeTrade(currentPrice, "TARGET: +" + String.format("%.1f%%", pnlPercent));
            return;
        }

        // Time stop
        if (holdMinutes >= MAX_HOLD_MINUTES) {
            closeTrade(currentPrice, "TIME_STOP: held " + holdMinutes + " min, pnl=" + String.format("%.1f%%", pnlPercent));
            return;
        }

        // Stop loss: close below middle band AND swing low break
        List<Candle> candles5m = liveCandleBuilder.getHistory(NIFTY_SPOT_TOKEN, Timeframe.FIVE_MINUTE);
        if (candles5m.size() >= BB_PERIOD) {
            double[] bb = calculateBollingerBands(candles5m);
            double middleBand = bb[1];
            double spot = candle.close().doubleValue();

            boolean belowMiddle = (trade.getOptionType().equals("CE") && spot < middleBand)
                    || (trade.getOptionType().equals("PE") && spot > middleBand);

            if (belowMiddle && isSwingLowBroken(candles5m, trade.getOptionType())) {
                closeTrade(currentPrice, "BB_SL: below middle band + swing break, pnl=" + String.format("%.1f%%", pnlPercent));
                return;
            }
        }

        // Hard SL fallback
        if (pnlPercent <= -STOPLOSS_PERCENT) {
            closeTrade(currentPrice, "HARD_SL: " + String.format("%.1f%%", pnlPercent));
        }
    }

    private boolean isSwingLowBroken(List<Candle> candles, String optionType) {
        if (candles.size() < 4) return false;

        // Swing low = lowest low of last 3 candles (excluding current)
        double swingLow = candles.subList(candles.size() - 4, candles.size() - 1).stream()
                .mapToDouble(c -> c.low().doubleValue()).min().orElse(0);
        double swingHigh = candles.subList(candles.size() - 4, candles.size() - 1).stream()
                .mapToDouble(c -> c.high().doubleValue()).max().orElse(Double.MAX_VALUE);

        double currentClose = candles.getLast().close().doubleValue();

        if ("CE".equals(optionType)) {
            return currentClose < swingLow; // Broke below swing low = bearish
        } else {
            return currentClose > swingHigh; // Broke above swing high = bullish (bad for PE)
        }
    }

    private void closeTrade(BigDecimal exitPrice, String reason) {
        if (activeTradeId == null) return;
        TradeEntity trade = tradeRepository.findById(activeTradeId).orElse(null);
        if (trade == null || trade.getStatus() != TradeStatus.OPEN) {
            activeTradeId = null;
            return;
        }

        if (exitPrice == null || exitPrice.signum() <= 0) exitPrice = trade.getEntryPrice();

        BigDecimal pnl = exitPrice.subtract(trade.getEntryPrice()).multiply(BigDecimal.valueOf(LOT_SIZE));
        trade.close(exitPrice, Instant.now(), pnl, "PAPER_EXIT: " + reason);
        tradeRepository.save(trade);

        boolean isLoss = pnl.signum() < 0;
        if (isLoss) {
            lossesToday++;
            cooldownCandlesRemaining = COOLDOWN_CANDLES_AFTER_SL;
            if (lossesToday >= MAX_LOSSES_PER_DAY) {
                stoppedForDay = true;
                log.warn("[BreakoutReentry] Max losses ({}) reached — stopped for day", lossesToday);
            }
        }

        log.info("[BreakoutReentry] Trade closed: id={}, entry=₹{}, exit=₹{}, pnl=₹{}, reason={}",
                activeTradeId, trade.getEntryPrice(), exitPrice, pnl, reason);
        activeTradeId = null;
    }

    // ── Bollinger Band Calculation ──────────────────────────────────────────

    private double[] calculateBollingerBands(List<Candle> candles) {
        int size = candles.size();
        int start = size - BB_PERIOD;
        if (start < 0) start = 0;

        // Middle band = 20-period SMA
        double sum = 0;
        for (int i = start; i < size; i++) {
            sum += candles.get(i).close().doubleValue();
        }
        double middle = sum / BB_PERIOD;

        // Standard deviation
        double variance = 0;
        for (int i = start; i < size; i++) {
            double diff = candles.get(i).close().doubleValue() - middle;
            variance += diff * diff;
        }
        double stdDev = Math.sqrt(variance / BB_PERIOD);

        double upper = middle + BB_STD_DEV * stdDev;
        double lower = middle - BB_STD_DEV * stdDev;
        double bandWidth = upper - lower;

        // Average band width (for squeeze detection)
        double avgBandWidth = bandWidth; // Simplified — use current as proxy
        if (size > BB_PERIOD + 10) {
            double sumWidth = 0;
            for (int i = size - BB_PERIOD - 10; i < size - BB_PERIOD; i++) {
                double localSum = 0;
                for (int j = i; j < i + BB_PERIOD && j < size; j++) {
                    localSum += candles.get(j).close().doubleValue();
                }
                double localMean = localSum / BB_PERIOD;
                double localVar = 0;
                for (int j = i; j < i + BB_PERIOD && j < size; j++) {
                    double d = candles.get(j).close().doubleValue() - localMean;
                    localVar += d * d;
                }
                double localStd = Math.sqrt(localVar / BB_PERIOD);
                sumWidth += 2 * BB_STD_DEV * localStd;
            }
            avgBandWidth = sumWidth / 10;
        }

        return new double[]{upper, middle, lower, bandWidth, avgBandWidth};
    }

    private double calculateATR(List<Candle> candles, int period) {
        if (candles.size() < period + 1) return 0;
        double sum = 0;
        for (int i = candles.size() - period; i < candles.size(); i++) {
            double high = candles.get(i).high().doubleValue();
            double low = candles.get(i).low().doubleValue();
            double prevClose = candles.get(i - 1).close().doubleValue();
            double tr = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
            sum += tr;
        }
        return sum / period;
    }

    private boolean isTrendIntact(List<Candle> candles15m, OptionType direction) {
        if (candles15m.size() < 5) return true; // Not enough data, allow
        double currentClose = candles15m.getLast().close().doubleValue();
        // Simple: price above 5-period SMA for bullish, below for bearish
        double sma = candles15m.subList(candles15m.size() - 5, candles15m.size()).stream()
                .mapToDouble(c -> c.close().doubleValue()).average().orElse(currentClose);
        return direction == OptionType.CE ? currentClose > sma : currentClose < sma;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private boolean isEnabled() {
        try {
            StrategyConfig config = strategyConfigService.getConfig(StrategyType.BREAKOUT_REENTRY, UNDERLYING);
            return config != null && config.isEnabled() && config.isPaperTrading();
        } catch (Exception e) {
            return false;
        }
    }

    private void resetDay(LocalDate today) {
        currentDay = today;
        lossesToday = 0;
        ceEntriesToday = 0;
        peEntriesToday = 0;
        cooldownCandlesRemaining = 0;
        squeezeDetected = false;
        lastBreakoutDirection = null;
        lastOptionPrice = BigDecimal.ZERO;
        stoppedForDay = false;
        activeTradeId = null;
        log.info("[BreakoutReentry] Day reset: {}", today);
    }

    private BigDecimal getLastPrice(String instrumentKey) {
        return marketDataService.quote(instrumentKey).map(Quote::lastPrice).orElse(BigDecimal.ZERO);
    }

    private Optional<Instrument> resolveOption(int strike, OptionType optionType) {
        LocalDate expiry = instrumentCache.nearestExpiry(UnderlyingSymbol.NIFTY, LocalDate.now(IST)).orElse(null);
        if (expiry == null) return Optional.empty();
        return instrumentCache.findOption(UnderlyingSymbol.NIFTY, expiry, BigDecimal.valueOf(strike), optionType);
    }

    private int roundToStrike(double spot, int interval) {
        return (int) (Math.round(spot / interval) * interval);
    }

    // ── Public API ──────────────────────────────────────────────────────────

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", isEnabled());
        status.put("underlying", UNDERLYING);
        status.put("paperTrading", true);
        status.put("currentDay", currentDay);
        status.put("activeTradeId", activeTradeId);
        status.put("ceEntriesToday", ceEntriesToday);
        status.put("peEntriesToday", peEntriesToday);
        status.put("maxEntriesPerDirection", MAX_ENTRIES_PER_DIRECTION);
        status.put("lossesToday", lossesToday);
        status.put("maxLossesPerDay", MAX_LOSSES_PER_DAY);
        status.put("stoppedForDay", stoppedForDay);
        status.put("cooldownCandlesRemaining", cooldownCandlesRemaining);
        status.put("squeezeDetected", squeezeDetected);
        status.put("lastBreakoutDirection", lastBreakoutDirection);
        status.put("targetPercent", TARGET_PERCENT);
        status.put("stoplossPercent", STOPLOSS_PERCENT);
        status.put("maxHoldMinutes", MAX_HOLD_MINUTES);
        return status;
    }
}
