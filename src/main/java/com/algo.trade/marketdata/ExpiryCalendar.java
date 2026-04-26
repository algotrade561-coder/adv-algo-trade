package com.algo.trade.marketdata;

import com.algo.trade.domain.IndexType;
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

    private static final List<LocalDate> HOLIDAYS = List.of(
        LocalDate.of(2025, 1, 26), LocalDate.of(2025, 3, 14),
        LocalDate.of(2025, 4, 14), LocalDate.of(2025, 4, 18),
        LocalDate.of(2025, 5, 1),  LocalDate.of(2025, 8, 15),
        LocalDate.of(2025, 10, 2), LocalDate.of(2025, 10, 24),
        LocalDate.of(2025, 11, 5), LocalDate.of(2025, 12, 25),
        LocalDate.of(2026, 1, 26), LocalDate.of(2026, 3, 3),
        LocalDate.of(2026, 4, 3),  LocalDate.of(2026, 4, 14),
        // 2026 holidays (NSE tentative — verify with NSE circular)
        LocalDate.of(2026, 5, 1),  // Maharashtra Day
        LocalDate.of(2026, 7, 17), // Muharram
        LocalDate.of(2026, 8, 15), // Independence Day
        LocalDate.of(2026, 9, 25), // Milad-un-Nabi
        LocalDate.of(2026, 10, 2), // Mahatma Gandhi Jayanti
        LocalDate.of(2026, 10, 13),// Dussehra
        LocalDate.of(2026, 11, 2), // Diwali (Laxmi Puja)
        LocalDate.of(2026, 11, 3), // Diwali (Balipratipada)
        LocalDate.of(2026, 11, 19),// Guru Nanak Jayanti
        LocalDate.of(2026, 12, 25) // Christmas
    );

    /**
     * Get the current/next weekly expiry for an index.
     * If today is expiry day and market is still open (before 15:00), returns today.
     * After 15:00 on expiry day, returns next week's expiry.
     */
    public LocalDate getCurrentWeeklyExpiry(IndexType indexType) {
        LocalDate today = LocalDate.now();
        DayOfWeek expiryDay = indexType.expiryDay();
        LocalDate candidate = today;
        for (int i = 0; i < 7; i++) {
            if (candidate.getDayOfWeek() == expiryDay) {
                if (candidate.equals(today) && LocalTime.now().isAfter(LocalTime.of(15, 0))) {
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
        return LocalDate.now().getDayOfWeek() == indexType.expiryDay()
                && !isHoliday(LocalDate.now());
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
        long days = ChronoUnit.DAYS.between(LocalDate.now(), getCurrentWeeklyExpiry(indexType));
        return days <= daysThreshold;
    }

    public long daysToExpiry(IndexType indexType) {
        return ChronoUnit.DAYS.between(LocalDate.now(), getCurrentWeeklyExpiry(indexType));
    }

    public List<LocalDate> getUpcomingExpiries(IndexType indexType, int weeks) {
        List<LocalDate> expiries = new ArrayList<>();
        LocalDate current = getCurrentWeeklyExpiry(indexType);
        for (int i = 0; i < weeks; i++) {
            expiries.add(current);
            current = adjustForHoliday(current.plusWeeks(1));
        }
        return expiries;
    }

    public boolean isHoliday(LocalDate date) {
        return HOLIDAYS.contains(date)
                || date.getDayOfWeek() == DayOfWeek.SATURDAY
                || date.getDayOfWeek() == DayOfWeek.SUNDAY;
    }

    public boolean isTradingDay(LocalDate date) { return !isHoliday(date); }

    private LocalDate adjustForHoliday(LocalDate date) {
        while (isHoliday(date)) date = date.minusDays(1);
        return date;
    }
}
