package com.algo.trade.marketdata;

import com.algo.trade.domain.IndexType;
import com.algo.trade.config.MarketCalendarProperties;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Computes expiry dates for index options.
 * Handles NSE/BSE trading holidays, weekly/monthly expiries,
 * and expiry-day danger zones.
 */
@Component
public class ExpiryCalendar {

    private final List<LocalDate> holidays;

    public ExpiryCalendar(MarketCalendarProperties calendarProperties) {
        List<LocalDate> cfg = calendarProperties != null ? calendarProperties.getHolidays() : null;
        this.holidays = (cfg == null) ? List.of() : List.copyOf(cfg);
    }

    /**
     * Get the current/next weekly expiry for an index.
     * If today is expiry day and market is still open (before 15:00), returns today.
     * After 15:00 on expiry day, returns next week's expiry.
     */
    public LocalDate getCurrentWeeklyExpiry(IndexType indexType) {
        return getCurrentWeeklyExpiry(indexType, LocalDate.now(), LocalTime.now());
    }

    LocalDate getCurrentWeeklyExpiry(IndexType indexType, LocalDate today, LocalTime now) {
        DayOfWeek expiryDay = indexType.expiryDay();
        LocalDate candidate = today;
        for (int i = 0; i < 7; i++) {
            if (candidate.getDayOfWeek() == expiryDay) {
                if (candidate.equals(today) && now.isAfter(LocalTime.of(15, 0))) {
                    candidate = adjustForHoliday(candidate.plusWeeks(1));
                } else {
                    candidate = adjustForHoliday(candidate);
                }
                return candidate;
            }
            candidate = candidate.plusDays(1);
        }
        return candidate;
    }

    /**
     * Returns the current expiry for an index, handling:
     * - Weekly indices: next weekly expiry (with holiday preponement).
     * - Monthly-only indices: last <expiryDay> of the month (with holiday preponement).
     *
     * <p>For monthly-only indices, if the resolved monthly expiry has already passed for the
     * current session (after 15:00 on expiry day), this rolls forward to next month.
     */
    public LocalDate getCurrentExpiry(IndexType indexType) {
        return getCurrentExpiry(indexType, LocalDate.now(), LocalTime.now());
    }

    LocalDate getCurrentExpiry(IndexType indexType, LocalDate today, LocalTime now) {
        if (indexType.hasWeeklyExpiry()) {
            return getCurrentWeeklyExpiry(indexType, today, now);
        }
        LocalDate candidate = getMonthlyExpiry(indexType, today.getYear(), today.getMonthValue());
        // If we've crossed the monthly expiry session (post 15:00), roll to next month.
        if (candidate.isBefore(today) || (candidate.equals(today) && now.isAfter(LocalTime.of(15, 0)))) {
            LocalDate next = today.plusMonths(1);
            candidate = getMonthlyExpiry(indexType, next.getYear(), next.getMonthValue());
        }
        return adjustForHoliday(candidate);
    }

    public LocalDate getNextWeeklyExpiry(IndexType indexType) {
        return adjustForHoliday(getCurrentWeeklyExpiry(indexType).plusWeeks(1));
    }

    public LocalDate getMonthlyExpiry(IndexType indexType, int year, int month) {
        LocalDate lastDay = LocalDate.of(year, month, 1)
                .withDayOfMonth(LocalDate.of(year, month, 1).lengthOfMonth());
        LocalDate candidate = lastDay;
        while (candidate.getDayOfWeek() != indexType.expiryDay()) {
            candidate = candidate.minusDays(1);
        }
        return adjustForHoliday(candidate);
    }

    public boolean isExpiryDay(IndexType indexType) {
        return isExpiryDay(indexType, LocalDate.now(), LocalTime.now());
    }

    boolean isExpiryDay(IndexType indexType, LocalDate today, LocalTime now) {
        if (isHoliday(today)) return false;
        return today.equals(getCurrentExpiry(indexType, today, now));
    }

    /** After 14:00 on expiry day — gamma risk is extreme, no new entries. */
    public boolean isExpiryAfternoon(IndexType indexType) {
        return isExpiryDay(indexType) && LocalTime.now().isAfter(LocalTime.of(14, 0));
    }

    /** After 15:00 on expiry day — last 30 mins, extreme gamma, exit all positions. */
    public boolean isExpiryDangerZone(IndexType indexType) {
        return isExpiryDay(indexType) && LocalTime.now().isAfter(LocalTime.of(15, 0));
    }

    /** Within 2 days of expiry but not expiry day itself — consider rolling to next expiry. */
    public boolean shouldRollToNextExpiry(IndexType indexType) {
        return isNearExpiry(indexType, 2) && !isExpiryDay(indexType);
    }

    public boolean isNearExpiry(IndexType indexType, int daysThreshold) {
        long days = ChronoUnit.DAYS.between(LocalDate.now(), getCurrentExpiry(indexType));
        return days <= daysThreshold;
    }

    public long daysToExpiry(IndexType indexType) {
        return ChronoUnit.DAYS.between(LocalDate.now(), getCurrentExpiry(indexType));
    }

    public List<LocalDate> getUpcomingExpiries(IndexType indexType, int weeks) {
        List<LocalDate> expiries = new ArrayList<>();
        LocalDate current = getCurrentExpiry(indexType);
        for (int i = 0; i < weeks; i++) {
            expiries.add(current);
            // Monthly-only indices still return a sequence of upcoming "expiry sessions" for visibility.
            // For weekly indices, this is weekly cadence; for monthly-only, this will hop by weeks from
            // the current monthly expiry date (used only for display/diagnostics).
            current = adjustForHoliday(current.plusWeeks(1));
        }
        return expiries;
    }

    public boolean isHoliday(LocalDate date) {
        return holidays.contains(date)
                || date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    public boolean isTradingDay(LocalDate date) { return !isHoliday(date); }

    private LocalDate adjustForHoliday(LocalDate date) {
        while (isHoliday(date)) date = date.minusDays(1);
        return date;
    }
}
