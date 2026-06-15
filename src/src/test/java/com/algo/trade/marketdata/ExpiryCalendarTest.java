package com.algo.trade.marketdata;

import com.algo.trade.domain.IndexType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;

import static org.assertj.core.api.Assertions.assertThat;

class ExpiryCalendarTest {

    @Test
    void sensex_prepones_when_thursday_is_holiday() {
        ExpiryCalendar cal = new ExpiryCalendar(new com.algo.trade.config.MarketCalendarProperties() {{
            setHolidays(java.util.List.of(
                    LocalDate.of(2026, 5, 28) // holiday that prepones SENSEX expiry
            ));
        }});

        // Thu 2026-05-28 is a holiday in HOLIDAYS, so SENSEX weekly expiry should pre-pone to Wed 27.
        LocalDate today = LocalDate.of(2026, 5, 27);
        LocalTime now = LocalTime.of(10, 0);

        LocalDate expiry = cal.getCurrentExpiry(IndexType.SENSEX, today, now);
        assertThat(expiry).isEqualTo(LocalDate.of(2026, 5, 27));
        assertThat(cal.isExpiryDay(IndexType.SENSEX, today, now)).isTrue();
    }

    @Test
    void banknifty_monthly_only_rolls_after_15_00_on_expiry_day() {
        ExpiryCalendar cal = new ExpiryCalendar(new com.algo.trade.config.MarketCalendarProperties() {{
            setHolidays(java.util.List.of(LocalDate.of(2026, 5, 28)));
        }});

        // BANKNIFTY is monthly-only; in May 2026, last Tuesday is 2026-05-26.
        LocalDate expiryDay = LocalDate.of(2026, 5, 26);

        assertThat(cal.getCurrentExpiry(IndexType.BANKNIFTY, LocalDate.of(2026, 5, 22), LocalTime.of(12, 0)))
                .isEqualTo(expiryDay);

        assertThat(cal.getCurrentExpiry(IndexType.BANKNIFTY, expiryDay, LocalTime.of(14, 0)))
                .isEqualTo(expiryDay);

        // After 15:00 on the expiry day, resolve to next month's monthly expiry.
        LocalDate nextMonthly = LocalDate.of(2026, 6, 30); // last Tuesday of June 2026
        assertThat(cal.getCurrentExpiry(IndexType.BANKNIFTY, expiryDay, LocalTime.of(15, 1)))
                .isEqualTo(nextMonthly);
    }
}

