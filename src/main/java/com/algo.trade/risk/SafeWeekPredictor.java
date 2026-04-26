package com.algo.trade.risk;

import com.algo.trade.domain.IndexType;
import com.algo.trade.indicator.IVRankTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;

/**
 * Rule-based week safety predictor.
 * Score >= 60 = SAFE (full sizing), 40-60 = MODERATE (half), < 40 = RISKY (quarter).
 */
@Component
public class SafeWeekPredictor {

    private static final Logger log = LoggerFactory.getLogger(SafeWeekPredictor.class);

    private final MarketGuard marketGuard;
    private final IVRankTracker ivRankTracker;
    private final WeeklyExposureTracker weeklyExposure;

    private volatile int currentScore = 50;
    private volatile String currentRisk = "MODERATE";

    public SafeWeekPredictor(MarketGuard marketGuard, IVRankTracker ivRankTracker,
                              WeeklyExposureTracker weeklyExposure) {
        this.marketGuard = marketGuard;
        this.ivRankTracker = ivRankTracker;
        this.weeklyExposure = weeklyExposure;
    }

    @Scheduled(fixedDelay = 60_000)
    public void evaluate() {
        if (!isMarketHours()) return;
        int score = 50;

        double vix = marketGuard.getCurrentVix();
        if (vix >= 12 && vix <= 18) score += 20;
        else if (vix >= 10 && vix <= 22) score += 10;
        else score -= 15;

        double ivRank = ivRankTracker.getIVRank(IndexType.NIFTY);
        if (ivRank > 0 && ivRank < 30) score += 15;
        else if (ivRank > 70) score -= 10;

        DayOfWeek day = LocalDate.now().getDayOfWeek();
        if (day == DayOfWeek.MONDAY || day == DayOfWeek.TUESDAY) score += 5;
        else if (day == DayOfWeek.FRIDAY) score -= 5;

        if (weeklyExposure.getRemainingCapacity().doubleValue() > weeklyExposure.getWeeklyExposureCap().doubleValue() * 0.5) {
            score += 5;
        } else {
            score -= 5;
        }

        score = Math.max(0, Math.min(100, score));
        currentScore = score;
        currentRisk = score >= 60 ? "SAFE" : score >= 40 ? "MODERATE" : "RISKY";
        log.debug("[SafeWeek] Score={} Risk={}", score, currentRisk);
    }

    public int getScore() { return currentScore; }
    public String getRisk() { return currentRisk; }
    public boolean isSafe() { return currentScore >= 60; }
    public double getSizeMultiplier() {
        if (currentScore >= 60) return 1.0;
        if (currentScore >= 40) return 0.5;
        return 0.25;
    }

    private boolean isMarketHours() {
        LocalTime now = LocalTime.now(java.time.ZoneId.of("Asia/Kolkata"));
        return now.isAfter(LocalTime.of(9, 16)) && now.isBefore(LocalTime.of(15, 25));
    }
}
