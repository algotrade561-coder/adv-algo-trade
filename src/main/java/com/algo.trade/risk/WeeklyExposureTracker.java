package com.algo.trade.risk;

import com.algo.trade.persistence.TradeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tracks cumulative premium spent on option buying this week.
 * Blocks new trades if weekly exposure exceeds cap.
 * Default cap: ₹50,000 per week (configurable via GlobalConfig).
 */
@Component
public class WeeklyExposureTracker {

    private static final Logger log = LoggerFactory.getLogger(WeeklyExposureTracker.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final TradeRepository tradeRepository;
    private final AtomicReference<BigDecimal> weeklyExposure = new AtomicReference<>(BigDecimal.ZERO);
    private BigDecimal weeklyExposureCap = BigDecimal.valueOf(50_000);

    public WeeklyExposureTracker(TradeRepository tradeRepository) {
        this.tradeRepository = tradeRepository;
    }

    public boolean canTrade(BigDecimal premiumCost) {
        recalculate();
        return weeklyExposure.get().add(premiumCost).compareTo(weeklyExposureCap) <= 0;
    }

    public void recordTrade(BigDecimal premiumCost) {
        // Don't manually add — let recalculate() be the source of truth.
        // This avoids double-counting when recalculate runs after recordTrade.
        log.info("[WeeklyExposure] Trade recorded (₹{}) — next recalculate will update total",
                premiumCost.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    public BigDecimal getWeeklyExposure() { return weeklyExposure.get(); }
    public BigDecimal getWeeklyExposureCap() { return weeklyExposureCap; }
    public BigDecimal getRemainingCapacity() { return weeklyExposureCap.subtract(weeklyExposure.get()); }

    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Kolkata")
    public void resetWeekly() {
        BigDecimal prev = weeklyExposure.getAndSet(BigDecimal.ZERO);
        log.info("[WeeklyExposure] Reset for new week. Last week: ₹{}", prev.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 1_000)
    public void recalculate() {
        try {
            LocalDate monday = LocalDate.now(IST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            Instant weekStart = monday.atStartOfDay(IST).toInstant();
            BigDecimal total = tradeRepository.findByEntryTimeBetween(weekStart, Instant.now()).stream()
                    .filter(t -> !t.isPaperTrade())
                    .filter(t -> !t.getTradeId().startsWith("SYNC-"))
                    .filter(t -> t.getStatus() == com.algo.trade.domain.TradeStatus.OPEN)
                    .filter(t -> t.getEntryPrice() != null && t.getEntryPrice().signum() > 0)
                    .filter(t -> t.getQuantity() > 0)
                    .map(t -> t.getEntryPrice().multiply(BigDecimal.valueOf(t.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal prev = weeklyExposure.getAndSet(total);
            if (prev.compareTo(total) != 0) {
                log.info("[WeeklyExposure] Recalculated: ₹{} (was ₹{})", 
                        total.setScale(0, java.math.RoundingMode.HALF_UP),
                        prev.setScale(0, java.math.RoundingMode.HALF_UP));
            }
        } catch (Exception e) {
            log.warn("[WeeklyExposure] Recalculate failed: {}", e.getMessage());
        }
    }
}
