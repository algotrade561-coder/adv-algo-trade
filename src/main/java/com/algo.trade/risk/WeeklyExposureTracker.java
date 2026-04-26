package com.algo.trade.risk;

import com.algo.trade.domain.TradeStatus;
import com.algo.trade.persistence.TradeEntity;
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
        return weeklyExposure.get().add(premiumCost).compareTo(weeklyExposureCap) <= 0;
    }

    public void recordTrade(BigDecimal premiumCost) {
        weeklyExposure.updateAndGet(v -> v.add(premiumCost));
        log.info("[WeeklyExposure] Recorded ₹{} | Total: ₹{} / ₹{}",
                premiumCost.setScale(0, java.math.RoundingMode.HALF_UP),
                weeklyExposure.get().setScale(0, java.math.RoundingMode.HALF_UP),
                weeklyExposureCap.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    public BigDecimal getWeeklyExposure() { return weeklyExposure.get(); }
    public BigDecimal getWeeklyExposureCap() { return weeklyExposureCap; }
    public BigDecimal getRemainingCapacity() { return weeklyExposureCap.subtract(weeklyExposure.get()); }

    @Scheduled(cron = "0 0 9 * * MON", zone = "Asia/Kolkata")
    public void resetWeekly() {
        BigDecimal prev = weeklyExposure.getAndSet(BigDecimal.ZERO);
        log.info("[WeeklyExposure] Reset for new week. Last week: ₹{}", prev.setScale(0, java.math.RoundingMode.HALF_UP));
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 5_000)
    public void recalculate() {
        try {
            LocalDate monday = LocalDate.now(IST).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            Instant weekStart = monday.atStartOfDay(IST).toInstant();
            BigDecimal total = tradeRepository.findByEntryTimeBetween(weekStart, Instant.now()).stream()
                    .filter(t -> !t.isPaperTrade())
                    .map(t -> t.getEntryPrice().multiply(BigDecimal.valueOf(t.getQuantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            weeklyExposure.set(total);
        } catch (Exception e) {
            log.debug("[WeeklyExposure] Recalculate failed: {}", e.getMessage());
        }
    }
}
