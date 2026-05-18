package com.algo.trade.monitoring;

import com.algo.trade.domain.OrderSide;
import com.algo.trade.domain.PositionGroup;
import com.algo.trade.domain.SpreadLeg;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Tracks hedge cost vs premium collected for spread entries (daily aggregates).
 */
@Component
public class HedgeCostTracker {

    private static final Logger log = LoggerFactory.getLogger(HedgeCostTracker.class);
    private static final MathContext MC = MathContext.DECIMAL64;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final AtomicReference<LocalDate> aggregateDate = new AtomicReference<>(LocalDate.now(IST));
    private final Map<String, TradeHedgeStats> byGroup = new ConcurrentHashMap<>();

    private volatile BigDecimal dailyPremiumCollected = BigDecimal.ZERO;
    private volatile BigDecimal dailyHedgeCostPaid = BigDecimal.ZERO;
    private volatile BigDecimal dailyNetCredit = BigDecimal.ZERO;

    public record TradeHedgeStats(
            String groupId,
            BigDecimal premiumCollected,
            BigDecimal hedgeCostPaid,
            BigDecimal netCredit,
            int hedgeDistanceStrikes
    ) {}

    public record DailySummary(
            LocalDate date,
            BigDecimal totalPremiumCollected,
            BigDecimal totalHedgeCostPaid,
            BigDecimal netCredit,
            double hedgeEfficiencyPercent,
            String distanceRecommendation
    ) {}

    public void recordEntry(PositionGroup group) {
        resetIfNewDay();
        TradeHedgeStats stats = computeStats(group);
        byGroup.put(group.groupId(), stats);
        dailyPremiumCollected = dailyPremiumCollected.add(stats.premiumCollected(), MC);
        dailyHedgeCostPaid = dailyHedgeCostPaid.add(stats.hedgeCostPaid(), MC);
        dailyNetCredit = dailyNetCredit.add(stats.netCredit(), MC);
    }

    public void recordExit(PositionGroup group, BigDecimal pnl) {
        TradeHedgeStats stats = byGroup.remove(group.groupId());
        if (stats != null) {
            // Subtract this group's contribution from daily aggregates
            dailyPremiumCollected = dailyPremiumCollected.subtract(stats.premiumCollected(), MC);
            dailyHedgeCostPaid = dailyHedgeCostPaid.subtract(stats.hedgeCostPaid(), MC);
            dailyNetCredit = dailyNetCredit.subtract(stats.netCredit(), MC);
        }
        log.debug("HedgeCostTracker exit recorded: group={} pnl={} statsRemoved={}",
                group.groupId(), pnl, stats != null);
    }

    public DailySummary dailySummary() {
        double efficiency = hedgeEfficiencyPercent(dailyPremiumCollected, dailyHedgeCostPaid);
        return new DailySummary(
                aggregateDate.get(),
                dailyPremiumCollected,
                dailyHedgeCostPaid,
                dailyNetCredit,
                efficiency,
                getHedgeDistanceAdjustment(efficiency));
    }

    /**
     * &lt; 40% efficiency → widen hedge; &gt; 80% → tighten; else hold.
     */
    public static String getHedgeDistanceAdjustment(double efficiencyPercent) {
        if (efficiencyPercent < 40) {
            return "WIDEN";
        }
        if (efficiencyPercent > 80) {
            return "TIGHTEN";
        }
        return "HOLD";
    }

    private static double hedgeEfficiencyPercent(BigDecimal premium, BigDecimal hedgeCost) {
        if (premium.signum() <= 0) {
            return 0;
        }
        BigDecimal net = premium.subtract(hedgeCost, MC);
        return net.multiply(BigDecimal.valueOf(100), MC)
                .divide(premium, MC)
                .doubleValue();
    }

    private static TradeHedgeStats computeStats(PositionGroup group) {
        BigDecimal premium = BigDecimal.ZERO;
        BigDecimal hedge = BigDecimal.ZERO;
        int wingDistance = 0;
        for (SpreadLeg leg : group.legs()) {
            BigDecimal px = group.entryPrices().getOrDefault(leg.instrumentKey(), BigDecimal.ZERO);
            BigDecimal legVal = px.multiply(BigDecimal.valueOf(leg.quantity()), MC);
            if (leg.side() == OrderSide.SELL) {
                premium = premium.add(legVal, MC);
            } else {
                hedge = hedge.add(legVal, MC);
                wingDistance = Math.max(wingDistance, Math.abs(leg.strike()));
            }
        }
        BigDecimal net = premium.subtract(hedge, MC);
        return new TradeHedgeStats(group.groupId(), premium, hedge, net, wingDistance);
    }

    private void resetIfNewDay() {
        LocalDate today = LocalDate.now(IST);
        if (!today.equals(aggregateDate.get())) {
            aggregateDate.set(today);
            dailyPremiumCollected = BigDecimal.ZERO;
            dailyHedgeCostPaid = BigDecimal.ZERO;
            dailyNetCredit = BigDecimal.ZERO;
            byGroup.clear();
        }
    }

    @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
    public void logDailySummary() {
        DailySummary s = dailySummary();
        log.info("[HedgeCost] Daily summary {}: premium={} hedge={} net={} efficiency={}% recommendation={}",
                s.date(), s.totalPremiumCollected().setScale(0, RoundingMode.HALF_UP),
                s.totalHedgeCostPaid().setScale(0, RoundingMode.HALF_UP),
                s.netCredit().setScale(0, RoundingMode.HALF_UP),
                String.format("%.1f", s.hedgeEfficiencyPercent()), s.distanceRecommendation());
    }
}
