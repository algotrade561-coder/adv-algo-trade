package com.algo.trade.execution;

import com.algo.trade.domain.IndexType;
import com.algo.trade.marketdata.ExpiryCalendar;
import com.algo.trade.notification.TelegramAlertService;
import com.algo.trade.persistence.TradeEntity;
import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * BTST Position Manager — handles next-day exit logic for overnight carry positions.
 *
 * Responsibilities:
 * 1. On market open (9:15 AM), identify BTST positions from previous day
 * 2. At configured time (default 10:00 AM), evaluate exit using BTSTMorningClassifier
 * 3. Manage hard SL, max hold days, expiry-day exit, and gap protection
 *
 * BTST positions are identified by:
 * - Entry date < today
 * - Position still open (exitTime == null)
 * - Product type = NRML (set via config)
 */
@Component
public class BTSTPositionManager implements DailyResettable {

    private static final Logger log = LoggerFactory.getLogger(BTSTPositionManager.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradeRepository tradeRepository;
    private final BTSTMorningClassifier morningClassifier;
    private final ExpiryCalendar expiryCalendar;
    private final TelegramAlertService alertService;
    private final TradingStateService tradingStateService;

    @Value("${trading.btst.enabled:false}")
    private boolean btstEnabled;

    @Value("${trading.btst.max-carry-days:2}")
    private int maxCarryDays;

    @Value("${trading.btst.next-day-exit-hour:10}")
    private int nextDayExitHour;

    @Value("${trading.btst.next-day-exit-minute:0}")
    private int nextDayExitMinute;

    @Value("${trading.btst.min-profit-to-carry:10.0}")
    private double minProfitToCarry;

    @Value("${trading.btst.gap-loss-exit-percent:25.0}")
    private double gapLossExitPercent;

    @Value("${trading.btst.hard-sl-percent:20.0}")
    private double hardSlPercent;

    private volatile boolean morningCheckDone = false;
    private volatile LocalDate lastCheckDate = null;

    public BTSTPositionManager(TradeRepository tradeRepository,
                                BTSTMorningClassifier morningClassifier,
                                ExpiryCalendar expiryCalendar,
                                TelegramAlertService alertService,
                                TradingStateService tradingStateService) {
        this.tradeRepository = tradeRepository;
        this.morningClassifier = morningClassifier;
        this.expiryCalendar = expiryCalendar;
        this.alertService = alertService;
        this.tradingStateService = tradingStateService;
    }

    /**
     * Runs every 30 seconds during market hours. Manages BTST positions carried overnight.
     */
    @Scheduled(fixedDelay = 30_000)
    public void manageBTSTPositions() {
        if (!btstEnabled) return;

        LocalTime now = LocalTime.now(IST);
        LocalDate today = LocalDate.now(IST);

        if (lastCheckDate == null || !lastCheckDate.equals(today)) {
            morningCheckDone = false;
            lastCheckDate = today;
        }

        if (now.isBefore(LocalTime.of(9, 16)) || now.isAfter(LocalTime.of(15, 25))) return;

        List<TradeEntity> btstPositions = getBTSTPositions();
        if (btstPositions.isEmpty()) return;

        // Morning alert + gap risk protection
        if (!morningCheckDone && now.isAfter(LocalTime.of(9, 16))) {
            morningCheckDone = true;
            log.info("[BTST] Morning check: {} positions carried overnight", btstPositions.size());
            alertService.systemAlert("🌅 BTST morning: " + btstPositions.size()
                    + " positions from yesterday. Monitoring for exit signals.");

            for (TradeEntity pos : btstPositions) {
                double profitPct = calculateProfitPercent(pos);
                if (profitPct <= -gapLossExitPercent) {
                    log.error("[BTST] OVERNIGHT GAP LOSS for {}: {}% — flagging for exit",
                            pos.getInstrumentKey(), String.format("%.1f", profitPct));
                    alertService.systemAlert(String.format(
                            "🚨 BTST GAP LOSS: %s dropped %.1f%% overnight",
                            pos.getInstrumentKey(), profitPct));
                    // Mark for exit — actual exit handled by LivePositionExitMonitor
                    pos.setExitReason("BTST_OVERNIGHT_GAP_SL");
                    tradeRepository.save(pos);
                }
            }
        }

        // Before configured exit time: only exit on hard SL
        if (now.isBefore(LocalTime.of(nextDayExitHour, nextDayExitMinute))) {
            for (TradeEntity pos : btstPositions) {
                double profitPct = calculateProfitPercent(pos);
                if (profitPct <= -hardSlPercent) {
                    log.warn("[BTST] Hard SL hit for {}: profit={}%", pos.getInstrumentKey(),
                            String.format("%.1f", profitPct));
                    pos.setExitReason("BTST_HARD_SL");
                    tradeRepository.save(pos);
                }
            }
            return;
        }

        // At 9:30–9:45 AM: morning classifier
        if (now.isAfter(LocalTime.of(9, 30)) && now.isBefore(LocalTime.of(9, 46))) {
            for (TradeEntity pos : btstPositions) {
                classifyAndAct(pos);
            }
        }

        // After exit time: evaluate normal exit conditions
        for (TradeEntity pos : btstPositions) {
            evaluateBTSTExit(pos, now, today);
        }
    }

    private void classifyAndAct(TradeEntity pos) {
        try {
            IndexType indexType = resolveIndex(pos);
            if (indexType == null) return;

            String direction = pos.getOptionType() != null && pos.getOptionType().contains("CE")
                    ? "BULLISH" : "BEARISH";

            BTSTMorningClassifier.ClassificationResult result = morningClassifier.classify(indexType, direction);

            switch (result.verdict()) {
                case HOLD -> log.info("[BTST] HOLD {} — score={} gap={}%",
                        pos.getInstrumentKey(), result.score(), String.format("%.2f", result.gapPercent()));
                case EXIT_QUICK -> {
                    log.warn("[BTST] EXIT_QUICK {} — score={}", pos.getInstrumentKey(), result.score());
                    pos.setExitReason("BTST_MORNING_FADE");
                    tradeRepository.save(pos);
                }
                case MONITOR -> log.info("[BTST] MONITOR {} — normal trailing applies", pos.getInstrumentKey());
            }
        } catch (Exception e) {
            log.error("[BTST] Morning classification failed for {}: {}", pos.getInstrumentKey(), e.getMessage());
        }
    }

    private void evaluateBTSTExit(TradeEntity pos, LocalTime now, LocalDate today) {
        if (pos.getEntryTime() == null) return;
        long daysHeld = ChronoUnit.DAYS.between(
                pos.getEntryTime().atZone(IST).toLocalDate(), today);

        // Max carry days
        if (daysHeld >= maxCarryDays) {
            log.warn("[BTST] Max carry days exceeded for {} — flagging exit", pos.getInstrumentKey());
            pos.setExitReason("BTST_MAX_CARRY_DAYS");
            tradeRepository.save(pos);
            return;
        }

        // Expiry day: exit by 2:00 PM
        if (now.isAfter(LocalTime.of(14, 0))) {
            IndexType idx = resolveIndex(pos);
            if (idx != null && expiryCalendar.isExpiryDay(idx)) {
                log.warn("[BTST] Expiry day exit for {}", pos.getInstrumentKey());
                pos.setExitReason("BTST_EXPIRY_DAY_EXIT");
                tradeRepository.save(pos);
                return;
            }
        }

        // EOD: close by 3:00 PM
        if (now.isAfter(LocalTime.of(15, 0))) {
            pos.setExitReason("BTST_NEXT_DAY_EOD");
            tradeRepository.save(pos);
        }
    }

    private List<TradeEntity> getBTSTPositions() {
        Instant todayStart = LocalDate.now(IST).atStartOfDay(IST).toInstant();
        return tradeRepository.findAll().stream()
                .filter(t -> t.getExitTime() == null) // still open
                .filter(t -> t.getEntryTime() != null && t.getEntryTime().isBefore(todayStart))
                .toList();
    }

    private double calculateProfitPercent(TradeEntity pos) {
        if (pos.getEntryPrice() == null || pos.getEntryPrice().signum() <= 0) return 0;
        // Use peak price as best approximation of current unrealized position value
        if (pos.getPeakPrice() == null || pos.getPeakPrice().signum() <= 0) return 0;
        return pos.getPeakPrice().subtract(pos.getEntryPrice())
                .divide(pos.getEntryPrice(), 4, java.math.RoundingMode.HALF_UP)
                .doubleValue() * 100;
    }

    private IndexType resolveIndex(TradeEntity pos) {
        String key = pos.getInstrumentKey();
        if (key == null) return null;
        String upper = key.toUpperCase();
        if (upper.contains("BANKNIFTY")) return IndexType.BANKNIFTY;
        if (upper.contains("SENSEX")) return IndexType.SENSEX;
        if (upper.contains("NIFTY")) return IndexType.NIFTY;
        return IndexType.NIFTY;
    }

    @Override
    public void resetDaily() {
        morningCheckDone = false;
    }
}
